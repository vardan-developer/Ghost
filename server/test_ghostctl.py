"""Focused unit tests for the ghostctl admin client.

Run from server/: python3 -m unittest test_ghostctl.py
"""
import io
import json
import os
import socket
import sys
import tempfile
import types
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

import envfile
import ghostctl


def _import_daemon():
    """Import daemon.py without ever reading the real server/.env.

    Some environments lack the optional websockets package, so stub it the
    same way test_daemon.py does.
    """
    os.environ.setdefault("GHOSTCTL_TEST", "1")
    if "websockets" not in sys.modules:
        try:
            import websockets  # noqa: F401
        except ImportError:
            stub = types.ModuleType("websockets")
            stub.ConnectionClosed = type("ConnectionClosed", (Exception,), {})
            sys.modules["websockets"] = stub
    import daemon
    return daemon


class FakeSocket:
    def __init__(self, data=b"", error=None):
        self.data = data
        self.error = error
        self.sent = []
        self.timeout = None

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        return False

    def settimeout(self, timeout):
        self.timeout = timeout

    def sendall(self, data):
        self.sent.append(data)

    def recv(self, n):
        if self.error is not None:
            raise self.error
        if not self.data:
            return b""
        chunk, self.data = self.data[:n], self.data[n:]
        return chunk


class TokenTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.base_patcher = mock.patch.object(ghostctl, "BASE", self.base)
        self.base_patcher.start()
        self.addCleanup(self.base_patcher.stop)
        self.env_patcher = mock.patch.dict(
            os.environ, {"GHOSTCTL_ADMIN_TOKEN": ""}, clear=False)
        self.env_patcher.start()
        self.addCleanup(self.env_patcher.stop)

    def test_env_wins(self):
        (self.base / ".env").write_text("GHOSTCTL_ADMIN_TOKEN=dotenv-token\n")
        (self.base / "admin.key").write_text("key-token\n")
        with mock.patch.dict(
                os.environ, {"GHOSTCTL_ADMIN_TOKEN": "env-token"}):
            self.assertEqual(ghostctl._load_admin_token(), "env-token")

    def test_dotenv_used_when_env_absent(self):
        (self.base / ".env").write_text(
            "GHOSTCTL_ADMIN_TOKEN='dotenv-token'\n")
        (self.base / "admin.key").write_text("key-token\n")
        self.assertEqual(ghostctl._load_admin_token(), "dotenv-token")

    def test_admin_key_fallback(self):
        (self.base / "admin.key").write_text("key-token\n")
        self.assertEqual(ghostctl._load_admin_token(), "key-token")

    def test_missing_token_returns_none(self):
        self.assertIsNone(ghostctl._load_admin_token())

    def test_dotenv_matches_shared_parser(self):
        text = ("# operator token below\n"
                "GHOSTCTL_ADMIN_TOKEN=dotenv-token   # trailing note\n"
                "OTHER='a # b'\n")
        (self.base / ".env").write_text(text)
        expected = envfile.parse_dotenv(
            self.base / ".env")["GHOSTCTL_ADMIN_TOKEN"]
        self.assertEqual(expected, "dotenv-token")
        self.assertEqual(ghostctl._load_admin_token(), expected)

    def test_dotenv_quoted_value_with_hash_kept(self):
        (self.base / ".env").write_text(
            "GHOSTCTL_ADMIN_TOKEN='do#t-env'\n")
        self.assertEqual(ghostctl._load_admin_token(), "do#t-env")

    def test_whitespace_env_token_falls_back_to_admin_key(self):
        (self.base / ".env").write_text("GHOSTCTL_ADMIN_TOKEN='   '\n")
        (self.base / "admin.key").write_text("key-token\n")
        with mock.patch.dict(
                os.environ, {"GHOSTCTL_ADMIN_TOKEN": "   "}):
            self.assertEqual(ghostctl._load_admin_token(), "key-token")

    def test_whitespace_only_admin_key_is_missing(self):
        (self.base / "admin.key").write_text("  \n\t\n")
        self.assertIsNone(ghostctl._load_admin_token())

    def test_token_loading_does_not_print(self):
        (self.base / ".env").write_text("GHOSTCTL_ADMIN_TOKEN=dotenv-token\n")
        out = io.StringIO()
        err = io.StringIO()
        with redirect_stdout(out), mock.patch("sys.stderr", err):
            ghostctl._load_admin_token()
        self.assertEqual(out.getvalue(), "")
        self.assertEqual(err.getvalue(), "")


class RpcTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.base_patcher = mock.patch.object(ghostctl, "BASE", self.base)
        self.base_patcher.start()
        self.addCleanup(self.base_patcher.stop)
        self.env_patcher = mock.patch.dict(
            os.environ, {"GHOSTCTL_ADMIN_TOKEN": ""}, clear=False)
        self.env_patcher.start()
        self.addCleanup(self.env_patcher.stop)

    @mock.patch("ghostctl.socket.create_connection")
    def test_rpc_includes_token(self, mock_create):
        (self.base / ".env").write_text("GHOSTCTL_ADMIN_TOKEN=dummy-token\n")
        fake = FakeSocket(b'{"ok": true}\n')
        mock_create.return_value = fake
        resp = ghostctl.rpc({"op": "list"})
        self.assertEqual(resp, {"ok": True})
        sent = json.loads(fake.sent[0].decode())
        self.assertEqual(sent["token"], "dummy-token")

    @mock.patch("ghostctl.socket.create_connection")
    def test_rpc_update_timeout_covers_push_and_install(self, mock_create):
        fake = FakeSocket(b'{"ok": true}\n')
        mock_create.return_value = fake
        ghostctl.rpc({"op": "update", "wait": 10})
        self.assertGreaterEqual(fake.timeout, 600.0)
        self.assertEqual(fake.timeout, ghostctl.UPDATE_TIMEOUT)

    @mock.patch("ghostctl.socket.create_connection")
    def test_rpc_update_timeout_with_max_wait(self, mock_create):
        fake = FakeSocket(b'{"ok": true}\n')
        mock_create.return_value = fake
        ghostctl.rpc({"op": "update", "wait": 300})
        self.assertGreaterEqual(fake.timeout, 600.0)

    @mock.patch("ghostctl.socket.create_connection")
    def test_rpc_files_get_timeout_wait_plus_15(self, mock_create):
        fake = FakeSocket(b'{"ok": true}\n')
        mock_create.return_value = fake
        ghostctl.rpc({"op": "files_get"})
        self.assertEqual(fake.timeout, 75.0)

    @mock.patch("ghostctl.socket.create_connection")
    def test_rpc_files_put_timeout_wait_plus_15(self, mock_create):
        fake = FakeSocket(b'{"ok": true}\n')
        mock_create.return_value = fake
        ghostctl.rpc({"op": "files_put"})
        self.assertEqual(fake.timeout, 195.0)

    @mock.patch("ghostctl.socket.create_connection")
    def test_rpc_files_put_explicit_wait_plus_15(self, mock_create):
        fake = FakeSocket(b'{"ok": true}\n')
        mock_create.return_value = fake
        ghostctl.rpc({"op": "files_put", "wait": 60})
        self.assertEqual(fake.timeout, 75.0)

    @mock.patch("ghostctl.socket.create_connection")
    def test_rpc_files_get_explicit_wait_plus_15(self, mock_create):
        fake = FakeSocket(b'{"ok": true}\n')
        mock_create.return_value = fake
        ghostctl.rpc({"op": "files_get", "wait": 120})
        self.assertEqual(fake.timeout, 135.0)

    @mock.patch("ghostctl.socket.create_connection")
    def test_rpc_normal_timeout(self, mock_create):
        fake = FakeSocket(b'{"ok": true}\n')
        mock_create.return_value = fake
        ghostctl.rpc({"op": "command", "wait": 120})
        self.assertGreaterEqual(fake.timeout, 135.0)
        self.assertLess(fake.timeout, 400.0)

    @mock.patch("ghostctl.socket.create_connection")
    def test_rpc_wait_bounded(self, mock_create):
        fake = FakeSocket(b'{"ok": true}\n')
        mock_create.return_value = fake
        ghostctl.rpc({"op": "command", "wait": 1000})
        self.assertGreaterEqual(fake.timeout, 300.0)
        self.assertLess(fake.timeout, 400.0)
        self.assertEqual(json.loads(fake.sent[0].decode())["wait"], 300.0)

    @mock.patch("ghostctl.socket.create_connection")
    def test_rpc_wait_floor(self, mock_create):
        fake = FakeSocket(b'{"ok": true}\n')
        mock_create.return_value = fake
        ghostctl.rpc({"op": "command", "wait": -5})
        self.assertEqual(fake.timeout, 30.0)
        self.assertEqual(json.loads(fake.sent[0].decode())["wait"], 0.1)

    @mock.patch("ghostctl.socket.create_connection")
    def test_rpc_empty_response(self, mock_create):
        mock_create.return_value = FakeSocket(b"")
        with self.assertRaises(ghostctl.RpcError):
            ghostctl.rpc({"op": "list"})

    @mock.patch("ghostctl.socket.create_connection")
    def test_rpc_incomplete_response(self, mock_create):
        mock_create.return_value = FakeSocket(b'{"ok": true}')
        with self.assertRaises(ghostctl.RpcError):
            ghostctl.rpc({"op": "list"})

    @mock.patch("ghostctl.socket.create_connection")
    def test_rpc_invalid_json(self, mock_create):
        mock_create.return_value = FakeSocket(b"not-json\n")
        with self.assertRaises(ghostctl.RpcError):
            ghostctl.rpc({"op": "list"})

    @mock.patch("ghostctl.socket.create_connection")
    def test_rpc_non_object_json(self, mock_create):
        mock_create.return_value = FakeSocket(b"[]\n")
        with self.assertRaises(ghostctl.RpcError):
            ghostctl.rpc({"op": "list"})

    @mock.patch("ghostctl.socket.create_connection")
    def test_rpc_socket_error(self, mock_create):
        mock_create.side_effect = OSError("refused")
        with self.assertRaises(ghostctl.RpcError):
            ghostctl.rpc({"op": "list"})

    @mock.patch("ghostctl.socket.create_connection")
    def test_rpc_recv_error(self, mock_create):
        mock_create.return_value = FakeSocket(error=socket.timeout("timed out"))
        with self.assertRaises(ghostctl.RpcError):
            ghostctl.rpc({"op": "list"})

    @mock.patch.object(ghostctl, "MAX_RESPONSE", 10)
    @mock.patch("ghostctl.socket.create_connection")
    def test_rpc_response_cap(self, mock_create):
        mock_create.return_value = FakeSocket(b"x" * 11)
        with self.assertRaises(ghostctl.RpcError):
            ghostctl.rpc({"op": "list"})

    def test_max_response_constant(self):
        self.assertEqual(ghostctl.MAX_RESPONSE, 48 * 1024 * 1024)


