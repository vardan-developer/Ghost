#!/usr/bin/env python3
"""ghostctl web console — responsive operator dashboard.

Stdlib-only HTTP server (ThreadingHTTPServer). Talks to the daemon the same
way ghostctl.py does: imports ghostctl and uses rpc() against the admin port.

Security:
  * HTTP Basic auth (constant-time compare, base64 validate=True)
  * Per-IP failed-auth rate limiting/backoff
  * CSRF: per-process random token in SameSite=Strict HttpOnly cookie plus
    hidden form field; Origin/Referer verification when supplied
  * Unguessable stream tokens for feed GETs
  * Strict path parsing (unquote + validate device ids)
  * CSP, X-Frame-Options DENY, nosniff, Referrer-Policy no-referrer,
    Cache-Control no-store on all responses (including 401)
  * Python server header suppressed
  * Content-Length strict 0..64MiB for uploads, 1MiB otherwise
  * Global request concurrency + max 4 MJPEG clients
  * Socket read/write timeouts; broken-pipe handling
  * Password never printed to stdout; generated password written to mode 600
    console.pass for loopback binds; GHOST_CONSOLE_PASS required otherwise
  * Native TLS (1.2+) with worker-thread handshakes and a bounded handshake
    timeout; Secure CSRF cookie and HSTS when HTTPS is active
  * Non-loopback HTTP refused unless GHOST_CONSOLE_ALLOW_HTTP=1

Run:  python3 console.py [--host 127.0.0.1] [--port 9443]
      [--certfile console.crt --keyfile console.key]
"""
import argparse
import base64
import email.parser
import hmac
import html
import json
import os
import re
import secrets
import ssl
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, unquote, urlparse

BASE = Path(__file__).resolve().parent
UPLOAD_DIR = BASE / "uploads"
UPLOAD_DIR.mkdir(mode=0o700, exist_ok=True)
try:
    os.chmod(UPLOAD_DIR, 0o700)
except OSError:
    pass

# Secure umask for anything created by this process.
try:
    os.umask(0o077)
except Exception:
    pass

sys.path.insert(0, str(BASE))
from envfile import apply_dotenv  # noqa: E402

# .env next to the script (shared with daemon) may carry GHOST_CONSOLE_PASS.
# Existing process environment wins; values are never printed.
apply_dotenv(BASE / ".env")

import ghostctl  # noqa: E402  (reuse the CLI's daemon client code)
import console_ui as ui  # noqa: E402

# ---------------------------------------------------------------------------
# Globals
# ---------------------------------------------------------------------------
CSRF_TOKEN = secrets.token_urlsafe(32)
CSRF_FAILED = "CSRF check failed: reload this page and retry"
SCHEME = "http"             # set to "https" when native TLS is enabled
AUTH_FAILURES = {}          # ip -> [count, last_fail_ts]
AUTH_LOCK = threading.Lock()
MAX_AUTH_FAILURES = 10
AUTH_BACKOFF = 60.0         # seconds
MAX_AUTH_ENTRIES = 1024     # hard cap on tracked source IPs

REQUEST_SEM = threading.BoundedSemaphore(64)
STREAM_SEM = threading.BoundedSemaphore(4)
TLS_HANDSHAKE_SEM = threading.BoundedSemaphore(32)
STREAM_TOKENS = {}          # token -> (device_id, expires_ts)
STREAM_TOKENS_LOCK = threading.Lock()
STREAM_TOKEN_TTL = 300.0    # seconds

# Device id validation: safe URL/HTML/JS charset.
DEVICE_ID_RE = re.compile(r"^[A-Za-z0-9_.-]{1,128}$")
MAX_PATH_LEN = 512

MAX_BODY = 1 * 1024 * 1024          # 1 MiB for normal POSTs
MAX_UPLOAD = 64 * 1024 * 1024       # 64 MiB for file uploads
MAX_FRAME_BYTES = 16 * 1024 * 1024  # 16 MiB display/response cap
FRAME_MAX_AGE = 15.0                # seconds; stale frames are rejected


def _now():
    return time.time()


def _log(msg):
    """Log to stderr with timestamp. No secrets in messages."""
    sys.stderr.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')} console: {msg}\n")
    sys.stderr.flush()


def _secure_log_file():
    """If console.log exists, enforce mode 600."""
    p = BASE / "console.log"
    try:
        if p.exists():
            os.chmod(p, 0o600)
    except OSError:
        pass


