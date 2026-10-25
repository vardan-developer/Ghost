"""Focused async tests for the server/daemon audit fixes.

Run from server/ with:
    python3 -m unittest test_daemon.py
"""
import asyncio
import base64
import hashlib
import json
import os
import sys
import tempfile
import time
import types
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

# Some environments (including this checkout) do not have the optional
# websockets package installed. Provide a tiny stub so daemon.py can import;
# tests that exercise run() patch this stub anyway.
if "websockets" not in sys.modules:
    try:
        import websockets  # noqa: F401
    except ImportError:
        _ws = types.ModuleType("websockets")

        class _ConnectionClosed(Exception):
            pass

        _exc = types.ModuleType("websockets.exceptions")
        _exc.ConnectionClosed = _ConnectionClosed
        _ws.exceptions = _exc
        _ws.serve = MagicMock()
        sys.modules["websockets"] = _ws
        sys.modules["websockets.exceptions"] = _exc

os.environ["GHOSTCTL_TEST"] = "1"
os.environ["GHOST_ENROLL_KEY"] = "test-enroll-key"
os.environ["GHOSTCTL_ADMIN_TOKEN"] = "test-admin-token"

import daemon  # noqa: E402


class FakeWS:
    def __init__(self, remote_address=("127.0.0.1", 1234)):
        self.remote_address = remote_address
        self.sent = []
        self.closed = False
        self.incoming = asyncio.Queue()

    async def send(self, data):
        if self.closed:
            raise daemon.websockets.exceptions.ConnectionClosed(None, None)
        self.sent.append(data)

    async def close(self):
        if not self.closed:
            self.closed = True
            await self.incoming.put(None)

    async def recv(self):
        item = await self.incoming.get()
        if item is None:
            raise daemon.websockets.exceptions.ConnectionClosed(None, None)
        return item

    def push(self, msg):
        if isinstance(msg, dict):
            msg = json.dumps(msg)
        self.incoming.put_nowait(msg)


class FakeAdminReader:
    """Newline reader that yields requests, then EOF after an optional delay."""

    def __init__(self, lines, eof_delay=0.0):
        self.lines = list(lines)
        self.eof_delay = eof_delay

    async def readline(self):
        if self.lines:
            return self.lines.pop(0)
        if self.eof_delay:
            await asyncio.sleep(self.eof_delay)
        return b""


class FakeAdminWriter:
    def __init__(self):
        self.writes = []
        self.closed = False

    def write(self, data):
        self.writes.append(data)

    async def drain(self):
        pass

    def close(self):
        self.closed = True