class WaitTests(unittest.TestCase):
    def test_bounded_wait(self):
        self.assertEqual(ghostctl.bounded_wait(None, 30), 30.0)
        self.assertEqual(ghostctl.bounded_wait(0.05, 30), 0.1)
        self.assertEqual(ghostctl.bounded_wait(1000, 30), 300.0)
        self.assertEqual(ghostctl.bounded_wait(float("inf"), 30), 30.0)
        self.assertEqual(ghostctl.bounded_wait(float("nan"), 30), 30.0)
        self.assertEqual(ghostctl.bounded_wait("bad", 30), 30.0)
        self.assertEqual(ghostctl.bounded_wait("120", 30), 120.0)
        self.assertEqual(ghostctl.bounded_wait(-5, 30), 0.1)

    def test_default_wait_for(self):
        self.assertEqual(
            ghostctl.default_wait_for("screen", ["shot"]), 120.0)
        self.assertEqual(
            ghostctl.default_wait_for("screen", ["record"]), 120.0)
        self.assertEqual(
            ghostctl.default_wait_for("cam", ["snap"]), 120.0)
        self.assertEqual(
            ghostctl.default_wait_for("mic", ["record"]), 120.0)
        self.assertEqual(
            ghostctl.default_wait_for("screen", ["live"]), 30.0)
        self.assertEqual(
            ghostctl.default_wait_for("cam", ["live"]), 30.0)
        self.assertEqual(
            ghostctl.default_wait_for("mic", ["live"]), 30.0)
        self.assertEqual(
            ghostctl.default_wait_for("screen", ["stop"]), 30.0)
        self.assertEqual(
            ghostctl.default_wait_for("shell", ["echo", "hi"]), 30.0)
        self.assertEqual(ghostctl.default_wait_for("screen", []), 30.0)


class ResolveDeviceTests(unittest.TestCase):
    @mock.patch("ghostctl.rpc")
    def test_exact_id(self, mock_rpc):
        mock_rpc.return_value = {
            "ok": True, "devices": {"abc123": {"name": "Pixel"}}}
        self.assertEqual(ghostctl.resolve_device("abc123"), "abc123")

    @mock.patch("ghostctl.rpc")
    def test_id_prefix(self, mock_rpc):
        mock_rpc.return_value = {
            "ok": True,
            "devices": {"abc123": {"name": "Pixel"}, "def456": {}}}
        self.assertEqual(ghostctl.resolve_device("abc"), "abc123")

    @mock.patch("ghostctl.rpc")
    def test_name_exact(self, mock_rpc):
        mock_rpc.return_value = {
            "ok": True, "devices": {"abc123": {"name": "Pixel 10a"}}}
        self.assertEqual(ghostctl.resolve_device("pixel 10a"), "abc123")

    @mock.patch("ghostctl.rpc")
    def test_name_prefix(self, mock_rpc):
        mock_rpc.return_value = {
            "ok": True,
            "devices": {"abc123": {"name": "Pixel 10a"},
                        "def456": {"name": "Other"}}}
        self.assertEqual(ghostctl.resolve_device("pixel"), "abc123")

    @mock.patch("ghostctl.rpc")
    def test_ambiguous_id_prefix(self, mock_rpc):
        mock_rpc.return_value = {
            "ok": True, "devices": {"abc123": {}, "abc456": {}}}
        with self.assertRaises(SystemExit) as cm:
            ghostctl.resolve_device("abc")
        self.assertIn("ambiguous", str(cm.exception))

    @mock.patch("ghostctl.rpc")
    def test_ambiguous_name(self, mock_rpc):
        mock_rpc.return_value = {
            "ok": True,
            "devices": {"abc123": {"name": "Pixel"},
                        "def456": {"name": "Pixel"}}}
        with self.assertRaises(SystemExit) as cm:
            ghostctl.resolve_device("pixel")
        self.assertIn("ambiguous", str(cm.exception))

    @mock.patch("ghostctl.rpc")
    def test_ambiguous_name_prefix(self, mock_rpc):
        mock_rpc.return_value = {
            "ok": True,
            "devices": {"abc123": {"name": "Pixel 10a"},
                        "def456": {"name": "Pixel 9"}}}
        with self.assertRaises(SystemExit) as cm:
            ghostctl.resolve_device("pixel")
        self.assertIn("ambiguous", str(cm.exception))

    @mock.patch("ghostctl.rpc")
    def test_not_found(self, mock_rpc):
        mock_rpc.return_value = {"ok": True, "devices": {"abc123": {}}}
        with self.assertRaises(SystemExit) as cm:
            ghostctl.resolve_device("zzz")
        self.assertIn("not found", str(cm.exception))

    @mock.patch("ghostctl.rpc", side_effect=ghostctl.RpcError("boom"))
    def test_list_failure_exits(self, mock_rpc):
        with self.assertRaises(SystemExit) as cm:
            ghostctl.resolve_device("abc")
        self.assertNotEqual(cm.exception.code, 0)


