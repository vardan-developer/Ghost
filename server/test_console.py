"""Tests for the rebuilt ghostctl console (console.py / console_ui.py).

Run: python3 -m unittest test_console.py
"""
import base64
import http.client
import json
import os
import sys
import threading
import time
import unittest
from http.server import ThreadingHTTPServer
from unittest.mock import patch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import console  # noqa: E402
import console_ui as ui  # noqa: E402

TEST_PASS = "testpass"
TEST_CSRF = "test-csrf-token"


class TestConsole(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        console.PASSWORD = TEST_PASS
        console.CSRF_TOKEN = TEST_CSRF
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", 0), console.Handler)
        cls.port = cls.httpd.server_address[1]
        cls.thread = threading.Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()

    def setUp(self):
        console.AUTH_FAILURES.clear()

    # ------------------------------------------------------------ helpers --
    def auth_header(self, password=TEST_PASS):
        raw = f"user:{password}".encode()
        return "Basic " + base64.b64encode(raw).decode()

    def request(self, method, path, body=None, headers=None, auth=True):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        hdrs = {"Host": f"127.0.0.1:{self.port}"}
        if headers:
            hdrs.update(headers)
        if auth:
            hdrs["Authorization"] = self.auth_header()
        if body is not None and isinstance(body, str):
            body = body.encode()
        conn.request(method, path, body=body, headers=hdrs)
        resp = conn.getresponse()
        data = resp.read()
        out = (resp.status, {k.lower(): v for k, v in resp.getheaders()}, data)
        conn.close()
        return out

    def _get_cookie(self):
        _s, headers, _ = self.request("GET", "/", headers={"Accept": "text/html"})
        return headers.get("set-cookie", "")

    @staticmethod
    def _multipart(fields, files, boundary="----testboundary"):
        """Build a multipart body; files is [(name, filename, bytes), ...]."""
        chunks = []
        for name, value in fields.items():
            chunks.append(
                f"--{boundary}\r\n"
                f'Content-Disposition: form-data; name="{name}"\r\n\r\n'
                f"{value}\r\n")
        for name, filename, content in files:
            chunks.append(
                f"--{boundary}\r\n"
                f'Content-Disposition: form-data; name="{name}"; '
                f'filename="{filename}"\r\n'
                "Content-Type: application/octet-stream\r\n\r\n")
        # Filenames and binary content need byte concatenation.
        out = "".join(chunks).encode()
        for _name, _filename, content in files:
            out += content + b"\r\n"
        out += f"--{boundary}--\r\n".encode()
        return boundary, out

    def _posted_upload(self, fields, files, headers=None):
        boundary, body = self._multipart(fields, files)
        hdrs = {"Content-Type": f"multipart/form-data; boundary={boundary}",
                "Origin": f"http://127.0.0.1:{self.port}"}
        if headers:
            hdrs.update(headers)
        return self.request("POST", "/files/upload", body=body, headers=hdrs)

    # --------------------------------------------------------------- auth --
    def test_unauthorized_headers(self):
        status, headers, _ = self.request("GET", "/", auth=False)
        self.assertEqual(status, 401)
        self.assertIn("www-authenticate", headers)
        self.assertEqual(headers.get("x-frame-options"), "DENY")
        self.assertEqual(headers.get("x-content-type-options"), "nosniff")
        self.assertEqual(headers.get("referrer-policy"), "no-referrer")
        self.assertEqual(headers.get("cache-control"), "no-store")
        self.assertNotIn("python", headers.get("server", "").lower())

    def test_bad_password(self):
        status, _, _ = self.request("GET", "/", auth=False, headers={"Authorization": self.auth_header("wrong")})
        self.assertEqual(status, 401)

    def test_bad_base64(self):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        conn.request("GET", "/", headers={"Authorization": "Basic !!!"})
        resp = conn.getresponse()
        self.assertEqual(resp.status, 401)
        conn.close()

    def test_good_auth(self):
        with patch("console._rpc_list", return_value={"ok": True, "devices": {}}):
            with patch("console._rpc_pending", return_value={"ok": True, "pending": {}}):
                status, headers, body = self.request("GET", "/")
                self.assertEqual(status, 200)
                self.assertIn(b"Devices", body)
                self.assertEqual(headers.get("x-frame-options"), "DENY")
                self.assertIn("csrf_token=", headers.get("set-cookie", ""))

    def test_rate_limit(self):
        for _ in range(console.MAX_AUTH_FAILURES):
            self.request("GET", "/", auth=False, headers={"Authorization": "Basic bad"})
        status, headers, _ = self.request("GET", "/", auth=False, headers={"Authorization": self.auth_header()})
        self.assertEqual(status, 429)
        self.assertIn("retry-after", headers)

    def test_auth_failures_expire(self):
        now = time.time()
        console.AUTH_FAILURES["old-ip"] = [3, now - console.AUTH_BACKOFF - 5]
        console._auth_failure("new-ip")
        self.assertNotIn("old-ip", console.AUTH_FAILURES)
        self.assertIn("new-ip", console.AUTH_FAILURES)

    def test_auth_failures_map_is_bounded(self):
        now = time.time()
        for i in range(console.MAX_AUTH_ENTRIES + 25):
            console.AUTH_FAILURES[f"ip-{i}"] = [1, now]
        console._auth_failure("fresh-ip")
        self.assertLessEqual(len(console.AUTH_FAILURES),
                             console.MAX_AUTH_ENTRIES)
        self.assertIn("fresh-ip", console.AUTH_FAILURES)

    # --------------------------------------------------------------- csrf --
    def test_csrf_missing(self):
        with patch("console._rpc", return_value={"ok": True}):
            status, _, _ = self.request("POST", "/device/abc/approve", body="name=x")
            self.assertEqual(status, 403)

    def test_csrf_invalid_token(self):
        with patch("console._rpc", return_value={"ok": True}):
            status, _, _ = self.request("POST", "/device/abc/approve", body="csrf_token=wrong")
            self.assertEqual(status, 403)

    def test_csrf_bad_origin(self):
        with patch("console._rpc", return_value={"ok": True}):
            status, _, _ = self.request(
                "POST", "/device/abc/approve", body=f"csrf_token={TEST_CSRF}",
                headers={"Origin": "http://evil.example"})
            self.assertEqual(status, 403)

    def test_csrf_valid(self):
        with patch("console._rpc", return_value={"ok": True}):
            status, _, _ = self.request(
                "POST", "/device/abc/approve", body=f"csrf_token={TEST_CSRF}",
                headers={"Origin": f"http://127.0.0.1:{self.port}"})
            self.assertEqual(status, 200)

    def test_csrf_stale_cookie_is_refreshed(self):
        # A restart changes CSRF_TOKEN; a stale cookie must not pin the
        # browser to the old value or force a permanent 403 loop.
        with patch("console._rpc", return_value={"ok": True}):
            status, headers, _ = self.request(
                "POST", "/device/abc/approve", body=f"csrf_token={TEST_CSRF}",
                headers={"Origin": f"http://127.0.0.1:{self.port}",
                         "Cookie": "csrf_token=stale-process-token"})
        self.assertEqual(status, 200)
        self.assertIn(f"csrf_token={TEST_CSRF}", headers.get("set-cookie", ""))

    def test_get_refreshes_missing_and_stale_cookie(self):
        with patch("console._rpc_list", return_value={"ok": True, "devices": {}}), \
                patch("console._rpc_pending", return_value={"ok": True, "pending": {}}):
            _s, headers, _ = self.request(
                "GET", "/", headers={"Cookie": "csrf_token=stale"})
        self.assertIn(f"csrf_token={TEST_CSRF}", headers.get("set-cookie", ""))

    def test_csrf_valid_with_no_cookie(self):
        with patch("console._rpc", return_value={"ok": True}):
            status, _headers, _ = self.request(
                "POST", "/device/abc/approve", body=f"csrf_token={TEST_CSRF}",
                headers={"Origin": f"http://127.0.0.1:{self.port}"})
        self.assertEqual(status, 200)

    # ------------------------------------------------------ path / xss ----
    def test_path_injection(self):
        status, _, _ = self.request("GET", "/device/%3Cscript%3E")
        self.assertIn(status, (400, 404))
        self.assertNotIn(b"<script>", _)

    def test_xss_escaping(self):
        devices = {
            "x<script>alert(1)</script>": {
                "name": "<img src=x onerror=alert(1)>",
                "online": True, "approved": True, "model": "Pixel",
                "app_version": "1", "sidekick_version": "2", "battery": 80,
                "ip": "10.0.0.1", "last_seen": time.time(),
            }
        }
        html = ui.render_dashboard(devices, {}, csrf_token=TEST_CSRF)
        self.assertNotIn("<script>alert", html)
        self.assertNotIn("<img src=x", html)
        self.assertIn("&lt;script&gt;", html)
        self.assertIn("&lt;img src=x", html)

    def test_url_quoting(self):
        devices = {"a/b": {"name": "slash", "approved": True, "online": True}}
        html = ui.render_dashboard(devices, {}, csrf_token=TEST_CSRF)
        self.assertIn("/device/a%2Fb", html)
        self.assertNotIn("/device/a/b", html)

    # ------------------------------------------------------- limits -------
    def test_negative_content_length(self):
        status, _, _ = self.request(
            "POST", "/device/abc/approve", body="",
            headers={"Content-Length": "-1"})
        self.assertEqual(status, 400)

    def test_malformed_content_length(self):
        status, _, _ = self.request(
            "POST", "/device/abc/approve", body="",
            headers={"Content-Length": "abc"})
        self.assertEqual(status, 400)

    def test_large_content_length(self):
        # Avoid actually sending 2MB; just declare a large Content-Length.
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        conn.request("POST", "/device/abc/approve", body=b"",
                     headers={"Authorization": self.auth_header(),
                              "Content-Length": str(2 * 1024 * 1024)})
        resp = conn.getresponse()
        resp.read()
        self.assertEqual(resp.status, 413)
        conn.close()

    # ------------------------------------------------------- streams ------
    def test_stream_feed_no_token(self):
        status, _, _ = self.request("GET", "/stream/abc/feed")
        self.assertEqual(status, 403)

    def test_stream_feed_offline(self):
        console._new_stream_token("abc")
        with patch("console._check_stream_token", return_value=True), \
             patch("console._device_ok", return_value=False):
            status, _, _ = self.request("GET", "/stream/abc/feed?t=x")
            self.assertEqual(status, 503)

    def test_stream_feed_valid(self):
        tok = "streamtok"
        console._new_stream_token("abc")
        with patch("console._check_stream_token", return_value=True), \
             patch("console._device_ok", return_value=True), \
             patch("console._fetch_frame", return_value=("screen", b"\xff\xd8jpeg", time.time(), None)):
            status, headers, body = self.request("GET", f"/stream/abc/feed?t={tok}")
            self.assertEqual(status, 200)
            self.assertEqual(headers.get("content-type"), "image/jpeg")

    def test_stream_feed_no_frame(self):
        with patch("console._check_stream_token", return_value=True), \
             patch("console._device_ok", return_value=True), \
             patch("console._fetch_frame", return_value=(None, None, None, "no frame")):
            status, _, _ = self.request("GET", "/stream/abc/feed?t=x")
            self.assertEqual(status, 503)

    def test_stream_mjpg_cap(self):
        with patch("console._check_stream_token", return_value=True), \
             patch("console._device_ok", return_value=True), \
             patch("console.STREAM_SEM.acquire", return_value=False):
            status, _, _ = self.request("GET", "/stream/abc/feed.mjpg?t=x")
            self.assertEqual(status, 503)

    def test_stream_mjpg_first_frame_missing(self):
        with patch("console._check_stream_token", return_value=True), \
             patch("console._device_ok", return_value=True), \
             patch("console._fetch_frame", return_value=(None, None, None, "none")):
            status, _, _ = self.request("GET", "/stream/abc/feed.mjpg?t=x")
            self.assertEqual(status, 503)

    # ------------------------------------------------------ history -------
    def test_history_rpc(self):
        dev = {"device_id": "abc", "name": "Test", "approved": True, "online": True}
        hist = [{"ts": time.time(), "action": "status", "args": [], "status": "ok",
                 "elapsed": 1.2, "actor": "127.0.0.1", "result": {"ok": True}}]
        with patch("console._device_by_id", return_value=dev), \
             patch("console._rpc_history", return_value=hist) as m:
            status, _, body = self.request("GET", "/device/abc")
            self.assertEqual(status, 200)
            self.assertIn(b"status", body)
            self.assertIn(b"127.0.0.1", body)
            m.assert_called_once_with("abc", 60)

    # ----------------------------------------------------- settings -------
    def test_settings_page(self):
        with patch("console._rpc_config", return_value={
                "ok": True, "config": {"max_pending": 5, "max_per_ip": 2}}):
            status, _, body = self.request("GET", "/settings")
            self.assertEqual(status, 200)
            self.assertIn(b"max_pending", body)
            self.assertIn(b"max_per_ip", body)

    def test_settings_post(self):
        with patch("console._rpc_config_set", return_value={"ok": True, "config": {"max_pending": 3}}) as m:
            status, _, _ = self.request(
                "POST", "/settings", body=f"csrf_token={TEST_CSRF}&max_pending=3",
                headers={"Origin": f"http://127.0.0.1:{self.port}"})
            self.assertEqual(status, 200)
            m.assert_called_once_with("max_pending", "3")

    def test_settings_invalid(self):
        with patch("console._rpc_config_set", return_value={"ok": False, "error": "must be integer"}):
            status, _, body = self.request(
                "POST", "/settings", body=f"csrf_token={TEST_CSRF}&max_pending=abc",
                headers={"Origin": f"http://127.0.0.1:{self.port}"})
            self.assertEqual(status, 200)
            self.assertIn(b"FAILED", body)
            self.assertIn(b"must be integer", body)

    # ---------------------------------------------------- file upload -----
    def _staged_names(self):
        return set(os.listdir(console.UPLOAD_DIR))

    def test_file_upload(self):
        before = self._staged_names()
        with patch("console._rpc_files_put", return_value={"ok": True}) as m:
            status, _, _ = self._posted_upload(
                {"device_id": "abc", "dest": "/sdcard/Download/",
                 "csrf_token": TEST_CSRF},
                [("file", "test.txt", b"hello upload")])
        self.assertEqual(status, 200)
        self.assertTrue(m.called)
        args = m.call_args[0]
        self.assertEqual(args[0], "abc")
        self.assertTrue(args[1].startswith(str(console.UPLOAD_DIR)))
        self.assertEqual(args[2], "/sdcard/Download/")
        self.assertFalse(os.path.exists(args[1]))
        self.assertEqual(self._staged_names(), before)

    def test_file_upload_blank_dest_uses_safe_basename(self):
        before = self._staged_names()
        with patch("console._rpc_files_put", return_value={"ok": True}) as m:
            status, _, _ = self._posted_upload(
                {"device_id": "abc", "dest": "", "csrf_token": TEST_CSRF},
                [("file", "../../evil name.txt", b"x")])
        self.assertEqual(status, 200)
        self.assertEqual(m.call_args[0][2], "/sdcard/Download/evil_name.txt")
        self.assertEqual(self._staged_names(), before)

    def test_file_upload_missing_dest_uses_basename(self):
        with patch("console._rpc_files_put", return_value={"ok": True}) as m:
            status, _, _ = self._posted_upload(
                {"device_id": "abc", "csrf_token": TEST_CSRF},
                [("file", "photo.jpg", b"jpeg")])
        self.assertEqual(status, 200)
        self.assertEqual(m.call_args[0][2], "/sdcard/Download/photo.jpg")

    def test_file_upload_multiple_files_and_cleanup(self):
        before = self._staged_names()
        with patch("console._rpc_files_put", return_value={"ok": True}) as m:
            status, _, _ = self._posted_upload(
                {"device_id": "abc", "dest": "", "csrf_token": TEST_CSRF},
                [("file", "a.txt", b"aaa"), ("file", "b.txt", b"bbb")])
        self.assertEqual(status, 200)
        self.assertEqual(m.call_count, 2)
        dests = [c[0][2] for c in m.call_args_list]
        self.assertEqual(dests, ["/sdcard/Download/a.txt",
                                 "/sdcard/Download/b.txt"])
        for call in m.call_args_list:
            self.assertFalse(os.path.exists(call[0][1]))
        self.assertEqual(self._staged_names(), before)

    def test_file_upload_multiple_requires_directory_dest(self):
        before = self._staged_names()
        with patch("console._rpc_files_put", return_value={"ok": True}) as m:
            status, _, body = self._posted_upload(
                {"device_id": "abc", "dest": "/sdcard/Download/one.bin",
                 "csrf_token": TEST_CSRF},
                [("file", "a.txt", b"aaa"), ("file", "b.txt", b"bbb")])
        self.assertEqual(status, 400)
        self.assertIn(b"must be a directory", body)
        m.assert_not_called()
        self.assertEqual(self._staged_names(), before)

    def test_file_upload_invalid_dest_rejected_and_cleaned(self):
        for dest in ("../escape", "relative/path", "/sdcard/../../data/x",
                     "C:\\temp\\x"):
            before = self._staged_names()
            with patch("console._rpc_files_put", return_value={"ok": True}) as m:
                status, _, body = self._posted_upload(
                    {"device_id": "abc", "dest": dest,
                     "csrf_token": TEST_CSRF},
                    [("file", "a.txt", b"aaa")])
            self.assertEqual(status, 400, dest)
            self.assertIn(b"invalid upload destination", body)
            m.assert_not_called()
            self.assertEqual(self._staged_names(), before, dest)

    def test_file_upload_bad_device_cleans_staged_files(self):
        before = self._staged_names()
        with patch("console._rpc_files_put", return_value={"ok": True}) as m:
            status, _, _ = self._posted_upload(
                {"device_id": "<bad>", "csrf_token": TEST_CSRF},
                [("file", "a.txt", b"aaa"), ("file", "b.txt", b"bbb")])
        self.assertEqual(status, 400)
        m.assert_not_called()
        self.assertEqual(self._staged_names(), before)

    def test_file_upload_csrf_missing(self):
        before = self._staged_names()
        status, _, _ = self._posted_upload(
            {"device_id": "abc"},
            [("file", "test.txt", b"hello")])
        self.assertEqual(status, 403)
        self.assertEqual(self._staged_names(), before)

    def test_file_upload_rpc_failure_returns_error_status(self):
        before = self._staged_names()
        with patch("console._rpc_files_put",
                   return_value={"ok": False, "error": "device offline"}):
            status, _, body = self._posted_upload(
                {"device_id": "abc", "dest": "/sdcard/Download/x.bin",
                 "csrf_token": TEST_CSRF},
                [("file", "x.bin", b"data")])
        self.assertEqual(status, 502)
        self.assertIn(b"FAILED", body)
        self.assertIn(b"device offline", body)
        self.assertEqual(self._staged_names(), before)

    def test_upload_passes_bounded_wait(self):
        with patch("console._rpc", return_value={"ok": True}) as m:
            console._rpc_files_put("abc", "/tmp/x", "/sdcard/x", "ip")
        req = m.call_args[0][0]
        self.assertEqual(req["op"], "files_put")
        self.assertGreaterEqual(req["wait"], 30.0)
        self.assertLessEqual(req["wait"], 300.0)

    # -------------------------------------------------- file download -----
    def test_file_download(self):
        payload = base64.b64encode(b"file-content").decode()
        with patch("console._rpc_files_get", return_value={"ok": True, "data": payload}):
            status, headers, body = self.request("GET", "/files/download?device_id=abc&path=/sdcard/x.txt")
            self.assertEqual(status, 200)
            self.assertEqual(body, b"file-content")
            self.assertIn("attachment", headers.get("content-disposition", ""))

    def test_file_download_error(self):
        with patch("console._rpc_files_get", return_value={"ok": False, "error": "not found"}):
            status, _, _ = self.request("GET", "/files/download?device_id=abc&path=/sdcard/x.txt")
            self.assertEqual(status, 502)

    def test_file_download_command_fallback_shape(self):
        # Older daemons answer the command verb with entry.result.b64.
        b64 = base64.b64encode(b"legacy-content").decode()
        legacy = {"ok": True, "entry": {"status": "ok",
                                        "result": {"b64": b64,
                                                   "path": "/sdcard/x.txt"}}}
        with patch("console._rpc_files_get", return_value=legacy):
            status, headers, body = self.request(
                "GET", "/files/download?device_id=abc&path=/sdcard/x.txt")
        self.assertEqual(status, 200)
        self.assertEqual(body, b"legacy-content")
        self.assertIn("attachment", headers.get("content-disposition", ""))

    def test_files_get_falls_back_to_command_op(self):
        b64 = base64.b64encode(b"legacy").decode()
        responses = [
            {"ok": False, "error": "unknown op 'files_get'"},
            {"ok": True, "entry": {"status": "ok",
                                   "result": {"b64": b64}}},
        ]
        with patch("console._rpc", side_effect=responses) as m:
            resp = console._rpc_files_get("abc", "/sdcard/x.txt", "ip")
        self.assertEqual(resp["entry"]["result"]["b64"], b64)
        first = m.call_args_list[0][0][0]
        second = m.call_args_list[1][0][0]
        self.assertEqual(first["op"], "files_get")
        self.assertGreaterEqual(first["wait"], 30.0)
        self.assertEqual(second["op"], "command")
        self.assertEqual(second["args"], ["get", "/sdcard/x.txt"])
        self.assertGreaterEqual(second["wait"], 30.0)

    def test_file_download_no_content_is_502_not_empty_200(self):
        shape = {"ok": True, "entry": {"status": "ok", "result": {}}}
        with patch("console._rpc_files_get", return_value=shape):
            status, _, body = self.request(
                "GET", "/files/download?device_id=abc&path=/sdcard/x.txt")
        self.assertEqual(status, 502)
        self.assertNotEqual(body, b"")
        self.assertIn(b"download failed", body)

    def test_file_download_valid_empty_file(self):
        for shape in ({"ok": True, "data": ""},
                      {"ok": True, "entry": {"status": "ok",
                                             "result": {"b64": ""}}}):
            with patch("console._rpc_files_get", return_value=shape):
                status, _, body = self.request(
                    "GET", "/files/download?device_id=abc&path=/sdcard/x.txt")
            self.assertEqual(status, 200, shape)
            self.assertEqual(body, b"")

    def test_file_download_fallback_failure_reports_entry_error(self):
        legacy = {"ok": False,
                  "entry": {"status": "error",
                            "result": {"error": "permission denied"}}}
        with patch("console._rpc_files_get", return_value=legacy):
            status, _, body = self.request(
                "GET", "/files/download?device_id=abc&path=/sdcard/x.txt")
        self.assertEqual(status, 502)
        self.assertIn(b"permission denied", body)

    # ------------------------------------------------------ file abort ----
    def test_files_abort_calls_daemon_command(self):
        with patch("console._rpc_command",
                   return_value={"ok": True, "entry": {"status": "ok"}}) as m:
            status, _, body = self.request(
                "POST", "/files/abort",
                body=f"csrf_token={TEST_CSRF}&device_id=abc",
                headers={"Origin": f"http://127.0.0.1:{self.port}"})
        self.assertEqual(status, 200)
        m.assert_called_once_with("abc", "files abort", "127.0.0.1",
                                  wait=20.0)
        self.assertIn(b"abort requested", body)
        # Truthful: server-side work may still finish.
        self.assertIn(b"may still", body)

    def test_files_abort_requires_csrf(self):
        with patch("console._rpc_command", return_value={"ok": True}) as m:
            status, _, _ = self.request(
                "POST", "/files/abort", body="device_id=abc",
                headers={"Origin": f"http://127.0.0.1:{self.port}"})
        self.assertEqual(status, 403)
        m.assert_not_called()

    def test_files_abort_failure_is_502(self):
        with patch("console._rpc_command",
                   return_value={"ok": False,
                                 "entry": {"status": "error",
                                           "result": {"error": "device offline"}}}):
            status, _, body = self.request(
                "POST", "/files/abort",
                body=f"csrf_token={TEST_CSRF}&device_id=abc",
                headers={"Origin": f"http://127.0.0.1:{self.port}"})
        self.assertEqual(status, 502)
        self.assertIn(b"device offline", body)

    # --------------------------------------------------- update route -----
    def test_update_routing(self):
        with patch("console._rpc_update", return_value={"ok": True}) as m:
            status, _, _ = self.request(
                "POST", "/device/abc/update", body=f"csrf_token={TEST_CSRF}",
                headers={"Origin": f"http://127.0.0.1:{self.port}"})
            self.assertEqual(status, 200)
            m.assert_called_once()
            self.assertEqual(m.call_args[0][0], "abc")

    # ---------------------------------------------------- remove ---------
    @staticmethod
    def _remove_form(markup):
        idx = markup.index("/remove")
        start = markup.rfind("<form", 0, idx)
        end = markup.index("</form>", idx) + len("</form>")
        return markup[start:end]

    def test_remove_markup_is_danger_without_extra_confirm(self):
        devices = {"d1": {"name": "Device 1", "approved": True,
                          "online": True}}
        dashboard = ui.render_dashboard(devices, {}, csrf_token=TEST_CSRF)
        form = self._remove_form(dashboard)
        self.assertIn('action="/device/d1/remove"', form)
        self.assertIn('name="csrf_token"', form)
        self.assertIn("danger", form)
        self.assertIn("Remove device", form)
        self.assertNotIn("data-confirm", form)

        device = {"device_id": "d1", "name": "Device 1", "approved": True,
                  "online": True}
        page = ui.render_device(device, [], csrf_token=TEST_CSRF)
        form = self._remove_form(page)
        self.assertIn('action="/device/d1/remove"', form)
        self.assertIn("danger", form)
        self.assertIn("Remove device", form)
        self.assertNotIn("data-confirm", form)

    def test_remove_route_success(self):
        dev = {"device_id": "abc", "name": "Test Phone", "approved": True,
               "online": True}
        with patch("console._device_by_id", return_value=dev), \
                patch("console._rpc_remove", return_value={
                    "ok": True, "removed": True, "device_id": "abc"}) as m:
            status, _, body = self.request(
                "POST", "/device/abc/remove", body=f"csrf_token={TEST_CSRF}",
                headers={"Origin": f"http://127.0.0.1:{self.port}"})
        self.assertEqual(status, 200)
        m.assert_called_once_with("abc", "127.0.0.1")
        self.assertIn(b"was removed", body)
        self.assertIn(b"record", body)
        self.assertIn(b'href="/"', body)
        self.assertNotIn(b"NOT removed", body)

    def test_remove_route_requires_csrf(self):
        with patch("console._rpc_remove", return_value={"ok": True}) as m:
            status, _, _ = self.request("POST", "/device/abc/remove", body="")
        self.assertEqual(status, 403)
        m.assert_not_called()

    def test_remove_route_failure_does_not_claim_uninstall(self):
        dev = {"device_id": "abc", "name": "Test Phone", "approved": True,
               "online": True}
        with patch("console._device_by_id", return_value=dev), \
                patch("console._rpc_remove", return_value={
                    "ok": False, "removed": False, "revoked": True,
                    "error": "device is locked: unlock the phone"}):
            status, _, body = self.request(
                "POST", "/device/abc/remove", body=f"csrf_token={TEST_CSRF}",
                headers={"Origin": f"http://127.0.0.1:{self.port}"})
        self.assertEqual(status, 200)
        self.assertIn(b"NOT removed", body)
        self.assertIn(b"device is locked", body)
        self.assertIn(b"kept", body)
        self.assertIn(b"/device/abc", body)

    def test_remove_route_offline_wording(self):
        dev = {"device_id": "abc", "name": "Test Phone", "approved": True,
               "online": False}
        with patch("console._device_by_id", return_value=dev), \
                patch("console._rpc_remove", return_value={
                    "ok": False, "removed": False, "revoked": False,
                    "error": "device abc is offline"}):
            status, _, body = self.request(
                "POST", "/device/abc/remove", body=f"csrf_token={TEST_CSRF}",
                headers={"Origin": f"http://127.0.0.1:{self.port}"})
        self.assertEqual(status, 200)
        self.assertIn(b"NOT removed", body)
        self.assertIn(b"offline", body)
        self.assertIn(b"revoke", body)

    def test_remove_result_expected_disconnect_note(self):
        dev = {"device_id": "abc", "name": "Test Phone"}
        html = ui.render_remove_result(
            dev, {"ok": True, "removed": True, "expected_disconnect": True},
            csrf_token=TEST_CSRF)
        self.assertIn("disconnected before", html)
        self.assertIn("was removed", html)

    def test_remove_finalizing_is_pending_not_failure(self):
        dev = {"device_id": "abc", "name": "Test Phone"}
        html = ui.render_remove_result(
            dev, {"ok": True, "removed": False, "finalizing": True,
                  "device_id": "abc"}, csrf_token=TEST_CSRF)
        self.assertIn("Removal requested", html)
        self.assertIn("Awaiting disconnect verification", html)
        self.assertNotIn("Remove failed", html)

    # --------------------------------------------------- broadcast --------
    def test_broadcast_destroy_blocked(self):
        with patch("console._rpc_broadcast", return_value={"ok": True}):
            status, _, _ = self.request(
                "POST", "/broadcast", body=f"csrf_token={TEST_CSRF}&verb=destroy",
                headers={"Origin": f"http://127.0.0.1:{self.port}"})
            self.assertEqual(status, 400)

    def test_broadcast_safe(self):
        with patch("console._rpc_broadcast", return_value={"ok": True, "broadcast": True}) as m:
            status, _, _ = self.request(
                "POST", "/broadcast", body=f"csrf_token={TEST_CSRF}&verb=status",
                headers={"Origin": f"http://127.0.0.1:{self.port}"})
            self.assertEqual(status, 200)
            self.assertTrue(m.called)
            self.assertEqual(m.call_args[0][0], "status")

    # ---------------------------------------------------- static assets ---
    def test_static_css(self):
        status, headers, body = self.request("GET", "/static/app.css")
        self.assertEqual(status, 200)
        self.assertIn("text/css", headers.get("content-type", ""))
        self.assertIn(b"@media", body)

    def test_static_js(self):
        status, headers, body = self.request("GET", "/static/app.js")
        self.assertEqual(status, 200)
        self.assertIn("javascript", headers.get("content-type", ""))
        self.assertIn(b"csrf", body.lower())
        # Upload must branch on the real HTTP status and report errors.
        self.assertIn(b"xhr.status", body)
        self.assertIn(b"responseError", body)
        # Cancel must invoke the real daemon abort endpoint.
        self.assertIn(b"/files/abort", body)
        self.assertIn(b"may still finish", body)

    # ----------------------------------------------------- markup ---------
    def test_dashboard_markup(self):
        devices = {
            "d1": {"name": "Device 1", "approved": True, "online": True,
                   "model": "M1", "app_version": "1.0", "sidekick_version": "0.9",
                   "battery": 77, "ip": "10.0.0.2", "last_seen": time.time()},
        }
        pending = {"p1": {"name": "Pending", "ip": "10.0.0.3", "last_seen": time.time()}}
        html = ui.render_dashboard(devices, pending, csrf_token=TEST_CSRF)
        self.assertIn('id="device-search"', html)
        self.assertIn('data-device-row', html)
        self.assertIn('data-search-text', html)
        self.assertIn('pending-only', html)
        self.assertIn('data-confirm', html)
        # No inline handlers.
        self.assertNotIn('onclick=', html)
        self.assertNotIn('<script>', html.replace('<script src="/static/app.js"></script>', ''))

    def test_pending_device_rendered_once(self):
        devices = {"p1": {"name": "Pending One", "approved": False,
                          "online": True, "ip": "10.0.0.9"}}
        pending = {"p1": {"name": "Pending One", "ip": "10.0.0.9"}}
        html = ui.render_dashboard(devices, pending, csrf_token=TEST_CSRF)
        self.assertEqual(html.count("data-device-row"), 1)
        self.assertIn("Pending approval (1)", html)
        self.assertIn("No approved devices yet", html)

    def test_device_markup(self):
        dev = {"device_id": "d1", "name": "Device 1", "approved": True, "online": True}
        html = ui.render_device(dev, [], csrf_token=TEST_CSRF)
        for group in ("Files", "Screen", "UI", "Media", "System"):
            self.assertIn(group, html)
        self.assertIn('data-tab-group="actions"', html)
        self.assertIn('data-confirm', html)
        self.assertNotIn('onclick=', html)
        self.assertNotIn('<script>', html.replace('<script src="/static/app.js"></script>', ''))

    def test_no_generic_file_transfer_forms(self):
        dev = {"device_id": "d1", "name": "Device 1", "approved": True,
               "online": True}
        html = ui.render_device(dev, [], csrf_token=TEST_CSRF)
        # File bytes must never travel through the generic command form.
        self.assertNotIn('value="files put"', html)
        self.assertNotIn('value="files get"', html)
        self.assertNotIn("files put", ui.ALLOWED_VERBS)
        self.assertNotIn("files get", ui.ALLOWED_VERBS)
        self.assertIn("files abort", ui.ALLOWED_VERBS)
        self.assertIn('action="/files/upload"', html)
        self.assertIn('action="/files/download"', html)

    def test_download_form_markup(self):
        dev = {"device_id": "d1", "name": "Device 1", "approved": True,
               "online": True}
        html = ui.render_device(dev, [], csrf_token=TEST_CSRF)
        self.assertIn('action="/files/download"', html)
        idx = html.index('action="/files/download"')
        start = html.rfind("<form", 0, idx)
        end = html.index("</form>", idx)
        form = html[start:end]
        self.assertIn('method="get"', form)
        self.assertIn('name="path"', form)
        self.assertIn('name="device_id"', form)
        self.assertIn('value="d1"', form)
        # No base64 dump through a generic `files get` action form.
        self.assertNotIn("files get", html)

    def test_no_destroy_or_ping_forms(self):
        dev = {"device_id": "d1", "name": "Device 1", "approved": True, "online": True}
        html = ui.render_device(dev, [], csrf_token=TEST_CSRF)
        self.assertNotIn('value="destroy"', html)
        self.assertNotIn('value="ping"', html)

    def test_grouped_actions_validation(self):
        self.assertIn("status", ui.ALLOWED_VERBS)
        self.assertNotIn("destroy", ui.ALLOWED_VERBS)
        self.assertNotIn("ping", ui.ALLOWED_VERBS)
        self.assertIn("status", ui.BROADCAST_VERBS)
        self.assertNotIn("destroy", ui.BROADCAST_VERBS)
        self.assertNotIn("update", ui.BROADCAST_VERBS)

    # ---------------------------------------------------- masking ---------
    def test_mask_sensitive(self):
        summary = ui.json_summary({"token": "abc123", "pin": "9999", "ok": True})
        self.assertNotIn("abc123", summary)
        self.assertNotIn("9999", summary)
        self.assertIn("<masked>", summary)

    def test_truncate_large(self):
        summary = ui.json_summary({"data": "x" * 5000}, max_len=100)
        self.assertLess(len(summary), 1000)
        self.assertIn("chars", summary)


if __name__ == "__main__":
    unittest.main()