class DaemonTestBase(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        base = Path(self.tmp.name)
        self.patches = [
            patch.object(daemon, "BASE", base),
            patch.object(daemon, "STATE_FILE", base / "state.json"),
            patch.object(daemon, "HISTORY_FILE", base / "history.json"),
            patch.object(daemon, "APK_DIR", base / "apks"),
            patch.object(daemon, "UPLOAD_DIR", base / "uploads"),
            patch.object(daemon, "ENROLL_KEY_FILE", base / "enroll.key"),
            patch.object(daemon, "ADMIN_KEY_FILE", base / "admin.key"),
            patch.object(daemon, "CONFIG_FILE", base / "config.json"),
            patch.object(daemon, "LOG_FILE", base / "daemon.log"),
            patch.object(daemon, "ENV_FILE", base / ".env"),
        ]
        for p in self.patches:
            p.start()
            self.addCleanup(p.stop)
        self.d = daemon.Daemon()
        self.d.admin_token = "test-admin-token"
        self.d.enroll_key = "test-enroll-key"

    async def _start_handler(self, ws):
        task = asyncio.create_task(self.d.device_handler(ws))

        async def cleanup():
            await ws.close()
            task.cancel()
            try:
                await task
            except (asyncio.CancelledError, Exception):
                pass

        self.addAsyncCleanup(cleanup)
        return task


class HelloTests(DaemonTestBase):
    async def test_invalid_id_denied_without_phantom(self):
        ws = FakeWS()
        did, ack = await self.d.handle_hello(
            ws, {"type": "hello", "device_id": "bad\x00id"})
        self.assertIsNone(did)
        self.assertEqual(ack["status"], "denied")
        self.assertEqual(self.d.devices, {})

    async def test_enroll_pending_and_cap_is_atomic(self):
        self.d.config["max_pending"] = 1
        ws = FakeWS()
        did, ack = await self.d.handle_hello(
            ws, {"type": "hello", "device_id": "d1",
                 "enroll_key": "test-enroll-key"})
        self.assertEqual(did, "d1")
        self.assertEqual(ack["status"], "pending")
        self.assertIn("d1", self.d.devices)
        did2, ack2 = await self.d.handle_hello(
            ws, {"type": "hello", "device_id": "d2",
                 "enroll_key": "test-enroll-key"})
        self.assertIsNone(did2)
        self.assertEqual(ack2["status"], "denied")
        self.assertNotIn("d2", self.d.devices)

    async def test_approved_device_reenroll_rejected(self):
        self.d.devices["d1"] = {"approved": True, "token": "tok"}
        ws = FakeWS()
        did, ack = await self.d.handle_hello(
            ws, {"type": "hello", "device_id": "d1",
                 "enroll_key": "test-enroll-key"})
        self.assertIsNone(did)
        self.assertEqual(ack["status"], "denied")
        self.assertTrue(self.d.devices["d1"]["approved"])

    async def test_valid_token_approved(self):
        self.d.devices["d1"] = {"approved": True, "token": "tok"}
        ws = FakeWS()
        did, ack = await self.d.handle_hello(
            ws, {"type": "hello", "device_id": "d1", "token": "tok"})
        self.assertEqual(did, "d1")
        self.assertEqual(ack["status"], "approved")
        self.assertNotIn("token", ack)
        self.assertEqual(self.d.devices["d1"]["token_sha256"],
                         daemon._token_hash("tok"))
        self.assertNotIn("token", self.d.devices["d1"])

    async def test_pending_enrollment_stores_only_token_hash(self):
        ws = FakeWS()
        did, ack = await self.d.handle_hello(
            ws, {"type": "hello", "device_id": "d1",
                 "enroll_key": "test-enroll-key"})
        self.assertEqual(did, "d1")
        self.assertEqual(ack["status"], "pending")
        self.assertNotIn("token", self.d.devices["d1"])
        self.assertEqual(self.d.devices["d1"]["token_sha256"],
                         daemon._token_hash(ack["token"]))

    async def test_rotated_token_promotes_on_reconnect(self):
        new = "0123456789abcdef0123456789abcdef"
        self.d.devices["d1"] = {
            "approved": True, "token": "tok",
            "token_sha256": daemon._token_hash("tok"),
            "token_next_sha256": daemon._token_hash(new),
        }
        ws = FakeWS()
        did, ack = await self.d.handle_hello(
            ws, {"type": "hello", "device_id": "d1", "token": new})
        self.assertEqual(did, "d1")
        self.assertEqual(ack["status"], "approved")
        self.assertEqual(self.d.devices["d1"]["token_sha256"],
                         daemon._token_hash(new))
        self.assertNotIn("token_next_sha256", self.d.devices["d1"])

    async def test_hello_and_info_store_numeric_version_code(self):
        self.d.devices["d1"] = {"approved": True, "token": "tok"}
        ws = FakeWS()
        ws.push({"type": "hello", "device_id": "d1", "token": "tok",
                 "version_code": 42})
        await self._start_handler(ws)
        await asyncio.sleep(0.05)
        self.assertEqual(self.d.devices["d1"]["version_code"], 42)
        ws.push({"type": "info", "battery": 90, "version_code": 43})
        await asyncio.sleep(0.05)
        self.assertEqual(self.d.devices["d1"]["version_code"], 43)
        # An older client omitting the field must not erase the known code.
        ws.push({"type": "info", "battery": 91})
        await asyncio.sleep(0.05)
        self.assertEqual(self.d.devices["d1"]["version_code"], 43)


class StateTokenMigrationTests(DaemonTestBase):
    async def test_plaintext_state_tokens_migrate_to_hashes(self):
        daemon.STATE_FILE.write_text(json.dumps({
            "d1": {"approved": True, "token": "old-secret"},
            "d2": {"approved": False, "token_next": "next-secret"},
        }))
        d = daemon.Daemon()
        self.assertEqual(d.devices["d1"]["token_sha256"],
                         daemon._token_hash("old-secret"))
        self.assertNotIn("token", d.devices["d1"])
        self.assertEqual(d.devices["d2"]["token_next_sha256"],
                         daemon._token_hash("next-secret"))
        self.assertNotIn("token_next", d.devices["d2"])
        persisted = json.loads(daemon.STATE_FILE.read_text())
        self.assertNotIn("token", persisted["d1"])
        self.assertNotIn("token_next", persisted["d2"])
        self.assertEqual(persisted["d1"]["token_sha256"],
                         daemon._token_hash("old-secret"))


class AdminTests(DaemonTestBase):
    async def test_auth_and_token_redaction(self):
        self.d.devices["d1"] = {"approved": True, "token": "secret-token"}
        self.d.devices["d2"] = {"approved": False, "token": "secret2"}
        resp = await self.d.admin_dispatch({"op": "list"})
        self.assertFalse(resp["ok"])
        self.assertEqual(resp["error"], "unauthorized")
        resp = await self.d.admin_dispatch(
            {"op": "list", "token": "test-admin-token"})
        self.assertTrue(resp["ok"])
        self.assertEqual(resp["devices"]["d1"]["token"], "***")
        resp = await self.d.admin_dispatch(
            {"op": "pending", "token": "test-admin-token"})
        self.assertTrue(resp["ok"])
        self.assertEqual(resp["pending"]["d2"]["token"], "***")

    async def test_rotate_admin_key_updates_runtime_and_file(self):
        with patch.object(daemon, "_env_secret", return_value=None):
            resp = await self.d.admin_dispatch(
                {"op": "rotate_admin", "token": "test-admin-token"})
        self.assertTrue(resp["ok"])
        self.assertNotEqual(self.d.admin_token, "test-admin-token")
        self.assertEqual(daemon.ADMIN_KEY_FILE.stat().st_mode & 0o777, 0o600)
        self.assertEqual(daemon.ADMIN_KEY_FILE.read_text().strip(),
                         self.d.admin_token)
        old = await self.d.admin_dispatch(
            {"op": "list", "token": "test-admin-token"})
        self.assertFalse(old["ok"])
        new = await self.d.admin_dispatch(
            {"op": "list", "token": self.d.admin_token})
        self.assertTrue(new["ok"])

    async def test_rotate_admin_key_rejects_env_override(self):
        with patch.object(daemon, "_env_secret", return_value="env-token"):
            resp = await self.d.admin_dispatch(
                {"op": "rotate_admin", "token": "test-admin-token"})
        self.assertFalse(resp["ok"])
        self.assertIn("GHOSTCTL_ADMIN_TOKEN", resp["error"])

    async def test_hashed_token_fields_are_redacted(self):
        self.d.devices["d1"] = {
            "approved": True,
            "token_sha256": daemon._token_hash("tok"),
            "token_next_sha256": daemon._token_hash("next"),
        }
        resp = await self.d.admin_dispatch(
            {"op": "list", "token": "test-admin-token"})
        self.assertEqual(resp["devices"]["d1"]["token_sha256"], "***")
        self.assertEqual(resp["devices"]["d1"]["token_next_sha256"], "***")

    async def test_rotate_token_requires_device_ack(self):
        self.d.devices["d1"] = {"approved": True, "token": "tok"}
        ws = FakeWS()
        self.d.conns["d1"] = ws
        task = asyncio.create_task(self.d.admin_dispatch(
            {"op": "rotate_token", "token": "test-admin-token",
             "device_id": "d1", "wait": 5}))
        await asyncio.sleep(0.05)
        frame = next(json.loads(m) for m in ws.sent
                     if isinstance(m, str)
                     and json.loads(m).get("type") == "auth_rotate")
        new = frame["token"]
        self.assertRegex(new, r"^[0-9a-f]{32}$")
        self.assertNotEqual(new, "tok")
        self.assertEqual(len(ws.sent), 1)
        self.assertTrue(self.d._handle_token_rotation_ack(
            "d1", ws, {"type": "auth_rotate_ack", "ok": True}))
        resp = await task
        self.assertTrue(resp["ok"])
        self.assertTrue(resp["rotated"])
        self.assertEqual(self.d.devices["d1"]["token_sha256"],
                         daemon._token_hash(new))
        self.assertNotIn("token_next_sha256", self.d.devices["d1"])

    async def test_rotate_token_rejects_offline_device(self):
        self.d.devices["d1"] = {"approved": True, "token": "tok"}
        resp = await self.d.admin_dispatch(
            {"op": "rotate_token", "token": "test-admin-token",
             "device_id": "d1"})
        self.assertFalse(resp["ok"])
        self.assertIn("offline", resp["error"])

    async def test_failed_rotation_ack_keeps_old_token(self):
        old = daemon._token_hash("tok")
        self.d.devices["d1"] = {
            "approved": True, "token_sha256": old,
            "token_next_sha256": daemon._token_hash("next"),
        }
        ws = FakeWS()
        self.d.conns["d1"] = ws
        self.assertFalse(self.d._handle_token_rotation_ack(
            "d1", ws, {"type": "auth_rotate_ack", "ok": False}))
        self.assertEqual(self.d.devices["d1"]["token_sha256"], old)
        self.assertNotIn("token_next_sha256", self.d.devices["d1"])

    async def test_history_op_is_bounded(self):
        for i in range(150):
            self.d.history.append({
                "cmd_id": str(i), "device_id": "d1", "action": "status",
                "args": [], "status": "ok", "result": "x"})
        resp = await self.d.admin_dispatch(
            {"op": "history", "token": "test-admin-token",
             "device_id": "d1", "limit": 100})
        self.assertTrue(resp["ok"])
        self.assertEqual(len(resp["history"]), 100)
        for bad in (101, 0, -1, "100", True):
            resp = await self.d.admin_dispatch(
                {"op": "history", "token": "test-admin-token",
                 "limit": bad})
            self.assertFalse(resp["ok"], bad)

    async def test_request_and_response_bounds(self):
        req, err = self.d._admin_request("[]")
        self.assertIsNone(req)
        self.assertFalse(err["ok"])
        req, err = self.d._admin_request("{bad")
        self.assertIsNone(req)
        self.assertFalse(err["ok"])
        with patch.object(daemon, "MAX_ADMIN_LINE", 10):
            req, err = self.d._admin_request("x" * 11)
            self.assertIsNone(req)
            self.assertFalse(err["ok"])
        with patch.object(daemon, "MAX_ADMIN_RESPONSE", 10):
            out = self.d._admin_response({"ok": True, "data": "x" * 20})
            self.assertIn("response too large", out)

    async def test_non_loopback_admin_requires_env_token(self):
        self.assertTrue(daemon._check_admin_host("127.0.0.1"))
        self.assertTrue(daemon._check_admin_host("localhost"))
        old = os.environ.pop("GHOSTCTL_ADMIN_TOKEN", None)
        try:
            self.assertFalse(daemon._check_admin_host("0.0.0.0"))
        finally:
            if old is not None:
                os.environ["GHOSTCTL_ADMIN_TOKEN"] = old


class ConnectionTests(DaemonTestBase):
    async def test_revoke_closes_socket_drops_streams_and_wakes(self):
        ws = FakeWS()
        self.d.devices["d1"] = {"approved": True, "token": "tok"}
        self.d.conns["d1"] = ws
        self.d.streams.put("d1", "screen", b"\xff\xd8jpeg", "mjpeg")
        rec = {"event": asyncio.Event(), "result": None, "ok": False,
               "device_id": "d1", "ws": ws}
        self.d.pending["cmd1"] = rec
        resp = await self.d.admin_dispatch(
            {"op": "revoke", "token": "test-admin-token", "device_id": "d1"})
        self.assertTrue(resp["ok"])
        self.assertTrue(ws.closed)
        self.assertNotIn("d1", self.d.conns)
        self.assertEqual(self.d.streams.listing(), [])
        self.assertTrue(rec["event"].is_set())
        self.assertFalse(self.d.devices["d1"]["approved"])

    async def test_replacement_closes_old_socket(self):
        self.d.devices["d1"] = {"approved": True, "token": "tok"}
        ws1 = FakeWS()
        ws1.push({"type": "hello", "device_id": "d1", "token": "tok"})
        task1 = await self._start_handler(ws1)
        await asyncio.sleep(0.1)
        self.assertIs(self.d.conns["d1"], ws1)
        ws2 = FakeWS()
        ws2.push({"type": "hello", "device_id": "d1", "token": "tok"})
        task2 = await self._start_handler(ws2)
        await asyncio.sleep(0.1)
        self.assertIs(self.d.conns["d1"], ws2)
        self.assertTrue(ws1.closed)
        await ws2.close()
        await asyncio.gather(task1, task2, return_exceptions=True)

    async def test_per_ip_eviction(self):
        self.d.config["max_per_ip"] = 2
        tasks = []
        wss = []
        for i in range(3):
            ws = FakeWS()
            ws.push({"type": "hello", "device_id": f"d{i}",
                     "enroll_key": "test-enroll-key"})
            tasks.append(await self._start_handler(ws))
            wss.append(ws)
            await asyncio.sleep(0.1)
        self.assertTrue(wss[0].closed)
        self.assertFalse(wss[1].closed)
        self.assertFalse(wss[2].closed)
        for ws in wss[1:]:
            await ws.close()
        await asyncio.gather(*tasks, return_exceptions=True)

    async def test_pre_hello_timeout_and_preauth_cap(self):
        with patch.object(daemon, "PRE_HELLO_TIMEOUT", 0.1):
            ws = FakeWS()
            task = asyncio.create_task(self.d.device_handler(ws))
            await asyncio.wait_for(task, timeout=1)
            self.assertTrue(ws.closed)
        with patch.object(daemon, "PRE_HELLO_MAX_MESSAGES", 2):
            ws = FakeWS()
            ws.push({"type": "ping"})
            ws.push({"type": "ping"})
            ws.push({"type": "ping"})
            task = asyncio.create_task(self.d.device_handler(ws))
            await asyncio.wait_for(task, timeout=1)
            self.assertTrue(ws.closed)

    async def test_malformed_message_ignored(self):
        ws = FakeWS()
        ws.push(b"not json")
        ws.push({"type": "hello", "device_id": "d1",
                 "enroll_key": "test-enroll-key"})
        task = await self._start_handler(ws)
        await asyncio.sleep(0.1)
        self.assertIn("d1", self.d.conns)
        await ws.close()
        await asyncio.wait_for(task, timeout=1)


class RemoveTests(DaemonTestBase):
    """Admin `remove`: destroy online, purge on success, revoke on failure."""

    def _online(self, approved=True):
        self.d.devices["d1"] = {"approved": approved, "token": "secret-token",
                                "name": "Phone"}
        ws = FakeWS()
        if approved:
            self.d.conns["d1"] = ws
        return ws

    @staticmethod
    def _cmd_id(ws):
        for sent in ws.sent:
            if not isinstance(sent, str):
                continue
            try:
                msg = json.loads(sent)
            except json.JSONDecodeError:
                continue
            if isinstance(msg, dict) and msg.get("type") == "cmd":
                return msg
        raise AssertionError("no cmd frame sent")

    async def test_success_purges_record_and_all_server_state(self):
        ws = self._online()
        self.d.devices["d2"] = {"approved": True, "token": "keep-token"}
        self.d.history = [
            {"device_id": "d1", "action": "status", "status": "ok"},
            {"device_id": "d2", "action": "status", "status": "ok"},
        ]
        self.d.streams.put("d1", "screen", b"\xff\xd8jpeg", "mjpeg")
        self.d.streams.put("d2", "screen", b"\xff\xd8jpeg", "mjpeg")
        other_ws = FakeWS()
        self.d.downloads[("d1", "c1", ws)] = {"buffer": bytearray()}
        self.d.downloads[("d2", "c2", other_ws)] = {"buffer": bytearray()}
        pending = {"event": asyncio.Event(), "device_id": "d1", "ws": ws}
        self.d.pending["other"] = pending
        self.d._late_results = {
            "late1": (time.time() + 60, {"device_id": "d1"}, {}),
            "late2": (time.time() + 60, {"device_id": "d2"}, {}),
        }
        self.d.dispatch = AsyncMock(return_value={
            "status": "ok",
            "result": {"ok": True, "stage": "replaced", "replaced": True,
                       "removing": "com.tg.ghostd"}})

        resp = await self.d.admin_dispatch({
            "op": "remove", "token": "test-admin-token", "device_id": "d1",
            "actor": "operator", "source": "console", "wait": 1})

        self.assertTrue(resp["ok"])
        self.assertTrue(resp["removed"])
        self.assertTrue(resp["requested"])
        self.assertTrue(resp["verified"])
        self.assertTrue(resp["replaced"])
        self.assertIn("removal verified", resp["message"])
        self.assertNotIn("secret-token", json.dumps(resp))
        self.d.dispatch.assert_awaited_once()
        args, kwargs = self.d.dispatch.call_args
        self.assertEqual(args, ("d1", "destroy", []))
        self.assertEqual(kwargs["actor"], "operator")
        self.assertEqual(kwargs["source"], "console")
        self.assertEqual(kwargs["wait"], 1.0)
        self.assertNotIn("d1", self.d.devices)
        self.assertIn("d2", self.d.devices)
        self.assertEqual(pending["event"].is_set(), True)
        self.assertNotIn("other", self.d.pending)
        self.assertNotIn(("d1", "c1", ws), self.d.downloads)
        self.assertIn(("d2", "c2", other_ws), self.d.downloads)
        self.assertEqual([s["device_id"] for s in self.d.streams.listing()],
                         ["d2"])
        self.assertEqual([h["device_id"] for h in self.d.history], ["d2"])
        self.assertEqual(list(self.d._late_results), ["late2"])
        self.assertTrue(ws.closed)
        persisted_state = json.loads(daemon.STATE_FILE.read_text())
        persisted_history = json.loads(daemon.HISTORY_FILE.read_text())
        self.assertNotIn("d1", persisted_state)
        self.assertEqual([h["device_id"] for h in persisted_history], ["d2"])

    async def test_plain_ok_without_replaced_never_purges(self):
        ws = self._online()
        task = asyncio.create_task(self.d.admin_dispatch({
            "op": "remove", "token": "test-admin-token", "device_id": "d1",
            "source": "cli", "wait": 2}))
        await asyncio.sleep(0.05)
        cmd = self._cmd_id(ws)
        self.assertEqual(cmd["action"], "destroy")
        self.assertEqual(cmd["args"], [])
        self.d._handle_result("d1", ws, {
            "type": "result", "cmd_id": cmd["cmd_id"], "ok": True,
            "result": {"ok": True, "uninstall_requested": True,
                       "removing": "com.tg.ghostd"}})
        resp = await asyncio.wait_for(task, timeout=1)
        self.assertFalse(resp["ok"])
        self.assertFalse(resp["removed"])
        self.assertTrue(resp["requested"])
        self.assertFalse(resp["verified"])
        self.assertIn("replacement proof", resp["error"])
        self.assertIn("d1", self.d.devices)
        self.assertEqual(self.d.remove_pending, {})
        self.assertFalse(ws.closed)

    async def test_replaced_result_purges(self):
        ws = self._online()
        task = asyncio.create_task(self.d.admin_dispatch({
            "op": "remove", "token": "test-admin-token", "device_id": "d1",
            "source": "cli", "wait": 2}))
        await asyncio.sleep(0.05)
        cmd = self._cmd_id(ws)
        self.d._handle_result("d1", ws, {
            "type": "result", "cmd_id": cmd["cmd_id"], "ok": True,
            "result": {"ok": True, "stage": "replaced", "replaced": True,
                       "uninstall_requested": True,
                       "removing": "com.tg.ghostd"}})
        resp = await asyncio.wait_for(task, timeout=1)
        self.assertTrue(resp["ok"])
        self.assertTrue(resp["removed"])
        self.assertTrue(resp["requested"])
        self.assertTrue(resp["verified"])
        self.assertNotIn("d1", self.d.devices)
        self.assertTrue(ws.closed)

    async def test_expected_disconnect_waits_for_grace_then_purges(self):
        ws = self._online()
        with patch.object(daemon, "REMOVE_GRACE", 0.05):
            task = asyncio.create_task(self.d.admin_dispatch({
                "op": "remove", "token": "test-admin-token",
                "device_id": "d1", "wait": 5}))
            await asyncio.sleep(0.05)
            self._cmd_id(ws)
            self.d._disconnect_device("d1", ws)
            resp = await asyncio.wait_for(task, timeout=1)
            self.assertTrue(resp["ok"])
            self.assertTrue(resp["requested"])
            self.assertFalse(resp["verified"])
            self.assertFalse(resp["removed"])
            self.assertTrue(resp["finalizing"])
            self.assertTrue(resp["expected_disconnect"])
            # Not purged until the bounded grace window finishes.
            self.assertIn("d1", self.d.devices)
            grace = self.d.remove_pending["d1"]["grace_task"]
            await asyncio.wait_for(grace, timeout=1)
        self.assertNotIn("d1", self.d.devices)
        self.assertEqual(self.d.remove_pending, {})

    async def test_hello_during_grace_cancels_purge_and_records_failure(self):
        ws = self._online()
        with patch.object(daemon, "REMOVE_GRACE", 10.0):
            task = asyncio.create_task(self.d.admin_dispatch({
                "op": "remove", "token": "test-admin-token",
                "device_id": "d1", "wait": 5}))
            await asyncio.sleep(0.05)
            self._cmd_id(ws)
            self.d._disconnect_device("d1", ws)
            resp = await asyncio.wait_for(task, timeout=1)
            self.assertTrue(resp["finalizing"])
            grace = self.d.remove_pending["d1"]["grace_task"]
            # A fresh hello means the app survived the uninstall request.
            ws2 = FakeWS()
            ws2.push({"type": "hello", "device_id": "d1",
                      "token": "secret-token"})
            await self._start_handler(ws2)
            await asyncio.sleep(0.1)
            self.assertIn("d1", self.d.devices)
            self.assertEqual(self.d.remove_pending, {})
            self.assertTrue(grace.cancelled() or grace.done())
            await asyncio.gather(grace, return_exceptions=True)
        hist = [h for h in self.d.history if h.get("device_id") == "d1"]
        self.assertEqual(hist[-1]["action"], "destroy")
        self.assertEqual(hist[-1]["status"], "error")
        self.assertIn("reconnected", hist[-1]["result"]["error"])
        self.assertTrue(ws2.closed is False)

    async def test_ordinary_disconnect_never_schedules_or_purges(self):
        ws = self._online()
        task = asyncio.create_task(
            self.d.dispatch("d1", "status", [], wait=5))
        await asyncio.sleep(0.05)
        self.d._disconnect_device("d1", ws)
        entry = await asyncio.wait_for(task, timeout=1)
        self.assertEqual(entry["status"], "error")
        self.assertIn("disconnected", entry["result"]["error"])
        self.assertIn("d1", self.d.devices)
        self.assertEqual(self.d.remove_pending, {})

    async def test_remove_pending_is_bounded(self):
        ws = self._online()
        entry = self.d._begin_remove_pending("d1", ws)
        self.assertIn("d1", self.d.remove_pending)
        entry["created"] = time.time() - daemon.REMOVE_PENDING_TTL - 1
        self.d._prune_remove_pending()
        self.assertNotIn("d1", self.d.remove_pending)
        with patch.object(daemon, "MAX_REMOVE_PENDING", 2):
            for i in range(4):
                did = f"x{i}"
                self.d.devices[did] = {"approved": True, "token": "t"}
                self.d._begin_remove_pending(did, FakeWS())
            self.assertLessEqual(len(self.d.remove_pending), 2)

    async def test_failure_preserves_access_for_retry(self):
        ws = self._online()
        self.d.streams.put("d1", "screen", b"\xff\xd8jpeg", "mjpeg")
        pending = {"event": asyncio.Event(), "device_id": "d1", "ws": ws}
        self.d.pending["other"] = pending
        task = asyncio.create_task(self.d.admin_dispatch({
            "op": "remove", "token": "test-admin-token", "device_id": "d1",
            "actor": "operator", "source": "console", "wait": 2}))
        await asyncio.sleep(0.05)
        cmd = self._cmd_id(ws)
        self.d._handle_result("d1", ws, {
            "type": "result", "cmd_id": cmd["cmd_id"], "ok": False,
            "result": {"ok": False, "stage": "keyguard",
                       "error": "device is locked: unlock the phone"}})
        resp = await asyncio.wait_for(task, timeout=1)
        self.assertFalse(resp["ok"])
        self.assertFalse(resp["removed"])
        self.assertFalse(resp["revoked"])
        self.assertIn("device is locked", resp["error"])
        self.assertEqual(resp["result"]["stage"], "keyguard")
        self.assertIn("d1", self.d.devices)
        rec = self.d.devices["d1"]
        self.assertTrue(rec.get("approved"))
        self.assertIs(self.d.conns.get("d1"), ws)
        self.assertEqual(rec.get("token"), "secret-token")
        self.assertFalse(ws.closed)
        self.assertEqual(len(self.d.streams.listing()), 1)
        self.assertFalse(pending["event"].is_set())
        hist = [h for h in self.d.history if h.get("device_id") == "d1"]
        self.assertEqual(len(hist), 1)
        self.assertEqual(hist[0]["action"], "destroy")
        self.assertEqual(hist[0]["status"], "error")
        self.assertEqual(hist[0]["actor"], "operator")
        self.assertEqual(hist[0]["source"], "console")
        persisted_hist = json.loads(daemon.HISTORY_FILE.read_text())
        self.assertEqual(persisted_hist[0]["device_id"], "d1")

    async def test_offline_remove_is_rejected_without_claiming_uninstall(self):
        self.d.devices["d1"] = {"approved": True, "token": "secret-token"}
        self.d.dispatch = AsyncMock()
        resp = await self.d.admin_dispatch({
            "op": "remove", "token": "test-admin-token", "device_id": "d1"})
        self.assertFalse(resp["ok"])
        self.assertFalse(resp["removed"])
        self.assertFalse(resp["revoked"])
        self.assertIn("offline", resp["error"])
        self.assertIn("revoke", resp["error"])
        self.d.dispatch.assert_not_called()
        self.assertTrue(self.d.devices["d1"]["approved"])
        self.assertEqual(self.d.devices["d1"]["token"], "secret-token")

    async def test_unapproved_remove_is_rejected(self):
        self._online(approved=False)
        self.d.dispatch = AsyncMock()
        resp = await self.d.admin_dispatch({
            "op": "remove", "token": "test-admin-token", "device_id": "d1"})
        self.assertFalse(resp["ok"])
        self.assertIn("not approved", resp["error"])
        self.d.dispatch.assert_not_called()

    async def test_unknown_and_invalid_device_rejected(self):
        for did, needle in (("missing", "unknown device"),
                            ("bad\x00id", "invalid device_id")):
            resp = await self.d.admin_dispatch({
                "op": "remove", "token": "test-admin-token",
                "device_id": did})
            self.assertFalse(resp["ok"], did)
            self.assertFalse(resp["removed"], did)
            self.assertIn(needle, resp["error"], did)


class AdminDisconnectTests(DaemonTestBase):
    """A closed CLI must cancel in-flight admin work before it sends/purges."""

    def _online(self):
        ws = FakeWS()
        self.d.devices["d1"] = {"approved": True, "token": "tok"}
        self.d.conns["d1"] = ws
        return ws

    async def test_closed_cli_cancels_inflight_remove_without_purge(self):
        ws = self._online()
        req = json.dumps({"op": "remove", "token": "test-admin-token",
                          "device_id": "d1", "wait": 30}) + "\n"
        reader = FakeAdminReader([req], eof_delay=0.05)
        writer = FakeAdminWriter()
        await asyncio.wait_for(self.d.admin_handler(reader, writer), timeout=1)
        self.assertIn("d1", self.d.devices)
        self.assertEqual(self.d.pending, {})
        self.assertEqual(self.d.remove_pending, {})
        self.assertFalse(ws.closed)
        self.assertTrue(writer.closed)

    async def test_closed_cli_cancels_inflight_update_before_completion(self):
        self._online()
        started = asyncio.Event()
        completed = []

        async def slow_update(*args, **kwargs):
            started.set()
            await asyncio.Event().wait()
            completed.append(True)
            return {"status": "ok"}

        self.d.update_push = slow_update
        req = json.dumps({"op": "update", "token": "test-admin-token",
                          "device_id": "d1", "target": "self",
                          "wait": 30}) + "\n"
        reader = FakeAdminReader([req], eof_delay=0.05)
        writer = FakeAdminWriter()
        await asyncio.wait_for(self.d.admin_handler(reader, writer), timeout=1)
        self.assertTrue(started.is_set())
        self.assertEqual(completed, [])
        self.assertIn("d1", self.d.devices)
        self.assertTrue(writer.closed)

    async def test_closed_cli_drops_deferred_remove_marker(self):
        ws = self._online()

        async def fake_dispatch(req):
            self.d._begin_remove_pending(req["device_id"], ws)
            return {"ok": True, "requested": True, "verified": False,
                    "removed": False, "finalizing": True}

        self.d.admin_dispatch = fake_dispatch
        req = json.dumps({"op": "remove", "token": "test-admin-token",
                          "device_id": "d1"}) + "\n"
        writer = FakeAdminWriter()
        await asyncio.wait_for(
            self.d.admin_handler(FakeAdminReader([req]), writer), timeout=1)
        self.assertEqual(self.d.remove_pending, {})
        self.assertIn("d1", self.d.devices)
        self.assertTrue(writer.closed)

    async def test_buffered_next_request_still_runs(self):
        self._online()
        calls = []

        async def fake_dispatch(req):
            calls.append(req.get("op"))
            return {"ok": True, "op": req.get("op")}

        self.d.admin_dispatch = fake_dispatch
        req1 = json.dumps({"op": "first", "token": "test-admin-token"}) + "\n"
        req2 = json.dumps({"op": "second", "token": "test-admin-token"}) + "\n"
        reader = FakeAdminReader([req1, req2])
        writer = FakeAdminWriter()
        await asyncio.wait_for(self.d.admin_handler(reader, writer), timeout=1)
        self.assertEqual(calls, ["first", "second"])
        self.assertEqual(len(writer.writes), 2)


class PendingTests(DaemonTestBase):
    async def _online_device(self, token="tok"):
        ws = FakeWS()
        self.d.devices["d1"] = {"approved": True, "token": token}
        self.d.conns["d1"] = ws
        return ws

    async def test_timeout_cleans_pending_and_records_late(self):
        ws = await self._online_device()
        entry = await self.d.dispatch("d1", "status", [], wait=0.1)
        self.assertEqual(entry["status"], "timeout")
        self.assertEqual(self.d.pending, {})
        self.assertNotEqual(self.d.history[-1]["status"], "pending")
        self.assertIn(entry["cmd_id"], self.d._late_results)
        self.d._handle_result("d1", ws, {
            "type": "result", "cmd_id": entry["cmd_id"],
            "ok": True, "result": "done"})
        self.assertEqual(self.d.history[-1]["status"], "ok")
        self.assertEqual(self.d.history[-1]["result"], "done")
        self.assertNotIn(entry["cmd_id"], self.d._late_results)

    async def test_late_result_after_ttl_ignored(self):
        ws = await self._online_device()
        entry = await self.d.dispatch("d1", "status", [], wait=0.1)
        _, e, h = self.d._late_results[entry["cmd_id"]]
        self.d._late_results[entry["cmd_id"]] = (time.time() - 1, e, h)
        self.d._handle_result("d1", ws, {
            "type": "result", "cmd_id": entry["cmd_id"],
            "ok": True, "result": "late"})
        self.assertEqual(self.d.history[-1]["status"], "timeout")

    async def test_send_failure_cleans_pending(self):
        class FailWS(FakeWS):
            async def send(self, data):
                raise ConnectionError("send failed")

        ws = FailWS()
        self.d.devices["d1"] = {"approved": True, "token": "tok"}
        self.d.conns["d1"] = ws
        entry = await self.d.dispatch("d1", "status", [], wait=0.1)
        self.assertEqual(entry["status"], "error")
        self.assertIn("send failed", entry["result"]["error"])
        self.assertEqual(self.d.pending, {})
        self.assertNotEqual(self.d.history[-1]["status"], "pending")

    async def test_disconnect_wakes_dispatch_immediately(self):
        ws = await self._online_device()
        task = asyncio.create_task(
            self.d.dispatch("d1", "status", [], wait=10))
        await asyncio.sleep(0.05)
        self.d._disconnect_device("d1", ws)
        entry = await asyncio.wait_for(task, timeout=1)
        self.assertEqual(entry["status"], "error")
        self.assertIn("disconnected", entry["result"]["error"])
        self.assertEqual(self.d.pending, {})

    async def test_cancellation_cleans_pending(self):
        ws = await self._online_device()
        task = asyncio.create_task(
            self.d.dispatch("d1", "status", [], wait=10))
        await asyncio.sleep(0.05)
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertEqual(self.d.pending, {})
        self.assertEqual(self.d.history[-1]["status"], "cancelled")
        self.assertFalse(ws.closed)

    async def test_invalid_wait_rejected(self):
        await self._online_device()
        for bad in (0.0, 0.09, 301, float("nan"), float("inf")):
            with self.assertRaises(ValueError):
                await self.d.dispatch("d1", "status", [], wait=bad)

    async def test_per_device_lock_serializes(self):
        ws = await self._online_device()
        order = []
        orig = self.d._send_ws

        async def slow(ws, obj, timeout=5):
            order.append("start")
            await asyncio.sleep(0.05)
            order.append("end")
            return await orig(ws, obj, timeout)

        self.d._send_ws = slow
        await asyncio.gather(
            self.d.dispatch("d1", "status", [], wait=0.1),
            self.d.dispatch("d1", "status", [], wait=0.1))
        self.assertEqual(order, ["start", "end", "start", "end"])

    async def test_result_wait_does_not_hold_device_lock(self):
        ws = await self._online_device()
        first = asyncio.create_task(
            self.d.dispatch("d1", "status", [], wait=10))
        await asyncio.sleep(0.05)
        # If the lock covered the result wait, the second command could not
        # even be sent until the first timed out.
        second = asyncio.create_task(
            self.d.dispatch("d1", "status", [], wait=0.1))
        entry2 = await asyncio.wait_for(second, timeout=1)
        self.assertEqual(entry2["status"], "timeout")
        self.assertFalse(first.done())
        first_cmd = None
        for sent in ws.sent:
            if isinstance(sent, str):
                msg = json.loads(sent)
                if msg.get("type") == "cmd":
                    first_cmd = msg["cmd_id"]
                    break
        self.d._handle_result("d1", ws, {
            "type": "result", "cmd_id": first_cmd, "ok": True,
            "result": "done"})
        entry1 = await asyncio.wait_for(first, timeout=1)
        self.assertEqual(entry1["status"], "ok")


class SelfUpdateTests(DaemonTestBase):
    """U1: self-update completion survives the replaced ghostd process."""

    DEST = "/sdcard/Download/ghostd.apk"

    async def _online_device(self, version_code=10, app_version="0.3.8"):
        rec = {"approved": True, "token": "tok", "app_version": app_version}
        if version_code is not None:
            rec["version_code"] = version_code
        self.d.devices["d1"] = rec
        ws = FakeWS()
        self.d.conns["d1"] = ws
        return ws

    async def _start_update(self, ws, wait=1.0):
        task = asyncio.create_task(
            self.d.dispatch("d1", "update", ["self", self.DEST], wait=wait))

        async def cleanup():
            if not task.done():
                task.cancel()
            await asyncio.gather(task, return_exceptions=True)

        self.addAsyncCleanup(cleanup)
        await asyncio.sleep(0.05)
        for sent in ws.sent:
            if not isinstance(sent, str):
                continue
            msg = json.loads(sent)
            if msg.get("type") == "cmd":
                return task, msg["cmd_id"]
        raise AssertionError("self-update command not sent")

    async def _reconnect(self, version_code, include=True):
        ws = FakeWS()
        hello = {"type": "hello", "device_id": "d1", "token": "tok"}
        if include:
            hello["version_code"] = version_code
        ws.push(hello)
        await self._start_handler(ws)
        await asyncio.sleep(0.05)
        self.assertIs(self.d.conns["d1"], ws)
        return ws

    @staticmethod
    def _result(cmd_id, ok=True):
        return {"type": "result", "cmd_id": cmd_id, "ok": ok,
                "result": {"ok": ok}}

    async def test_history_records_identity_and_previous_version_code(self):
        ws = await self._online_device(10)
        task, cmd_id = await self._start_update(ws, wait=5.0)
        self.assertFalse(task.done())
        hist = self.d.history[-1]
        self.assertEqual(hist["cmd_id"], cmd_id)
        update = hist["update"]
        self.assertEqual(update["target"], "self")
        self.assertEqual(update["dest"], self.DEST)
        self.assertEqual(update["previous_version_code"], 10)
        self.assertEqual(update["previous_version"], "0.3.8")
        self.assertEqual(update["state"], "installing")
        self.assertEqual(self.d.devices["d1"]["version_code"], 10)
        persisted = json.loads(daemon.HISTORY_FILE.read_text())
        self.assertEqual(persisted[-1]["update"]["previous_version_code"], 10)
        self.assertEqual(persisted[-1]["update"]["dest"], self.DEST)

    async def test_direct_result_before_replacement_still_succeeds(self):
        ws = await self._online_device(10)
        task, cmd_id = await self._start_update(ws, wait=1.0)
        self.d._handle_result("d1", ws, {
            "type": "result", "cmd_id": cmd_id, "ok": True,
            "result": {"ok": True, "old_version": "0.3.8",
                       "new_version": "0.3.9"}})
        entry = await asyncio.wait_for(task, timeout=1.0)
        self.assertEqual(entry["status"], "ok")
        self.assertEqual(entry["result"]["new_version"], "0.3.9")
        self.assertEqual(self.d.pending, {})
        self.assertEqual(self.d.history[-1]["update"]["state"], "installed")

    async def test_disconnect_reconnect_higher_code_succeeds(self):
        ws1 = await self._online_device(10)
        task, cmd_id = await self._start_update(ws1, wait=2.0)
        self.d._disconnect_device("d1", ws1)
        await asyncio.sleep(0.02)
        self.assertFalse(task.done())
        rec = self.d.pending[cmd_id]
        self.assertEqual(rec["update_state"], "reconnecting")
        self.assertEqual(self.d.history[-1]["status"], "reconnecting")
        # A late result from the replaced process's old socket must not win.
        self.d._handle_result("d1", ws1, self._result(cmd_id))
        self.assertFalse(task.done())
        ws2 = await self._reconnect(11)
        entry = await asyncio.wait_for(task, timeout=1.0)
        self.assertEqual(entry["status"], "ok")
        self.assertTrue(entry["result"]["reconciled"])
        self.assertEqual(entry["result"]["previous_version_code"], 10)
        self.assertEqual(entry["result"]["version_code"], 11)
        self.assertEqual(self.d.pending, {})
        self.assertIs(self.d.conns["d1"], ws2)
        hist = self.d.history[-1]
        self.assertEqual(hist["status"], "ok")
        self.assertEqual(hist["update"]["state"], "installed")
        self.assertEqual(hist["update"]["last_version_code"], 11)
        # Even after completion an old-socket result cannot overwrite it.
        self.d._handle_result("d1", ws1, {
            "type": "result", "cmd_id": cmd_id, "ok": False,
            "result": {"error": "stale"}})
        self.assertEqual(self.d.history[-1]["result"]["version_code"], 11)
        persisted = json.loads(daemon.HISTORY_FILE.read_text())
        self.assertEqual(persisted[-1]["status"], "ok")
        self.assertEqual(persisted[-1]["update"]["previous_version_code"], 10)

    async def test_same_code_keeps_waiting_then_higher_succeeds(self):
        ws1 = await self._online_device(10)
        task, cmd_id = await self._start_update(ws1, wait=2.0)
        self.d._disconnect_device("d1", ws1)
        ws2 = await self._reconnect(10)
        self.assertFalse(task.done())
        rec = self.d.pending[cmd_id]
        self.assertIs(rec["ws"], ws2)
        self.assertEqual(rec["last_version_code"], 10)
        # Same code is not success, and the new socket cannot shortcut the
        # version check with an optimistic result.
        self.d._handle_result("d1", ws2, self._result(cmd_id))
        self.assertFalse(task.done())
        self.d._handle_result("d1", ws1, self._result(cmd_id))
        self.assertFalse(task.done())
        await self._reconnect(11)
        entry = await asyncio.wait_for(task, timeout=1.0)
        self.assertEqual(entry["status"], "ok")
        self.assertTrue(entry["result"]["reconciled"])
        self.assertEqual(entry["result"]["version_code"], 11)
        self.assertEqual(self.d.pending, {})

    async def test_other_device_reconnect_does_not_complete_update(self):
        ws1 = await self._online_device(10)
        task, cmd_id = await self._start_update(ws1, wait=2.0)
        self.d._disconnect_device("d1", ws1)
        self.d.devices["d2"] = {"approved": True, "token": "tok2"}
        ws2 = FakeWS()
        ws2.push({"type": "hello", "device_id": "d2", "token": "tok2",
                  "version_code": 99})
        await self._start_handler(ws2)
        await asyncio.sleep(0.05)
        self.assertFalse(task.done())
        self.assertIn(cmd_id, self.d.pending)
        await self._reconnect(11)
        entry = await asyncio.wait_for(task, timeout=1.0)
        self.assertEqual(entry["status"], "ok")
        self.assertEqual(entry["result"]["version_code"], 11)

    async def test_lower_code_waits_then_fails_actionably(self):
        ws1 = await self._online_device(10)
        task, cmd_id = await self._start_update(ws1, wait=0.25)
        self.d._disconnect_device("d1", ws1)
        ws2 = await self._reconnect(9)
        self.assertFalse(task.done())
        self.d._handle_result("d1", ws2, self._result(cmd_id))
        self.assertFalse(task.done())
        entry = await asyncio.wait_for(task, timeout=1.0)
        self.assertEqual(entry["status"], "error")
        error = entry["result"]["error"]
        self.assertIn("not greater than the previous 10", error)
        self.assertIn("retry", error)
        self.assertEqual(self.d.pending, {})
        hist = self.d.history[-1]
        self.assertEqual(hist["status"], "error")
        self.assertEqual(hist["update"]["state"], "failed")
        self.assertEqual(hist["update"]["last_version_code"], 9)

    async def test_missing_code_waits_then_fails_actionably(self):
        ws1 = await self._online_device(10)
        task, cmd_id = await self._start_update(ws1, wait=0.25)
        self.d._disconnect_device("d1", ws1)
        await self._reconnect(None, include=False)
        self.assertFalse(task.done())
        entry = await asyncio.wait_for(task, timeout=1.0)
        self.assertEqual(entry["status"], "error")
        self.assertIn("without a numeric version_code", entry["result"]["error"])
        self.assertEqual(self.d.pending, {})
        self.assertEqual(self.d.history[-1]["update"]["state"], "failed")
        # An omitted field must not erase the last known numeric code.
        self.assertEqual(self.d.devices["d1"]["version_code"], 10)

    async def test_unknown_previous_code_is_not_success(self):
        ws1 = await self._online_device(None)
        task, cmd_id = await self._start_update(ws1, wait=0.25)
        self.d._disconnect_device("d1", ws1)
        await self._reconnect(11)
        self.assertFalse(task.done())
        entry = await asyncio.wait_for(task, timeout=1.0)
        self.assertEqual(entry["status"], "error")
        self.assertIn("previous version_code is unknown",
                      entry["result"]["error"])
        self.assertEqual(self.d.pending, {})

    async def test_normal_command_and_sidekick_update_fail_on_disconnect(self):
        for action, args in (("status", []),
                             ("sidekick", ["install",
                                           "/sdcard/Download/ghosta.apk"])):
            ws = await self._online_device(10)
            task = asyncio.create_task(
                self.d.dispatch("d1", action, args, wait=5.0))

            async def cleanup():
                if not task.done():
                    task.cancel()
                await asyncio.gather(task, return_exceptions=True)

            self.addAsyncCleanup(cleanup)
            await asyncio.sleep(0.05)
            self.d._disconnect_device("d1", ws)
            entry = await asyncio.wait_for(task, timeout=1.0)
            self.assertEqual(entry["status"], "error", action)
            self.assertIn("disconnected", entry["result"]["error"], action)
            self.assertEqual(self.d.pending, {}, action)

    async def test_sent_barrier_set_before_send_and_cleared_on_failure(self):
        ws = await self._online_device(10)
        captured = {}

        async def fail_send(target, obj, timeout=5):
            if obj.get("type") == "cmd":
                rec = next(iter(self.d.pending.values()))
                captured["rec"] = rec
                captured["sent_at_send"] = rec.get("sent")
                raise ConnectionError("send boom")
            return await target.send(json.dumps(obj))

        self.d._send_ws = fail_send
        entry = await self.d.dispatch("d1", "update", ["self", self.DEST],
                                      wait=1.0)
        self.assertTrue(captured["sent_at_send"])
        self.assertFalse(captured["rec"]["sent"])
        self.assertEqual(entry["status"], "error")
        self.assertIn("send boom", entry["result"]["error"])
        self.assertEqual(self.d.pending, {})

    async def test_restart_history_reconciled_on_higher_hello(self):
        entry = {
            "cmd_id": "old-update", "device_id": "d1", "action": "update",
            "args": ["self", self.DEST], "status": "interrupted",
            "update": {"cmd_id": "old-update", "target": "self",
                       "dest": self.DEST, "previous_version_code": 10,
                       "previous_version": "0.3.8",
                       "state": "interrupted"}}
        daemon.save_json(daemon.HISTORY_FILE, [entry])
        d2 = daemon.Daemon()
        d2.devices["d1"] = {"approved": True, "token": "tok"}
        ws = FakeWS()
        ws.push({"type": "hello", "device_id": "d1", "token": "tok",
                 "version_code": 11})
        task = asyncio.create_task(d2.device_handler(ws))

        async def cleanup():
            await ws.close()
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)

        self.addAsyncCleanup(cleanup)
        await asyncio.sleep(0.1)
        self.assertIs(d2.conns["d1"], ws)
        hist = d2.history[-1]
        self.assertEqual(hist["status"], "ok")
        self.assertEqual(hist["update"]["state"], "installed")
        self.assertEqual(hist["update"]["last_version_code"], 11)
        self.assertTrue(hist["result"]["reconciled"])
        persisted = json.loads(daemon.HISTORY_FILE.read_text())
        self.assertEqual(persisted[-1]["status"], "ok")
        self.assertEqual(persisted[-1]["update"]["last_version_code"], 11)

    async def test_newest_update_reconciled_late_not_older(self):
        older = {
            "cmd_id": "old-update", "device_id": "d1", "action": "update",
            "args": ["self", self.DEST], "status": "interrupted",
            "update": {"target": "self", "previous_version_code": 10,
                       "state": "interrupted"}}
        # The newest attempt previously timed out but completed late.
        newer = {
            "cmd_id": "newer-update", "device_id": "d1", "action": "update",
            "args": ["self", self.DEST], "status": "error",
            "update": {"target": "self", "previous_version_code": 12,
                       "state": "failed"}}
        self.d.history = [older, newer]
        self.d.devices["d1"] = {"approved": True, "token": "tok"}
        ws = FakeWS()
        ws.push({"type": "hello", "device_id": "d1", "token": "tok",
                 "version_code": 13})
        await self._start_handler(ws)
        await asyncio.sleep(0.1)
        self.assertEqual(self.d.history[0]["status"], "interrupted")
        self.assertEqual(self.d.history[1]["status"], "ok")
        self.assertEqual(self.d.history[1]["update"]["state"], "installed")
        self.assertEqual(self.d.history[1]["update"]["last_version_code"], 13)

    async def test_no_reconcile_when_newest_update_already_completed(self):
        older = {
            "cmd_id": "old-update", "device_id": "d1", "action": "update",
            "args": ["self", self.DEST], "status": "interrupted",
            "update": {"target": "self", "previous_version_code": 10,
                       "state": "interrupted"}}
        newer = {
            "cmd_id": "newer-update", "device_id": "d1", "action": "update",
            "args": ["self", self.DEST], "status": "ok",
            "update": {"target": "self", "previous_version_code": 12,
                       "state": "installed", "last_version_code": 13}}
        self.d.history = [older, newer]
        self.d.devices["d1"] = {"approved": True, "token": "tok"}
        ws = FakeWS()
        ws.push({"type": "hello", "device_id": "d1", "token": "tok",
                 "version_code": 14})
        await self._start_handler(ws)
        await asyncio.sleep(0.1)
        self.assertEqual(self.d.history[0]["status"], "interrupted")
        self.assertEqual(self.d.history[1]["status"], "ok")

    async def test_update_push_bounds_total_push_plus_install(self):
        self.d.devices["d1"] = {"approved": True, "token": "tok"}
        apk = daemon.APK_DIR / "ghostd.apk"
        apk.write_bytes(b"apk")
        self.d.push_file = AsyncMock(return_value={"status": "ok"})
        self.d.dispatch = AsyncMock(return_value={"status": "ok"})
        await self.d.update_push("d1", "self", wait=2.0)
        push_wait = self.d.push_file.call_args.kwargs["wait"]
        install_wait = self.d.dispatch.call_args.kwargs["wait"]
        self.assertLessEqual(push_wait, 2.0)
        self.assertLessEqual(install_wait, 2.0)
        self.assertGreater(install_wait, 0)

    async def test_update_push_self_keeps_dest_and_reconcile_wait(self):
        self.d.devices["d1"] = {"approved": True, "token": "tok"}
        apk = daemon.APK_DIR / "ghostd.apk"
        apk.write_bytes(b"apk")
        self.d.push_file = AsyncMock(return_value={"status": "ok"})
        self.d.dispatch = AsyncMock(return_value={"status": "ok"})
        entry = await self.d.update_push("d1", "self")
        args, kwargs = self.d.dispatch.call_args
        self.assertEqual(args, ("d1", "update", ["self", self.DEST]))
        self.assertEqual(kwargs["wait"], daemon.SELF_UPDATE_WAIT)
        self.assertEqual(kwargs["update_meta"]["target"], "self")
        self.assertEqual(kwargs["update_meta"]["dest"], self.DEST)
        self.assertEqual(kwargs["update_meta"]["apk"], str(apk))
        self.assertEqual(entry, {"status": "ok"})