def _prune_auth_locked(now):
    """Expire stale failure records and keep the source-IP map bounded.

    Called with AUTH_LOCK held. Without this, an attacker cycling source
    addresses would grow AUTH_FAILURES forever.
    """
    for ip, rec in list(AUTH_FAILURES.items()):
        if not rec or now - rec[1] > AUTH_BACKOFF:
            AUTH_FAILURES.pop(ip, None)
    if len(AUTH_FAILURES) >= MAX_AUTH_ENTRIES:
        # Keep the most recent half: one sweep amortizes the next many calls.
        newest = sorted(AUTH_FAILURES.items(), key=lambda kv: kv[1][1])
        keep = max(0, MAX_AUTH_ENTRIES // 2 - 1)
        for ip, _rec in newest[:len(newest) - keep]:
            AUTH_FAILURES.pop(ip, None)


def _auth_failure(ip):
    with AUTH_LOCK:
        now = _now()
        _prune_auth_locked(now)
        rec = AUTH_FAILURES.get(ip)
        if rec is None:
            AUTH_FAILURES[ip] = [1, now]
            return
        # Reset window after backoff period (normally pruned above).
        if now - rec[1] > AUTH_BACKOFF:
            AUTH_FAILURES[ip] = [1, now]
        else:
            rec[0] += 1
            rec[1] = now


def _auth_blocked(ip):
    with AUTH_LOCK:
        now = _now()
        _prune_auth_locked(now)
        rec = AUTH_FAILURES.get(ip)
        if not rec:
            return False
        count, last = rec
        if count >= MAX_AUTH_FAILURES and now - last < AUTH_BACKOFF:
            return True
        return False


def _auth_success(ip):
    with AUTH_LOCK:
        AUTH_FAILURES.pop(ip, None)


def _new_stream_token(did):
    tok = secrets.token_urlsafe(32)
    now = _now()
    with STREAM_TOKENS_LOCK:
        # Prune expired tokens opportunistically.
        for k in list(STREAM_TOKENS):
            if STREAM_TOKENS[k][1] < now:
                STREAM_TOKENS.pop(k, None)
        STREAM_TOKENS[tok] = (did, now + STREAM_TOKEN_TTL)
    return tok


def _check_stream_token(tok, did):
    if not tok:
        return False
    with STREAM_TOKENS_LOCK:
        rec = STREAM_TOKENS.get(tok)
        if not rec:
            return False
        tdid, exp = rec
        if exp < _now():
            STREAM_TOKENS.pop(tok, None)
            return False
        return tdid == did


# ---------------------------------------------------------------------------
# RPC helpers — wrap ghostctl.rpc with error handling and actor attribution.
# ---------------------------------------------------------------------------
def _rpc(req):
    try:
        return ghostctl.rpc(req)
    except Exception as ex:  # daemon down, timeout, decode error
        return {"ok": False, "error": str(ex)}


def _rpc_list():
    return _rpc({"op": "list"})


def _rpc_pending():
    return _rpc({"op": "pending"})


def _rpc_history(did, limit=60):
    resp = _rpc({"op": "history", "device_id": did, "limit": limit})
    if isinstance(resp, dict) and resp.get("ok"):
        return resp.get("history") or []
    return []


def _rpc_config():
    return _rpc({"op": "config"})


def _rpc_config_set(key, value):
    return _rpc({"op": "config", "args": ["set", key, str(value)]})


def _rpc_command(did, verb, actor, wait=20.0):
    parts = verb.split(None, 1)
    action = parts[0]
    rest = parts[1] if len(parts) > 1 else ""
    args = [rest] if action == "shell" and rest else (rest.split() if rest else [])
    return _rpc({"op": "command", "all": False, "device_id": did,
                 "action": action, "args": args, "wait": wait,
                 "actor": actor, "source": "console"})


def _rpc_broadcast(verb, actor, wait=20.0):
    parts = verb.split(None, 1)
    action = parts[0]
    rest = parts[1] if len(parts) > 1 else ""
    args = [rest] if action == "shell" and rest else (rest.split() if rest else [])
    return _rpc({"op": "command", "all": True, "device_id": None,
                 "action": action, "args": args, "wait": wait,
                 "actor": actor, "source": "console"})


def _rpc_update(did, actor):
    return _rpc({"op": "update", "device_id": did, "target": "self",
                 "actor": actor, "source": "console"})


def _rpc_remove(did, actor):
    # One explicit Remove command is the authorization: no separate confirm.
    return _rpc({"op": "remove", "device_id": did, "actor": actor,
                 "source": "console", "wait": 150.0})


UPLOAD_WAIT = 180.0      # seconds; large staged uploads can be slow
DOWNLOAD_WAIT = 120.0    # seconds; device -> server relay can be slow


def _bounded_wait(value, default):
    """Clamp a wait to the daemon's accepted finite 0.1..300s range."""
    try:
        return ghostctl.bounded_wait(value, default)
    except Exception:
        return default


def _rpc_files_put(did, src, dest, actor, wait=UPLOAD_WAIT):
    # The daemon ignores `wait` for files_put, but ghostctl.rpc uses it to
    # size the admin socket timeout so a real upload is not cut off at 30s.
    return _rpc({"op": "files_put", "device_id": did, "src": src,
                 "dest": dest, "wait": _bounded_wait(wait, UPLOAD_WAIT),
                 "actor": actor, "source": "console"})


def _rpc_files_get(did, path, actor, wait=DOWNLOAD_WAIT):
    # Prefer a dedicated op; fall back to the command verb for older daemons.
    resp = _rpc({"op": "files_get", "device_id": did, "path": path,
                 "wait": _bounded_wait(wait, DOWNLOAD_WAIT),
                 "actor": actor, "source": "console"})
    if isinstance(resp, dict) and "error" in resp and "unknown op" in str(resp.get("error", "")):
        resp = _rpc({"op": "command", "all": False, "device_id": did,
                     "action": "files", "args": ["get", path],
                     "wait": _bounded_wait(wait, DOWNLOAD_WAIT),
                     "actor": actor, "source": "console"})
    return resp


def _rpc_stream_frame(did):
    return _rpc({"op": "stream_frame", "device_id": did})


# ---------------------------------------------------------------------------
# Validation / parsing helpers
# ---------------------------------------------------------------------------
def _valid_device_id(did):
    return bool(did) and bool(DEVICE_ID_RE.match(did))


def _valid_path(path):
    # Device filenames may contain spaces, punctuation and non-ASCII text.
    # Reject control characters and traversal rather than restricting the
    # filename charset, so exact-path downloads match what `files ls` returns.
    if not isinstance(path, str) or not 1 <= len(path) <= MAX_PATH_LEN:
        return False
    if not all(c.isprintable() for c in path):
        return False
    if ".." in path or "\\" in path:
        return False
    return True


def _parse_multipart(content_type, body, max_parts=10):
    """Parse multipart/form-data safely. Returns (fields, file_paths)."""
    fields = {}
    file_paths = []
    try:
        msg = email.parser.BytesParser().parsebytes(
            b"Content-Type: " + content_type.encode() + b"\r\n\r\n" + body)
    except Exception:
        return fields, file_paths
    if not msg.is_multipart():
        return fields, file_paths
    count = 0
    try:
        for part in msg.get_payload():
            count += 1
            if count > max_parts:
                break
            name = part.get_param("name", header="content-disposition") or ""
            filename = part.get_filename() or ""
            payload = part.get_payload(decode=True) or b""
            if filename:
                # Save to uploads with a random name and mode 600.
                fname = secrets.token_hex(16) + ".bin"
                fpath = UPLOAD_DIR / fname
                fd = os.open(str(fpath),
                             os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
                try:
                    with os.fdopen(fd, "wb") as f:
                        f.write(payload)
                except Exception:
                    try:
                        os.close(fd)
                    except OSError:
                        pass
                    try:
                        fpath.unlink()
                    except OSError:
                        pass
                    raise
                file_paths.append((name, str(fpath), filename))
            else:
                try:
                    fields[name] = payload.decode("utf-8", "replace")
                except Exception:
                    fields[name] = ""
    except Exception:
        # Never leak a partially staged multipart body.
        _cleanup_staged(file_paths)
        return fields, []
    return fields, file_paths


def _clean_filename(name):
    base = os.path.basename(str(name or "download.bin").replace("\\", "/"))
    base = re.sub(r"[^A-Za-z0-9_.-]", "_", base)
    if not base or base.startswith("."):
        base = "download.bin"
    return base[:255]


def _default_dest(original_name):
    """Blank upload dest -> /sdcard/Download/<sanitized original basename>."""
    return "/sdcard/Download/" + _clean_filename(original_name)


def _valid_upload_dest(path):
    """Explicit upload destination: absolute, no traversal, safe charset."""
    if not isinstance(path, str) or not path.startswith("/"):
        return False
    return _valid_path(path)


def _cleanup_staged(files):
    """Unlink every staged multipart file; safe to call more than once."""
    for item in files or []:
        try:
            fpath = item[1] if isinstance(item, (tuple, list)) else item
        except Exception:
            continue
        try:
            os.unlink(fpath)
        except OSError:
            pass


def _response_error(resp, default="request failed"):
    """Best-effort error text from direct or command-entry RPC envelopes."""
    if not isinstance(resp, dict):
        return default
    err = resp.get("error")
    if isinstance(err, str) and err:
        return err
    entry = resp.get("entry")
    if isinstance(entry, dict):
        result = entry.get("result")
        if isinstance(result, dict):
            rerr = result.get("error")
            if isinstance(rerr, str) and rerr:
                return rerr
        elif isinstance(result, str) and result:
            return result
        status = entry.get("status")
        if isinstance(status, str) and status and status != "ok":
            return f"{default} ({status})"
    return default


def _download_b64(resp):
    """Extract base64 file content from either daemon response shape.

    Primary `files_get`: {"ok": true, "data": "<b64>"}
    Legacy command fallback: {"ok": true,
                              "entry": {"result": {"b64": "<b64>"}}}
    """
    if not isinstance(resp, dict) or not resp.get("ok"):
        return None
    for key in ("data", "content", "b64"):
        val = resp.get(key)
        if isinstance(val, str):
            return val
    entry = resp.get("entry")
    result = entry.get("result") if isinstance(entry, dict) else None
    if isinstance(result, dict):
        for key in ("b64", "data", "content"):
            val = result.get(key)
            if isinstance(val, str):
                return val
    return None


# ---------------------------------------------------------------------------
# Handler
# ---------------------------------------------------------------------------
class Handler(BaseHTTPRequestHandler):
    server_version = ""
    sys_version = ""

    def version_string(self):
        return ""

    def setup(self):
        super().setup()
        try:
            self.connection.settimeout(30)
        except OSError:
            pass

    def log_message(self, fmt, *args):
        # Suppress default logging; keep a compact line without secrets.
        _log(f"{self.address_string()} {fmt % args}")

    # ---------------------------------------------------------- helpers --
    def _client_ip(self):
        try:
            return self.client_address[0]
        except Exception:
            return "unknown"

    def _cookie_token(self):
        cookie = self.headers.get("Cookie", "")
        for part in cookie.split(";"):
            k, _, v = part.strip().partition("=")
            if k == "csrf_token":
                return v
        return ""

    def _send(self, code, body, ctype="text/html; charset=utf-8",
              extra_headers=None):
        data = body.encode("utf-8", "replace") if isinstance(body, str) else body
        try:
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Frame-Options", "DENY")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Referrer-Policy", "no-referrer")
            if SCHEME == "https":
                self.send_header("Strict-Transport-Security",
                                 "max-age=31536000")
            self.send_header(
                "Content-Security-Policy",
                "default-src 'self'; frame-ancestors 'none'; base-uri 'none'; "
                "form-action 'self'; script-src 'self'; style-src 'self' "
                "'unsafe-inline'; img-src 'self' data: blob:")
            # Refresh the CSRF cookie when absent or stale (e.g. after a
            # console restart). Comparing the value, not just the key,
            # prevents a stale cookie from pinning the browser to a token
            # the process no longer accepts.
            if code != 401:
                current = self._cookie_token()
                if not current or not hmac.compare_digest(
                        current.encode(), CSRF_TOKEN.encode()):
                    self.send_header(
                        "Set-Cookie",
                        f"csrf_token={CSRF_TOKEN}; HttpOnly; SameSite=Strict; "
                        f"Path=/; Max-Age=86400"
                        + ("; Secure" if SCHEME == "https" else ""))
            if extra_headers:
                for k, v in extra_headers.items():
                    self.send_header(k, v)
            self.end_headers()
            if data:
                self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, OSError):
            pass

    def _authed(self):
        ip = self._client_ip()
        if _auth_blocked(ip):
            return False
        hdr = self.headers.get("Authorization", "")
        if not hdr.startswith("Basic "):
            _auth_failure(ip)
            return False
        try:
            raw = base64.b64decode(hdr[6:].strip(), validate=True)
            decoded = raw.decode("utf-8")
        except Exception:
            _auth_failure(ip)
            return False
        _user, sep, pw = decoded.partition(":")
        if not sep:
            _auth_failure(ip)
            return False
        try:
            ok = hmac.compare_digest(pw.encode(), PASSWORD.encode())
        except Exception:
            ok = False
        if ok:
            _auth_success(ip)
            return True
        _auth_failure(ip)
        return False

    def _check(self):
        if _auth_blocked(self._client_ip()):
            body = "too many failed auth attempts; retry later"
            self._send(429, body, "text/plain",
                       extra_headers={"Retry-After": str(int(AUTH_BACKOFF))})
            return False
        if self._authed():
            return True
        body = ui.render_unauthorized()
        self.send_response(401)
        self.send_header("WWW-Authenticate", 'Basic realm="ghostctl"')
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body.encode())))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        if SCHEME == "https":
            self.send_header("Strict-Transport-Security", "max-age=31536000")
        self.send_header("Content-Security-Policy",
                         "default-src 'none'; frame-ancestors 'none'")
        self.end_headers()
        try:
            self.wfile.write(body.encode())
        except (BrokenPipeError, OSError):
            pass
        return False

    def _check_csrf(self, tok=None):
        """Verify Origin/Referer when supplied and the CSRF token."""
        origin = self.headers.get("Origin")
        referer = self.headers.get("Referer")
        host = self.headers.get("Host", "")
        allowed = set()
        if host:
            allowed.add(f"{SCHEME}://{host}")
            # A TLS reverse proxy may front a loopback plain-HTTP console.
            if SCHEME != "https":
                allowed.add(f"https://{host}")
        if origin and origin not in allowed:
            return False
        if not origin and referer:
            ok = False
            for a in allowed:
                if referer.startswith(a + "/") or referer == a:
                    ok = True
                    break
            if not ok:
                return False
        # Token in form or header must match the process token.
        if tok is None:
            tok = self.headers.get("X-CSRF-Token", "")
            if not tok:
                qs = self._last_qs or {}
                vals = qs.get("csrf_token") or []
                tok = vals[0] if vals else ""
        if not tok or not hmac.compare_digest(tok.encode(), CSRF_TOKEN.encode()):
            return False
        # The submitted token is compared against the process token above, so
        # a stale cookie is not an authorization failure: _send refreshes it
        # on this same response instead of locking the operator out.
        return True

    def _read_body(self, max_size):
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            self._send(400, "bad Content-Length", "text/plain")
            return None
        if n < 0:
            self._send(400, "bad Content-Length", "text/plain")
            return None
        if n > max_size:
            self._send(413, "payload too large", "text/plain")
            return None
        try:
            return self.rfile.read(n)
        except (OSError, ValueError):
            self._send(400, "bad request body", "text/plain")
            return None

    def _body_qs(self):
        raw = self._read_body(MAX_BODY)
        if raw is None:
            return None
        try:
            return parse_qs(raw.decode("utf-8", "replace"))
        except Exception:
            return {}

    _last_qs = None

    # -------------------------------------------------------------- GET --
    def do_GET(self):
        if not REQUEST_SEM.acquire(blocking=False):
            self._send(503, "server busy", "text/plain")
            return
        try:
            if not self._check():
                return
            u = urlparse(self.path)
            parts = [unquote(p) for p in u.path.strip("/").split("/") if p]
            if not parts:
                self._send(200, ui.render_dashboard(
                    *_dashboard_data(), csrf_token=CSRF_TOKEN))
                return
            if parts[0] == "static":
                self._serve_static(parts)
                return
            if parts[0] == "settings":
                resp = _rpc_config()
                cfg = resp.get("config") if isinstance(resp, dict) else {}
                self._send(200, ui.render_settings(cfg or {}, csrf_token=CSRF_TOKEN))
                return
            if parts[0] == "files" and len(parts) > 1 and parts[1] == "download":
                self._file_download(u.query)
                return
            if parts[0] == "stream" and len(parts) >= 2:
                did = parts[1]
                if not _valid_device_id(did):
                    self._send(400, "bad device id", "text/plain")
                    return
                if len(parts) == 2:
                    # Render stream page with a fresh token.
                    tok = _new_stream_token(did)
                    dev = _device_by_id(did)
                    if dev is None:
                        self._send(404, ui.render_404(f"unknown device {did}"))
                        return
                    self._send(200, ui.render_stream(dev, tok, csrf_token=CSRF_TOKEN))
                    return
                if len(parts) == 3 and parts[2] == "feed":
                    self.stream_feed(did)
                    return
                if len(parts) == 3 and parts[2] == "feed.mjpg":
                    self.stream_mjpg(did)
                    return
                self._send(404, ui.render_404())
                return
            if parts[0] == "device" and len(parts) >= 2:
                did = parts[1]
                if not _valid_device_id(did):
                    self._send(400, "bad device id", "text/plain")
                    return
                if len(parts) == 2:
                    dev = _device_by_id(did)
                    if dev is None:
                        self._send(404, ui.render_404(f"unknown device {did}"))
                        return
                    hist = _rpc_history(did, 60)
                    self._send(200, ui.render_device(dev, hist, csrf_token=CSRF_TOKEN))
                    return
                self._send(404, ui.render_404())
                return
            if parts[0] == "favicon.ico":
                self._send(204, b"", "image/x-icon")
                return
            self._send(404, ui.render_404())
        except Exception as ex:
            _log(f"GET error: {ex}")
            self._send(500, "internal error", "text/plain")
        finally:
            REQUEST_SEM.release()

    def _serve_static(self, parts):
        if len(parts) != 2:
            self._send(404, "not found", "text/plain")
            return
        if parts[1] == "app.css":
            self._send(200, ui.CSS, "text/css; charset=utf-8")
        elif parts[1] == "app.js":
            self._send(200, ui.JS, "application/javascript; charset=utf-8")
        else:
            self._send(404, "not found", "text/plain")

    # ------------------------------------------------------------- POST --
    def do_POST(self):
        if not REQUEST_SEM.acquire(blocking=False):
            self._send(503, "server busy", "text/plain")
            return
        try:
            if not self._check():
                return
            u = urlparse(self.path)
            parts = [unquote(p) for p in u.path.strip("/").split("/") if p]
            if not parts:
                self._send(404, ui.render_404())
                return

            # File uploads use raw multipart, not _body_qs.
            if parts[0] == "files" and len(parts) > 1 and parts[1] == "upload":
                self._file_upload()
                return
            if parts[0] == "files" and len(parts) > 1 and parts[1] == "abort":
                self._file_abort()
                return

            self._last_qs = self._body_qs()
            if self._last_qs is None:
                return
            if not self._check_csrf():
                self._send(403, CSRF_FAILED, "text/plain")
                return

            qs = self._last_qs
            actor = self._client_ip()

            if parts[0] == "settings":
                results = {}
                for key in ("max_pending", "max_per_ip"):
                    vals = qs.get(key) or []
                    if vals:
                        results[key] = _rpc_config_set(key, vals[0])
                if not results:
                    resp = {"ok": False, "error": "no settings provided"}
                else:
                    resp = {"ok": all(r.get("ok") for r in results.values()),
                            "results": results}
                self._send(200, ui.render_result("settings", resp, "/settings",
                                                 csrf_token=CSRF_TOKEN))
                return

            if parts[0] == "broadcast":
                verb = (qs.get("verb") or [""])[0].strip()
                arg = (qs.get("arg") or [""])[0].strip()
                if verb not in ui.BROADCAST_VERBS:
                    self._send(400, ui.render_404(f"broadcast verb not allowed: {verb}"))
                    return
                full = f"{verb} {arg}".strip() if arg else verb
                resp = _rpc_broadcast(full, actor)
                self._send(200, ui.render_result(f"broadcast {full}", resp,
                                                 "/", csrf_token=CSRF_TOKEN))
                return

            if parts[0] == "device" and len(parts) >= 3:
                did = parts[1]
                if not _valid_device_id(did):
                    self._send(400, "bad device id", "text/plain")
                    return
                action = parts[2]
                back = f"/device/{ui.quote_safe(did)}"
                if action == "remove":
                    dev = _device_by_id(did) or {"device_id": did}
                    resp = _rpc_remove(did, actor)
                    self._send(200, ui.render_remove_result(
                        dev, resp, csrf_token=CSRF_TOKEN))
                    return
                if action == "rename":
                    name = (qs.get("name") or [""])[0].strip()
                    resp = _rpc({"op": "rename", "device_id": did,
                                 "name": name, "actor": actor, "source": "console"})
                elif action == "approve":
                    resp = _rpc({"op": "approve", "device_id": did,
                                 "actor": actor, "source": "console"})
                elif action == "revoke":
                    resp = _rpc({"op": "revoke", "device_id": did,
                                 "actor": actor, "source": "console"})
                elif action == "update":
                    resp = _rpc_update(did, actor)
                elif action == "cmd":
                    verb = (qs.get("verb") or [""])[0].strip()
                    arg = (qs.get("arg") or [""])[0].strip()
                    if verb not in ui.ALLOWED_VERBS:
                        self._send(400, ui.render_404(f"verb not allowed: {verb}"))
                        return
                    full = f"{verb} {arg}".strip() if arg else verb
                    resp = _rpc_command(did, full, actor)
                else:
                    self._send(404, ui.render_404())
                    return
                self._send(200, ui.render_result(f"{action} → {did}", resp, back,
                                                 csrf_token=CSRF_TOKEN))
                return

            self._send(404, ui.render_404())
        except Exception as ex:
            _log(f"POST error: {ex}")
            self._send(500, "internal error", "text/plain")
        finally:
            REQUEST_SEM.release()

    # ---------------------------------------------------------- uploads --
    def _file_upload(self):
        ctype = self.headers.get("Content-Type", "")
        if "multipart/form-data" not in ctype:
            self._send(400, "expected multipart/form-data", "text/plain")
            return
        raw = self._read_body(MAX_UPLOAD)
        if raw is None:
            return
        fields = {}
        files = []

        def reply(status, body, content_type="text/plain"):
            # Do not report a result until staged bytes are gone.
            _cleanup_staged(files)
            self._send(status, body, content_type)

        try:
            fields, files = _parse_multipart(ctype, raw)
            if not files:
                reply(400, "no file part")
                return
            if not self._check_csrf(fields.get("csrf_token")):
                reply(403, CSRF_FAILED)
                return
            did = fields.get("device_id", "").strip()
            if not _valid_device_id(did):
                reply(400, "bad device id")
                return
            dest_raw = fields.get("dest", "").strip()
            if dest_raw and not _valid_upload_dest(dest_raw):
                reply(400, "invalid upload destination: use an absolute "
                           "path without '..'")
                return
            if len(files) > 1 and dest_raw and not dest_raw.endswith("/"):
                reply(400, "upload destination must be a directory "
                           "(trailing /) when sending multiple files")
                return
            actor = self._client_ip()
            results = []
            for _field, fpath, orig in files:
                dest = dest_raw or _default_dest(orig)
                resp = _rpc_files_put(did, fpath, dest, actor)
                results.append({"file": orig, "dest": dest, "result": resp})
            ok = all(isinstance(r["result"], dict) and r["result"].get("ok")
                     for r in results)
            if len(results) == 1:
                payload = results[0]["result"]
            else:
                payload = {"ok": ok, "files": results}
            reply(200 if ok else 502,
                  ui.render_result(f"upload → {did}", payload,
                                   f"/device/{ui.quote_safe(did)}",
                                   csrf_token=CSRF_TOKEN),
                  "text/html; charset=utf-8")
        finally:
            # Every validation and transfer path must remove staged bytes.
            _cleanup_staged(files)

    def _file_abort(self):
        qs = self._body_qs()
        if qs is None:
            return
        self._last_qs = qs
        if not self._check_csrf():
            self._send(403, CSRF_FAILED, "text/plain")
            return
        did = (qs.get("device_id") or [""])[0].strip()
        if not _valid_device_id(did):
            self._send(400, "bad device id", "text/plain")
            return
        # This is a real device command (`files abort`), not a local ack:
        # the daemon may have already streamed bytes, so say "requested".
        resp = _rpc_command(did, "files abort", self._client_ip(), wait=20.0)
        if isinstance(resp, dict) and resp.get("ok"):
            self._send(200, f"abort requested for {did}; the device cancels "
                            f"the transfer when the command arrives and any "
                            f"server-side work already in progress may still "
                            f"finish", "text/plain")
            return
        err = _response_error(resp, "abort request failed")
        self._send(502, f"abort request failed: {err}", "text/plain")

    def _file_download(self, query):
        qs = parse_qs(query)
        did = (qs.get("device_id") or [""])[0]
        path = (qs.get("path") or [""])[0]
        if not _valid_device_id(did) or not _valid_path(path):
            self._send(400, "bad device id or path", "text/plain")
            return
        actor = self._client_ip()
        resp = _rpc_files_get(did, path, actor)
        if not isinstance(resp, dict) or not resp.get("ok"):
            err = _response_error(resp)
            self._send(502, f"download failed: {err}", "text/plain")
            return
        data = _download_b64(resp)
        if data is None:
            err = _response_error(
                resp, "daemon or device did not return file content")
            self._send(502, f"download failed: {err}", "text/plain")
            return
        try:
            # Empty base64 is a valid verified zero-byte download.
            payload = base64.b64decode(data, validate=True)
        except Exception:
            self._send(502, "download decode failed", "text/plain")
            return
        if len(payload) > MAX_UPLOAD:
            self._send(413, "download too large", "text/plain")
            return
        fname = _clean_filename(os.path.basename(path))
        self._send(200, payload, "application/octet-stream",
                   extra_headers={"Content-Disposition":
                                  f'attachment; filename="{fname}"'})

    # ----------------------------------------------------------- stream --
    def stream_feed(self, did):
        tok = parse_qs(urlparse(self.path).query).get("t", [""])[0]
        if not _check_stream_token(tok, did):
            self._send(403, "invalid or expired stream token", "text/plain")
            return
        if not _device_ok(did):
            self._send(503, "device offline or not approved", "text/plain")
            return
        stream, frame, ts, err = _fetch_frame(did)
        if err or frame is None:
            self._send(503, f"no active jpeg stream: {err}", "text/plain")
            return
        self._send(200, frame, "image/jpeg")

    def stream_mjpg(self, did):
        tok = parse_qs(urlparse(self.path).query).get("t", [""])[0]
        if not _check_stream_token(tok, did):
            self._send(403, "invalid or expired stream token", "text/plain")
            return
        if not _device_ok(did):
            self._send(503, "device offline or not approved", "text/plain")
            return
        if not STREAM_SEM.acquire(blocking=False):
            self._send(503, "too many stream clients", "text/plain")
            return
        try:
            # Fetch first frame BEFORE headers so a dead/empty stream gets 503.
            stream, first, _ts, err = _fetch_frame(did)
            if err or first is None:
                self._send(503, f"no active jpeg stream: {err}", "text/plain")
                return
            self.send_response(200)
            self.send_header("Content-Type",
                             "multipart/x-mixed-replace; boundary=frame")
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Frame-Options", "DENY")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            last = b""
            last_send = _now()
            try:
                while True:
                    _stream, frame, _ts, err = _fetch_frame(did)
                    now = _now()
                    if frame and not err and frame != last:
                        last = frame
                        self.wfile.write(b"--frame\r\n")
                        self.wfile.write(b"Content-Type: image/jpeg\r\n"
                                         + f"Content-Length: {len(frame)}\r\n\r\n".encode()
                                         + frame + b"\r\n")
                        self.wfile.flush()
                        last_send = now
                    elif now - last_send > 10.0:
                        break
                    else:
                        # Heartbeat comment keeps dead sockets raising.
                        try:
                            self.wfile.write(b"\r\n--frame\r\n")
                            self.wfile.flush()
                        except (BrokenPipeError, ConnectionResetError, OSError):
                            break
                        time.sleep(0.5)
            except (BrokenPipeError, ConnectionResetError,
                    ConnectionAbortedError, OSError):
                pass
        finally:
            STREAM_SEM.release()


