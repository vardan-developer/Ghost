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
  --bg: #0f1117; --card: #181b24; --card2: #1e2230;
  --text: #d7dbe7; --muted: #7c8296; --line: #2a3040;
  --accent: #4c9aff; --accent2: #2d6fbf;
  --ok: #2ecc71; --warn: #f1c40f; --err: #e74c3c; --danger: #b03030;
  --radius: 8px; --pad: 12px;
}
* { box-sizing: border-box; }
body {
  margin: 0; padding: 0; background: var(--bg); color: var(--text);
  font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
  font-size: 15px; line-height: 1.45;
}
a { color: var(--accent); text-decoration: none; }
a:hover { text-decoration: underline; }
h1, h2, h3 { font-weight: 600; margin: 0 0 0.4em; }
h1 { font-size: 1.5rem; } h2 { font-size: 1.15rem; } h3 { font-size: 1rem; }
.muted { color: var(--muted); }
.small { font-size: 0.82rem; }
.mono { font-family: ui-monospace, "SF Mono", Menlo, Consolas, monospace; }
nav {
  display: flex; flex-wrap: wrap; gap: 12px; align-items: center;
  padding: 10px 16px; background: var(--card); border-bottom: 1px solid var(--line);
}
nav .brand { font-weight: 700; letter-spacing: 0.02em; color: var(--text); }
nav a { color: var(--muted); }
nav a.active { color: var(--text); font-weight: 600; }
main { max-width: 1200px; margin: 0 auto; padding: 16px; }
.card {
  background: var(--card); border: 1px solid var(--line);
  border-radius: var(--radius); padding: var(--pad); margin-bottom: 12px;
}
.card2 { background: var(--card2); }
.grid { display: grid; gap: 12px; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); }
.row { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
.badge {
  display: inline-block; padding: 2px 8px; border-radius: 99px;
  font-size: 0.75rem; font-weight: 600; letter-spacing: 0.02em;
}
.badge.ok { background: #1a3d2a; color: var(--ok); }
.badge.warn { background: #3d331a; color: var(--warn); }
.badge.err { background: #3d1a1a; color: var(--err); }
.badge.muted { background: #262a36; color: var(--muted); }
.dot { display: inline-block; width: 9px; height: 9px; border-radius: 50%; }
.dot.on { background: var(--ok); box-shadow: 0 0 6px var(--ok); }
.dot.off { background: var(--muted); }
.dot.pending { background: var(--warn); }
button, .btn {
  display: inline-block; background: var(--accent2); color: #fff;
  border: 1px solid var(--accent); border-radius: 6px;
  padding: 6px 14px; font-size: 0.9rem; cursor: pointer;
  text-decoration: none; transition: background 0.15s;
}
button:hover, .btn:hover { background: var(--accent); text-decoration: none; }
button.danger { background: var(--danger); border-color: #d05050; }
button.danger:hover { background: #d05050; }
button.secondary { background: #2a3040; border-color: #3a4255; color: var(--text); }
button.secondary:hover { background: #3a4255; }
button.small { padding: 3px 9px; font-size: 0.8rem; }
button:disabled { opacity: 0.5; cursor: not-allowed; }
input, select, textarea {
  background: #12151c; color: var(--text); border: 1px solid var(--line);
  border-radius: 6px; padding: 7px 10px; font-size: 0.9rem;
}
input:focus, select:focus, textarea:focus { outline: none; border-color: var(--accent); }
table { width: 100%; border-collapse: collapse; }
th, td { padding: 8px 10px; border-bottom: 1px solid var(--line); text-align: left; font-size: 0.88rem; }
th { color: var(--muted); font-weight: 600; font-size: 0.78rem; text-transform: uppercase; letter-spacing: 0.04em; }
tr:last-child td { border-bottom: none; }
pre {
  background: #12151c; border: 1px solid var(--line); border-radius: 6px;
  padding: 10px; overflow-x: auto; font-size: 0.82rem; white-space: pre-wrap;
  word-break: break-word; max-height: 360px;
}
code { font-family: ui-monospace, "SF Mono", Menlo, Consolas, monospace; font-size: 0.88em; }
details { margin: 6px 0; }
summary { cursor: pointer; color: var(--accent); font-weight: 600; }
summary:hover { text-decoration: underline; }
details > div { margin-top: 8px; padding-left: 12px; border-left: 2px solid var(--line); }
.actions-grid { display: grid; gap: 10px; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); }
.action-form { background: var(--card2); border: 1px solid var(--line); border-radius: 6px; padding: 10px; }
.action-form label { display: block; font-size: 0.82rem; color: var(--muted); margin-bottom: 4px; }
.action-form .row { gap: 6px; }
.action-form input[type=text] { flex: 1; min-width: 80px; }
.stream-box { position: relative; background: #000; border: 1px solid var(--line); border-radius: var(--radius); overflow: hidden; }
.stream-box img { display: block; width: 100%; height: auto; max-height: 70vh; object-fit: contain; }
.stream-note { position: absolute; bottom: 8px; left: 8px; right: 8px; background: rgba(0,0,0,0.65); padding: 4px 8px; border-radius: 4px; font-size: 0.78rem; }
.empty { text-align: center; color: var(--muted); padding: 24px; }
.progress { height: 6px; background: var(--line); border-radius: 3px; overflow: hidden; margin: 8px 0; }
.progress > div { height: 100%; background: var(--accent); width: 0%; transition: width 0.2s; }
.toast { position: fixed; top: 12px; right: 12px; background: var(--card); border: 1px solid var(--line); border-radius: 6px; padding: 10px 14px; font-size: 0.88rem; z-index: 1000; box-shadow: 0 4px 20px rgba(0,0,0,0.4); }
.toast.ok { border-color: var(--ok); color: var(--ok); }
.toast.err { border-color: var(--err); color: var(--err); }
.search-row { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.search-row input { flex: 1; min-width: 180px; }
.hidden { display: none !important; }
.device-card { display: flex; flex-direction: column; gap: 6px; }
.device-card .head { display: flex; justify-content: space-between; align-items: center; }
.device-card .meta { display: grid; grid-template-columns: 1fr 1fr; gap: 4px 12px; font-size: 0.82rem; }
.device-card .meta dt { color: var(--muted); }
.device-card .meta dd { margin: 0; }
.pending-card { border-left: 3px solid var(--warn); }
.tabs { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 12px; }
.tab { background: var(--card2); border: 1px solid var(--line); border-radius: 6px; padding: 5px 12px; cursor: pointer; font-size: 0.88rem; }
.tab.active { background: var(--accent2); border-color: var(--accent); color: #fff; }
.tab-panel { display: none; }
.tab-panel.active { display: block; }
@media (max-width: 600px) {
  main { padding: 10px; }
  .grid { grid-template-columns: 1fr; }
  th, td { padding: 6px 8px; font-size: 0.82rem; }
  .device-card .meta { grid-template-columns: 1fr; }
}
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
def render_page(title, body, csrf_token="", refresh=None, extra_head=""):
    head = ""
    if refresh:
        head += f'<meta http-equiv="refresh" content="{int(refresh)}">'
    head += extra_head
    return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="csrf-token" content="{e(csrf_token)}">
<title>{e(title)} — ghostctl</title>
<link rel="stylesheet" href="/static/app.css">
{head}
</head>
<body>
<nav>
  <span class="brand">ghostctl</span>
  <a href="/" class="{'active' if title == 'Devices' else ''}">Devices</a>
  <a href="/settings">Settings</a>
</nav>
<main>
{body}
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
    <div>
      <a href="/device/{e(quote_safe(did))}"><strong>{e(name)}</strong></a>
      <div class="small muted mono">{e(did)}</div>
    </div>
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
  <div class="row">
    <a class="btn small" href="/device/{e(quote_safe(did))}">Details</a>
    <a class="btn small secondary" href="/stream/{e(quote_safe(did))}">Screen</a>
    {'' if approved else f'''<form method="post" action="/device/{e(quote_safe(did))}/approve" style="display:inline"><input type="hidden" name="csrf_token" value="{e(csrf_token)}"><button class="small">Approve</button></form>'''}
    <form method="post" action="/device/{e(quote_safe(did))}/rename" style="display:inline"><input type="hidden" name="csrf_token" value="{e(csrf_token)}"><input type="text" name="name" placeholder="new name" size="12"><button class="small secondary">Rename</button></form>
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
<div class="grid">{''.join(pending_cards)}</div>
"""

    empty = ('<div class="empty">No approved devices yet.</div>'
             if pending_cards else '<div class="empty">No devices yet.</div>')
    body = f"""
<h1>Devices</h1>
<div class="search-row">
  <input type="text" id="device-search" placeholder="Search name, id, model, IP…" autocomplete="off">
  <label class="small"><input type="checkbox" id="pending-only"> pending only</label>
  <label class="small"><input type="checkbox" id="auto-refresh" checked> auto-refresh</label>
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
        tab_buttons.append(f'<div class="tab{active}" data-tab-group="actions" data-tab="{gid}">{e(group_name)}</div>')
        panel_forms = []
        for verb, fields in actions:
            confirm = ""
            if verb in ("restart", "screen stop", "cam stop", "mic stop", "revoke", "update"):
                confirm = f' data-confirm="Run {e(verb)} on {e(name)}?"'
            if verb == "restart":
                confirm = f' data-confirm="Restart {e(name)}? This will disconnect the device."'
            inputs = "".join(
                f'<input type="text" name="{e(fn)}" placeholder="{e(ph)}" value="{e(val)}" size="30">'
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
<h1>{e(name)}</h1>
<p class="row small">
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
    <input type="text" name="name" placeholder="device name" value="{e(device.get('name') or '')}" size="24">
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
<div class="card">
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
      <input type="file" name="file" multiple required>
      <input type="text" name="dest" placeholder="blank = /sdcard/Download/&lt;filename&gt;" size="30">
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
    <input type="text" name="path" placeholder="/sdcard/Download/file.bin" size="30" required>
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
<h1>Screen — {e(name)}</h1>
<p class="row small">
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
<h1>Settings</h1>
<div class="card">
<form method="post" action="/settings" class="row" style="flex-direction:column;align-items:stretch">
  {_csrf_input(csrf_token)}
  <label>max_pending <input type="number" name="max_pending" value="{e(max_pending)}" min="0" max="10000"></label>
  <label>max_per_ip <input type="number" name="max_per_ip" value="{e(max_per_ip)}" min="0" max="10000"></label>
  <button class="small" style="align-self:flex-start">Save</button>
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