class HistoryTests(DaemonTestBase):
    def test_cap_and_redaction(self):
        for i in range(1100):
            self.d.history.append({
                "cmd_id": str(i), "action": "status", "args": [],
                "result": "x"})
        self.d._trim_history()
        self.assertEqual(len(self.d.history), 1000)
        entry = {
            "action": "unlock", "args": ["1234"],
            "result": {"token": "secret", "frame_b64": "A" * 10000,
                       "b64": "A" * 10000, "pin": "1234",
                       "data": "A" * 10000},
        }
        sanitized = daemon._history_entry(entry)
        self.assertEqual(sanitized["args"], ["***"])
        self.assertEqual(sanitized["result"]["token"], "***")
        self.assertEqual(sanitized["result"]["frame_b64"], "***")
        self.assertEqual(sanitized["result"]["b64"], "***")
        self.assertEqual(sanitized["result"]["pin"], "***")
        self.assertEqual(sanitized["result"]["data"], "[base64 omitted]")
        self.assertNotIn("secret", json.dumps(sanitized))
        self.assertNotIn("1234", json.dumps(sanitized))

    def test_secure_save_and_corrupt_preservation(self):
        path = daemon.STATE_FILE
        self.assertTrue(daemon.save_json(path, {"a": 1}))
        self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        path.write_text("{bad")
        self.assertEqual(daemon.load_json(path, {}), {})
        self.assertTrue(path.with_suffix(".json.bad").exists())

    def test_save_is_best_effort(self):
        with patch.object(daemon, "_secure_write", side_effect=OSError("full")):
            self.assertFalse(daemon.save_json(daemon.STATE_FILE, {"a": 1}))

    def test_empty_keys_regenerate(self):
        for env_name, loader, path in (
                ("GHOST_ENROLL_KEY", daemon.load_enroll_key,
                 daemon.ENROLL_KEY_FILE),
                ("GHOSTCTL_ADMIN_TOKEN", daemon.load_admin_key,
                 daemon.ADMIN_KEY_FILE)):
            old = os.environ.pop(env_name, None)
            try:
                path.write_text("")
                key = loader()
                self.assertTrue(key, env_name)
                self.assertEqual(path.read_text().strip(), key)
            finally:
                if old is not None:
                    os.environ[env_name] = old

    def test_whitespace_env_secrets_fall_back_to_file(self):
        for env_name, loader, path in (
                ("GHOST_ENROLL_KEY", daemon.load_enroll_key,
                 daemon.ENROLL_KEY_FILE),
                ("GHOSTCTL_ADMIN_TOKEN", daemon.load_admin_key,
                 daemon.ADMIN_KEY_FILE)):
            old = os.environ.get(env_name)
            try:
                path.write_text("file-" + env_name)
                os.environ[env_name] = "   \t "
                self.assertEqual(loader(), "file-" + env_name)
            finally:
                if old is None:
                    os.environ.pop(env_name, None)
                else:
                    os.environ[env_name] = old
        self.assertFalse(daemon._valid_secret("   "))
        self.assertFalse(daemon._valid_secret(""))
        self.assertTrue(daemon._valid_secret(" real "))

    def test_private_directories_are_mode_0700(self):
        for path in (daemon.APK_DIR, daemon.UPLOAD_DIR):
            self.assertEqual(path.stat().st_mode & 0o777, 0o700, path)

    def test_load_dotenv_uses_shared_parser(self):
        path = daemon.BASE / ".env.test"
        path.write_text('GHOST_ENVFILE_TEST="quoted value"\n')
        path.write_text(path.read_text() + "GHOST_ENVFILE_OTHER=plain # note\n")
        old = os.environ.pop("GHOST_ENVFILE_TEST", None)
        old_other = os.environ.pop("GHOST_ENVFILE_OTHER", None)
        try:
            daemon.load_dotenv(path)
            self.assertEqual(os.environ["GHOST_ENVFILE_TEST"],
                             "quoted value")
            self.assertEqual(os.environ["GHOST_ENVFILE_OTHER"], "plain")
            os.environ["GHOST_ENVFILE_TEST"] = "process wins"
            daemon.load_dotenv(path)
            self.assertEqual(os.environ["GHOST_ENVFILE_TEST"],
                             "process wins")
        finally:
            if old is None:
                os.environ.pop("GHOST_ENVFILE_TEST", None)
            else:
                os.environ["GHOST_ENVFILE_TEST"] = old
            if old_other is None:
                os.environ.pop("GHOST_ENVFILE_OTHER", None)
            else:
                os.environ["GHOST_ENVFILE_OTHER"] = old_other

    def test_loaded_pending_and_reconnecting_become_interrupted(self):
        entries = [
            {"cmd_id": "p", "device_id": "d1", "action": "status",
             "args": [], "status": "pending"},
            {"cmd_id": "r", "device_id": "d1", "action": "update",
             "args": ["self", "/x"], "status": "reconnecting",
             "update": {"target": "self", "previous_version_code": 5,
                        "state": "reconnecting"}},
        ]
        daemon.save_json(daemon.HISTORY_FILE, entries)
        d2 = daemon.Daemon()
        self.assertEqual([h["status"] for h in d2.history],
                         ["interrupted", "interrupted"])
        self.assertEqual(d2.history[1]["update"]["state"], "interrupted")