# ---------------------------------------------------------------------------
# Device lookup helpers
# ---------------------------------------------------------------------------
def _device_ok(did):
    """Return True when the device exists, is online, and approved."""
    dev = _device_by_id(did)
    return bool(dev and dev.get("online") and dev.get("approved"))


def _fetch_frame(did):
    """Return (stream, frame_bytes, ts, err). err is None on success."""
    try:
        resp = _rpc_stream_frame(did)
    except Exception as ex:
        return None, None, None, f"daemon unreachable: {ex}"
    if not isinstance(resp, dict):
        return None, None, None, "malformed daemon response"
    if not resp.get("ok"):
        return None, None, None, resp.get("error", "no frame")
    raw = resp.get("frame_b64")
    if not isinstance(raw, str) or not raw:
        return None, None, None, "missing frame_b64"
    try:
        frame = base64.b64decode(raw, validate=True)
    except Exception:
        return None, None, None, "invalid base64 frame"
    if len(frame) > MAX_FRAME_BYTES:
        return None, None, None, "frame too large"
    ts = resp.get("ts")
    if ts is not None:
        try:
            age = _now() - float(ts)
            if age > FRAME_MAX_AGE:
                return None, None, None, f"stale frame ({int(age)}s old)"
        except (TypeError, ValueError):
            return None, None, None, "invalid frame timestamp"
    return resp.get("stream"), frame, ts, None