class SafeRpcTests(unittest.TestCase):
    def test_success_emits(self):
        with mock.patch("ghostctl.rpc",
                        return_value={"ok": True, "value": 1}):
            out = io.StringIO()
            with redirect_stdout(out):
                resp = ghostctl.safe_rpc({"op": "list"}, False)
        self.assertEqual(resp, {"ok": True, "value": 1})
        self.assertEqual(json.loads(out.getvalue()),
                         {"ok": True, "value": 1})

    def test_rpc_error_exits_without_traceback(self):
        with mock.patch("ghostctl.rpc",
                        side_effect=ghostctl.RpcError("boom")):
            with self.assertRaises(SystemExit) as cm:
                ghostctl.safe_rpc({"op": "list"}, False)
        self.assertEqual(cm.exception.code, "boom")

    def test_ok_false_emits_and_exits(self):
        with mock.patch("ghostctl.rpc",
                        return_value={"ok": False, "error": "nope"}):
            out = io.StringIO()
            with redirect_stdout(out):
                with self.assertRaises(SystemExit) as cm:
                    ghostctl.safe_rpc({"op": "list"}, False)
        self.assertEqual(json.loads(out.getvalue()),
                         {"ok": False, "error": "nope"})
        self.assertEqual(cm.exception.code, "nope")