class StreamTests(DaemonTestBase):
    def test_newest_jpeg_wins_over_newer_pcm(self):
        cache = daemon.StreamCache()
        cache.put("d1", "screen", b"\xff\xd8jpeg", "mjpeg")
        cache.put("d1", "mic", b"\x00\x01pcm", "pcm")
        latest = cache.latest("d1")
        self.assertIsNotNone(latest)
        self.assertEqual(latest[0], "screen")
        self.assertEqual(latest[4], base64.b64encode(b"\xff\xd8jpeg").decode())

    def test_ttl_and_stale_listing(self):
        cache = daemon.StreamCache()
        cache.put("d1", "screen", b"\xff\xd8jpeg", "mjpeg")
        cache.streams[("d1", "screen")]["frames"].clear()
        cache.streams[("d1", "screen")]["frames"].append(
            (time.time() - 20, b"\xff\xd8old", None))
        self.assertIsNone(cache.latest("d1"))
        self.assertEqual(cache.listing(), [])

    def test_per_device_and_global_caps(self):
        cache = daemon.StreamCache()
        for i in range(5):
            cache.put("d1", f"s{i}", b"\xff\xd8", "mjpeg")
        self.assertLessEqual(
            len([k for k in cache.streams if k[0] == "d1"]),
            daemon.StreamCache.MAX_STREAMS_PER_DEVICE)

    def test_frame_size_cap(self):
        cache = daemon.StreamCache()
        big = b"\xff\xd8" + b"x" * daemon.StreamCache.MAX_FRAME_BYTES
        cache.put("d1", "big", big, "mjpeg")
        self.assertNotIn(("d1", "big"), cache.streams)

    async def test_stream_frame_requires_approved_online(self):
        self.d.devices["d1"] = {"approved": False, "token": "tok"}
        self.d.streams.put("d1", "screen", b"\xff\xd8jpeg", "mjpeg")
        resp = await self.d.admin_dispatch(
            {"op": "stream_frame", "token": "test-admin-token",
             "device_id": "d1"})
        self.assertFalse(resp["ok"])
        self.d.devices["d1"]["approved"] = True
        resp = await self.d.admin_dispatch(
            {"op": "stream_frame", "token": "test-admin-token",
             "device_id": "d1"})
        self.assertFalse(resp["ok"])  # offline
        ws = FakeWS()
        self.d.conns["d1"] = ws
        resp = await self.d.admin_dispatch(
            {"op": "stream_frame", "token": "test-admin-token",
             "device_id": "d1"})
        self.assertTrue(resp["ok"])
        self.assertEqual(resp["frame_b64"],
                         base64.b64encode(b"\xff\xd8jpeg").decode())
        self.assertEqual(set(resp),
                         {"ok", "stream", "encoding", "ts", "frame_b64"})