def _dashboard_data():
    resp = _rpc_list()
    devices = resp.get("devices", {}) if isinstance(resp, dict) else {}
    if not isinstance(devices, dict):
        devices = {}
    pending_resp = _rpc_pending()
    pending = pending_resp.get("pending", {}) if isinstance(pending_resp, dict) else {}
    if not isinstance(pending, dict):
        pending = {}
    # Pending ids are already in the main device list; avoid double-render.
    pending = {k: v for k, v in pending.items() if not (devices.get(k) or {}).get("approved")}
    return devices, pending


def _device_by_id(did):
    resp = _rpc_list()
    if not isinstance(resp, dict):
        return None
    devs = resp.get("devices", {})
    if not isinstance(devs, dict):
        return None
    d = devs.get(did)
    if not isinstance(d, dict):
        return None
    d = dict(d)
    d.setdefault("device_id", did)
    return d


# ---------------------------------------------------------------------------
# Startup / password handling
# ---------------------------------------------------------------------------
def _is_loopback(host):
    return host in ("127.0.0.1", "::1", "localhost")


def _resolve_password(host):
    pw = os.environ.get("GHOST_CONSOLE_PASS")
    if pw:
        return pw
    if not _is_loopback(host):
        sys.exit("GHOST_CONSOLE_PASS is required for non-loopback binds; "
                 "set it in the environment or server/.env")
    pw = secrets.token_urlsafe(18)
    pass_file = BASE / "console.pass"
    fd = os.open(str(pass_file), os.O_CREAT | os.O_TRUNC | os.O_WRONLY, 0o600)
    try:
        with os.fdopen(fd, "w") as f:
            f.write(pw)
        os.chmod(str(pass_file), 0o600)
    except Exception:
        try:
            os.close(fd)
        except OSError:
            pass
        raise
    return pw