class MainTests(unittest.TestCase):
    def run_main(self, argv):
        with mock.patch.object(sys, "argv", argv):
            return ghostctl.main()

    def test_all_update_rejected(self):
        with self.assertRaises(SystemExit) as cm:
            self.run_main(["ghostctl.py", "--all", "update", "self"])
        self.assertIn("--all", str(cm.exception))
        self.assertIn("not supported", str(cm.exception))

    def test_all_and_device_rejected(self):
        with mock.patch("ghostctl.resolve_device") as mock_resolve, \
                mock.patch("ghostctl.safe_rpc") as mock_safe:
            with self.assertRaises(SystemExit) as cm:
                self.run_main(["ghostctl.py", "--all", "-d", "dev1",
                               "shell", "echo", "hi"])
        self.assertIn("mutually exclusive", str(cm.exception))
        mock_resolve.assert_not_called()
        mock_safe.assert_not_called()

    def test_all_and_device_rejected_for_remove(self):
        with mock.patch("ghostctl.resolve_device") as mock_resolve, \
                mock.patch("ghostctl.safe_rpc") as mock_safe:
            with self.assertRaises(SystemExit) as cm:
                self.run_main(["ghostctl.py", "--all", "-d", "dev1",
                               "remove"])
        self.assertIn("mutually exclusive", str(cm.exception))
        mock_resolve.assert_not_called()
        mock_safe.assert_not_called()

    def test_all_and_device_rejected_for_local_apks(self):
        out = io.StringIO()
        with redirect_stdout(out):
            with self.assertRaises(SystemExit) as cm:
                self.run_main(["ghostctl.py", "--all", "-d", "dev1",
                               "apks", "list"])
        self.assertIn("mutually exclusive", str(cm.exception))
        self.assertEqual(out.getvalue(), "")

    @mock.patch("ghostctl.resolve_device", return_value="dev1")
    @mock.patch("ghostctl.safe_rpc", return_value={"ok": True})
    def test_command_default_wait_screen_shot(self, mock_safe, mock_resolve):
        self.run_main(
            ["ghostctl.py", "-d", "dev1", "screen", "shot"])
        req = mock_safe.call_args[0][0]
        self.assertEqual(req["op"], "command")
        self.assertEqual(req["wait"], 120.0)
        self.assertEqual(req["device_id"], "dev1")
        self.assertFalse(req["all"])

    @mock.patch("ghostctl.resolve_device", return_value="dev1")
    @mock.patch("ghostctl.safe_rpc", return_value={"ok": True})
    def test_command_explicit_wait_wins(self, mock_safe, mock_resolve):
        self.run_main(
            ["ghostctl.py", "-d", "dev1", "--wait", "5",
             "screen", "shot"])
        req = mock_safe.call_args[0][0]
        self.assertEqual(req["wait"], 5.0)

    @mock.patch("ghostctl.resolve_device", return_value="dev1")
    @mock.patch("ghostctl.safe_rpc", return_value={"ok": True})
    def test_command_default_wait_ordinary(self, mock_safe, mock_resolve):
        self.run_main(
            ["ghostctl.py", "-d", "dev1", "shell", "echo", "hi"])
        req = mock_safe.call_args[0][0]
        self.assertEqual(req["wait"], 30.0)

    @mock.patch("ghostctl.resolve_device", return_value="dev1")
    @mock.patch("ghostctl.safe_rpc", return_value={"ok": True})
    def test_command_default_wait_live(self, mock_safe, mock_resolve):
        self.run_main(["ghostctl.py", "-d", "dev1", "screen", "live"])
        req = mock_safe.call_args[0][0]
        self.assertEqual(req["wait"], 30.0)

    @mock.patch("ghostctl.resolve_device", return_value="dev1")
    @mock.patch("ghostctl.safe_rpc", return_value={"ok": True})
    def test_update_default_wait_and_no_apk(self, mock_safe, mock_resolve):
        self.run_main(["ghostctl.py", "-d", "dev1", "update", "self"])
        req = mock_safe.call_args[0][0]
        self.assertEqual(req["op"], "update")
        self.assertEqual(req["wait"], 300.0)
        self.assertNotIn("apk", req)

    @mock.patch("ghostctl.resolve_device", return_value="dev1")
    @mock.patch("ghostctl.safe_rpc", return_value={"ok": True})
    def test_update_explicit_wait_wins(self, mock_safe, mock_resolve):
        self.run_main(
            ["ghostctl.py", "-d", "dev1", "--wait", "10",
             "update", "self"])
        req = mock_safe.call_args[0][0]
        self.assertEqual(req["wait"], 10.0)

    @mock.patch("ghostctl.rpc", side_effect=ghostctl.RpcError("boom"))
    def test_list_rpc_failure_exits(self, mock_rpc):
        with self.assertRaises(SystemExit) as cm:
            self.run_main(["ghostctl.py", "list"])
        self.assertNotEqual(cm.exception.code, 0)

    @mock.patch("ghostctl.rpc",
                return_value={"ok": False, "error": "nope"})
    def test_list_ok_false_exits(self, mock_rpc):
        out = io.StringIO()
        with redirect_stdout(out):
            with self.assertRaises(SystemExit) as cm:
                self.run_main(["ghostctl.py", "list"])
        self.assertNotEqual(cm.exception.code, 0)
        self.assertEqual(json.loads(out.getvalue()),
                         {"ok": False, "error": "nope"})

    @mock.patch("ghostctl.resolve_device", return_value="dev1")
    @mock.patch("ghostctl.rpc",
                return_value={"ok": False, "error": "cmd failed"})
    def test_command_failure_exits(self, mock_rpc, mock_resolve):
        out = io.StringIO()
        with redirect_stdout(out):
            with self.assertRaises(SystemExit) as cm:
                self.run_main(
                    ["ghostctl.py", "-d", "dev1", "shell", "echo", "hi"])
        self.assertNotEqual(cm.exception.code, 0)
        self.assertEqual(json.loads(out.getvalue()),
                         {"ok": False, "error": "cmd failed"})

    @mock.patch("ghostctl.rpc",
                return_value={"ok": True, "devices": {"abc123": {}}})
    def test_command_unknown_device_exits(self, mock_rpc):
        with self.assertRaises(SystemExit) as cm:
            self.run_main(
                ["ghostctl.py", "-d", "typo", "shell", "echo", "hi"])
        self.assertIn("not found", str(cm.exception))

    @mock.patch("ghostctl.rpc",
                return_value={"ok": True,
                              "devices": {"abc123": {}, "abc456": {}}})
    def test_command_ambiguous_device_exits(self, mock_rpc):
        with self.assertRaises(SystemExit) as cm:
            self.run_main(
                ["ghostctl.py", "-d", "abc", "shell", "echo", "hi"])
        self.assertIn("ambiguous", str(cm.exception))

    def test_destroy_verb_rejected(self):
        with self.assertRaises(SystemExit) as cm:
            self.run_main(["ghostctl.py", "-d", "dev1", "destroy"])
        self.assertIn("unknown verb", str(cm.exception))

    def test_ping_verb_rejected(self):
        with self.assertRaises(SystemExit) as cm:
            self.run_main(["ghostctl.py", "-d", "dev1", "ping"])
        self.assertIn("unknown verb", str(cm.exception))

    def test_restart_kept(self):
        ns = mock.Mock(verb="restart", rest=[])
        self.assertEqual(ghostctl.build_args(ns), ("restart", []))

    def test_unimplemented_verbs_removed(self):
        self.assertNotIn("ping", ghostctl.PASS_THROUGH)
        self.assertNotIn("destroy", ghostctl.PASS_THROUGH)
        self.assertIn("restart", ghostctl.PASS_THROUGH)

    @mock.patch("ghostctl.ADMIN_HOST", "127.0.0.1")
    @mock.patch("ghostctl.resolve_device", return_value="dev1")
    @mock.patch("ghostctl.safe_rpc", return_value={"ok": True})
    def test_update_apk_staged_for_loopback(self, mock_safe, mock_resolve):
        with tempfile.TemporaryDirectory() as td:
            apk_dir = Path(td) / "apks"
            src = Path(td) / "custom.apk"
            src.write_bytes(b"fake apk")
            with mock.patch.object(ghostctl, "APK_DIR", apk_dir):
                self.run_main(
                    ["ghostctl.py", "-d", "dev1", "update", "self",
                     "--apk", str(src)])
            dest = apk_dir / "ghostd.apk"
            self.assertTrue(dest.is_file())
            self.assertEqual(dest.read_bytes(), b"fake apk")
            req = mock_safe.call_args[0][0]
            self.assertEqual(req["op"], "update")
            self.assertNotIn("apk", req)

    @mock.patch("ghostctl.ADMIN_HOST", "192.168.1.5")
    @mock.patch("ghostctl.resolve_device", return_value="dev1")
    @mock.patch("ghostctl.safe_rpc", return_value={"ok": True})
    def test_update_apk_rejected_non_loopback(self, mock_safe, mock_resolve):
        with tempfile.TemporaryDirectory() as td:
            src = Path(td) / "custom.apk"
            src.write_bytes(b"fake apk")
            with self.assertRaises(SystemExit) as cm:
                self.run_main(
                    ["ghostctl.py", "-d", "dev1", "update", "self",
                     "--apk", str(src)])
        self.assertIn("loopback", str(cm.exception))
        mock_safe.assert_not_called()

    @mock.patch("ghostctl.ADMIN_HOST", "127.0.0.1")
    @mock.patch("ghostctl.resolve_device", return_value="dev1")
    @mock.patch("ghostctl.safe_rpc", return_value={"ok": True})
    def test_update_apk_missing_file(self, mock_safe, mock_resolve):
        with tempfile.TemporaryDirectory() as td:
            with self.assertRaises(SystemExit) as cm:
                self.run_main(
                    ["ghostctl.py", "-d", "dev1", "update", "self",
                     "--apk", str(Path(td) / "nope.apk")])
        self.assertIn("no such file", str(cm.exception))
        mock_safe.assert_not_called()

    @mock.patch("ghostctl.ADMIN_HOST", "192.168.1.5")
    @mock.patch("ghostctl.resolve_device", return_value="dev1")
    @mock.patch("ghostctl.safe_rpc", return_value={"ok": True})
    def test_update_without_apk_allowed_non_loopback(
            self, mock_safe, mock_resolve):
        self.run_main(["ghostctl.py", "-d", "dev1", "update", "self"])
        req = mock_safe.call_args[0][0]
        self.assertEqual(req["op"], "update")
        self.assertNotIn("apk", req)