class BroadcastTests(DaemonTestBase):
    async def test_result_and_offline_skipped(self):
        self.d.devices["d1"] = {"approved": True, "token": "t1"}
        self.d.devices["d2"] = {"approved": True, "token": "t2"}
        self.d.devices["d3"] = {"approved": True, "token": "t3"}
        self.d.conns["d1"] = FakeWS()
        self.d.conns["d2"] = FakeWS()
        b = await self.d.broadcast("status", [], wait=0.1)
        self.assertFalse(b["ok"])
        self.assertEqual(b["results"]["d1"]["status"], "timeout")
        self.assertEqual(b["results"]["d2"]["status"], "timeout")
        self.assertEqual(b["results"]["d3"]["status"], "skipped")
        self.assertEqual(b["results"]["d3"]["result"]["error"], "offline")

    async def test_histories_persist_once_and_semaphore(self):
        self.d.devices["d1"] = {"approved": True, "token": "t1"}
        self.d.devices["d2"] = {"approved": True, "token": "t2"}
        self.d.conns["d1"] = FakeWS()
        self.d.conns["d2"] = FakeWS()
        with patch.object(self.d, "persist_history") as mock:
            await self.d.broadcast("status", [], wait=0.1)
            mock.assert_called_once()
        self.assertEqual(self.d.broadcast_sem._value,
                         daemon.BROADCAST_CONCURRENCY)

    async def test_all_update_rejected(self):
        resp = await self.d.admin_dispatch(
            {"op": "command", "token": "test-admin-token",
             "action": "update", "args": [], "all": True})
        self.assertFalse(resp["ok"])
        self.assertIn("--all update", resp["error"])


