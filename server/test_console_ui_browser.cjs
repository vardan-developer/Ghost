/* UI-only regression suite. Requires Playwright + Chrome; no daemon/device/server.
 * Run: NODE_PATH=<playwright node_modules> node server/test_console_ui_browser.cjs
 * Optional: UI_SCREENSHOT_DIR=/tmp/ghost-ui captures the final layouts.
 */
const assert = require('node:assert/strict');
const {spawnSync} = require('node:child_process');
const {mkdirSync} = require('node:fs');
const {chromium} = require('playwright');
const fixture = spawnSync(process.env.PYTHON || 'python3', ['-c', `
import json, console_ui as ui
D={'device_id':'demo','name':'Studio Pixel','approved':True,'online':True,'model':'Pixel 9 Pro','battery':86}
def result(value, ok=True):
 return ui.render_result('Command',{'ok':ok,'entry':{'status':'ok' if ok else 'error','result':value}},'/device/demo')
entries=[{'name':'Reports','path':'/sdcard/Download/Reports','dir':True,'size':0},
 {'name':'Team documents','path':'/sdcard/Download/Team documents','dir':True,'size':0},
 {'name':'Scope notes.txt','path':'/sdcard/Download/Scope notes.txt','dir':False,'size':1240},
 {'name':'<img src=x onerror=alert(1)>.txt','path':'/sdcard/Download/<img src=x onerror=alert(1)>.txt','dir':False,'size':2}]
print(json.dumps({'css':ui.CSS,'js':ui.JS,'pages':{
 '/':ui.render_dashboard({'demo':D},{},'fixture-csrf'),
 '/device/demo':ui.render_device(D,[],'fixture-csrf'),
 '/settings':ui.render_settings({'max_pending':100,'max_per_ip':5},'fixture-csrf'),
 '/stream/demo':ui.render_stream(D,'fixture-stream','fixture-csrf'),
 '/result':result({'model':'Pixel 9 Pro'}),
 '/empty':ui.render_dashboard({},{}),
 '/long':ui.render_device(dict(D,name='Very long device name with extra words and a long address 192.0.2.100'),[])},
 'listing':result(entries),'nested':result([{'name':'report.txt','path':'/sdcard/Download/Reports/report.txt','dir':False,'size':2048}]),
 'empty':result([]),'success':result({'model':'Pixel 9 Pro','charging':True,'password':'must-stay-masked','stdout':'<script>unsafe</script>'}),
 'error':result({'error':'Device offline. Reconnect before retrying.'},False)}))
`], {cwd:__dirname, encoding:'utf8', maxBuffer:4*1024*1024});
assert.equal(fixture.status,0,fixture.stderr);
const data=JSON.parse(fixture.stdout);
const csp="default-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:";
(async()=>{
 const browser=await chromium.launch({channel:'chrome',headless:true});
 const page=await browser.newPage({viewport:{width:1440,height:1000}});
 const errors=[], violations=[], posts=[];
 page.on('pageerror',e=>errors.push(e.message));
 page.on('console',m=>{if(m.text().includes('Content Security Policy'))violations.push(m.text());});
 let mode='success', release, held, holdSeen;
 async function routes(route){
  const request=route.request(), url=new URL(request.url());
  assert.equal(url.origin,'http://console.test','Unexpected external request');
  const send=(body,status=200,type='text/html')=>route.fulfill({status,contentType:type,headers:{'Content-Security-Policy':csp},body});
  if(url.pathname==='/static/app.css')return send(data.css,200,'text/css');
  if(url.pathname==='/static/app.js')return send(data.js,200,'application/javascript');
  if(request.method()==='POST'){
   posts.push(request);
   if(url.pathname==='/files/abort'){
    assert.match(request.headers()['content-type'],/^application\/x-www-form-urlencoded/);
    const params=new URLSearchParams(request.postData());assert.equal(params.get('csrf_token'),'fixture-csrf');assert.equal(params.get('device_id'),'demo');
    return send('Abort requested',200,'text/plain');
   }
   if(url.pathname==='/files/upload'){
    assert.match(request.headers()['content-type'],/^multipart\/form-data/);
    assert.match(request.postData(),/fixture-csrf/);
    if(mode==='upload-hold')await new Promise(resolve=>{release=resolve; holdSeen();});
    return send(data.success).catch(()=>{});
   }
   const params=new URLSearchParams(request.postData());
   assert.equal(params.get('csrf_token'),'fixture-csrf');
   assert.equal(url.pathname,'/device/demo/cmd');
   if(mode==='hold')await new Promise(resolve=>{release=resolve;holdSeen();});
   if(mode==='network')return route.abort('failed');
   if(mode==='csrf')return send('CSRF check failed: reload this page and retry',403,'text/plain');
   if(mode==='error')return send(data.error);
   if(params.get('verb')==='files ls'){
    if(params.get('arg')==='/sdcard/Download')return send(data.listing);
    if(params.get('arg')==='/sdcard/Download/Reports')return send(data.nested);
    return send(data.empty);
   }
   return send(data.success);
  }
  if(data.pages[url.pathname])return send(data.pages[url.pathname]);
  throw new Error('Unexpected request '+request.url());
 }
 await page.route('**/*',routes);
 const visit=path=>page.goto('http://console.test'+path);
 async function tool(name){
  const picker=page.locator('.tool-picker');
  if(!await picker.getAttribute('open') && !await page.locator('[data-tab="'+name+'"]').isVisible())await picker.locator('summary').click();
  await page.locator('[data-tab="'+name+'"]').click();
 }
 async function subgroup(label){const group=page.locator('.tab-panel.active .tool-section').filter({has:page.locator('summary',{hasText:label})});if(!await group.evaluate(el=>el.open))await group.locator('summary').click();}
 let layouts=0;
 for(const width of [320,390,600,768,1440,1920]){
  await page.setViewportSize({width,height:900});
  for(const path of ['/','/device/demo','/settings','/stream/demo','/result','/empty','/long']){
   await visit(path);assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true,width+' '+path);layouts++;
  }
 }
 await page.setViewportSize({width:1440,height:1000});await visit('/device/demo');
 await page.waitForFunction(()=>Array.from(document.fonts).some(f=>f.family==='IBM Plex Mono'&&f.status==='loaded'));
 await page.getByRole('button',{name:'Browse folder',exact:true}).click();
 await page.getByRole('button',{name:'Open folder Reports',exact:true}).waitFor();
 assert.equal(await page.locator('.file-row').count(),4);
 assert.equal(await page.locator('.file-list img,.file-list script').count(),0);
 const link=page.getByRole('link',{name:'Download Scope notes.txt',exact:true});
 assert.equal(new URL(await link.getAttribute('href'),'http://console.test').searchParams.get('path'),'/sdcard/Download/Scope notes.txt');
 await page.getByLabel('Filter files in this folder').fill('Scope');assert.equal(await page.locator('.file-row:visible').count(),1);
 await page.getByLabel('Filter files in this folder').fill('');
 await page.getByRole('button',{name:'Open folder Reports',exact:true}).click();await page.getByText('report.txt',{exact:true}).waitFor();
 await page.getByRole('button',{name:'Up',exact:true}).click();await page.getByRole('button',{name:'Open folder Reports',exact:true}).waitFor();
 const count=posts.length;await page.getByRole('button',{name:'Open folder Team documents',exact:true}).click();
 assert.equal(posts.length,count);assert.match(await page.locator('#browser-note').innerText(),/cannot list folder paths containing spaces/);
 if(process.env.UI_SCREENSHOT_DIR){mkdirSync(process.env.UI_SCREENSHOT_DIR,{recursive:true});await page.screenshot({path:process.env.UI_SCREENSHOT_DIR+'/explorer-desktop.png'});}
 await tool('system');
 const before=page.url();await page.getByRole('button',{name:'Device information',exact:true}).click();
 await page.getByText('Device information completed.',{exact:true}).waitFor();
 assert.equal(page.url(),before);assert.match(await page.locator('#command-results').innerText(),/Pixel 9 Pro/);
 assert.equal(await page.locator('#command-results pre:visible').count(),0);
 assert.equal(await page.locator('#command-results script').count(),0);
 assert.doesNotMatch(await page.locator('#command-results').textContent(),/must-stay-masked/);
 await subgroup('Session & recovery');page.once('dialog',dialog=>dialog.dismiss());
 const noRestart=posts.length;await page.getByRole('button',{name:'Restart service',exact:true}).click();assert.equal(posts.length,noRestart);
 await subgroup('Terminal');const shell=page.locator('form[data-command-label="Run shell command"]');
 await shell.locator('input[name=arg]').fill('echo hello');await shell.getByRole('button',{name:'Run',exact:true}).click();
 await page.getByText('Run shell command completed.',{exact:true}).waitFor();
 assert.equal(new URLSearchParams(posts.at(-1).postData()).get('arg'),'echo hello');
 for(const scenario of ['error','csrf','network']){
  mode=scenario;await page.getByRole('button',{name:'Check status',exact:true}).click();
  if(scenario==='error')await page.getByText('Check status failed. See the response below.',{exact:true}).waitFor();
  else await page.waitForFunction(()=>/reload|Connection lost/.test(document.querySelector('#command-state').textContent));
  assert.equal(page.url(),before);
 }
 mode='hold';held=new Promise(resolve=>{holdSeen=resolve;});await page.getByRole('button',{name:'Check status',exact:true}).click();await held;
 assert.equal(await page.getByRole('button',{name:'Device information',exact:true}).isDisabled(),true);
 mode='success';release();await page.getByText('Check status completed.',{exact:true}).waitFor();
 assert.equal(await page.getByRole('button',{name:'Device information',exact:true}).isDisabled(),false);
 await tool('files');await page.getByText('Upload files',{exact:true}).click();
 await page.getByLabel('Files to upload',{exact:true}).setInputFiles({name:'demo.txt',mimeType:'text/plain',buffer:Buffer.from('fixture')});
 await page.getByRole('button',{name:'Upload',exact:true}).click();await page.getByText('File upload completed.',{exact:true}).waitFor();assert.equal(page.url(),'http://console.test/device/demo#tool-files');
 mode='upload-hold';held=new Promise(resolve=>{holdSeen=resolve;});await page.getByRole('button',{name:'Upload',exact:true}).click();await held;
 await page.getByRole('button',{name:'Cancel',exact:true}).click();await page.waitForFunction(()=>document.querySelector('#upload-note').textContent.includes('abort requested'));
 mode='success';release();
 await page.setViewportSize({width:390,height:844});await visit('/device/demo');
 assert.equal(await page.locator('.tool-picker').evaluate(el=>el.open),false);
 assert.equal(await page.locator('.tab-panel.active .tool-section').evaluate(el=>el.open),false);
 await tool('system');assert.equal(await page.locator('.tool-picker').evaluate(el=>el.open),false);
 await subgroup('Terminal');await page.locator('form[data-command-label="Run shell command"] input[name=arg]').fill('echo mobile');
 if(process.env.UI_SCREENSHOT_DIR)await page.screenshot({path:process.env.UI_SCREENSHOT_DIR+'/tools-phone.png'});
 await page.getByRole('link',{name:'Settings',exact:true}).click();assert.match(page.url(),/\/settings$/);
 const fallback=await browser.newPage({javaScriptEnabled:false,viewport:{width:1440,height:900}});await fallback.route('**/*',routes);
 await fallback.goto('http://console.test/device/demo');assert.equal(await fallback.locator('[data-tab="system"]').isVisible(),true);
 await fallback.getByRole('button',{name:'Browse folder',exact:true}).click();await fallback.locator('#result-content .result-table').waitFor();
 assert.deepEqual(errors,[]);assert.deepEqual(violations,[]);
 await browser.close();
 console.log('PASS: '+layouts+' responsive layouts; grouped tools; inline success/error/CSRF/network states; duplicate-submit prevention; confirmations; folder navigation/filtering/download URLs; upload/cancel contracts; no-JS fallback; bundled font under production CSP.');
})().catch(error=>{console.error(error);process.exit(1);});
