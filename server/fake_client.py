#!/usr/bin/env python3
"""Fake ghostd client for testing the ghostctl daemon.

Registers as a device over WebSocket, answers routed commands:
- 'shell' runs locally via subprocess
- 'files put <dest>' receives the server's file stream (FileReceiver
  protocol: file_start/file_chunk binary frames/file_end, sha256 check)
  and ACKs with {path, bytes, chunks, sha256}
- 'screen live' simulates a MediaBus stream: stream_start + a few framed
  fake JPEG binary frames (no stream_end, so the daemon's stream cache
  keeps the latest frame for /stream/<id>/feed)
- everything else echoes action+args back as the result.

Usage: python3 fake_client.py <device_id> [ws://127.0.0.1:8443]
"""
import asyncio
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path

import websockets

BASE = Path(__file__).resolve().parent


def frame(header, payload):
    """MediaBus framing: JSON header + b'\\n' + payload."""
    return json.dumps(header).encode() + b"\n" + payload


def fake_jpeg(seq):
    return (b"\xff\xd8\xff\xe0" + f"FAKEJPEG-{seq:03d}".encode() +
            b"\x00" * 64 + b"\xff\xd9")


async def main():
    device_id = sys.argv[1] if len(sys.argv) > 1 else "test-1"
    url = sys.argv[2] if len(sys.argv) > 2 else "ws://127.0.0.1:8443"
    # enrollment key / stored token: env overrides, else local files next to
    # the script (mirror the daemon's bootstrap for testing)
    enroll_key = os.environ.get("GHOST_ENROLL_KEY",
                                (BASE / "enroll.key").read_text().strip()
                                if (BASE / "enroll.key").exists() else "")
    token_file = BASE / f".fake-client-token-{device_id}"
    token = token_file.read_text().strip() if token_file.exists() else None
    rx = None  # in-progress files-put receive: {"cmd_id","path","digest","written","chunks"}
    async with websockets.connect(url) as ws:
        hello = {"type": "hello", "device_id": device_id,
                 "battery": 88, "ip": "127.0.0.1"}
        if enroll_key:
            hello["enroll_key"] = enroll_key
        if token:
            hello["token"] = token
        await ws.send(json.dumps(hello))
        async for raw in ws:
            if isinstance(raw, (bytes, bytearray)):  # framed binary
                nl = raw.find(b"\n")
                if nl <= 0:
                    continue
                try:
                    header = json.loads(raw[:nl])
                except json.JSONDecodeError:
                    continue
                if header.get("type") == "file_chunk" and rx:
                    payload = raw[nl + 1:]
                    rx["digest"].update(payload)
                    rx["written"] += len(payload)
                    rx["chunks"] += 1
                continue
            msg = json.loads(raw)
            mtype = msg.get("type")
            if mtype == "hello_ack":
                print(f"hello_ack: {msg.get('status')}", file=sys.stderr)
                if msg.get("token"):
                    token_file.write_text(msg["token"])
                if msg.get("status") == "denied":
                    return
                continue
            if mtype == "cmd":
                action, cmd_args = msg["action"], msg.get("args", [])
                if action == "shell":
                    try:
                        out = subprocess.run(cmd_args[0], shell=True,
                                              capture_output=True, text=True, timeout=20)
                        result = {"rc": out.returncode,
                                  "stdout": out.stdout, "stderr": out.stderr}
                        ok = True
                    except Exception as e:  # noqa: BLE001
                        result, ok = str(e), False
                elif action == "files" and cmd_args[:1] == ["put"]:
                    # start receiving; result is sent on file_end
                    rx = {"cmd_id": msg["cmd_id"], "path": cmd_args[1],
                          "digest": hashlib.sha256(), "written": 0, "chunks": 0}
                    continue
                elif action == "screen" and cmd_args[:1] in (["live"], ["start"]):
                    stream = f"screen-live"
                    await ws.send(json.dumps(
                        {"type": "stream_start", "stream": stream,
                         "encoding": "mjpeg",
                         "meta": {"cmd_id": msg["cmd_id"]}}))
                    for i in range(5):
                        payload = fake_jpeg(i)
                        await ws.send(frame(
                            {"type": "frame", "stream": stream, "seq": i + 1,
                             "ts": 1_700_000_000_000 + i, "len": len(payload)},
                            payload))
                        await asyncio.sleep(0.1)
                    result, ok = {"streaming": stream, "frames": 5}, True
                elif action == "screen" and cmd_args[:1] == ["stop"]:
                    await ws.send(json.dumps({"type": "stream_end",
                                              "stream": "screen-live",
                                              "frames": 5, "reason": "stopped"}))
                    result, ok = {"stopped": True}, True
                else:
                    result, ok = {"echo": {"action": action, "args": cmd_args}}, True
                await ws.send(json.dumps({"type": "result", "cmd_id": msg["cmd_id"],
                                          "ok": ok, "result": result}))
            elif mtype == "file_end":
                # server finished the files-put stream -> ACK the pending cmd
                if rx:
                    sha = rx["digest"].hexdigest()
                    ok = (not msg.get("sha256")
                          or msg["sha256"].lower() == sha.lower())
                    result = {"path": rx["path"], "bytes": rx["written"],
                              "chunks": rx["chunks"], "sha256": sha}
                    if not ok:
                        result["error"] = "sha256 mismatch: server sent " + str(msg.get("sha256"))
                    await ws.send(json.dumps({"type": "result",
                                              "cmd_id": rx["cmd_id"],
                                              "ok": ok, "result": result}))
                    rx = None
            elif mtype == "file_start":
                continue
            elif mtype == "pong":
                continue


if __name__ == "__main__":
    asyncio.run(main())