def _tls_context(certfile, keyfile):
    """Build a server TLS context, refusing a group/world-readable key."""
    try:
        mode = os.stat(keyfile).st_mode
    except OSError as ex:
        raise ValueError(f"cannot read TLS keyfile: {ex.strerror or ex}") from ex
    if mode & 0o077:
        raise ValueError("TLS keyfile must not be group/world-readable "
                         "(chmod 600)")
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(certfile=certfile, keyfile=keyfile)
    return context


def enable_tls(httpd, context):
    """Enable native HTTPS on a TLSServer using a prevalidated context."""
    httpd.tls_context = context
    global SCHEME
    SCHEME = "https"
    return "https"


class TLSServer(ThreadingHTTPServer):
    """Handshake in the worker thread so an idle TCP client cannot stall accept."""
    tls_context = None

    def get_request(self):
        # Never wrap the listening socket: SSLSocket.accept() would perform
        # the handshake in the accept loop.
        return self.socket.accept()

    def finish_request(self, request, client_address):
        context = self.tls_context
        if context is None:
            return super().finish_request(request, client_address)
        try:
            request.settimeout(10)
        except OSError:
            pass
        if not TLS_HANDSHAKE_SEM.acquire(blocking=False):
            return
        try:
            tls = context.wrap_socket(request, server_side=True)
        except (ssl.SSLError, OSError):
            return
        finally:
            TLS_HANDSHAKE_SEM.release()
        try:
            super().finish_request(tls, client_address)
        finally:
            try:
                tls.close()
            except OSError:
                pass


