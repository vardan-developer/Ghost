#!/usr/bin/env python3
"""Fake ghostd client for testing the ghostctl daemon.

Registers as a device over WebSocket, answers routed commands:
runs 'shell' locally via subprocess, echoes everything else.

Usage: python3 fake_client.py <device_id> [ws://127.0.0.1:8443]
"""
import asyncio
import json
import subprocess
import sys

import websockets


async def main():
    device_id = sys.argv[1] if len(sys.argv) > 1 else "test-1"
    url = sys.argv[2] if len(sys.argv) > 2 else "ws://127.0.0.1:8443"
    async with websockets.connect(url) as ws:
        await ws.send(json.dumps({"type": "hello", "device_id": device_id,
                                  "battery": 88, "ip": "127.0.0.1"}))
        async for raw in ws:
            msg = json.loads(raw)
            if msg.get("type") == "cmd":
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
                else:
                    result, ok = {"echo": {"action": action, "args": cmd_args}}, True
                await ws.send(json.dumps({"type": "result", "cmd_id": msg["cmd_id"],
                                          "ok": ok, "result": result}))
            elif msg.get("type") == "pong":
                continue


if __name__ == "__main__":
    asyncio.run(main())