class PathAndContractTests(DaemonTestBase):
    async def test_update_and_files_put_path_restrictions(self):
        self.d.devices["d1"] = {"approved": True, "token": "tok"}
        resp = await self.d.admin_dispatch(
            {"op": "update", "token": "test-admin-token", "device_id": "d1",
             "target": "self", "apk": "/etc/passwd"})
        self.assertFalse(resp["ok"])
        self.assertIn("server/apks", resp["error"])
        resp = await self.d.admin_dispatch(
            {"op": "files_put", "token": "test-admin-token",
             "device_id": "d1", "dest": "/sdcard/x", "src": "/etc/passwd"})
        self.assertFalse(resp["ok"])
        self.assertIn("server/uploads", resp["error"])

    async def test_files_put_contract(self):
        self.d.devices["d1"] = {"approved": True, "token": "tok"}
        src = daemon.UPLOAD_DIR / "test.txt"
        src.write_text("hello")
        self.d.push_file = AsyncMock(return_value={"status": "ok"})
        resp = await self.d.admin_dispatch(
            {"op": "files_put", "token": "test-admin-token",
             "device_id": "d1", "dest": "/sdcard/test.txt",
             "src": "test.txt", "actor": "alice", "source": "cli"})
        self.assertTrue(resp["ok"])
        args, kwargs = self.d.push_file.call_args
        self.assertEqual(args[0], "d1")
        self.assertEqual(args[1], src.resolve())
        self.assertEqual(args[2], "/sdcard/test.txt")
        self.assertEqual(kwargs["actor"], "alice")
        self.assertEqual(kwargs["source"], "cli")

    async def test_update_contract_inside_apks(self):
        self.d.devices["d1"] = {"approved": True, "token": "tok"}
        apk = daemon.APK_DIR / "ghostd.apk"
        apk.write_bytes(b"apk")
        self.d.update_push = AsyncMock(return_value={"status": "ok"})
        resp = await self.d.admin_dispatch(
            {"op": "update", "token": "test-admin-token", "device_id": "d1",
             "target": "self", "apk": "ghostd.apk",
             "actor": "bob", "source": "console"})
        self.assertTrue(resp["ok"])
        args, kwargs = self.d.update_push.call_args
        self.assertEqual(args[0], "d1")
        self.assertEqual(args[1], "self")
        self.assertEqual(args[2], str(apk.resolve()))
        self.assertEqual(kwargs["actor"], "bob")
        self.assertEqual(kwargs["source"], "console")

    async def test_config_failure_reports_applied_not_persisted(self):
        with patch.object(daemon, "save_json", return_value=False):
            resp = await self.d.admin_dispatch(
                {"op": "config", "token": "test-admin-token",
                 "args": ["set", "max_pending", "10"]})
        self.assertTrue(resp["ok"])
        self.assertTrue(resp["applied"])
        self.assertFalse(resp["persisted"])
        self.assertIn("could not be persisted", resp["warning"])
        self.assertEqual(self.d.config["max_pending"], 10)

    async def test_per_op_wait_forwarding(self):
        self.d.devices["d1"] = {"approved": True, "token": "tok"}
        src = daemon.UPLOAD_DIR / "wait.txt"
        src.write_bytes(b"x")
        self.d.push_file = AsyncMock(return_value={"status": "ok"})
        resp = await self.d.admin_dispatch(
            {"op": "files_put", "token": "test-admin-token",
             "device_id": "d1", "dest": "/sdcard/x", "src": "wait.txt",
             "wait": 12.5})
        self.assertTrue(resp["ok"])
        self.assertEqual(self.d.push_file.call_args.kwargs["wait"], 12.5)
        resp = await self.d.admin_dispatch(
            {"op": "files_put", "token": "test-admin-token",
             "device_id": "d1", "dest": "/sdcard/x", "src": "wait.txt",
             "wait": 0})
        self.assertFalse(resp["ok"])

        self.d.dispatch = AsyncMock(return_value={
            "status": "ok", "result": {"b64": "", "path": "/sdcard/x"}})
        resp = await self.d.admin_dispatch(
            {"op": "files_get", "token": "test-admin-token",
             "device_id": "d1", "path": "/sdcard/x", "wait": 7})
        self.assertTrue(resp["ok"])
        self.assertEqual(self.d.dispatch.call_args.kwargs["wait"], 7)
        self.d.dispatch.reset_mock()
        resp = await self.d.admin_dispatch(
            {"op": "files_get", "token": "test-admin-token",
             "device_id": "d1", "path": "/sdcard/x", "wait": 301})
        self.assertFalse(resp["ok"])
        self.d.dispatch.assert_not_called()

        apk = daemon.APK_DIR / "ghostd.apk"
        apk.write_bytes(b"apk")
        self.d.update_push = AsyncMock(return_value={"status": "ok"})
        resp = await self.d.admin_dispatch(
            {"op": "update", "token": "test-admin-token",
             "device_id": "d1", "target": "self", "wait": 12.5})
        self.assertTrue(resp["ok"])
        self.assertEqual(self.d.update_push.call_args.kwargs["wait"], 12.5)
        self.d.update_push.reset_mock()
        resp = await self.d.admin_dispatch(
            {"op": "update", "token": "test-admin-token",
             "device_id": "d1", "target": "self", "wait": 0})
        self.assertFalse(resp["ok"])
        self.d.update_push.assert_not_called()

    async def test_bare_files_put_and_broadcast_destructive_rejected(self):
        resp = await self.d.admin_dispatch(
            {"op": "command", "token": "test-admin-token",
             "device_id": "d1", "action": "files",
             "args": ["put", "/sdcard/x"]})
        self.assertFalse(resp["ok"])
        self.assertIn("files_put", resp["error"])
        for action in ("destroy", "update"):
            resp = await self.d.admin_dispatch(
                {"op": "command", "token": "test-admin-token",
                 "all": True, "action": action, "args": []})
            self.assertFalse(resp["ok"], action)
            self.assertIn(f"--all {action}", resp["error"])

    async def test_push_reads_source_before_push_lock(self):
        ws = FakeWS()
        self.d.devices["d1"] = {"approved": True, "token": "tok"}
        self.d.conns["d1"] = ws
        src = daemon.UPLOAD_DIR / "race.txt"
        src.write_bytes(b"payload")
        await self.d.push_lock.acquire()
        task = asyncio.create_task(
            self.d.push_file("d1", src, "/sdcard/race.txt", wait=0.1))
        await asyncio.sleep(0.05)
        src.unlink()
        self.d.push_lock.release()
        entry = await asyncio.wait_for(task, timeout=1)
        self.assertEqual(entry["status"], "timeout")
        self.assertTrue(any(isinstance(s, bytes) for s in ws.sent))

    async def test_push_size_cap(self):
        src = daemon.UPLOAD_DIR / "big.bin"
        src.write_bytes(b"12345")
        with patch.object(daemon, "MAX_PUSH_BYTES", 4):
            with self.assertRaises(ValueError):
                await self.d.push_file("d1", src, "/sdcard/big.bin")

    async def test_config_bounds(self):
        for bad in ("0", "10001"):
            resp = await self.d.admin_dispatch(
                {"op": "config", "token": "test-admin-token",
                 "args": ["set", "max_pending", bad]})
            self.assertFalse(resp["ok"])
        resp = await self.d.admin_dispatch(
            {"op": "config", "token": "test-admin-token",
             "args": ["set", "max_pending", "10"]})
        self.assertTrue(resp["ok"])
        self.assertEqual(self.d.config["max_pending"], 10)

    async def test_run_closes_admin_on_device_bind_failure(self):
        class FakeServer:
            def __init__(self):
                self.closed = False

            def close(self):
                self.closed = True

            async def wait_closed(self):
                pass

            async def __aenter__(self):
                return self

            async def __aexit__(self, *args):
                pass

            async def serve_forever(self):
                await asyncio.sleep(10)

        admin = FakeServer()
        with patch.object(daemon.asyncio, "start_server",
                          new=AsyncMock(return_value=admin)), \
                patch.object(daemon.websockets, "serve",
                             side_effect=OSError("bind failed")):
            with self.assertRaises(OSError):
                await self.d.run("0.0.0.0", 8443, "127.0.0.1", 8444,
                                 allow_plaintext=True)
        self.assertTrue(admin.closed)

    async def test_run_refuses_plaintext_nonloopback_bind(self):
        with patch.object(daemon.asyncio, "start_server",
                          new=AsyncMock()) as start:
            with self.assertRaises(ValueError):
                await self.d.run("0.0.0.0", 8443, "127.0.0.1", 8444)
        start.assert_not_called()

    async def test_run_allows_plaintext_loopback_bind(self):
        class FakeServer:
            def close(self):
                pass

            async def wait_closed(self):
                pass

            async def __aenter__(self):
                return self

            async def __aexit__(self, *args):
                pass

            async def serve_forever(self):
                await asyncio.sleep(10)

        admin = FakeServer()
        with patch.object(daemon.asyncio, "start_server",
                          new=AsyncMock(return_value=admin)), \
                patch.object(daemon.websockets, "serve",
                             side_effect=OSError("bind failed")):
            with self.assertRaises(OSError):
                await self.d.run("127.0.0.1", 8443, "127.0.0.1", 8444)