class RemoveCliTests(unittest.TestCase):
    def run_main(self, argv):
        with mock.patch.object(sys, "argv", argv):
            return ghostctl.main()

    @mock.patch("ghostctl.resolve_device", return_value="dev1")
    @mock.patch("ghostctl.safe_rpc", return_value={"ok": True})
    def test_remove_positional_routes_admin_op(self, mock_safe, mock_resolve):
        self.run_main(["ghostctl.py", "remove", "Pixel 10a"])
        mock_resolve.assert_called_once_with("Pixel 10a")
        req = mock_safe.call_args[0][0]
        self.assertEqual(req["op"], "remove")
        self.assertEqual(req["device_id"], "dev1")
        self.assertEqual(req["source"], "cli")
        self.assertEqual(req["wait"], 150.0)
        self.assertNotIn("action", req)

    @mock.patch("ghostctl.resolve_device", return_value="dev1")
    @mock.patch("ghostctl.safe_rpc", return_value={"ok": True})
    def test_remove_d_flag_matches_approve_revoke(self, mock_safe,
                                                  mock_resolve):
        self.run_main(["ghostctl.py", "-d", "dev1", "remove"])
        mock_resolve.assert_called_once_with("dev1")
        req = mock_safe.call_args[0][0]
        self.assertEqual(req["op"], "remove")
        self.assertEqual(req["device_id"], "dev1")

    @mock.patch("ghostctl.resolve_device", return_value="dev1")
    @mock.patch("ghostctl.safe_rpc", return_value={"ok": True})
    def test_remove_explicit_wait_bounded(self, mock_safe, mock_resolve):
        self.run_main(["ghostctl.py", "-d", "dev1", "--wait", "5",
                       "remove"])
        self.assertEqual(mock_safe.call_args[0][0]["wait"], 5.0)

    def test_remove_requires_device(self):
        with self.assertRaises(SystemExit) as cm:
            self.run_main(["ghostctl.py", "remove"])
        self.assertIn("specify device id", str(cm.exception))

    @mock.patch("ghostctl.resolve_device", return_value="dev1")
    @mock.patch("ghostctl.rpc",
                return_value={"ok": False, "removed": False,
                              "error": "device is offline"})
    def test_remove_failure_exits_nonzero(self, mock_rpc, mock_resolve):
        out = io.StringIO()
        with redirect_stdout(out):
            with self.assertRaises(SystemExit) as cm:
                self.run_main(["ghostctl.py", "remove", "dev1"])
        self.assertEqual(cm.exception.code, "device is offline")
        self.assertFalse(json.loads(out.getvalue())["ok"])

    def test_remove_is_not_a_passthrough_verb(self):
        self.assertNotIn("destroy", ghostctl.PASS_THROUGH)
        self.assertNotIn("remove", ghostctl.PASS_THROUGH)


