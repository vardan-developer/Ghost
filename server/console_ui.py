"""console_ui.py — HTML/CSS/JS for the ghostctl operator dashboard.

All assets are served as plain text by console.py. No frameworks, no inline
event handlers, no inline script. CSP-friendly (script-src 'self').
"""
import html
import json
import time

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
  --bg:#101213; --card:#181b1c; --card2:#1e2223; --text:#edf0ed;
  --muted:#a0aaa5; --line:#303637; --accent:#c4e899; --accent2:#b3d487;
  --ok:#a9dd82; --warn:#f0c581; --err:#f1a29b; --danger:#422727;
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
nav { position:fixed; inset:0 auto 0 0; width:228px; padding:35px 20px 24px; background:#151819; border-right:1px solid var(--line); display:flex; flex-direction:column; gap:8px; z-index:20; }
nav .brand { display:flex; align-items:center; gap:10px; color:var(--text); font-size:23px; letter-spacing:-.9px; font-weight:650; padding:0 12px 37px; }
.brand-mark { display:grid; place-items:center; width:32px; height:32px; border-radius:3px; background:var(--accent); color:#182112; }
nav .nav-caption { padding:0 14px 12px; font-size:10px; letter-spacing:1.6px; color:var(--muted); }
nav a.nav-link { color:var(--muted); padding:12px 14px; display:flex; align-items:center; gap:12px; border-radius:3px; }
nav a.nav-link:hover { background:var(--card2); color:var(--text); text-decoration:none; }
nav a.active { background:#293326; color:var(--accent); }
.nav-bottom { margin-top:auto; padding:20px 12px 0; border-top:1px solid var(--line); }
.nav-bottom p { margin:8px 0 0; font-size:11px; }
.operator { display:flex; align-items:center; gap:10px; margin-top:24px; font-size:12px; }
.avatar { width:32px; height:32px; display:grid; place-items:center; border:1px solid #4c554c; border-radius:3px; background:#2a312b; color:var(--accent); font-size:10px; font-weight:650; }
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
.stat.online { background:#202b20; border-color:#41543a; }
.stat.online strong,.stat.online .icon { color:var(--accent); }
.section-title { display:flex; align-items:center; gap:10px; margin:0 0 18px; font-size:18px; }
.section-title .count { color:var(--muted); font-size:11px; border:1px solid var(--line); border-radius:3px; padding:1px 7px; }
.card { background:var(--card); border:1px solid var(--line); border-radius:var(--radius); padding:var(--pad); margin-bottom:20px; min-width:0; }
.card2 { background:var(--card2); }
.grid { display:grid; gap:20px; grid-template-columns:repeat(auto-fill,minmax(min(100%,310px),1fr)); }
.row { display:flex; flex-wrap:wrap; align-items:center; gap:10px; min-width:0; }
.badge { display:inline-flex; align-items:center; gap:6px; padding:4px 9px; border-radius:3px; font-size:10px; font-weight:550; letter-spacing:.2px; white-space:nowrap; }
.badge::before { content:""; width:5px; height:5px; border-radius:50%; background:currentColor; }
.badge.ok { background:#29392b; color:var(--ok); }
.badge.warn { background:#3b3226; color:var(--warn); }
.badge.err { background:#3a2928; color:var(--err); }
.badge.muted { background:#282d2e; color:#a0aaa5; }
.dot { width:6px; height:6px; border-radius:50%; display:inline-block; flex-shrink:0; }
.dot.on { background:var(--ok); }
.dot.off { background:var(--muted); }
.dot.pending { background:var(--warn); }
button,.btn { display:inline-flex; justify-content:center; align-items:center; gap:8px; background:var(--accent); color:#1a2514; border:1px solid var(--accent); border-radius:3px; padding:10px 15px; font-family:inherit; font-size:13px; font-weight:550; line-height:1.4; cursor:pointer; text-decoration:none; min-height:42px; transition:background .16s,border-color .16s,transform .16s; }
button:hover,.btn:hover { background:#dfffa9; border-color:#dfffa9; text-decoration:none; }
button:active,.btn:active { transform:translateY(1px); }
button.danger,.btn.danger { background:transparent; border-color:#65403c; color:var(--err); }
button.danger:hover { background:var(--danger); }
button.secondary,.btn.secondary { background:transparent; border-color:#424a47; color:var(--text); }
button.secondary:hover,.btn.secondary:hover { background:#2b3330; border-color:#737e74; }
button.small,.btn.small { font-size:11px; padding:8px 11px; min-height:38px; }
button:disabled { opacity:.5; cursor:not-allowed; }
input,select,textarea { min-width:0; max-width:100%; background:#121617; color:var(--text); border:1px solid #3b4340; border-radius:3px; padding:11px 12px; font:inherit; font-size:13px; min-height:42px; }
input::placeholder,textarea::placeholder { color:#929c96; }
input[type=checkbox] { accent-color:var(--accent); min-height:0; width:14px; height:14px; margin:0; }
input[type=file] { padding:7px; }
input::file-selector-button { background:#2c352d; color:var(--text); border:0; padding:7px 10px; border-radius:3px; margin-right:10px; cursor:pointer; }
:focus-visible { outline:2px solid var(--accent); outline-offset:4px; }
input:focus,select:focus,textarea:focus { border-color:var(--accent); }
table { width:100%; border-collapse:collapse; }
th,td { padding:16px 12px; border-bottom:1px solid var(--line); text-align:left; font-size:12px; vertical-align:top; overflow-wrap:anywhere; }
th { color:var(--muted); font-weight:500; font-size:10px; text-transform:uppercase; letter-spacing:1px; }
tr:last-child td { border-bottom:none; }
.table-card { overflow-x:auto; }
.table-card table { min-width:650px; }
pre { background:#111516; border:1px solid var(--line); border-radius:3px; padding:18px; overflow-x:auto; font-size:12px; white-space:pre-wrap; word-break:break-word; max-height:440px; }
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
.device-glyph { width:42px; height:46px; border:1px solid #495345; border-radius:3px; background:#283025; color:var(--accent); display:grid; place-items:center; flex-shrink:0; }
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
  nav { inset:auto 0 0; width:auto; height:calc(68px + env(safe-area-inset-bottom)); padding:8px 20px calc(8px + env(safe-area-inset-bottom)); border-right:0; border-top:1px solid var(--line); flex-direction:row; justify-content:space-around; gap:12px; background:#151819fa; }
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
"""

# ---------------------------------------------------------------------------
# JS — external only. Handles CSRF injection, confirmations, search, tabs,
# frame polling, upload progress, and abort. No inline handlers.
# ---------------------------------------------------------------------------
JS = r"""
(function () {
  "use strict";

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
    document.querySelectorAll('[data-tab-group="' + group + '"]').forEach(function (t) {
      t.classList.toggle("active", t === tab);
    });
    document.querySelectorAll('[data-panel-group="' + group + '"]').forEach(function (p) {
      p.classList.toggle("active", p.getAttribute("data-panel") === tab.getAttribute("data-tab"));
    });
  }, true);

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
      var formData = new FormData(uploadForm);
      var xhr = new XMLHttpRequest();
      var bar = document.getElementById("upload-progress");
      var note = document.getElementById("upload-note");
      var abortBtn = document.getElementById("upload-abort");

      function setNote(msg) { if (note) note.textContent = msg; }
      function hideAbort() { if (abortBtn) abortBtn.classList.add("hidden"); }
      function resetBar() { if (bar) bar.querySelector("div").style.width = "0%"; }
      function responseError() {
        var text = xhr.responseText || "";
        try {
          var doc = new DOMParser().parseFromString(text, "text/html");
          text = (doc.body && doc.body.textContent) || text;
        } catch (e) { /* fall back to the raw body */ }
        text = text.replace(/\s+/g, " ").trim();
        return text ? text.slice(0, 300) : "empty response body";
      }

      xhr.open("POST", uploadForm.action, true);
      // Slightly above the server's bounded upload RPC wait (180s + slack).
      xhr.timeout = 200000;
      xhr.upload.onprogress = function (e) {
        if (e.lengthComputable && bar) {
          var pct = Math.round((e.loaded / e.total) * 100);
          bar.querySelector("div").style.width = pct + "%";
          setNote("uploading " + pct + "%");
        }
      };
      xhr.onload = function () {
        hideAbort();
        if (xhr.status >= 200 && xhr.status < 300) {
          if (bar) bar.querySelector("div").style.width = "100%";
          setNote("upload complete");
          setTimeout(function () { window.location.reload(); }, 800);
        } else {
          resetBar();
          setNote("upload failed (HTTP " + xhr.status + "): " + responseError());
        }
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
          var body = new FormData();
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
    paths = {
        "devices": '<rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/>',
        "settings": '<path d="M4 7h16M4 17h16"/><circle cx="9" cy="7" r="3"/><circle cx="16" cy="17" r="3"/>',
        "phone": '<rect x="6" y="2" width="12" height="20" rx="3"/><path d="M10 5h4M11 19h2"/>',
        "pulse": '<path d="M2 12h5l3-8 4 16 3-8h5"/>',
        "clock": '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
        "search": '<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 5 5"/>',
        "arrow": '<path d="M5 12h14m-5-5 5 5-5 5"/>',
        "screen": '<rect x="3" y="4" width="18" height="13" rx="2"/><path d="M8 21h8m-4-4v4"/>',
        "mark": '<path d="M5 19V9a7 7 0 0 1 14 0v10l-3-2-4 2-4-2-3 2Z"/><path d="M9 9h.01M15 9h.01"/>',
    }
    return f'<svg class="icon" viewBox="0 0 24 24" aria-hidden="true">{paths[name]}</svg>'


def render_page(title, body, csrf_token="", refresh=None, extra_head=""):
    head = ""
    if refresh:
        head += f'<meta http-equiv="refresh" content="{int(refresh)}">'
    head += extra_head
    return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#101213">
<meta name="application-name" content="ghostctl">
<meta name="csrf-token" content="{e(csrf_token)}">
<title>{e(title)} — ghostctl</title>
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
  <dl class="meta">
    <dt>Model</dt><dd>{e(model)}</dd>
    <dt>App</dt><dd>{e(app_ver)}</dd>
    <dt>Sidekick</dt><dd>{e(side_ver)}</dd>
    <dt>Battery</dt><dd>{e(bat)}</dd>
    <dt>IP</dt><dd>{e(ip)}</dd>
    <dt>Last seen</dt><dd>{e(last)}</dd>
  </dl>
  <div class="row device-actions">
    <a class="btn small" href="/device/{e(quote_safe(did))}">Open device {icon("arrow")}</a>
    <a class="btn small secondary" href="/stream/{e(quote_safe(did))}">{icon("screen")} Screen</a>
    {'' if approved else f'''<form method="post" action="/device/{e(quote_safe(did))}/approve" style="display:inline"><input type="hidden" name="csrf_token" value="{e(csrf_token)}"><button class="small">Approve</button></form>'''}
    <form method="post" action="/device/{e(quote_safe(did))}/rename" style="display:inline"><input type="hidden" name="csrf_token" value="{e(csrf_token)}"><input type="text" name="name" placeholder="Device name" aria-label="New name for {e(name)}" size="12"><button class="small secondary">Rename</button></form>
    {'' if not approved else f'''<form method="post" action="/device/{e(quote_safe(did))}/remove" style="display:inline"><input type="hidden" name="csrf_token" value="{e(csrf_token)}"><button class="small danger">Remove device</button></form>'''}
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
  <label class="small"><input type="checkbox" id="pending-only"> Pending only</label>
  <label class="small"><input type="checkbox" id="auto-refresh" checked> Auto-refresh</label>
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


def render_device(device, history, csrf_token=""):
    did = device.get("device_id") or device.get("id") or ""
    name = device.get("name") or did
    online = bool(device.get("online"))
    approved = bool(device.get("approved"))
    model = device.get("model") or "-"
    app_ver = device.get("app_version") or device.get("version") or "-"
    side_ver = device.get("sidekick_version") or "-"
    bat = device.get("battery")
    bat = f"{bat}%" if bat is not None else "-"
    ip = device.get("ip") or "-"
    last = fmt_age(device.get("last_seen"))
    qdid = quote_safe(did)

    tab_buttons = []
    tab_panels = []
    first = True
    for group_name, actions in ACTION_GROUPS.items():
        gid = group_name.lower().replace(" ", "-")
        active = " active" if first else ""
        tab_buttons.append(f'<button type="button" class="tab{active}" data-tab-group="actions" data-tab="{gid}">{e(group_name)}</button>')
        panel_forms = []
        for verb, fields in actions:
            confirm = ""
            if verb in ("restart", "screen stop", "cam stop", "mic stop", "revoke", "update"):
                confirm = f' data-confirm="Run {e(verb)} on {e(name)}?"'
            if verb == "restart":
                confirm = f' data-confirm="Restart {e(name)}? This will disconnect the device."'
            inputs = "".join(
                f'<input type="text" aria-label="{e(verb)} arguments" name="{e(fn)}" placeholder="{e(ph)}" value="{e(val)}" size="30">'
                for fn, ph, val in fields
            )
            panel_forms.append(
                f'<form class="action-form" method="post" action="/device/{qdid}/cmd">'
                f'{_csrf_input(csrf_token)}'
                f'<input type="hidden" name="verb" value="{e(verb)}">'
                f'<label>{e(verb)}</label>'
                f'<div class="row">{inputs}<button{confirm} class="small">{e(verb)}</button></div>'
                f'</form>'
            )
        tab_panels.append(f'<div class="tab-panel{active}" data-panel-group="actions" data-panel="{gid}"><div class="actions-grid">{"".join(panel_forms)}</div></div>')
        first = False

    # History table
    hrows = []
    for h in (history or [])[:60]:
        args = h.get("args")
        if isinstance(args, str):
            args_str = args
        elif isinstance(args, list):
            args_str = " ".join(str(a) for a in args)
        else:
            args_str = str(args) if args else ""
        action_name = str(h.get("action", "")).lower()
        if action_name in ("unlock", "pin", "setpin"):
            args_str = "<masked>"
        result = json_summary(h.get("result"), max_len=300)
        hrows.append(
            f'<tr><td>{e(fmt_age(h.get("ts")))}</td>'
            f'<td>{e(h.get("action"))} <span class="small muted mono">{e(args_str[:120])}</span></td>'
            f'<td>{e(h.get("status"))}</td>'
            f'<td>{e(h.get("elapsed", "-"))}s</td>'
            f'<td>{e(h.get("actor") or h.get("source") or "-")}</td>'
            f'<td><details><summary>result</summary><pre>{e(result)}</pre></details></td></tr>'
        )
    history_html = "".join(hrows) or '<tr><td colspan="6" class="empty">No command history.</td></tr>'

    body = f"""
<div class="eyebrow">Device workspace</div>
<h1>{e(name)}</h1>
<p class="device-summary small">
  {_badge(online, approved)}
  <span class="mono muted">{e(did)}</span>
  <span>model: {e(model)}</span>
  <span>app: {e(app_ver)}</span>
  <span>sidekick: {e(side_ver)}</span>
  <span>battery: {e(bat)}</span>
  <span>ip: {e(ip)}</span>
  <span>last seen: {e(last)}</span>
</p>
<div class="card row">
  <form method="post" action="/device/{qdid}/rename" class="row" style="margin:0">
    {_csrf_input(csrf_token)}
    <input type="text" name="name" aria-label="Device name" placeholder="device name" value="{e(device.get('name') or '')}" size="24">
    <button class="small">Rename</button>
  </form>
  <form method="post" action="/device/{qdid}/approve" style="margin:0">
    {_csrf_input(csrf_token)}
    <button class="small">{'Re-approve' if approved else 'Approve'}</button>
  </form>
  <form method="post" action="/device/{qdid}/revoke" style="margin:0">
    {_csrf_input(csrf_token)}
    <button class="small danger" data-confirm="Revoke {e(name)}? The token will be rotated and the device must re-enroll.">Revoke</button>
  </form>
  <form method="post" action="/device/{qdid}/remove" style="margin:0">
    {_csrf_input(csrf_token)}
    <button class="small danger">Remove device</button>
  </form>
  <form method="post" action="/device/{qdid}/update" style="margin:0">
    {_csrf_input(csrf_token)}
    <button class="small" data-confirm="Trigger staged OTA update for {e(name)}?">Update</button>
  </form>
  <a class="btn small secondary" href="/stream/{qdid}">Screen</a>
</div>

<h2>Actions</h2>
<div class="tabs">{"".join(tab_buttons)}</div>
{"".join(tab_panels)}

<h2>Command history</h2>
<div class="card table-card">
<table>
<tr><th>When</th><th>Command</th><th>Status</th><th>Elapsed</th><th>Actor</th><th>Result</th></tr>
{history_html}
</table>
</div>

<div class="card">
  <h3>File upload</h3>
  <form id="upload-form" method="post" action="/files/upload" enctype="multipart/form-data">
    {_csrf_input(csrf_token)}
    <input type="hidden" name="device_id" value="{e(did)}">
    <div class="row">
      <input type="file" name="file" aria-label="Files to upload" multiple required>
      <input type="text" name="dest" aria-label="Upload destination" placeholder="blank = /sdcard/Download/&lt;filename&gt;" size="30">
      <button class="small">Upload</button>
    </div>
    <p class="small muted">Leave the destination blank to use
      <span class="mono">/sdcard/Download/&lt;original filename&gt;</span>.
      Multiple files go to one destination only if it is a directory.</p>
    <div id="upload-progress" class="progress"><div></div></div>
    <div id="upload-note" class="small muted"></div>
    <button type="button" id="upload-abort" class="small danger hidden">Cancel</button>
  </form>
</div>

<div class="card">
  <h3>File download</h3>
  <form method="get" action="/files/download" class="row">
    <input type="hidden" name="device_id" value="{e(did)}">
    <input type="text" name="path" aria-label="File path to download" placeholder="/sdcard/Download/file.bin" size="30" required>
    <button class="small secondary">Download</button>
  </form>
  <p class="small muted">The file is relayed by the server as a real
    download attachment, so binary content is never dumped as base64 in the
    command history.</p>
</div>
"""
    return render_page(f"Device {name}", body, csrf_token=csrf_token)


def render_result(title, resp, back_url, csrf_token=""):
    ok = bool(resp.get("ok"))
    summary = json_summary(resp)
    body = f"""
<h1>{e(title)} — {'OK' if ok else 'FAILED'}</h1>
<p><a href="{e(back_url)}">&larr; back</a></p>
<div class="card">
<pre>{e(summary)}</pre>
</div>
"""
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