class TlsContextTests(unittest.TestCase):
    def test_loopback_bind_detection(self):
        for host in ("127.0.0.1", "::1", "localhost"):
            self.assertTrue(daemon._is_loopback_bind(host), host)
        for host in ("0.0.0.0", "::", "", "example.com", "10.0.0.1"):
            self.assertFalse(daemon._is_loopback_bind(host), host)

    def test_loads_tls_context_and_rejects_unsafe_key(self):
        import subprocess
        with tempfile.TemporaryDirectory() as tmp:
            cert = Path(tmp) / "server.crt"
            key = Path(tmp) / "server.key"
            subprocess.run([
                "openssl", "req", "-x509", "-newkey", "rsa:2048",
                "-sha256", "-nodes", "-days", "1",
                "-keyout", str(key), "-out", str(cert),
                "-subj", "/CN=localhost",
            ], check=True, capture_output=True)
            os.chmod(key, 0o600)
            context = daemon._load_tls_context(str(cert), str(key))
            self.assertGreaterEqual(
                context.minimum_version, daemon.ssl.TLSVersion.TLSv1_2)
            os.chmod(key, 0o644)
            with self.assertRaises(ValueError):
                daemon._load_tls_context(str(cert), str(key))


class DownloadTests(DaemonTestBase):
    """Device -> server `files get` receive sink and result delivery."""

    async def _online_device(self):
        self.d.devices["d1"] = {"approved": True, "token": "tok"}
        ws = FakeWS()
        ws.push({"type": "hello", "device_id": "d1", "token": "tok"})
        await self._start_handler(ws)
        await asyncio.sleep(0.05)
        self.assertIs(self.d.conns["d1"], ws)
        return ws

    @staticmethod
    def _cmd_id(ws):
        for sent in ws.sent:
            if not isinstance(sent, str):
                continue
            try:
                msg = json.loads(sent)
            except json.JSONDecodeError:
                continue
            if isinstance(msg, dict) and msg.get("type") == "cmd":
                return msg["cmd_id"]
        raise AssertionError("no cmd frame sent")

    @staticmethod
    def _frames(cmd_id, data, path, dest=None, chunk=64 * 1024, sha=None):
        dest = dest or path
        chunks = (len(data) + chunk - 1) // chunk if data else 0
        yield {"type": "file_start", "cmd_id": cmd_id, "path": path,
               "dest": dest, "total": len(data), "chunks": chunks,
               "chunk": chunk}
        for seq, off in enumerate(range(0, len(data), chunk)):
            payload = data[off:off + chunk]
            yield (json.dumps({"type": "file_chunk", "cmd_id": cmd_id,
                               "seq": seq, "path": path,
                               "len": len(payload),
                               "total": len(data)}).encode()
                   + b"\n" + payload)
        yield {"type": "file_end", "cmd_id": cmd_id, "path": path,
               "dest": dest, "bytes": len(data), "chunks": chunks,
               "sha256": sha or hashlib.sha256(data).hexdigest()}

    async def test_successful_download_delivers_b64_and_metadata(self):
        ws = await self._online_device()
        data = b"hello-file-transfer" * 5000
        path = "/sdcard/Download/sample.bin"
        task = asyncio.create_task(
            self.d.dispatch("d1", "files", ["get", path], wait=2.0))
        await asyncio.sleep(0.05)
        cmd_id = self._cmd_id(ws)
        for msg in self._frames(cmd_id, data, path):
            ws.push(msg)
        ws.push({"type": "result", "cmd_id": cmd_id, "ok": True,
                 "result": {"path": path, "dest": path}})
        entry = await asyncio.wait_for(task, timeout=3.0)
        self.assertEqual(entry["status"], "ok")
        self.assertEqual(entry["result"]["bytes"], len(data))
        self.assertEqual(entry["result"]["chunks"], 2)
        self.assertEqual(entry["result"]["sha256"],
                         hashlib.sha256(data).hexdigest())
        self.assertEqual(base64.b64decode(entry["result"]["b64"]), data)
        self.assertEqual(self.d.downloads, {})

    async def test_empty_file_download_accepts_empty_b64(self):
        ws = await self._online_device()
        path = "/sdcard/empty.txt"
        task = asyncio.create_task(self.d.admin_dispatch({
            "op": "files_get", "token": "test-admin-token",
            "device_id": "d1", "path": path}))
        await asyncio.sleep(0.05)
        cmd_id = self._cmd_id(ws)
        for msg in self._frames(cmd_id, b"", path):
            ws.push(msg)
        ws.push({"type": "result", "cmd_id": cmd_id, "ok": True,
                 "result": {"path": path}})
        resp = await asyncio.wait_for(task, timeout=3.0)
        self.assertTrue(resp["ok"])
        self.assertEqual(resp["data"], "")
        self.assertEqual(resp["bytes"], 0)
        self.assertEqual(resp["chunks"], 0)
        self.assertEqual(resp["sha256"],
                         hashlib.sha256(b"").hexdigest())
        self.assertEqual(self.d.downloads, {})

    async def test_file_start_path_must_match_requested(self):
        ws = await self._online_device()
        task = asyncio.create_task(
            self.d.dispatch("d1", "files", ["get", "/sdcard/requested"],
                            wait=0.5))
        await asyncio.sleep(0.05)
        cmd_id = self._cmd_id(ws)
        self.d._on_file_start("d1", ws, {
            "type": "file_start", "cmd_id": cmd_id,
            "path": "/sdcard/other", "dest": "/sdcard/other",
            "total": 5, "chunks": 1, "chunk": 65536})
        self.assertEqual(self.d.downloads[("d1", cmd_id, ws)]["error"],
                         "file transfer path mismatch")
        ws.push({"type": "result", "cmd_id": cmd_id, "ok": True,
                 "result": {}})
        entry = await asyncio.wait_for(task, timeout=2.0)
        self.assertEqual(entry["status"], "error")
        self.assertIn("path mismatch", entry["result"]["error"])

    async def test_file_start_rejects_overlong_path(self):
        ws = await self._online_device()
        task = asyncio.create_task(
            self.d.dispatch("d1", "files", ["get", "/sdcard/x"], wait=0.5))
        await asyncio.sleep(0.05)
        cmd_id = self._cmd_id(ws)
        long_path = "/sdcard/" + "x" * daemon.MAX_DEST_LEN
        self.d._on_file_start("d1", ws, {
            "type": "file_start", "cmd_id": cmd_id,
            "path": long_path, "dest": long_path,
            "total": 5, "chunks": 1, "chunk": 65536})
        self.assertEqual(self.d.downloads[("d1", cmd_id, ws)]["error"],
                         "invalid file transfer path")
        ws.push({"type": "result", "cmd_id": cmd_id, "ok": True,
                 "result": {}})
        entry = await asyncio.wait_for(task, timeout=2.0)
        self.assertEqual(entry["status"], "error")
        self.assertIn("invalid file transfer path",
                      entry["result"]["error"])

    async def test_hash_mismatch_fails_and_cleans_up(self):
        ws = await self._online_device()
        task = asyncio.create_task(
            self.d.dispatch("d1", "files", ["get", "/sdcard/x"], wait=1.0))
        await asyncio.sleep(0.05)
        cmd_id = self._cmd_id(ws)
        for msg in self._frames(cmd_id, b"abc", "/sdcard/x", sha="00" * 32):
            ws.push(msg)
        ws.push({"type": "result", "cmd_id": cmd_id, "ok": True,
                 "result": {"path": "/sdcard/x"}})
        entry = await asyncio.wait_for(task, timeout=2.0)
        self.assertEqual(entry["status"], "error")
        self.assertIn("sha256", entry["result"]["error"])
        self.assertEqual(self.d.downloads, {})

    async def test_count_mismatch_fails_and_cleans_up(self):
        ws = await self._online_device()
        task = asyncio.create_task(
            self.d.dispatch("d1", "files", ["get", "/sdcard/x"], wait=1.0))
        await asyncio.sleep(0.05)
        cmd_id = self._cmd_id(ws)
        frames = list(self._frames(cmd_id, b"abc", "/sdcard/x"))
        frames[-1]["bytes"] = 99
        for msg in frames:
            ws.push(msg)
        ws.push({"type": "result", "cmd_id": cmd_id, "ok": True,
                 "result": {}})
        entry = await asyncio.wait_for(task, timeout=2.0)
        self.assertEqual(entry["status"], "error")
        self.assertIn("count mismatch", entry["result"]["error"])
        self.assertEqual(self.d.downloads, {})

    async def test_size_cap_rejects_oversized_start(self):
        ws = await self._online_device()
        task = asyncio.create_task(
            self.d.dispatch("d1", "files", ["get", "/sdcard/x"], wait=1.0))
        await asyncio.sleep(0.05)
        cmd_id = self._cmd_id(ws)
        self.d._on_file_start("d1", ws, {
            "type": "file_start", "cmd_id": cmd_id, "path": "/sdcard/x",
            "dest": "/sdcard/x", "total": daemon.MAX_DOWNLOAD_BYTES + 1,
            "chunks": 1, "chunk": 65536})
        self.assertIn(("d1", cmd_id, ws), self.d.downloads)
        self.assertIn("limit", self.d.downloads[("d1", cmd_id, ws)]["error"])
        ws.push({"type": "result", "cmd_id": cmd_id, "ok": True,
                 "result": {"path": "/sdcard/x"}})
        entry = await asyncio.wait_for(task, timeout=2.0)
        self.assertEqual(entry["status"], "error")
        self.assertIn("limit", entry["result"]["error"])
        self.assertEqual(self.d.downloads, {})

    async def test_wrong_cmd_id_isolated(self):
        ws = await self._online_device()
        task = asyncio.create_task(
            self.d.dispatch("d1", "files", ["get", "/sdcard/x"], wait=1.0))
        await asyncio.sleep(0.05)
        cmd_id = self._cmd_id(ws)
        for msg in self._frames("wrong-cmd", b"hello", "/sdcard/x"):
            ws.push(msg)
        ws.push({"type": "result", "cmd_id": cmd_id, "ok": True,
                 "result": {"path": "/sdcard/x"}})
        entry = await asyncio.wait_for(task, timeout=2.0)
        self.assertNotIn("b64", entry["result"])
        self.assertEqual(self.d.downloads, {})

    async def test_wrong_ws_isolated(self):
        ws = await self._online_device()
        other = FakeWS()
        task = asyncio.create_task(
            self.d.dispatch("d1", "files", ["get", "/sdcard/x"], wait=1.0))
        await asyncio.sleep(0.05)
        cmd_id = self._cmd_id(ws)
        self.d._on_file_start("d1", other, {
            "type": "file_start", "cmd_id": cmd_id, "path": "/sdcard/x",
            "dest": "/sdcard/x", "total": 5, "chunks": 1, "chunk": 65536})
        self.assertEqual(self.d.downloads, {})
        ws.push({"type": "result", "cmd_id": cmd_id, "ok": True,
                 "result": {}})
        entry = await asyncio.wait_for(task, timeout=2.0)
        self.assertNotIn("b64", entry["result"])

    async def test_duplicate_start_keeps_first(self):
        ws = await self._online_device()
        data = b"hello"
        task = asyncio.create_task(
            self.d.dispatch("d1", "files", ["get", "/sdcard/x"], wait=1.0))
        await asyncio.sleep(0.05)
        cmd_id = self._cmd_id(ws)
        frames = list(self._frames(cmd_id, data, "/sdcard/x"))
        ws.push(frames[0])
        ws.push(frames[0])
        ws.push(frames[1])
        ws.push(frames[2])
        ws.push({"type": "result", "cmd_id": cmd_id, "ok": True,
                 "result": {}})
        entry = await asyncio.wait_for(task, timeout=2.0)
        self.assertEqual(entry["status"], "ok")
        self.assertEqual(base64.b64decode(entry["result"]["b64"]), data)

    async def test_wrong_seq_fails(self):
        ws = await self._online_device()
        data = b"a" * 65536 + b"b"
        task = asyncio.create_task(
            self.d.dispatch("d1", "files", ["get", "/sdcard/x"], wait=1.0))
        await asyncio.sleep(0.05)
        cmd_id = self._cmd_id(ws)
        frames = list(self._frames(cmd_id, data, "/sdcard/x"))
        header, payload = frames[1].split(b"\n", 1)
        bad = json.loads(header.decode())
        bad["seq"] = 5
        ws.push(frames[0])
        ws.push(json.dumps(bad).encode() + b"\n" + payload)
        ws.push(frames[2])
        ws.push({"type": "result", "cmd_id": cmd_id, "ok": True,
                 "result": {}})
        entry = await asyncio.wait_for(task, timeout=2.0)
        self.assertEqual(entry["status"], "error")
        self.assertIn("malformed", entry["result"]["error"])

    async def test_replacement_socket_does_not_inherit_download(self):
        ws1 = await self._online_device()
        task = asyncio.create_task(
            self.d.dispatch("d1", "files", ["get", "/sdcard/x"], wait=0.5))
        await asyncio.sleep(0.05)
        cmd_id = self._cmd_id(ws1)
        frames = list(self._frames(cmd_id, b"hello", "/sdcard/x"))
        ws1.push(frames[0])
        ws1.push(frames[1])
        await asyncio.sleep(0.05)
        self.assertIn(("d1", cmd_id, ws1), self.d.downloads)
        ws2 = FakeWS()
        ws2.push({"type": "hello", "device_id": "d1", "token": "tok"})
        await self._start_handler(ws2)
        await asyncio.sleep(0.1)
        self.assertIs(self.d.conns["d1"], ws2)
        self.assertEqual(self.d.downloads, {})
        entry = await asyncio.wait_for(task, timeout=2.0)
        self.assertEqual(entry["status"], "timeout")

    async def test_chunk_after_end_ignored(self):
        ws = await self._online_device()
        data = b"hello"
        task = asyncio.create_task(
            self.d.dispatch("d1", "files", ["get", "/sdcard/x"], wait=1.0))
        await asyncio.sleep(0.05)
        cmd_id = self._cmd_id(ws)
        for msg in self._frames(cmd_id, data, "/sdcard/x"):
            ws.push(msg)
        extra = (json.dumps({"type": "file_chunk", "cmd_id": cmd_id,
                             "seq": 1, "path": "/sdcard/x", "len": 5,
                             "total": len(data)}).encode()
                 + b"\n" + b"extra")
        ws.push(extra)
        ws.push({"type": "result", "cmd_id": cmd_id, "ok": True,
                 "result": {}})
        entry = await asyncio.wait_for(task, timeout=2.0)
        self.assertEqual(base64.b64decode(entry["result"]["b64"]), data)
        self.assertEqual(entry["result"]["bytes"], len(data))

    async def test_disconnect_cleans_download(self):
        ws = await self._online_device()
        task = asyncio.create_task(
            self.d.dispatch("d1", "files", ["get", "/sdcard/x"], wait=5.0))
        await asyncio.sleep(0.05)
        cmd_id = self._cmd_id(ws)
        frames = list(self._frames(cmd_id, b"hello", "/sdcard/x"))
        ws.push(frames[0])
        ws.push(frames[1])
        await asyncio.sleep(0.05)
        self.assertIn(("d1", cmd_id, ws), self.d.downloads)
        await ws.close()
        entry = await asyncio.wait_for(task, timeout=2.0)
        self.assertEqual(entry["status"], "error")
        self.assertIn("disconnected", entry["result"]["error"])
        self.assertEqual(self.d.downloads, {})

    async def test_timeout_cleans_download(self):
        ws = await self._online_device()
        task = asyncio.create_task(
            self.d.dispatch("d1", "files", ["get", "/sdcard/x"], wait=0.2))
        await asyncio.sleep(0.05)
        cmd_id = self._cmd_id(ws)
        frames = list(self._frames(cmd_id, b"hello", "/sdcard/x"))
        ws.push(frames[0])
        ws.push(frames[1])
        entry = await asyncio.wait_for(task, timeout=2.0)
        self.assertEqual(entry["status"], "timeout")
        self.assertEqual(self.d.downloads, {})

    async def test_files_get_admin_op_delivers_data(self):
        ws = await self._online_device()
        data = b"admin-op-data"
        task = asyncio.create_task(self.d.admin_dispatch({
            "op": "files_get", "token": "test-admin-token",
            "device_id": "d1", "path": "/sdcard/x"}))
        await asyncio.sleep(0.05)
        cmd_id = self._cmd_id(ws)
        for msg in self._frames(cmd_id, data, "/sdcard/x"):
            ws.push(msg)
        ws.push({"type": "result", "cmd_id": cmd_id, "ok": True,
                 "result": {"path": "/sdcard/x"}})
        resp = await asyncio.wait_for(task, timeout=3.0)
        self.assertTrue(resp["ok"])
        self.assertEqual(base64.b64decode(resp["data"]), data)
        self.assertEqual(resp["bytes"], len(data))
        self.assertEqual(resp["sha256"], hashlib.sha256(data).hexdigest())