class RotateTokenCliTests(unittest.TestCase):
    def run_main(self, argv):
        with mock.patch.object(sys, "argv", argv):
            return ghostctl.main()

    @mock.patch("ghostctl.resolve_device", return_value="dev1")
    @mock.patch("ghostctl.safe_rpc", return_value={"ok": True,
                                                   "rotated": True})
    def test_rotate_token_routes_admin_op(self, mock_safe, mock_resolve):
        self.run_main(["ghostctl.py", "rotate-token", "Pixel 10a"])
        mock_resolve.assert_called_once_with("Pixel 10a")
        req = mock_safe.call_args[0][0]
        self.assertEqual(req["op"], "rotate_token")
        self.assertEqual(req["device_id"], "dev1")
        self.assertEqual(req["wait"], 10.0)
        self.assertNotIn("action", req)

    @mock.patch("ghostctl.resolve_device", return_value="dev1")
    @mock.patch("ghostctl.safe_rpc", return_value={"ok": True})
    def test_rotate_token_wait_is_capped_at_server_limit(self, mock_safe,
                                                         mock_resolve):
        self.run_main(["ghostctl.py", "-d", "dev1", "--wait", "300",
                       "rotate-token"])
        self.assertEqual(mock_safe.call_args[0][0]["wait"], 30.0)

    def test_rotate_token_requires_device(self):
        with self.assertRaises(SystemExit) as cm:
            self.run_main(["ghostctl.py", "rotate-token"])
        self.assertIn("specify device id", str(cm.exception))

    @mock.patch("ghostctl.safe_rpc", return_value={"ok": True,
                                                   "rotated": True})
    def test_rotate_admin_routes_admin_op(self, mock_safe):
        self.run_main(["ghostctl.py", "rotate-admin"])
        self.assertEqual(mock_safe.call_args[0][0], {"op": "rotate_admin"})

    def test_rotate_admin_rejects_device_target(self):
        with self.assertRaises(SystemExit) as cm:
            self.run_main(["ghostctl.py", "-d", "dev1", "rotate-admin"])
        self.assertIn("does not take a device target", str(cm.exception))


class FilesPutTests(unittest.TestCase):
    def run_main(self, argv):
        with mock.patch.object(sys, "argv", argv):
            return ghostctl.main()

    @mock.patch("ghostctl.ADMIN_HOST", "127.0.0.1")
    @mock.patch("ghostctl.resolve_device", return_value="dev1")
    def test_stages_random_mode600_and_cleans_up(self, mock_resolve):
        with tempfile.TemporaryDirectory() as td:
            base = Path(td)
            src = base / "hello.txt"
            src.write_text("hello")
            uploads = base / "uploads"
            captured = {}

            def fake_safe_rpc(req, pretty=None):
                captured["req"] = req
                staged = uploads / req["src"]
                captured["mode"] = staged.stat().st_mode & 0o777
                captured["bytes"] = staged.read_bytes()
                return {"ok": True}

            with mock.patch.object(ghostctl, "UPLOAD_DIR", uploads), \
                    mock.patch("ghostctl.safe_rpc",
                               side_effect=fake_safe_rpc):
                self.run_main(
                    ["ghostctl.py", "-d", "dev1", "files", "put",
                     "/sdcard/hello.txt", str(src)])
            req = captured["req"]
            self.assertEqual(req["op"], "files_put")
            self.assertEqual(req["device_id"], "dev1")
            self.assertEqual(req["dest"], "/sdcard/hello.txt")
            self.assertTrue(req["src"].startswith("put_"))
            self.assertEqual(len(req["src"]), len("put_") + 32)
            self.assertEqual(captured["mode"], 0o600)
            self.assertEqual(captured["bytes"], b"hello")
            self.assertEqual(list(uploads.glob("*")), [])

    @mock.patch("ghostctl.ADMIN_HOST", "192.168.1.5")
    @mock.patch("ghostctl.resolve_device", return_value="dev1")
    @mock.patch("ghostctl.safe_rpc", return_value={"ok": True})
    def test_non_loopback_admin_rejected(self, mock_safe, mock_resolve):
        with tempfile.TemporaryDirectory() as td:
            src = Path(td) / "hello.txt"
            src.write_text("hello")
            with self.assertRaises(SystemExit) as cm:
                self.run_main(
                    ["ghostctl.py", "-d", "dev1", "files", "put",
                     "/sdcard/hello.txt", str(src)])
        self.assertIn("loopback", str(cm.exception))
        mock_safe.assert_not_called()

    @mock.patch("ghostctl.ADMIN_HOST", "127.0.0.1")
    @mock.patch("ghostctl.resolve_device", return_value="dev1")
    @mock.patch("ghostctl.safe_rpc", return_value={"ok": True})
    def test_missing_local_source_rejected(self, mock_safe, mock_resolve):
        with self.assertRaises(SystemExit) as cm:
            self.run_main(
                ["ghostctl.py", "-d", "dev1", "files", "put",
                 "/sdcard/hello.txt", "/nonexistent/source"])
        self.assertIn("no such file", str(cm.exception))
        mock_safe.assert_not_called()

    @mock.patch("ghostctl.ADMIN_HOST", "127.0.0.1")
    @mock.patch("ghostctl.resolve_device", return_value="dev1")
    def test_missing_source_arg_fails_clearly(self, mock_resolve):
        with self.assertRaises(SystemExit) as cm:
            self.run_main(
                ["ghostctl.py", "-d", "dev1", "files", "put", "/sdcard/x"])
        self.assertIn("usage", str(cm.exception))

    @mock.patch("ghostctl.ADMIN_HOST", "127.0.0.1")
    @mock.patch("ghostctl.resolve_device", return_value="dev1")
    @mock.patch("ghostctl.safe_rpc", return_value={"ok": True})
    def test_all_rejected(self, mock_safe, mock_resolve):
        with self.assertRaises(SystemExit) as cm:
            self.run_main(
                ["ghostctl.py", "--all", "files", "put",
                 "/sdcard/x", "/tmp/x"])
        self.assertIn("--all", str(cm.exception))
        mock_safe.assert_not_called()

    @mock.patch("ghostctl.ADMIN_HOST", "127.0.0.1")
    @mock.patch("ghostctl.resolve_device", return_value="dev1")
    @mock.patch("ghostctl.safe_rpc", return_value={"ok": True})
    def test_files_get_stays_command(self, mock_safe, mock_resolve):
        self.run_main(
            ["ghostctl.py", "-d", "dev1", "files", "get", "/sdcard/x"])
        req = mock_safe.call_args[0][0]
        self.assertEqual(req["op"], "command")
        self.assertEqual(req["action"], "files")
        self.assertEqual(req["args"], ["get", "/sdcard/x"])