def main():
    ap = argparse.ArgumentParser(description="ghostctl web console")
    ap.add_argument("--host", default="127.0.0.1",
                    help="bind host (default 127.0.0.1; use 0.0.0.0 for remote "
                         "+ explicit firewall rule — never open preemptively)")
    ap.add_argument("--port", type=int, default=9443)
    ap.add_argument("--certfile", help="TLS certificate PEM for native HTTPS")
    ap.add_argument("--keyfile", help="private key PEM for native HTTPS")
    a = ap.parse_args()

    # An empty host means INADDR_ANY to socketserver, not loopback.
    if not a.host:
        a.host = "127.0.0.1"
    if bool(a.certfile) != bool(a.keyfile):
        sys.exit("HTTPS requires both --certfile and --keyfile")

    tls_context = None
    if a.certfile:
        try:
            tls_context = _tls_context(a.certfile, a.keyfile)
        except (ssl.SSLError, OSError, ValueError) as ex:
            sys.exit(f"TLS setup failed: {ex}")
    if not _is_loopback(a.host) and not a.certfile:
        allow_http = os.environ.get("GHOST_CONSOLE_ALLOW_HTTP") == "1"
        if not allow_http:
            sys.exit("Refusing to bind non-loopback without TLS. Supply "
                     "--certfile and --keyfile, use a TLS reverse proxy, or set "
                     "GHOST_CONSOLE_ALLOW_HTTP=1 to accept the risk.")
        _log(f"WARNING: serving plain HTTP on non-loopback {a.host} "
             f"(GHOST_CONSOLE_ALLOW_HTTP=1)")

    _secure_log_file()

    global PASSWORD
    PASSWORD = _resolve_password(a.host)

    httpd = TLSServer((a.host, a.port), Handler)
    scheme = enable_tls(httpd, tls_context) if tls_context else "http"
    _log(f"ghostctl console listening on {scheme}://{a.host}:{a.port}")
    if _is_loopback(a.host) and not os.environ.get("GHOST_CONSOLE_PASS"):
        _log(f"generated password written to {BASE / 'console.pass'} (mode 600)")
    _log(f"daemon admin at {ghostctl.ADMIN_HOST}:{ghostctl.ADMIN_PORT}")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