class ValidatorTests(DaemonTestBase):
    def test_ids_waits_actions_and_logs(self):
        self.assertFalse(daemon._valid_device_id(""))
        self.assertFalse(daemon._valid_device_id("a" * 129))
        self.assertFalse(daemon._valid_device_id("bad\x00id"))
        self.assertTrue(daemon._valid_device_id("good-id_123"))
        self.assertFalse(daemon._valid_wait(0.09))
        self.assertFalse(daemon._valid_wait(301))
        self.assertFalse(daemon._valid_wait(float("nan")))
        self.assertTrue(daemon._valid_wait(0.1))
        self.assertTrue(daemon._valid_wait(300))
        self.assertIsNone(daemon._valid_config_value("max_pending", 0))
        self.assertIsNone(daemon._valid_config_value("max_pending", 10001))
        self.assertEqual(daemon._valid_config_value("max_pending", 50), 50)
        self.assertIn("destroy", daemon.ACTIONS)
        self.assertNotIn("ping", daemon.ACTIONS)
        self.assertIn("restart", daemon.ACTIONS)
        self.assertEqual(daemon.sanitize_log("a\nb\x00c"), "a\\nb?c")

    def test_touch_persist_flag(self):
        with patch.object(self.d, "persist_state") as mock:
            self.d.touch("d1", online=None, persist=False)
            mock.assert_not_called()
        with patch.object(self.d, "persist_state") as mock:
            self.d.touch("d1", online=None, persist=True)
            mock.assert_called_once()


if __name__ == "__main__":
    unittest.main()