class AdminTokenParityTests(unittest.TestCase):
    """ghostctl must resolve GHOSTCTL_ADMIN_TOKEN exactly like the daemon.

    The daemon loads .env through the shared envfile parser (quotes stripped,
    comments ignored) and load_admin_key() prefers that value over its
    admin.key file. The CLI re-reads the same file with the same parser, so
    both sides must agree on quoting, comments, blank values and the
    admin.key fallback. All paths here are temp dirs; real secrets are never
    read.
    """

    ENV_TEXT = (
        "# operator admin token, quoted form\n"
        "GHOSTCTL_ADMIN_TOKEN='quoted-token'\n"
        "GHOST_MAX_PENDING=50   # trailing comment\n"
    )

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.env_path = self.base / ".env"
        self.key_path = self.base / "admin.key"
        self.daemon = _import_daemon()
        # Never inherit a real admin token from the outer environment.
        self.saved = {k: os.environ.pop(k)
                      for k in ("GHOSTCTL_ADMIN_TOKEN",)
                      if k in os.environ}
        self.addCleanup(os.environ.update, self.saved)
        for target, name, value in (
                (ghostctl, "BASE", self.base),
                (self.daemon, "ADMIN_KEY_FILE", self.key_path),
                (self.daemon, "ENV_FILE", self.env_path)):
            p = mock.patch.object(target, name, value)
            p.start()
            self.addCleanup(p.stop)

    def test_quoted_comment_env_parity(self):
        self.env_path.write_text(self.ENV_TEXT)
        self.key_path.write_text("key-token\n")
        envfile.apply_dotenv(self.env_path)
        self.assertEqual(os.environ["GHOSTCTL_ADMIN_TOKEN"], "quoted-token")
        self.assertEqual(self.daemon.load_admin_key(), "quoted-token")
        self.assertEqual(ghostctl._load_admin_token(), "quoted-token")
        self.assertEqual(self.daemon.load_admin_key(),
                         ghostctl._load_admin_token())

    def test_blank_env_value_falls_back_identically(self):
        self.env_path.write_text("GHOSTCTL_ADMIN_TOKEN=   \n")
        self.key_path.write_text("key-token\n")
        envfile.apply_dotenv(self.env_path)
        self.assertEqual(os.environ["GHOSTCTL_ADMIN_TOKEN"], "")
        self.assertEqual(self.daemon.load_admin_key(), "key-token")
        self.assertEqual(self.daemon.load_admin_key(),
                         ghostctl._load_admin_token())

    def test_admin_key_fallback_parity(self):
        self.env_path.write_text("# no admin token in this file\n")
        self.key_path.write_text("key-token\n")
        self.assertEqual(self.daemon.load_admin_key(), "key-token")
        self.assertEqual(self.daemon.load_admin_key(),
                         ghostctl._load_admin_token())


if __name__ == "__main__":
    unittest.main()
