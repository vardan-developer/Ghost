"""console_ui.py — HTML/CSS/JS for the ghostctl operator dashboard.

All assets are served as plain text by console.py. No frameworks, no inline
event handlers, no inline script. CSP-friendly (script-src 'self').
"""
import html
import json
import time
from urllib.parse import quote

def e(s):
    """Escape for HTML text/attribute contexts."""
    return html.escape(str(s if s is not None else ""))

def fmt_age(ts):
    if not ts:
        return "-"
    d = max(0, int(time.time() - ts))
    if d < 60:
        return f"{d}s ago"
    if d < 3600:
        return f"{d // 60}m ago"
    if d < 86400:
        return f"{d // 3600}h ago"
    return f"{d // 86400}d ago"

def truncate_json(obj, max_len=400):
    """Truncate strings/structure without building multi-MB dumps."""
    if isinstance(obj, str):
        if len(obj) > max_len:
            return obj[:max_len] + f"... ({len(obj)} chars)"
        return obj
    if isinstance(obj, dict):
        return {k: truncate_json(v, max_len) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [truncate_json(v, max_len) for v in obj]
    return obj

def mask_sensitive(obj):
    """Recursively mask token/pin/password/secret/auth keys."""
    SENSITIVE = ("token", "pin", "pass", "secret", "key", "auth", "credential")
    if isinstance(obj, dict):
        out = {}
        for k, v in obj.items():
            if any(s in str(k).lower() for s in SENSITIVE):
                out[k] = "<masked>"
            else:
                out[k] = mask_sensitive(v)
        return out
    if isinstance(obj, list):
        return [mask_sensitive(v) for v in obj]
    return obj

def json_summary(resp, max_len=2000):
    """Summarize JSON for display: truncate long strings and mask secrets."""
    try:
        sanitized = mask_sensitive(resp)
        truncated = truncate_json(sanitized, max_len)
        return json.dumps(truncated, indent=2, ensure_ascii=False)
    except Exception:
        return str(resp)[:max_len]

# ---------------------------------------------------------------------------
# CSS — responsive, dark, operator-focused. No external fonts/assets.
# ---------------------------------------------------------------------------
CSS = r"""
:root {
  color-scheme: dark;
  --bg:#090b0e; --card:#11151a; --card2:#171c22; --text:#e7ebef;
  --muted:#a3abb7; --line:#2b333f; --accent:#ee898c; --accent2:#c6626a;
  --ok:#80c6ad; --warn:#e2bd82; --err:#f29898; --danger:#422727;
  --radius:3px; --pad:22px;
  font-synthesis:none;
}
* { box-sizing:border-box; }
body { margin:0; background:var(--bg); color:var(--text); font:14px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif; -webkit-font-smoothing:antialiased; }
a { color:var(--accent); text-decoration:none; }
a:hover { text-decoration:underline; text-underline-offset:4px; }
h1,h2,h3,p { margin-top:0; }
h1 { font-size:clamp(28px,3.6vw,42px); line-height:1.15; letter-spacing:-1.6px; font-weight:600; margin-bottom:12px; overflow-wrap:anywhere; }
h2 { font-size:19px; font-weight:550; letter-spacing:-.4px; margin:32px 0 16px; }
h3 { font-size:16px; font-weight:550; margin-bottom:18px; }
.muted { color:var(--muted); }
.small { font-size:12px; }
.mono,code,pre { font-family:ui-monospace,"SF Mono",Menlo,Consolas,monospace; }
.eyebrow { font-size:10px; letter-spacing:1.8px; text-transform:uppercase; font-weight:650; color:var(--muted); }
.icon { width:20px; height:20px; fill:none; stroke:currentColor; stroke-width:1.6; stroke-linecap:round; stroke-linejoin:round; flex-shrink:0; vertical-align:middle; }
.skip-link { position:fixed; left:20px; top:-80px; z-index:100; padding:12px; background:var(--accent); color:var(--bg); }
.skip-link:focus { top:12px; }
nav { position:fixed; inset:0 auto 0 0; width:228px; padding:35px 20px 24px; background:#0c1015; border-right:1px solid var(--line); display:flex; flex-direction:column; gap:8px; z-index:20; }
nav .brand { display:flex; align-items:center; gap:10px; color:var(--text); font-size:23px; letter-spacing:-.9px; font-weight:650; padding:0 12px 37px; }
.brand-mark { display:grid; place-items:center; width:32px; height:32px; border-radius:3px; background:var(--accent); color:#182112; }
nav .nav-caption { padding:0 14px 12px; font-size:10px; letter-spacing:1.6px; color:var(--muted); }
nav a.nav-link { color:var(--muted); padding:12px 14px; display:flex; align-items:center; gap:12px; border-radius:3px; }
nav a.nav-link:hover { background:var(--card2); color:var(--text); text-decoration:none; }
nav a.active { background:#2b1a21; color:var(--accent); }
.nav-bottom { margin-top:auto; padding:20px 12px 0; border-top:1px solid var(--line); }
.nav-bottom p { margin:8px 0 0; font-size:11px; }
.operator { display:flex; align-items:center; gap:10px; margin-top:24px; font-size:12px; }
.avatar { width:32px; height:32px; display:grid; place-items:center; border:1px solid #4c554c; border-radius:3px; background:#221921; color:var(--accent); font-size:10px; font-weight:650; }
.topbar { height:83px; border-bottom:1px solid var(--line); margin-left:228px; padding:0 40px; display:flex; justify-content:space-between; align-items:center; color:var(--muted); font-size:12px; gap:12px; }
.breadcrumb { display:flex; align-items:center; gap:12px; min-width:0; }
.breadcrumb span:last-child { color:var(--text); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; max-width:55vw; }
.topbar-meta { display:flex; align-items:center; gap:10px; white-space:nowrap; }
main { margin-left:228px; max-width:1680px; padding:38px 40px 64px; min-width:0; }
.page-heading { display:flex; align-items:center; justify-content:space-between; gap:20px; margin-bottom:30px; }
.page-heading .eyebrow { margin-bottom:12px; }
.page-heading p { color:var(--muted); margin:0; font-size:13px; }
.heading-tag { border:1px solid var(--line); border-radius:3px; padding:8px 12px; color:var(--muted); font-size:11px; white-space:nowrap; }
.stats { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:16px; margin-bottom:34px; }
.stat { border:1px solid var(--line); border-radius:3px; padding:20px 22px; background:var(--card); }
.stat-top { display:flex; align-items:center; justify-content:space-between; gap:8px; color:var(--muted); font-size:12px; }
.stat strong { font-variant-numeric:tabular-nums; display:block; font-size:34px; letter-spacing:-1px; font-weight:500; line-height:1.2; margin:17px 0 8px; }
.stat small { font-size:11px; color:var(--muted); }
.stat.online { background:#132320; border-color:#294d41; }
.stat.online strong,.stat.online .icon { color:var(--ok); }
.section-title { display:flex; align-items:center; gap:10px; margin:0 0 18px; font-size:18px; }
.section-title .count { color:var(--muted); font-size:11px; border:1px solid var(--line); border-radius:3px; padding:1px 7px; }
.card { background:var(--card); border:1px solid var(--line); border-radius:var(--radius); padding:var(--pad); margin-bottom:20px; min-width:0; }
.card2 { background:var(--card2); }
.grid { display:grid; gap:20px; grid-template-columns:repeat(auto-fill,minmax(min(100%,310px),1fr)); }
.row { display:flex; flex-wrap:wrap; align-items:center; gap:10px; min-width:0; }
.badge { display:inline-flex; align-items:center; gap:6px; padding:4px 9px; border-radius:3px; font-size:10px; font-weight:550; letter-spacing:.2px; white-space:nowrap; }
.badge::before { content:""; width:5px; height:5px; border-radius:50%; background:currentColor; }
.badge.ok { background:#173029; color:var(--ok); }
.badge.warn { background:#3b3226; color:var(--warn); }
.badge.err { background:#3a2928; color:var(--err); }
.badge.muted { background:#282d2e; color:#a0aaa5; }
.dot { width:6px; height:6px; border-radius:50%; display:inline-block; flex-shrink:0; }
.dot.on { background:var(--ok); }
.dot.off { background:var(--muted); }
.dot.pending { background:var(--warn); }
button,.btn { display:inline-flex; justify-content:center; align-items:center; gap:8px; background:var(--accent); color:#170e12; border:1px solid var(--accent); border-radius:3px; padding:10px 15px; font-family:inherit; font-size:13px; font-weight:550; line-height:1.4; cursor:pointer; text-decoration:none; min-height:42px; transition:background .16s,border-color .16s,transform .16s; }
button:hover,.btn:hover { background:#f5b2b4; border-color:#f5b2b4; text-decoration:none; }
button:active,.btn:active { transform:translateY(1px); }
button.danger,.btn.danger { background:transparent; border-color:#65403c; color:var(--err); }
button.danger:hover { background:var(--danger); }
button.secondary,.btn.secondary { background:transparent; border-color:#424a47; color:var(--text); }
button.secondary:hover,.btn.secondary:hover { background:#26202a; border-color:#737e74; }
button.small,.btn.small { font-size:11px; padding:8px 11px; min-height:38px; }
button:disabled { opacity:.5; cursor:not-allowed; }
input,select,textarea { min-width:0; max-width:100%; background:#0b1016; color:var(--text); border:1px solid #3b4340; border-radius:3px; padding:11px 12px; font:inherit; font-size:13px; min-height:42px; }
input::placeholder,textarea::placeholder { color:#929c96; }
input[type=checkbox] { accent-color:var(--accent); min-height:0; width:14px; height:14px; margin:0; }
input[type=file] { padding:7px; }
input::file-selector-button { background:#27212a; color:var(--text); border:0; padding:7px 10px; border-radius:3px; margin-right:10px; cursor:pointer; }
:focus-visible { outline:2px solid var(--accent); outline-offset:4px; }
input:focus,select:focus,textarea:focus { border-color:var(--accent); }
table { width:100%; border-collapse:collapse; }
th,td { padding:16px 12px; border-bottom:1px solid var(--line); text-align:left; font-size:12px; vertical-align:top; overflow-wrap:anywhere; }
th { color:var(--muted); font-weight:500; font-size:10px; text-transform:uppercase; letter-spacing:1px; }
tr:last-child td { border-bottom:none; }
.table-card { overflow-x:auto; }
.table-card table { min-width:650px; }
pre { background:#080c12; border:1px solid var(--line); border-radius:3px; padding:18px; overflow-x:auto; font-size:12px; white-space:pre-wrap; word-break:break-word; max-height:440px; }
code { font-size:.88em; }
details { margin:6px 0; }
summary { cursor:pointer; color:var(--accent); font-size:12px; }
details>div { margin-top:12px; padding-left:12px; border-left:2px solid var(--line); }
.actions-grid { display:grid; gap:14px; grid-template-columns:repeat(auto-fill,minmax(min(100%,240px),1fr)); margin-bottom:32px; }
.action-form { background:var(--card); border:1px solid var(--line); border-radius:3px; padding:18px; }
.action-form label { display:block; color:var(--muted); margin-bottom:14px; font-family:ui-monospace,monospace; font-size:12px; }
.action-form .row { gap:8px; }
.action-form input[type=text] { flex:1; min-width:100px; width:100%; }
.pending-grid { grid-template-columns:1fr; }
.pending-grid .pending-card { display:flex; flex-direction:row; flex-wrap:wrap; align-items:center; justify-content:space-between; gap:16px 24px; padding:18px 22px; border-left:2px solid var(--warn); }
.pending-grid .pending-card .head { margin:0; gap:20px; min-width:220px; }
.pending-grid .pending-card .row:last-child { margin:0; }
.stream-box { position:relative; background:radial-gradient(ellipse at center,#202a24,#0c1010 75%); border:1px solid var(--line); border-radius:var(--radius); overflow:hidden; min-height:420px; display:grid; place-items:center; padding:24px; }
.stream-box img { display:block; max-width:100%; height:auto; max-height:70vh; object-fit:contain; color:var(--muted); }
.stream-note { position:absolute; bottom:16px; left:16px; right:16px; background:#141b17ee; border:1px solid #344139; padding:10px 14px; border-radius:3px; font-size:12px; }
.empty { text-align:center; color:var(--muted); padding:60px 24px; border:1px dashed var(--line); border-radius:3px; grid-column:1/-1; }
.empty .icon { width:36px; height:36px; display:block; margin:0 auto 14px; color:var(--accent); }
.progress { height:5px; background:var(--line); border-radius:3px; overflow:hidden; margin:18px 0 10px; }
.progress>div { height:100%; background:var(--accent); width:0%; transition:width .2s; }
.toast { position:fixed; top:16px; right:16px; max-width:calc(100vw - 32px); background:var(--card); border:1px solid var(--line); border-radius:3px; padding:14px 20px; z-index:1000; box-shadow:0 12px 50px #0006; }
.toast.ok { border-color:var(--ok); color:var(--ok); }
.toast.err { border-color:var(--err); color:var(--err); }
.search-row { display:flex; gap:20px; align-items:center; margin-bottom:24px; flex-wrap:wrap; padding:16px; border:1px solid var(--line); border-radius:3px; background:var(--card); }
.search-field { display:flex; align-items:center; flex:1; min-width:180px; gap:10px; }
.search-field .icon { color:var(--muted); }
.search-row input[type=search] { flex:1; width:100%; border:0; background:transparent; padding:6px 0; min-height:30px; }
.search-row label { display:flex; align-items:center; gap:8px; color:var(--muted); cursor:pointer; }
.hidden { display:none!important; }
.device-card { display:flex; flex-direction:column; gap:0; padding:22px; position:relative; transition:border-color .18s,box-shadow .18s; }
.device-card:hover { border-color:#65725e;  }
.device-card .head { display:flex; justify-content:space-between; align-items:flex-start; gap:10px; }
.device-identity { display:flex; align-items:center; gap:12px; min-width:0; }
.device-identity>div { min-width:0; }
.device-glyph { width:42px; height:46px; border:1px solid #495345; border-radius:3px; background:#241920; color:var(--accent); display:grid; place-items:center; flex-shrink:0; }
.device-card .head a { color:var(--text); font-size:15px; font-weight:550; letter-spacing:-.25px; overflow-wrap:anywhere; }
.device-card .head .mono { font-size:10px; margin-top:4px; overflow-wrap:anywhere; }
.device-card .meta { display:grid; grid-template-columns:1fr 1fr; gap:7px 16px; margin:22px 0; font-size:11px; }
.device-card .meta dt { color:var(--muted); }
.device-card .meta dd { margin:0; text-align:right; overflow-wrap:anywhere; }
.device-actions { padding-top:18px; border-top:1px solid var(--line); }
.device-actions>.btn { flex:1; }
.device-actions>form { display:flex!important; align-items:center; gap:6px; width:100%; }
.device-actions>form input[type=text] { flex:1; width:80px; font-size:11px; min-height:38px; }
.device-actions>form:has(button.danger) { justify-content:flex-end; }
.device-actions>form button.danger { border:0; padding:3px 0; min-height:28px; font-size:10px; }
.pending-card { border-color:#62513a; }
.pending-card .head { margin-bottom:14px; }
.pending-card .row:last-child { margin-top:16px; }
.tabs { display:flex; gap:4px; margin-bottom:20px; border-bottom:1px solid var(--line); overflow-x:auto; }
.tab { border:0; border-radius:0; padding:12px 20px; background:transparent; color:var(--muted); white-space:nowrap; font:inherit; font-size:13px; min-height:46px; }
.tab.active { color:var(--accent); border-bottom:2px solid var(--accent); background:transparent; }
.tab:hover { background:var(--card); border-color:var(--line); color:var(--text); }
.tab-panel { display:none; }
.tab-panel.active { display:block; }
.device-summary { display:flex; flex-wrap:wrap; gap:10px 24px; padding:18px 22px; background:var(--card); border:1px solid var(--line); border-radius:3px; margin:22px 0; color:var(--muted); }
.device-summary span { overflow-wrap:anywhere; }
.settings-card { max-width:700px; }
.settings-card label { display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:12px; padding:16px 0; border-bottom:1px solid var(--line); }
.settings-card label input { width:140px; }
.settings-card button { margin-top:14px; }
.page-footer { display:flex; justify-content:space-between; gap:16px; margin-top:40px; padding-top:20px; border-top:1px solid var(--line); font-size:10px; color:var(--muted); }
@media(min-width:1700px) { main { margin-right:auto; } }
@media(max-width:1150px) { nav { width:190px; padding-inline:14px; } main,.topbar { margin-left:190px; padding-left:26px; padding-right:26px; } .stats { gap:12px; } .stat { padding:16px; } }
@media(max-width:800px) { nav { width:76px; padding:28px 12px; align-items:center; } nav .brand { padding:0 0 30px; } .brand-name,nav .nav-caption,nav .nav-label,.nav-bottom { display:none; } nav a.nav-link { padding:14px; } main,.topbar { margin-left:76px; padding-left:22px; padding-right:22px; } .stats { grid-template-columns:repeat(2,minmax(0,1fr)); } .heading-tag { display:none; } }
@media(max-width:560px) {
  :root { --pad:18px; }
  body { font-size:14px; }
  nav { inset:auto 0 0; width:auto; height:calc(68px + env(safe-area-inset-bottom)); padding:8px 20px calc(8px + env(safe-area-inset-bottom)); border-right:0; border-top:1px solid var(--line); flex-direction:row; justify-content:space-around; gap:12px; background:#0c1015fa; }
  nav .brand { display:none; }
  nav a.nav-link { flex:1; flex-direction:column; gap:2px; padding:5px 12px; border-radius:3px; font-size:10px; }
  nav .nav-label { display:inline; }
  nav .icon { width:19px; height:19px; }
  .topbar { margin:0; height:64px; padding:0 18px; }
  .topbar-meta .small { display:none; }
  main { margin:0; padding:26px 18px calc(100px + env(safe-area-inset-bottom)); }
  h1 { letter-spacing:-1px; }
  .page-heading { margin-bottom:24px; }
  .page-heading p { max-width:29ch; font-size:12px; }
  .stats { gap:10px; margin-bottom:28px; }
  .stat { border-radius:3px; padding:15px; }
  .stat-top { font-size:11px; }
  .stat strong { font-size:30px; margin:12px 0 6px; }
  .stat small { font-size:10px; }
  .search-row { gap:4px 16px; padding:12px 14px; }
  .search-row label { min-height:44px; }
  .pending-grid .pending-card { align-items:stretch; }
  .pending-grid .pending-card .head { justify-content:space-between; width:100%; min-width:0; }
  .search-field { flex-basis:100%; }
  input,select,textarea { font-size:16px; }
  button.small,.btn.small { min-height:44px; }
  .device-actions>form input[type=text] { min-height:44px; font-size:16px; }
  .device-actions>form button.danger { min-height:44px; }
  .device-card { padding:20px; }
  .grid { gap:0; }
  .tabs { gap:0; }
  .tab { padding:12px 16px; }
  .row>input[type=text],.row>input[type=file] { flex:1 1 100%; width:100%; }
  .action-form input[type=text] { flex-basis:100%; }
  .device-summary { gap:10px 16px; }
  .stream-box { min-height:380px; }
  .page-footer { flex-direction:column; gap:4px; margin-top:28px; }
}
@media(prefers-reduced-motion:reduce) { *,*::before,*::after { transition:none!important; scroll-behavior:auto!important; } }
/* Dense workspaces use progressive disclosure, with the primary task visible. */
body { -webkit-tap-highlight-color:#ee898c30; }
button,.btn,summary { touch-action:manipulation; }
h1,h2,h3,.brand,.stat strong,.tab,.eyebrow,.mono,code,pre,.tool-picker>summary,.execution-heading { font-family:"IBM Plex Mono",ui-monospace,monospace; font-weight:400; }
h1 { font-size:clamp(25px,3vw,36px); letter-spacing:-1.3px; text-wrap:balance; }
.eyebrow { text-transform:none; letter-spacing:.4px; font-size:11px; }
.brand-mark { background:transparent; border:1px solid var(--accent); color:var(--accent); }
nav .brand { font-size:20px; letter-spacing:-1px; }
.avatar { background:#221921; border-color:#614048; color:var(--accent); }
main { background-image:linear-gradient(#53658205 1px,transparent 1px),linear-gradient(90deg,#53658205 1px,transparent 1px); background-size:36px 36px; }
.section-title { font-size:16px; }
.stat strong { font-size:29px; }
.ui-disclosure { margin:0 0 18px; background:var(--card); border:1px solid var(--line); border-radius:3px; min-width:0; }
.ui-disclosure>summary { display:flex; align-items:center; justify-content:space-between; gap:12px; list-style:none; padding:15px 18px; color:var(--text); font-weight:500; font-size:13px; min-height:48px; }
.ui-disclosure>summary::-webkit-details-marker { display:none; }
.ui-disclosure>summary::after { content:"+"; font:18px/1 ui-monospace,monospace; color:var(--muted); flex-shrink:0; }
.ui-disclosure[open]>summary::after { content:"−"; }
.ui-disclosure>summary:hover { background:#ffffff04; text-decoration:none; }
.ui-disclosure>div { margin:0; padding:0 18px 18px; border:0; }
.ui-disclosure[open]>summary { border-bottom:1px solid var(--line); margin-bottom:16px; }
.device-context { display:flex; justify-content:space-between; flex-wrap:wrap; align-items:center; gap:16px; margin-bottom:24px; }
.device-context h1 { margin:0 0 8px; }
.device-context .row { gap:12px; }
.device-context .mono { overflow-wrap:anywhere; }
.device-summary { margin:0; border:0; padding:0; background:transparent; }
.device-summary > span:not(.badge) { display:flex; flex-direction:column; gap:2px; color:var(--text); }
.device-summary span small { color:var(--muted); }
.management-actions { display:flex; flex-wrap:wrap; align-items:center; gap:12px; }
.management-actions form { margin:0; }
.management-actions form:first-child { display:flex; align-items:center; gap:8px; flex-wrap:wrap; margin-right:auto; }
.management-actions form:first-child input { width:200px; }
.tool-picker { margin-bottom:22px; background:#0d1219; border-color:#3b414e; }
.tool-picker>summary { display:none; }
.tool-picker[open]>summary { margin:0; }
.tool-picker>.tabs { margin:0; padding:0 8px; border:0; flex-wrap:wrap; gap:0; }
.tab { display:flex; gap:9px; align-items:center; padding:15px 22px; }
.tab.active { color:var(--accent); background:#251820; }
.tool-sections { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:16px; margin-bottom:26px; align-items:start; }
.tool-section { margin:0; }
.tool-section>summary { font-family:"IBM Plex Mono",ui-monospace,monospace; }
.section-count { margin-left:auto; color:var(--muted); font:10px ui-monospace,monospace; }
.command-list { display:flex; flex-wrap:wrap; gap:8px; }
.command-list .action-form { padding:0; margin:0; border:0; border-radius:0; background:transparent; }
.command-list .action-form:not(.field-form) { display:inline-flex; }
.command-list .field-form { width:100%; padding-top:12px; margin-top:6px; border-top:1px solid var(--line); }
.command-list .field-form:first-child { border-top:0; padding-top:0; margin-top:0; }
.command-list .field-form label { font-family:inherit; font-size:12px; color:var(--text); margin-bottom:8px; }
.command-list .field-form input { font-family:"IBM Plex Mono",ui-monospace,monospace; font-size:12px; }
.command-list .field-form .row { flex-wrap:nowrap; }
.command-list .action-form button { background:transparent; border-color:#444956; color:var(--text); }
.command-list .action-form button:hover { background:#30212c; border-color:var(--accent); }
.command-list .action-form button.danger { color:var(--err); border-color:#75424c; }
.workspace-header { display:flex; align-items:center; justify-content:space-between; gap:12px; margin:28px 0 16px; }
.workspace-header h2 { margin:0; }
.workspace-header span { color:var(--muted); font-size:11px; }
.file-browser { border:1px solid #3c4654; padding:0; margin-bottom:16px; background:#0d1219; }
.browser-heading { display:flex; align-items:center; justify-content:space-between; padding:17px 20px; border-bottom:1px solid var(--line); gap:12px; }
.browser-heading h3 { display:flex; gap:10px; align-items:center; margin:0; font-size:14px; }
.browser-heading h3 .icon { color:var(--accent); }
.browser-toolbar { display:flex; flex-wrap:wrap; gap:8px; padding:16px 18px 8px; }
.browser-toolbar input { flex:1; min-width:120px; font-family:"IBM Plex Mono",ui-monospace,monospace; }
.browser-shortcuts { display:flex; flex-wrap:wrap; gap:8px; padding:8px 18px 14px; }
.browser-shortcuts button { min-height:32px; padding:5px 10px; font-size:11px; background:transparent; border-color:var(--line); color:var(--muted); }
.browser-shortcuts button:hover { color:var(--text); border-color:var(--accent); }
.browser-filter { width:calc(100% - 36px); margin:0 18px 14px; min-height:36px; font-size:12px; }
.breadcrumb-path { display:flex; flex-wrap:wrap; align-items:center; gap:4px; padding:12px 18px; border-block:1px solid var(--line); }
.breadcrumb-path button { min-height:28px; padding:3px 6px; border:0; background:transparent; color:var(--muted); font:11px "IBM Plex Mono",monospace; overflow-wrap:anywhere; }
.breadcrumb-path button:hover { color:var(--accent); }
.file-list { padding:0 18px; max-height:480px; overflow:auto; overscroll-behavior:contain; }
.file-row { display:grid; grid-template-columns:minmax(0,1fr) 80px 82px; align-items:center; gap:12px; padding:11px 0; border-bottom:1px solid var(--line); content-visibility:auto; contain-intrinsic-size:auto 60px; }
.file-row:last-child { border:0; }
.file-name { display:flex; gap:10px; align-items:center; min-width:0; overflow-wrap:anywhere; }
.file-name .icon { color:var(--muted); width:18px; }
.file-name button { padding:4px 0; min-height:32px; border:0; background:transparent; color:var(--accent); text-align:left; font:inherit; justify-content:flex-start; overflow-wrap:anywhere; }
.file-name span { min-width:0; overflow-wrap:anywhere; font-size:12px; }
.file-size { color:var(--muted); font:11px "IBM Plex Mono",monospace; text-align:right; }
.browser-note { padding:13px 18px; font-size:12px; color:var(--muted); margin:0; }
.browser-note.error { color:var(--err); }
.transfer-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:16px; margin-bottom:16px; align-items:start; }
.transfer-grid .ui-disclosure { margin:0; }
.transfer-grid .row { gap:8px; }
.transfer-grid input:not([type=hidden]) { width:100%; flex-basis:100%; }
.execution-panel { margin:0 0 22px; border:1px solid #55505b; background:#0c1117; padding:0; scroll-margin-block:20px 100px; }
.execution-heading { padding:14px 18px; border-bottom:1px solid var(--line); display:flex; align-items:center; justify-content:space-between; gap:12px; font-size:12px; }
.execution-heading>span { display:flex; align-items:center; gap:10px; }
.execution-heading .icon { color:var(--accent); }
.execution-heading button { background:transparent; color:var(--muted); border:0; min-height:28px; padding:3px 8px; }
.execution-body { padding:18px; }
.execution-body>p { margin:0; }
.execution-state { margin:0 0 14px; font-size:12px; display:flex; align-items:center; gap:10px; }
.execution-result { border-bottom:1px solid var(--line); margin-bottom:18px; padding-bottom:18px; }
.execution-result:last-child { margin:0; padding:0; border:0; }
.execution-result h3 { display:flex; align-items:center; justify-content:space-between; gap:12px; font-family:inherit; font-size:13px; margin:0 0 14px; }
.execution-result details { margin-top:18px; }
.result-fields { margin:0; display:grid; grid-template-columns:minmax(90px,.35fr) minmax(0,1fr); gap:8px 20px; font-size:13px; }
.result-fields dt { color:var(--muted); overflow-wrap:anywhere; }
.result-fields dd { margin:0; overflow-wrap:anywhere; min-width:0; }
.result-fields .result-fields { display:block; }
.result-fields .result-fields dt { margin-top:8px; }
.result-text { margin:0; white-space:pre-wrap; word-break:break-word; font-size:12px; }
.result-list { padding-left:20px; margin:0; }
.result-table { overflow:auto; max-height:420px; }
.result-table table { min-width:420px; }
.result-table td { font-size:12px; }
.result-error { border-left:2px solid var(--err); padding:12px 16px; background:#2b171e; color:var(--err); }
#result-content { overflow-wrap:anywhere; }
#command-output:focus { outline:1px solid var(--accent); outline-offset:4px; }
body.command-busy [data-inline-command] button[type=submit],body.command-busy [data-folder] { opacity:.55; }
.device-more { border:0; background:transparent; margin:14px 0 0; }
.device-more>summary { padding:8px 0; min-height:36px; color:var(--muted); font-size:11px; }
.device-more[open]>summary { border:0; margin:0 0 8px; }
.device-more>div { padding:0; }
.device-more .meta { margin:10px 0 16px; }
.device-actions { margin-top:18px; }
.device-management { margin-top:14px; }
.device-management summary { color:var(--muted); font-size:11px; }
.filter-menu { border:0; margin:0; background:transparent; }
.filter-menu>summary { display:none; }
.filter-menu>div { display:flex; align-items:center; gap:18px; padding:0; }
.filter-menu[open]>summary { border:0; margin:0; }
@media(max-width:900px) { .tool-sections,.transfer-grid { grid-template-columns:1fr; } }
@media(max-width:600px) {
  .tool-picker>summary { display:flex; }
  .tool-picker>.tabs { flex-direction:column; gap:0; padding:8px; }
  .tool-picker .tab { justify-content:flex-start; padding:12px; border:0; border-left:2px solid transparent; }
  .tool-picker .tab.active { border-left-color:var(--accent); }
  .tool-picker[open]>summary { border-bottom:1px solid var(--line); }
  .tool-sections { gap:10px; }
  .tool-section[open]>summary { margin-bottom:12px; }
  .device-context { gap:14px; }
  .device-context>.btn { width:100%; }
  .management-actions { gap:8px; }
  .management-actions form:first-child { width:100%; }
  .management-actions form:first-child input { width:100%; }
  .workspace-header span { display:none; }
  .file-row { grid-template-columns:minmax(0,1fr) 76px; gap:4px 12px; }
  .file-name { grid-row:span 2; }
  .file-size { grid-column:2; font-size:10px; }
  .file-row>.btn { grid-column:2; font-size:10px; padding:4px; min-height:36px; }
  .browser-toolbar { padding:14px; }
  .browser-toolbar input { flex-basis:100%; width:100%; font-size:16px; }
  .browser-toolbar button { flex:1; }
  .browser-shortcuts { padding:0 14px 12px; }
  .browser-shortcuts button { min-height:44px; }
  .browser-heading { padding:14px; }
  .browser-filter { margin:0 14px 14px; width:calc(100% - 28px); font-size:16px; }
  .file-list { padding:0 14px; }
  .breadcrumb-path { padding:10px 14px; }
  .breadcrumb-path button { min-height:44px; }
  .browser-note { padding:12px 14px; }
  .file-name button { min-height:44px; }
  .result-fields { grid-template-columns:1fr; gap:3px; }
  .result-fields dd { margin-bottom:10px; }
  .execution-heading,.execution-body { padding:14px; }
  .execution-heading button { min-height:44px; }
  .execution-result h3 { align-items:flex-start; }
  .command-list .field-form .row { flex-wrap:wrap; }
  .command-list .field-form input { font-size:16px; }
  .filter-menu { width:100%; }
  .filter-menu>summary { display:flex; padding:8px 0; }
  .filter-menu>div { flex-wrap:wrap; padding-bottom:4px; }
}

"""

# ---------------------------------------------------------------------------
# JS — external only. Handles CSRF injection, confirmations, search, tabs,
# frame polling, upload progress, and abort. No inline handlers.
# ---------------------------------------------------------------------------
FONT_WOFF2 = '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'

JS = "var ghostConsoleFont = " + json.dumps(FONT_WOFF2) + ";\n" + r"""
(function () {
  "use strict";
  if (window.FontFace) {
    var fontBytes = Uint8Array.from(atob(ghostConsoleFont), function (c) { return c.charCodeAt(0); });
    new FontFace("IBM Plex Mono", fontBytes.buffer, { weight: "400", display: "swap" })
      .load().then(function (font) { document.fonts.add(font); }).catch(function () {});
  }


  // ---- CSRF: inject hidden token into every form -------------------------
  var meta = document.querySelector('meta[name="csrf-token"]');
  var token = meta ? meta.getAttribute("content") : "";
  document.addEventListener("submit", function (ev) {
    var form = ev.target;
    if (form.tagName !== "FORM" || form.dataset.noCsrf === "1") return;
    var inp = form.querySelector('input[name="csrf_token"]');
    if (!inp) {
      inp = document.createElement("input");
      inp.type = "hidden";
      inp.name = "csrf_token";
      form.appendChild(inp);
    }
    inp.value = token;
  }, true);

  // ---- Confirmations via data-confirm ------------------------------------
  document.addEventListener("click", function (ev) {
    var el = ev.target.closest("[data-confirm]");
    if (!el) return;
    var msg = el.getAttribute("data-confirm") || "Are you sure?";
    if (!window.confirm(msg)) {
      ev.preventDefault();
      ev.stopPropagation();
    }
  }, true);

  // ---- Tabs --------------------------------------------------------------
  document.addEventListener("click", function (ev) {
    var tab = ev.target.closest("[data-tab]");
    if (!tab) return;
    ev.preventDefault();
    var group = tab.getAttribute("data-tab-group");
    var picker = tab.closest(".tool-picker");
    if (picker) {
      picker.querySelector("[data-active-tool]").textContent = tab.textContent.trim();
      if (compact.matches) { picker.open = false; picker.querySelector("summary").focus(); }
      history.replaceState(null, "", "#tool-" + tab.getAttribute("data-tab"));
    }
    document.querySelectorAll('[data-tab-group="' + group + '"]').forEach(function (t) {
      t.classList.toggle("active", t === tab);
    });
    document.querySelectorAll('[data-panel-group="' + group + '"]').forEach(function (p) {
      p.classList.toggle("active", p.getAttribute("data-panel") === tab.getAttribute("data-tab"));
    });
  }, true);

  var compact = window.matchMedia("(max-width: 600px)");
  function adaptDisclosures() {
    document.querySelectorAll("[data-responsive]").forEach(function (el) { el.open = !compact.matches; });
  }
  adaptDisclosures();
  compact.addEventListener("change", adaptDisclosures);
  // ---- Search / filter ---------------------------------------------------
  var search = document.getElementById("device-search");
  if (search) {
    search.addEventListener("input", function () {
      var q = search.value.toLowerCase();
      document.querySelectorAll("[data-device-row]").forEach(function (row) {
        var text = row.getAttribute("data-search-text") || "";
        row.classList.toggle("hidden", q && text.indexOf(q) === -1);
      });
      var pendingOnly = document.getElementById("pending-only");
      if (pendingOnly) {
        document.querySelectorAll("[data-device-row]").forEach(function (row) {
          if (pendingOnly.checked && row.getAttribute("data-pending") !== "1") {
            row.classList.add("hidden");
          }
        });
      }
    });
  }
  var pendingOnly = document.getElementById("pending-only");
  if (pendingOnly) {
    pendingOnly.addEventListener("change", function () {
      document.querySelectorAll("[data-device-row]").forEach(function (row) {
        if (pendingOnly.checked && row.getAttribute("data-pending") !== "1") {
          row.classList.add("hidden");
        } else {
          row.classList.remove("hidden");
        }
      });
      if (search) search.dispatchEvent(new Event("input"));
    });
  }

  // ---- Inline command output and file browsing ---------------------------
  var output = document.getElementById("command-output");
  var outputBody = document.getElementById("command-results");
  var outputState = document.getElementById("command-state");
  var commandBusy = false;
  var browserForm = document.getElementById("browser-form");
  var currentFolder = null;

  function responseError(doc) {
    return (doc.querySelector("main") || doc.body).textContent.replace(/\s+/g, " ").trim().slice(0, 400);
  }

  function parseResponse(text) {
    var doc = new DOMParser().parseFromString(text, "text/html");
    var raw = doc.getElementById("result-data");
    var content = doc.getElementById("result-content");
    if (!raw || !content) {
      var message = responseError(doc);
      throw new Error(message.slice(0, 400) || "The server returned no readable response. Reload and check command history before retrying.");
    }
    var data;
    try { data = JSON.parse(raw.textContent); }
    catch (error) { throw new Error("The response could not be read. Check saved command history before retrying."); }
    return { ok: content.dataset.resultOk === "1", data: data, content: content, raw: raw.textContent };
  }

  function resultPayload(data) {
    if (data && data.entry && typeof data.entry === "object") {
      return Object.prototype.hasOwnProperty.call(data.entry, "result") ? data.entry.result : data.entry;
    }
    return data && Object.prototype.hasOwnProperty.call(data, "result") ? data.result : data;
  }

  function revealOutput() {
    if (output) output.classList.remove("hidden");
  }

  function showResponse(label, response) {
    if (!outputBody) return;
    revealOutput();
    var article = document.createElement("article");
    article.className = "execution-result";
    var heading = document.createElement("h3");
    var title = document.createElement("span");
    title.textContent = label;
    heading.appendChild(title);
    var status = document.createElement("span");
    status.className = "badge " + (response.ok ? "ok" : "err");
    status.textContent = response.ok ? "Completed" : "Failed";
    heading.appendChild(status);
    article.appendChild(heading);
    article.appendChild(document.importNode(response.content, true));
    article.lastChild.removeAttribute("id");
    var details = document.createElement("details");
    var summary = document.createElement("summary");
    summary.textContent = "Technical details";
    var pre = document.createElement("pre");
    pre.textContent = response.raw;
    details.append(summary, pre);
    article.appendChild(details);
    outputBody.prepend(article);
    while (outputBody.children.length > 5) outputBody.lastElementChild.remove();
    if (outputState) outputState.textContent = label + (response.ok ? " completed." : " failed. See the response below.");
    output.focus({ preventScroll: true });
    output.scrollIntoView({ block: "nearest", behavior: "auto" });
  }

  function showCommandError(message) {
    revealOutput();
    if (outputState) outputState.textContent = message;
  }

  async function sendCommand(form, overrides, quiet) {
    if (commandBusy) throw new Error("Another command is running. Wait for its result before sending another.");
    commandBusy = true;
    document.body.classList.add("command-busy");
    var buttons = Array.from(document.querySelectorAll("[data-inline-command] button[type=submit]"));
    var enabled = buttons.filter(function (button) { return !button.disabled; });
    enabled.forEach(function (button) { button.disabled = true; });
    var params = new URLSearchParams(new FormData(form));
    params.set("csrf_token", token);
    if (overrides) Object.keys(overrides).forEach(function (key) { params.set(key, overrides[key]); });
    var verb = params.get("verb") || "Command";
    var started = Date.now();
    var controller = new AbortController();
    var timeout = setTimeout(function () { controller.abort(); }, 60000);
    var ticker;
    if (!quiet) {
      revealOutput();
      function updateElapsed() { outputState.textContent = verb + " running… " + Math.floor((Date.now() - started) / 1000) + "s"; }
      updateElapsed(); ticker = setInterval(updateElapsed, 1000);
      output.scrollIntoView({ block: "nearest", behavior: "auto" });
    }
    try {
      var response = await fetch(form.action, { method: "POST", body: params, credentials: "same-origin", signal: controller.signal });
      if (response.status === 401) throw new Error("Authentication expired. Reload and sign in again.");
      var result = parseResponse(await response.text());
      if (!response.ok) result.ok = false;
      return result;
    } catch (error) {
      if (error.name === "AbortError") throw new Error("No response within 60 seconds. The command may still finish; check command history before retrying.");
      if (error.name === "TypeError") throw new Error("Connection lost. The command may still finish; check command history before retrying.");
      throw error;
    } finally {
      clearTimeout(timeout); clearInterval(ticker);
      commandBusy = false;
      enabled.forEach(function (button) { button.disabled = false; });
      document.body.classList.remove("command-busy");
    }
  }

  document.addEventListener("submit", async function (ev) {
    var form = ev.target;
    if (!form.matches("[data-inline-command]") || !output) return;
    ev.preventDefault();
    if (form === browserForm) { browseFolder(form.elements.arg.value); return; }
    var label = form.dataset.commandLabel || form.elements.verb.value;
    try {
      var result = await sendCommand(form);
      showResponse(label, result);
    } catch (error) {
      showCommandError(error.message || "Connection failed. Check command history before retrying.");
    }
  });
  var dismissOutput = document.getElementById("dismiss-output");
  if (dismissOutput) dismissOutput.addEventListener("click", function () {
    if (commandBusy) return;
    output.classList.add("hidden");
    var pickerSummary = document.querySelector(".tool-picker>summary");
    var activeTab = document.querySelector(".tab.active");
    (compact.matches ? pickerSummary : activeTab).focus();
  });

  function fileNote(message, error) {
    var note = document.getElementById("browser-note");
    if (note) { note.textContent = message; note.classList.toggle("error", !!error); }
  }
  function fileIcon(kind) {
    return document.getElementById(kind + "-icon").content.firstElementChild.cloneNode(true);
  }
  function humanSize(value) {
    var bytes = Number(value);
    if (!Number.isFinite(bytes) || bytes < 0) return "—";
    if (bytes < 1024) return bytes + " B";
    var units = ["KB", "MB", "GB", "TB"];
    var level = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)) - 1, 3);
    return (bytes / Math.pow(1024, level + 1)).toLocaleString(undefined, { maximumFractionDigits: 1 }) + " " + units[level];
  }
  function drawBreadcrumbs(path) {
    var crumbs = document.getElementById("browser-breadcrumbs");
    crumbs.replaceChildren();
    var parts = path.split("/").filter(Boolean);
    var prefix = "";
    ["/"].concat(parts).forEach(function (name, index) {
      if (index > 0) prefix += "/" + name;
      var button = document.createElement("button");
      button.type = "button"; button.dataset.folder = prefix || "/"; button.textContent = name;
      button.setAttribute("aria-label", "Open " + (prefix || "/"));
      crumbs.appendChild(button);
      if (index > 0 && index < parts.length) { var sep = document.createElement("span"); sep.textContent = "/"; sep.className = "muted"; sep.setAttribute("aria-hidden", "true"); crumbs.appendChild(sep); }
    });
  }
  function filterFiles() {
    var filter = document.getElementById("file-filter");
    var rows = Array.from(document.querySelectorAll(".file-row"));
    var query = filter.value.toLowerCase();
    rows.forEach(function (row) { row.classList.toggle("hidden", row.dataset.filename.indexOf(query) === -1); });
    document.getElementById("file-empty").classList.toggle("hidden", rows.some(function (row) { return !row.classList.contains("hidden"); }));
    document.getElementById("file-empty").textContent = rows.length ? "No files match this filter." : "This folder is empty.";
  }
  async function browseFolder(path) {
    if (!browserForm) return;
    path = path.trim().replace(/\/+$/, "") || "/";
    if (!path.startsWith("/") || /[\s\x00-\x1f]/.test(path)) {
      fileNote("Use an absolute path without spaces. This server cannot list folder paths containing spaces; exact-path file downloads still work.", true); return;
    }
    if (commandBusy) { fileNote("Another command is running. Wait for its result, then browse again.", true); return; }
    fileNote("Reading " + path + "…");
    var list = document.getElementById("file-list");
    list.setAttribute("aria-busy", "true");
    try {
      var result = await sendCommand(browserForm, { arg: path }, true);
      if (!result.ok) { showResponse("Browse folder", result); throw new Error("Could not open " + path + ". See the device response. The previous listing is unchanged."); }
      var entries = resultPayload(result.data);
      if (!Array.isArray(entries)) throw new Error("The device did not return a directory listing. The previous listing is unchanged.");
      var fragment = document.createDocumentFragment();
      entries.forEach(function (file) {
        if (!file || typeof file.path !== "string" || !file.path.startsWith("/") || typeof file.name !== "string") return;
        var row = document.createElement("div"); row.className = "file-row"; row.dataset.filename = file.name.toLowerCase(); row.setAttribute("role", "listitem");
        var name = document.createElement("div"); name.className = "file-name"; name.appendChild(fileIcon(file.dir === true ? "folder" : "file"));
        if (file.dir === true) {
          var folder = document.createElement("button"); folder.type = "button"; folder.textContent = file.name; folder.dataset.folder = file.path; folder.setAttribute("aria-label", "Open folder " + file.name); name.appendChild(folder);
        } else {
          var text = document.createElement("span"); text.textContent = file.name; name.appendChild(text);
        }
        row.appendChild(name);
        var size = document.createElement("span"); size.className = "file-size"; size.textContent = file.dir === true ? "Folder" : humanSize(file.size); row.appendChild(size);
        if (file.dir !== true) {
          var link = document.createElement("a"); link.className = "btn small secondary"; link.textContent = "Download";
          link.href = "/files/download?" + new URLSearchParams({ device_id: browserForm.dataset.deviceId, path: file.path });
          link.setAttribute("aria-label", "Download " + file.name); row.appendChild(link);
        }
        fragment.appendChild(row);
      });
      list.replaceChildren(fragment);
      currentFolder = path;
      browserForm.elements.arg.value = path;
      document.getElementById("file-filter").value = "";
      drawBreadcrumbs(path);
      document.getElementById("browser-up").disabled = path === "/";
      document.getElementById("file-count").textContent = entries.length + " items";
      var uploadDest = document.querySelector('#upload-form input[name="dest"]');
      if (uploadDest && !uploadDest.value) { uploadDest.placeholder = "Blank = /sdcard/Download/<filename>"; }
      filterFiles(); fileNote("Read " + entries.length + " items from " + path + ".");
    } catch (error) { fileNote(error.message || "Unable to read folder. Try again when the device is online.", true); }
    finally { list.removeAttribute("aria-busy"); }
  }
  document.addEventListener("click", function (ev) {
    var folder = ev.target.closest("[data-folder]");
    if (folder) browseFolder(folder.dataset.folder);
  });
  var up = document.getElementById("browser-up");
  if (up) up.addEventListener("click", function () {
    if (currentFolder) browseFolder(currentFolder.slice(0, currentFolder.lastIndexOf("/")) || "/");
  });
  var fileFilter = document.getElementById("file-filter");
  if (fileFilter) fileFilter.addEventListener("input", filterFiles);

  // ---- Stream single-frame polling ---------------------------------------
  var streamImg = document.getElementById("stream-img");
  var streamNote = document.getElementById("stream-note");
  var streamToggle = document.getElementById("stream-toggle");
  var streamStop = document.getElementById("stream-stop");
  var streamFrameAge = document.getElementById("stream-age");
  var streamTimer = null;
  var streamRunning = false;

  function setStreamNote(msg, cls) {
    if (!streamNote) return;
    streamNote.textContent = msg;
    streamNote.className = "stream-note" + (cls ? " " + cls : "");
  }

  function pollFrame() {
    if (!streamImg || !streamRunning) return;
    var feed = streamImg.getAttribute("data-feed");
    var tok = streamImg.getAttribute("data-token");
    if (!feed) return;
    var url = feed + "?t=" + encodeURIComponent(tok) + "&_=" + Date.now();
    var xhr = new XMLHttpRequest();
    xhr.open("GET", url, true);
    xhr.responseType = "blob";
    xhr.timeout = 8000;
    xhr.onload = function () {
      if (!streamRunning) return;
      if (xhr.status === 200) {
        var old = streamImg.getAttribute("data-object-url");
        var newUrl = URL.createObjectURL(xhr.response);
        streamImg.src = newUrl;
        streamImg.setAttribute("data-object-url", newUrl);
        if (old) URL.revokeObjectURL(old);
        setStreamNote("live frame received", "ok");
        if (streamFrameAge) streamFrameAge.textContent = "just now";
      } else {
        setStreamNote("no live frame (" + xhr.status + ")", "warn");
      }
      if (streamRunning) streamTimer = setTimeout(pollFrame, 1000);
    };
    xhr.onerror = function () {
      if (!streamRunning) return;
      setStreamNote("feed unreachable", "err");
      streamTimer = setTimeout(pollFrame, 2000);
    };
    xhr.ontimeout = function () {
      if (!streamRunning) return;
      setStreamNote("feed timeout", "warn");
      streamTimer = setTimeout(pollFrame, 2000);
    };
    xhr.send();
  }

  function startStream() {
    if (streamRunning) return;
    streamRunning = true;
    if (streamToggle) streamToggle.classList.add("hidden");
    if (streamStop) streamStop.classList.remove("hidden");
    setStreamNote("starting stream…", "warn");
    pollFrame();
  }

  function stopStream() {
    streamRunning = false;
    if (streamTimer) { clearTimeout(streamTimer); streamTimer = null; }
    if (streamToggle) streamToggle.classList.remove("hidden");
    if (streamStop) streamStop.classList.add("hidden");
    setStreamNote("stream stopped", "muted");
  }

  if (streamToggle) {
    streamToggle.addEventListener("click", function (ev) {
      ev.preventDefault();
      var form = document.getElementById("stream-start-form");
      if (form) form.submit();
      else startStream();
    });
  }
  if (streamStop) {
    streamStop.addEventListener("click", function (ev) {
      ev.preventDefault();
      var form = document.getElementById("stream-stop-form");
      if (form) form.submit();
      else stopStream();
    });
  }
  window.addEventListener("beforeunload", stopStream);

  // ---- Upload progress ---------------------------------------------------
  var uploadForm = document.getElementById("upload-form");
  if (uploadForm) {
    uploadForm.addEventListener("submit", function (ev) {
      ev.preventDefault();
      if (uploadForm.dataset.uploading === "1") return;
      uploadForm.dataset.uploading = "1";
      var uploadSubmit = uploadForm.querySelector('button:not([type="button"])');
      if (uploadSubmit) uploadSubmit.disabled = true;
      var formData = new FormData(uploadForm);
      var xhr = new XMLHttpRequest();
      var bar = document.getElementById("upload-progress");
      var note = document.getElementById("upload-note");
      var abortBtn = document.getElementById("upload-abort");

      function setNote(msg) { if (note) note.textContent = msg; }
      function hideAbort() {
        if (abortBtn) abortBtn.classList.add("hidden");
        uploadForm.dataset.uploading = "0";
        if (uploadSubmit) uploadSubmit.disabled = false;
      }
      function resetBar() { if (bar) bar.querySelector("div").style.width = "0%"; }


      xhr.open("POST", uploadForm.action, true);
      // Slightly above the server's bounded upload RPC wait (180s + slack).
      xhr.timeout = 200000;
      xhr.upload.onprogress = function (e) {
        if (e.lengthComputable && bar) {
          var pct = Math.round((e.loaded / e.total) * 100);
          bar.querySelector("div").style.width = pct + "%";
          setNote(pct === 100 ? "Sent to console; waiting for device confirmation…" : "Sending to console: " + pct + "%");
        }
      };
      xhr.onload = function () {
        hideAbort();
        var result;
        try { result = parseResponse(xhr.responseText); }
        catch (error) { resetBar(); setNote("Upload response could not be read: " + error.message); return; }
        if (xhr.status >= 200 && xhr.status < 300 && result.ok) {
          if (bar) bar.querySelector("div").style.width = "100%";
          setNote("Upload complete. Device response is shown above.");
        } else {
          resetBar();
          result.ok = false;
          setNote("Upload failed. See the device response above.");
        }
        showResponse("File upload", result);
      };
      xhr.onerror = function () {
        hideAbort();
        resetBar();
        setNote("upload failed: network error while sending");
      };
      xhr.ontimeout = function () {
        hideAbort();
        resetBar();
        setNote("upload timed out; server-side work may still finish");
      };
      xhr.onabort = function () {
        hideAbort();
        resetBar();
        setNote("upload cancelled by operator");
      };
      if (abortBtn) {
        abortBtn.classList.remove("hidden");
        abortBtn.onclick = function () {
          xhr.abort();
          var didInput = uploadForm.querySelector('input[name="device_id"]');
          var body = new URLSearchParams();
          body.append("csrf_token", token);
          body.append("device_id", didInput ? didInput.value : "");
          setNote("upload cancelled; requesting a device-side abort " +
                  "(server-side work may still finish)…");
          // Real daemon command `files abort`, not just a local ack.
          fetch("/files/abort", { method: "POST", body: body,
                                  credentials: "same-origin" })
            .then(function (r) {
              return r.text().then(function (t) {
                setNote("upload cancelled; abort " +
                        (r.ok ? "requested" : "failed") + ": " +
                        t.replace(/\s+/g, " ").trim().slice(0, 200));
              });
            })
            .catch(function () {
              setNote("upload cancelled; abort request could not be delivered");
            });
        };
      }
      xhr.send(formData);
    });
  }

  var requestedTool = location.hash.match(/^#tool-(files|screen|ui|media|system)$/);
  if (requestedTool) {
    var requestedTab = document.querySelector('[data-tab="' + requestedTool[1] + '"]');
    if (requestedTab) requestedTab.click();
  }

  // ---- Auto-refresh dashboard -------------------------------------------
  var refresh = document.getElementById("auto-refresh");
  if (refresh && refresh.checked) {
    setTimeout(function () { window.location.reload(); }, 5000);
  }
})();
"""

# ---------------------------------------------------------------------------
# HTML base template
# ---------------------------------------------------------------------------
def icon(name):
    # Lucide icons (ISC); see ui_assets/Lucide-LICENSE.txt.
    paths = {'devices': '<rect width="18" height="18" x="3" y="3" rx="2" />  <path d="M3 9h18" />  <path d="M9 21V9" />', 'settings': '<path d="M10 5H3" />  <path d="M12 19H3" />  <path d="M14 3v4" />  <path d="M16 17v4" />  <path d="M21 12h-9" />  <path d="M21 19h-5" />  <path d="M21 5h-7" />  <path d="M8 10v4" />  <path d="M8 12H3" />', 'phone': '<rect width="14" height="20" x="5" y="2" rx="2" ry="2" />  <path d="M12 18h.01" />', 'pulse': '<path d="M22 12h-2.48a2 2 0 0 0-1.93 1.46l-2.35 8.36a.25.25 0 0 1-.48 0L9.24 2.18a.25.25 0 0 0-.48 0l-2.35 8.36A2 2 0 0 1 4.49 12H2" />', 'clock': '<circle cx="12" cy="12" r="10" />  <path d="M12 6v6h4" />', 'search': '<path d="m21 21-4.34-4.34" />  <circle cx="11" cy="11" r="8" />', 'arrow': '<path d="M7 7h10v10" />  <path d="M7 17 17 7" />', 'screen': '<rect width="20" height="14" x="2" y="3" rx="2" />  <line x1="8" x2="16" y1="21" y2="21" />  <line x1="12" x2="12" y1="17" y2="21" />', 'mark': '<path d="M15 10v1" />  <path d="M7.528 20.472a1.6 1.6 0 012.277 0l1.057 1.056a1.6 1.6 0 002.276 0l1.057-1.056a1.6 1.6 0 012.277 0l1.114 1.114a1.4 1.4 0 002.414-1V10a8 8 0 00-16 0v10.586a1.4 1.4 0 002.414 1z" />  <path d="M9 10v1" />', 'folder': '<path d="M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z" />', 'file': '<path d="M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z" />  <path d="M14 2v5a1 1 0 0 0 1 1h5" />', 'terminal': '<path d="M12 19h8" />  <path d="m4 17 6-6-6-6" />', 'chevron': '<path d="m6 9 6 6 6-6" />', 'upload': '<path d="M12 3v12" />  <path d="m17 8-5-5-5 5" />  <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />', 'download': '<path d="M12 15V3" />  <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />  <path d="m7 10 5 5 5-5" />', 'back': '<path d="m12 19-7-7 7-7" />  <path d="M19 12H5" />', 'refresh': '<path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8" />  <path d="M21 3v5h-5" />  <path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16" />  <path d="M8 16H3v5" />'}
    return f'<svg class="icon" viewBox="0 0 24 24" aria-hidden="true">{paths[name]}</svg>'


def render_page(title, body, csrf_token="", refresh=None, extra_head=""):
    head = ""
    if refresh:
        head += f'<meta http-equiv="refresh" content="{int(refresh)}">'
    head += extra_head
    favicon = "data:image/svg+xml," + quote(icon("mark").replace('<svg ', '<svg xmlns="http://www.w3.org/2000/svg" ').replace('stroke="currentColor"', 'stroke="#ee898c"'), safe="")
    return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#090b0e">
<meta name="application-name" content="ghostctl">
<meta name="csrf-token" content="{e(csrf_token)}">
<title>{e(title)} — ghostctl</title>
<link rel="icon" href="{e(favicon)}" type="image/svg+xml">
<link rel="stylesheet" href="/static/app.css">
{head}
</head>
<body>
<a class="skip-link" href="#main-content">Skip to content</a>
<nav aria-label="Main navigation">
  <a class="brand" href="/" aria-label="ghostctl home"><span class="brand-mark">{icon('mark')}</span><span class="brand-name">ghostctl<span class="muted">.</span></span></a>
  <span class="nav-caption">WORKSPACE</span>
  <a href="/" class="nav-link {'active' if title != 'Settings' else ''}" {'aria-current="page"' if title == 'Devices' else ''}>{icon('devices')}<span class="nav-label">Devices</span></a>
  <a href="/settings" class="nav-link {'active' if title == 'Settings' else ''}" {'aria-current="page"' if title == 'Settings' else ''}>{icon('settings')}<span class="nav-label">Settings</span></a>
  <div class="nav-bottom">
    <span class="eyebrow">Your workspace</span>
    <p>Owner-authorized device<br>management &amp; support.</p>
    <div class="operator"><span class="avatar">OP</span><span>Operator console<br><span class="muted small">ghostctl</span></span></div>
  </div>
</nav>
<header class="topbar">
  <div class="breadcrumb">Workspace <span class="muted">/</span><span>{e(title)}</span></div>
  <div class="topbar-meta"><span class="small">Device management</span><span class="avatar" aria-label="Operator">OP</span></div>
</header>
<main id="main-content" tabindex="-1">
{body}
<footer class="page-footer"><span>ghostctl console</span><span>Device management &amp; remote support</span></footer>
</main>
<script src="/static/app.js"></script>
</body>
</html>"""


def _csrf_input(csrf_token):
    return f'<input type="hidden" name="csrf_token" value="{e(csrf_token)}">'


def _badge(online, approved):
    if approved and online:
        return '<span class="badge ok">online</span>'
    if approved and not online:
        return '<span class="badge muted">offline</span>'
    if online and not approved:
        return '<span class="badge warn">pending</span>'
    return '<span class="badge err">pending / offline</span>'


def render_dashboard(devices, pending, csrf_token=""):
    """Responsive device cards/table with search/filter and pending approve."""
    cards = []
    for did in sorted(devices):
        if did in pending:
            # Pending ids are also present in the device list: render them
            # once, in the pending-approval section below.
            continue
        d = devices[did] or {}
        online = bool(d.get("online"))
        approved = bool(d.get("approved"))
        name = d.get("name") or did
        model = d.get("model") or "-"
        app_ver = d.get("app_version") or d.get("version") or "-"
        side_ver = d.get("sidekick_version") or "-"
        bat = d.get("battery")
        bat = f"{bat}%" if bat is not None else "-"
        ip = d.get("ip") or "-"
        last = fmt_age(d.get("last_seen"))
        search_text = f"{did} {name} {model} {app_ver} {side_ver} {ip} {bat}".lower()
        pending_attr = "1" if not approved else "0"
        cards.append(f"""
<div class="card device-card {'pending-card' if not approved else ''}" data-device-row data-search-text="{e(search_text)}" data-pending="{pending_attr}">
  <div class="head">
    <div class="device-identity"><span class="device-glyph">{icon("phone")}</span><div>
      <a href="/device/{e(quote_safe(did))}"><strong>{e(name)}</strong></a>
      <div class="small muted mono">{e(did)}</div>
    </div></div>
    {_badge(online, approved)}
  </div>
  <details class="ui-disclosure device-more" data-responsive open><summary>Device details</summary><div><dl class="meta">
    <dt>Model</dt><dd>{e(model)}</dd>
    <dt>App</dt><dd>{e(app_ver)}</dd>
    <dt>Sidekick</dt><dd>{e(side_ver)}</dd>
    <dt>Battery</dt><dd>{e(bat)}</dd>
    <dt>IP</dt><dd>{e(ip)}</dd>
    <dt>Last seen</dt><dd>{e(last)}</dd>
  </dl></div></details>
  <div class="row device-actions">
    <a class="btn small" href="/device/{e(quote_safe(did))}">Open device {icon("arrow")}</a>
    <a class="btn small secondary" href="/stream/{e(quote_safe(did))}">{icon("screen")} Screen</a>
    {'' if approved else f'''<form method="post" action="/device/{e(quote_safe(did))}/approve" style="display:inline"><input type="hidden" name="csrf_token" value="{e(csrf_token)}"><button class="small">Approve</button></form>'''}
    <details class="device-management"><summary>Manage device</summary><div>    <form method="post" action="/device/{e(quote_safe(did))}/rename" style="display:inline"><input type="hidden" name="csrf_token" value="{e(csrf_token)}"><input type="text" name="name" placeholder="Device name" aria-label="New name for {e(name)}" size="12"><button class="small secondary">Rename</button></form>
    {'' if not approved else f'''<form method="post" action="/device/{e(quote_safe(did))}/remove" style="display:inline"><input type="hidden" name="csrf_token" value="{e(csrf_token)}"><button class="small danger">Remove device</button></form>'''}</div></details>
  </div>
</div>""")

    pending_cards = []
    for did in sorted(pending):
        d = pending[did] or {}
        name = d.get("name") or did
        ip = d.get("ip") or "-"
        last = fmt_age(d.get("last_seen"))
        pending_cards.append(f"""
<div class="card device-card pending-card" data-device-row data-search-text="{e(did + ' ' + name + ' ' + ip)}" data-pending="1">
  <div class="head">
    <div><strong>{e(name)}</strong><div class="small muted mono">{e(did)}</div></div>
    <span class="badge warn">pending</span>
  </div>
  <div class="row small muted">IP: {e(ip)} · Last seen: {e(last)}</div>
  <div class="row">
    <form method="post" action="/device/{e(quote_safe(did))}/approve" style="display:inline"><input type="hidden" name="csrf_token" value="{e(csrf_token)}"><button class="small">Approve</button></form>
    <form method="post" action="/device/{e(quote_safe(did))}/revoke" style="display:inline"><input type="hidden" name="csrf_token" value="{e(csrf_token)}"><button class="small danger" data-confirm="Revoke pending device {e(name)}? The token will be rotated.">Revoke</button></form>
  </div>
</div>""")

    pending_section = ""
    if pending_cards:
        pending_section = f"""
<h2>Pending approval ({len(pending_cards)})</h2>
<div class="grid pending-grid">{''.join(pending_cards)}</div>
"""

    fleet = [d or {} for did, d in devices.items() if did not in pending]
    online_count = sum(bool(d.get("online")) for d in fleet)
    offline_count = len(fleet) - online_count
    stats = "".join(
        f'<div class="stat {cls}"><div class="stat-top">{label}{icon(glyph)}</div>'
        f'<strong>{count:02d}</strong><small>{caption}</small></div>'
        for label, count, caption, glyph, cls in (
            ("Total devices", len(fleet), "In your workspace", "phone", ""),
            ("Online now", online_count, "Connected devices", "pulse", "online"),
            ("Offline", offline_count, "Currently disconnected", "screen", ""),
            ("Pending approval", len(pending), "Awaiting your review", "clock", ""),
        )
    )
    empty = ('<div class="empty">No approved devices yet.</div>'
             if pending_cards else f'<div class="empty">{icon("phone")}No devices yet.<br><span class="small">Enrolled devices will appear here for your review.</span></div>')
    body = f"""
<div class="page-heading">
  <div><div class="eyebrow">Your device workspace</div><h1>Devices</h1><p>Monitor connections and manage enrolled devices.</p></div>
  <span class="heading-tag">Workspace overview</span>
</div>
<div class="stats" aria-label="Device overview">{stats}</div>
<h2 class="section-title">Device directory <span class="count">{len(fleet)}</span></h2>
<div class="search-row">
  <div class="search-field">{icon('search')}<input type="search" id="device-search" aria-label="Search devices" placeholder="Search devices, models, or IP addresses…" autocomplete="off"></div>
  <details class="ui-disclosure filter-menu" data-responsive open><summary>Filters &amp; refresh</summary><div>
  <label class="small"><input type="checkbox" id="pending-only"> Pending only</label>
  <label class="small"><input type="checkbox" id="auto-refresh" checked> Auto-refresh</label>
  </div></details>
</div>
{pending_section}
<div class="grid">{''.join(cards) or empty}</div>
"""
    return render_page("Devices", body, csrf_token=csrf_token)


ACTION_GROUPS = {
    "Files": [
        ("files ls", [("arg", "/sdcard", "/sdcard")]),
        ("files tree", [("arg", "/sdcard --depth 3", "/sdcard")]),
        # Uploads/downloads have dedicated forms below; never route file
        # contents through the generic command result (base64 dump).
        ("files abort", []),
    ],
    "Screen": [
        ("screen shot", []),
        ("screen record", [("arg", "10 --fps 5", "10")]),
        ("screen live", [("arg", "--fps 5 --quality 60", "")]),
        ("screen stop", []),
        ("screen off", []),
    ],
    "UI": [
        ("ui dump", []),
        ("ui find", [("arg", "--text Login", "")]),
        ("tap", [("arg", "100 200", "")]),
        ("longclick", [("arg", "100 200", "")]),
        ("type", [("arg", "hello world", "")]),
        ("key", [("arg", "4", "")]),
        ("swipe", [("arg", "100 200 300 400", "")]),
    ],
    "Media": [
        ("cam snap", [("arg", "back", "back")]),
        ("cam live", [("arg", "back --quality high", "back")]),
        ("cam stop", []),
        ("mic record", [("arg", "10", "10")]),
        ("mic live", []),
        ("mic stop", []),
    ],
    "System": [
        ("shell", [("arg", "echo hi", "")]),
        ("arm", []),
        ("disarm", []),
        ("sidekick status", []),
        ("sidekick arm", []),
        ("sidekick disarm", []),
        ("sidekick install", [("arg", "/sdcard/Download/ghosta.apk", "")]),
        ("brightness get", []),
        ("brightness set", [("arg", "10", "10")]),
        ("volume get", [("arg", "media", "media")]),
        ("volume set", [("arg", "media 30", "media 30")]),
        ("volume mute", [("arg", "media", "media")]),
        ("volume unmute", [("arg", "media", "media")]),
        ("wake", []),
        ("sms recent", [("arg", "20", "20")]),
        ("loc once", []),
        ("device info", []),
        ("hide", []),
        ("unhide", []),
        ("status", []),
        ("restart", []),
    ],
}

# Verbs allowed on device command forms (no destroy, no ping, no generic update).
ALLOWED_VERBS = {verb for actions in ACTION_GROUPS.values() for verb, _ in actions}

# Broadcast limited to safe/idempotent read/stop actions. No destroy, no update.
BROADCAST_VERBS = {
    "status", "screen stop", "cam stop", "mic stop", "arm", "disarm",
    "hide", "unhide", "wake", "brightness get", "volume get",
    "sidekick status", "device info",
}


# Presentation-only groupings; the command allowlists above remain authoritative.
ACTION_SECTIONS = {
    "Files": [("Advanced file tools", ["files tree", "files abort"])],
    "Screen": [("Capture", ["screen shot", "screen record"]),
               ("Live view", ["screen live", "screen stop"]),
               ("Display power", ["screen off"])],
    "UI": [("Inspect interface", ["ui dump", "ui find"]),
           ("Touch gestures", ["tap", "longclick", "swipe"]),
           ("Text & keys", ["type", "key"])],
    "Media": [("Camera", ["cam snap", "cam live", "cam stop"]),
              ("Microphone", ["mic record", "mic live", "mic stop"])],
    "System": [("Device information", ["status", "device info", "sms recent", "loc once"]),
               ("Display", ["wake", "brightness get", "brightness set", "hide", "unhide"]),
               ("Audio", ["volume get", "volume set", "volume mute", "volume unmute"]),
               ("Sidekick", ["sidekick status", "sidekick arm", "sidekick disarm", "sidekick install"]),
               ("Session & recovery", ["arm", "disarm", "restart"]),
               ("Terminal", ["shell"])],
}

ACTION_LABELS = {
    "shell": "Run shell command", "files ls": "List folder", "files tree": "Directory tree", "files abort": "Abort stuck transfer",
    "screen shot": "Take screenshot", "screen record": "Record screen",
    "screen live": "Start live screen", "screen stop": "Stop live screen", "screen off": "Turn screen off",
    "ui dump": "Read interface", "ui find": "Find element", "tap": "Tap", "longclick": "Long press",
    "swipe": "Swipe", "type": "Type text", "key": "Send key",
    "cam snap": "Take photo", "cam live": "Start camera", "cam stop": "Stop camera",
    "mic record": "Record audio", "mic live": "Start microphone", "mic stop": "Stop microphone",
    "status": "Check status", "device info": "Device information", "sms recent": "Read recent SMS",
    "loc once": "Request location", "wake": "Wake screen", "brightness get": "Read brightness",
    "brightness set": "Set brightness", "hide": "Hide launcher icon", "unhide": "Show launcher icon",
    "volume get": "Read volume", "volume set": "Set volume", "volume mute": "Mute", "volume unmute": "Unmute",
    "sidekick status": "Check sidekick", "sidekick arm": "Arm sidekick", "sidekick disarm": "Disarm sidekick",
    "sidekick install": "Install sidekick", "arm": "Arm", "disarm": "Disarm", "restart": "Restart service",
}


def _result_payload(resp):
    entry = resp.get("entry")
    if isinstance(entry, dict):
        return entry.get("result", entry)
    return resp.get("result", resp)


def _readable_value(value, depth=0):
    """Render already-sanitized values as readable fields, text or records."""
    if value is None:
        return '<span class="muted">Not reported</span>'
    if isinstance(value, bool):
        return "Yes" if value else "No"
    if depth >= 4:
        return '<span class="muted">Expand technical details for nested data.</span>'
    if isinstance(value, dict):
        if not value:
            return '<span class="muted">No additional data returned.</span>'
        rows = []
        for key, item in list(value.items())[:80]:
            label = str(key).replace("_", " ").capitalize()
            rendered = ('<span class="muted">Binary content omitted from this view.</span>'
                        if "b64" in str(key).lower() or "base64" in str(key).lower()
                        else _readable_value(item, depth + 1))
            rows.append(f'<dt>{e(label)}</dt><dd>{rendered}</dd>')
        return '<dl class="result-fields">' + ''.join(rows) + '</dl>'
    if isinstance(value, list):
        if not value:
            return '<span class="muted">No items returned.</span>'
        if all(isinstance(row, dict) for row in value):
            keys = list(dict.fromkeys(key for row in value[:100] for key in row))[:6]
            headers = ''.join(f'<th scope="col">{e(str(key).replace("_", " "))}</th>' for key in keys)
            rows = ''.join('<tr>' + ''.join(f'<td>{_readable_value(row.get(key), depth + 1)}</td>' for key in keys) + '</tr>' for row in value[:100])
            table = f'<div class="result-table"><table><thead><tr>{headers}</tr></thead><tbody>{rows}</tbody></table></div>'
            if len(value) > 100:
                table += f'<p class="small muted">Showing 100 of {len(value)} items. Technical details contain the full list.</p>'
            return table
        items = ''.join(f'<li>{_readable_value(item, depth + 1)}</li>' for item in value[:100])
        note = '<p class="small muted">Showing the first 100 items.</p>' if len(value) > 100 else ''
        return f'<ul class="result-list">{items}</ul>{note}'
    if isinstance(value, str):
        return f'<p class="result-text">{e(value) if value else "(empty)"}</p>'
    return e(value)


def _render_response_content(resp):
    payload = truncate_json(mask_sensitive(_result_payload(resp)), 4000)
    if isinstance(payload, dict) and payload.get("error"):
        return f'<div class="result-error">{_readable_value(payload["error"])}</div>'
    return _readable_value(payload)


def render_device(device, history, csrf_token=""):
    did = device.get("device_id") or device.get("id") or ""
    name = device.get("name") or did
    online = bool(device.get("online"))
    approved = bool(device.get("approved"))
    qdid = quote_safe(did)
    fields_by_verb = {verb: fields for actions in ACTION_GROUPS.values() for verb, fields in actions}
    tab_icons = {"Files": "folder", "Screen": "screen", "UI": "devices", "Media": "pulse", "System": "terminal"}
    tabs, panels = [], []
    for index, (group_name, sections) in enumerate(ACTION_SECTIONS.items()):
        gid = group_name.lower()
        active = " active" if index == 0 else ""
        tabs.append(f'<button type="button" class="tab{active}" data-tab-group="actions" data-tab="{gid}">{icon(tab_icons[group_name])}{e(group_name)}</button>')
        section_html = []
        for section_index, (section_name, verbs) in enumerate(sections):
            forms = []
            for verb in verbs:
                label = ACTION_LABELS[verb]
                fields = fields_by_verb[verb]
                confirm = ""
                if verb in ("restart", "screen stop", "cam stop", "mic stop"):
                    message = (f"Restart {name}? This will disconnect the device." if verb == "restart"
                               else f"Run {verb} on {name}?")
                    confirm = f' data-confirm="{e(message)}"'
                input_html = ''.join(f'<input type="text" aria-label="{e(label)} arguments" name="{e(fn)}" placeholder="{e(ph)}" value="{e(val)}" autocomplete="off" spellcheck="false">' for fn, ph, val in fields)
                content = (f'<label for="args-{quote_safe(verb)}">{e(label)}</label><div class="row">' + input_html.replace('type="text"', f'type="text" id="args-{quote_safe(verb)}"') + f'<button type="submit" class="small secondary"{confirm}>Run</button></div>'
                           if fields else f'<button type="submit" class="small secondary {"danger" if verb == "restart" else ""}"{confirm}>{e(label)}</button>')
                forms.append(f'<form class="action-form {"field-form" if fields else ""}" method="post" action="/device/{qdid}/cmd" data-inline-command data-command-label="{e(label)}">{_csrf_input(csrf_token)}<input type="hidden" name="verb" value="{e(verb)}">{content}</form>')
            responsive = ' data-responsive open' if section_index == 0 and group_name != "Files" else ''
            section_html.append(f'<details class="ui-disclosure tool-section"{responsive}><summary>{e(section_name)}<span class="section-count">{len(verbs)} actions</span></summary><div class="command-list">{"".join(forms)}</div></details>')
        extras = render_file_browser(did, csrf_token) if group_name == "Files" else ""
        panels.append(f'<div class="tab-panel{active}" data-panel-group="actions" data-panel="{gid}">{extras}<div class="tool-sections">{"".join(section_html)}</div></div>')

    hrows = []
    for h in (history or [])[:60]:
        args = h.get("args")
        args_str = args if isinstance(args, str) else " ".join(str(a) for a in args) if isinstance(args, list) else str(args or "")
        if str(h.get("action", "")).lower() in ("unlock", "pin", "setpin"):
            args_str = "<masked>"
        result = _readable_value(truncate_json(mask_sensitive(h.get("result")), 300))
        hrows.append(f'<tr><td>{e(fmt_age(h.get("ts")))}</td><td>{e(h.get("action"))} <span class="small muted mono">{e(args_str[:120])}</span></td><td>{e(h.get("status"))}</td><td>{e(h.get("elapsed", "-"))}s</td><td>{e(h.get("actor") or h.get("source") or "-")}</td><td><details><summary>View result</summary>{result}</details></td></tr>')
    history_html = ''.join(hrows) or '<tr><td colspan="6" class="empty">No command history.</td></tr>'
    meta = []
    for label, value in (
        ("Model", device.get("model") or "Not reported"),
        ("App", device.get("app_version") or device.get("version") or "Not reported"),
        ("Sidekick", device.get("sidekick_version") or "Not reported"),
        ("Battery", f'{device["battery"]}%' if device.get("battery") is not None else "Not reported"),
        ("IP address", device.get("ip") or "Not reported"),
        ("Last seen", fmt_age(device.get("last_seen"))),
    ):
        meta.append(f'<span><small>{e(label)}</small>{e(value)}</span>')
    body = f'''
<div class="device-context"><div><h1>{e(name)}</h1><div class="row small">{_badge(online, approved)}<span class="mono muted">{e(did)}</span></div></div><a class="btn secondary" href="/stream/{qdid}">{icon('screen')}Open screen</a></div>
<details class="ui-disclosure" data-responsive open><summary>Connection details</summary><div><div class="device-summary small">{''.join(meta)}</div></div></details>
<details class="ui-disclosure"><summary>Manage device<span class="small muted">Name, access &amp; updates</span></summary><div class="management-actions">
<form method="post" action="/device/{qdid}/rename">{_csrf_input(csrf_token)}<input type="text" name="name" aria-label="Device name" value="{e(device.get('name') or '')}" placeholder="Device name…"><button class="small secondary">Rename</button></form>
<form method="post" action="/device/{qdid}/approve">{_csrf_input(csrf_token)}<button class="small secondary">{'Re-approve' if approved else 'Approve'}</button></form>
<form method="post" action="/device/{qdid}/revoke">{_csrf_input(csrf_token)}<button class="small danger" data-confirm="Revoke {e(name)}? The token will be rotated and the device must re-enroll.">Revoke</button></form>
<form method="post" action="/device/{qdid}/remove">{_csrf_input(csrf_token)}<button class="small danger">Remove device</button></form>
<form method="post" action="/device/{qdid}/update">{_csrf_input(csrf_token)}<button class="small secondary" data-confirm="Trigger staged OTA update for {e(name)}?">Update</button></form>
</div></details>
<div class="workspace-header"><h2>Device tools</h2><span>Choose a tool, then an action</span></div>
<details class="ui-disclosure tool-picker" data-responsive open><summary>Tools<span data-active-tool>Files</span></summary><div class="tabs">{''.join(tabs)}</div></details>
<section id="command-output" class="card execution-panel hidden" tabindex="-1" aria-label="Command output">
<div class="execution-heading"><span>{icon('terminal')}Command output</span><button type="button" id="dismiss-output" aria-label="Dismiss command output">Dismiss</button></div>
<div class="execution-body"><p id="command-state" class="execution-state" role="status" aria-live="polite"></p><div id="command-results"></div></div>
</section>
{''.join(panels)}
<details class="ui-disclosure"><summary>Saved command history<span class="small muted">{len(history or [])} entries loaded</span></summary><div class="table-card"><table><thead><tr><th>When</th><th>Command</th><th>Status</th><th>Elapsed</th><th>Actor</th><th>Result</th></tr></thead><tbody>{history_html}</tbody></table></div></details>
'''
    return render_page(f"Device {name}", body, csrf_token=csrf_token)


def render_file_browser(did, csrf_token=""):
    qdid = quote_safe(did)
    return f'''
<section class="card file-browser" aria-label="File explorer">
<div class="browser-heading"><h3>{icon('folder')}File explorer</h3><span id="file-count" class="small muted">Not loaded</span></div>
<form id="browser-form" class="browser-toolbar" method="post" action="/device/{qdid}/cmd" data-inline-command data-device-id="{e(did)}">
{_csrf_input(csrf_token)}<input type="hidden" name="verb" value="files ls">
<input type="text" name="arg" value="/sdcard/Download" aria-label="Folder path" autocomplete="off" spellcheck="false" required>
<button type="submit" class="small">Browse folder</button><button type="button" id="browser-up" class="small secondary" disabled>{icon('back')}Up</button>
</form>
<div class="browser-shortcuts"><button type="button" data-folder="/sdcard/Download">Downloads</button><button type="button" data-folder="/sdcard">Shared storage</button><button type="button" data-folder="/sdcard/DCIM">Camera photos</button></div>
<input id="file-filter" class="browser-filter" type="search" aria-label="Filter files in this folder" placeholder="Filter this folder…" autocomplete="off">
<div id="browser-breadcrumbs" class="breadcrumb-path" aria-label="Current folder"><span class="small muted">Choose a folder to begin</span></div>
<div id="file-list" class="file-list" role="list" aria-label="Files in current folder"></div><p id="file-empty" class="browser-note hidden"></p>
<p id="browser-note" class="browser-note" role="status" aria-live="polite">Browse a folder to read its contents from the device.</p>
<template id="folder-icon">{icon('folder')}</template><template id="file-icon">{icon('file')}</template>
</section>
<div class="transfer-grid">
<details class="ui-disclosure"><summary>Upload files</summary><div>
<form id="upload-form" method="post" action="/files/upload" enctype="multipart/form-data">
{_csrf_input(csrf_token)}<input type="hidden" name="device_id" value="{e(did)}">
<div class="row"><input type="file" name="file" aria-label="Files to upload" multiple required><input type="text" name="dest" aria-label="Upload destination" placeholder="Blank = /sdcard/Download/&lt;filename&gt;" autocomplete="off" spellcheck="false"><button class="small">Upload</button></div>
<p class="small muted">Blank destination uses /sdcard/Download/&lt;original filename&gt;. For multiple files, use a directory ending in /.</p>
<div id="upload-progress" class="progress"><div></div></div><div id="upload-note" class="small muted" role="status" aria-live="polite"></div><button type="button" id="upload-abort" class="small danger hidden">Cancel</button>
</form></div></details>
<details class="ui-disclosure"><summary>Download by path</summary><div>
<form method="get" action="/files/download" class="row"><input type="hidden" name="device_id" value="{e(did)}"><input type="text" name="path" aria-label="File path to download" placeholder="/sdcard/Download/file.bin" required autocomplete="off" spellcheck="false"><button class="small secondary">Download</button></form>
<p class="small muted">Downloads are delivered as attachments. Progress is shown by your browser.</p>
</div></details>
</div>
'''


def render_result(title, resp, back_url, csrf_token=""):
    ok = bool(resp.get("ok"))
    summary = json_summary(resp, max_len=4000)
    body = f'''
<h1>{e(title)} — {'OK' if ok else 'FAILED'}</h1>
<p><a href="{e(back_url)}">&larr; Back</a></p>
<div class="card"><p class="execution-state"><span class="badge {'ok' if ok else 'err'}">{'Completed' if ok else 'Failed'}</span>Device response</p>
<section id="result-content" data-result-ok="{'1' if ok else '0'}">{_render_response_content(resp)}</section>
<details><summary>Technical details</summary><pre id="result-data">{e(summary)}</pre></details></div>
'''
    return render_page(title, body, csrf_token=csrf_token)



def render_remove_result(device, resp, csrf_token=""):
    """Outcome page for Remove device: never claims an uninstall that failed."""
    did = device.get("device_id") or device.get("id") or ""
    name = device.get("name") or did
    qdid = quote_safe(did)
    ok = bool(resp.get("ok"))
    removed = bool(resp.get("removed"))
    if ok and removed:
        title = f"Removed {name}"
        extra = ""
        if resp.get("expected_disconnect"):
            extra = ('<p class="small muted">The device disconnected before its '
                     'final result arrived; that is expected when the app '
                     'uninstalls itself.</p>')
        outcome = f"""
<div class="card">
<h2>Device removed</h2>
<p><strong>{e(name)}</strong> was removed from the server. The device was
instructed to uninstall its own apps (ghostd + ghosta only), and the server
deleted this device's record, command history, stream cache and pending
transfers.</p>
<p class="small muted">Personal SMS, photos, contacts and other apps on the
phone were not touched.</p>
{extra}
</div>"""
        back = "/"
    elif ok and resp.get("finalizing"):
        title = f"Removal requested — {name}"
        outcome = f"""
<div class="card">
<h2>Awaiting disconnect verification</h2>
<p><strong>{e(name)}</strong> disconnected as expected during self-uninstall.
The server is waiting for the bounded grace period before deleting its record.
If the device reconnects, removal is not verified and its record is kept.</p>
<p class="small muted">This is a pending state, not a failure and not yet proof
that both apps were removed.</p>
</div>"""
        back = f"/device/{qdid}"
    else:
        title = f"Remove failed — {name}"
        error = resp.get("error") or "unknown error"
        revoked = ""
        if resp.get("revoked"):
            revoked = ('<p>Server access was revoked (token rotated) and streams '
                       'were dropped, but the device record was kept so you can '
                       'retry or audit the failure. Re-enroll and approve the '
                       'device before another removal.</p>')
        else:
            revoked = ('<p>Nothing was removed and no uninstall success is '
                       'claimed. Use revoke if you only need to remove server '
                       'access.</p>')
        outcome = f"""
<div class="card">
<h2>Not removed</h2>
<p><strong>{e(name)}</strong> was NOT removed: {e(error)}</p>
{revoked}
</div>"""
        back = f"/device/{qdid}"
    body = f"""
<h1>{e(title)}</h1>
<p><a href="{e(back)}">&larr; {'devices' if back == '/' else 'back to device'}</a></p>
{outcome}
<div class="card">
<pre>{e(json_summary(resp))}</pre>
</div>
"""
    return render_page(title, body, csrf_token=csrf_token)


def render_stream(device, stream_token, csrf_token=""):
    did = device.get("device_id") or device.get("id") or ""
    name = device.get("name") or did
    qdid = quote_safe(did)
    online = bool(device.get("online"))
    approved = bool(device.get("approved"))
    body = f"""
<div class="eyebrow">Remote view</div>
<h1>Screen — {e(name)}</h1>
<p class="device-summary small">
  {_badge(online, approved)}
  <span class="mono muted">{e(did)}</span>
  <span>Frame age: <span id="stream-age">waiting</span></span>
</p>
<div class="card">
  <div class="row">
    <form id="stream-start-form" method="post" action="/device/{qdid}/cmd" style="margin:0">
      {_csrf_input(csrf_token)}
      <input type="hidden" name="verb" value="screen live">
      <input type="hidden" name="arg" value="--fps 5 --quality 60">
      <button id="stream-toggle" class="small" data-confirm="Start live screen on {e(name)}?">Start stream</button>
    </form>
    <form id="stream-stop-form" method="post" action="/device/{qdid}/cmd" style="margin:0">
      {_csrf_input(csrf_token)}
      <input type="hidden" name="verb" value="screen stop">
      <button id="stream-stop" class="small danger hidden" data-confirm="Stop live screen on {e(name)}?">Stop stream</button>
    </form>
    <a class="btn small secondary" href="/device/{qdid}">Back to device</a>
  </div>
</div>
<div class="card stream-box">
  <img id="stream-img" alt="live screen" data-feed="/stream/{qdid}/feed" data-token="{e(stream_token)}">
  <div id="stream-note" class="stream-note muted">waiting for frame…</div>
</div>
<p class="small muted">
  The console polls timestamped single frames (not MJPEG by default) so stale
  cached frames are never presented as live. Frames older than 15s are rejected.
  <a href="/stream/{qdid}/feed.mjpg?t={e(stream_token)}">Open MJPEG fallback</a>
</p>
"""
    return render_page(f"Screen {name}", body, csrf_token=csrf_token)


def render_settings(config, csrf_token=""):
    max_pending = config.get("max_pending", "-")
    max_per_ip = config.get("max_per_ip", "-")
    body = f"""
<div class="page-heading"><div><div class="eyebrow">Workspace preferences</div><h1>Settings</h1><p>Manage device enrollment limits.</p></div></div>
<div class="card settings-card"><h3>Enrollment limits</h3><p class="small muted">Control how many devices can request access to your workspace.</p>
<form method="post" action="/settings" class="row" style="flex-direction:column;align-items:stretch">
  {_csrf_input(csrf_token)}
  <label>Maximum pending devices <input type="number" name="max_pending" value="{e(max_pending)}" min="0" max="10000"></label>
  <label>Maximum connections per IP <input type="number" name="max_per_ip" value="{e(max_per_ip)}" min="0" max="10000"></label>
  <button class="small" style="align-self:flex-start">Save changes</button>
</form>
</div>
<p class="small muted">Changes are applied live by the daemon.</p>
"""
    return render_page("Settings", body, csrf_token=csrf_token)


def render_404(msg="Not found"):
    return render_page("404", f'<h1>404</h1><p class="muted">{e(msg)}</p><p><a href="/">&larr; devices</a></p>')


def render_unauthorized():
    return """<!doctype html><html><head><meta charset="utf-8"><title>401 Unauthorized</title></head>
<body><h1>401 Unauthorized</h1><p>ghostctl console: bad password</p></body></html>"""


def quote_safe(s):
    """URL-quote a path component with safe='' (no slashes or special chars)."""
    from urllib.parse import quote
    return quote(str(s), safe="")
