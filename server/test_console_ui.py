"""Focused presentation checks; no daemon, credentials, or device required."""
import unittest
from html.parser import HTMLParser

import console_ui as ui


class OverviewParser(HTMLParser):
    def __init__(self, source):
        super().__init__()
        self.counts = []
        self.in_count = False
        self.feed(source)

    def handle_starttag(self, tag, attrs):
        if tag == "strong":
            self.in_count = True

    def handle_data(self, data):
        if self.in_count:
            self.counts.append(data)

    def handle_endtag(self, tag):
        if tag == "strong":
            self.in_count = False


class TestConsoleOverview(unittest.TestCase):
    def test_pending_devices_are_not_counted_twice_in_overview(self):
        devices = {
            "a": {"name": "Online", "approved": True, "online": True},
            "b": {"name": "Offline", "approved": True, "online": False},
            "c": {"name": "Pending", "approved": False, "online": True},
        }
        pending = {"c": devices["c"]}
        page = ui.render_dashboard(devices, pending)
        self.assertEqual(OverviewParser(page).counts[:4], ["02", "01", "01", "01"])
        self.assertEqual(page.count("data-device-row"), 3)

    def test_empty_overview_has_zero_counts(self):
        page = ui.render_dashboard({}, {})
        self.assertEqual(OverviewParser(page).counts, ["00", "00", "00", "00"])
        self.assertIn("No devices yet", page)


class TestReadableResponses(unittest.TestCase):
    def test_command_envelope_is_rendered_as_fields(self):
        response = {"ok": True, "entry": {"status": "ok", "result": {"model": "Pixel", "charging": True}}}
        page = ui.render_result("Device info", response, "/")
        content = page.split('id="result-content"', 1)[1].split("</section>", 1)[0]
        self.assertIn("<dt>Model</dt>", content)
        self.assertIn("<dd>Yes</dd>", content)
        self.assertNotIn("<dt>Entry</dt>", content)
        self.assertIn("Technical details", page)

    def test_output_is_escaped_and_secret_keys_stay_masked(self):
        result = {"stdout": "<img src=x onerror=alert(1)>", "password": "private-value"}
        page = ui.render_result("Shell", {"ok": True, "entry": {"result": result}}, "/")
        self.assertNotIn("<img src=x", page)
        self.assertIn("&lt;img src=x", page)
        self.assertNotIn("private-value", page)
        nested_error = ui.render_result("Failed", {"ok": False, "error": {"token": "hidden-token"}}, "/")
        self.assertNotIn("hidden-token", nested_error)

    def test_record_lists_have_table_output_and_empty_lists_are_clear(self):
        result = [{"name": "one.txt", "size": 10}]
        page = ui.render_result("List folder", {"ok": True, "entry": {"result": result}}, "/")
        self.assertIn('<th scope="col">name</th>', page)
        self.assertIn("one.txt", page)
        empty = ui.render_result("List folder", {"ok": True, "result": []}, "/")
        self.assertIn("No items returned", empty)

    def test_subgroups_preserve_exact_command_allowlist(self):
        grouped = [verb for sections in ui.ACTION_SECTIONS.values() for _, verbs in sections for verb in verbs]
        self.assertEqual(set(grouped) | {"files ls"}, ui.ALLOWED_VERBS)
        self.assertEqual(len(grouped), len(set(grouped)))
        self.assertEqual(set(ui.ACTION_LABELS), ui.ALLOWED_VERBS)

    def test_restart_has_one_button_inside_session_group(self):
        page = ui.render_device({"device_id": "d1", "name": "Demo"}, [])
        self.assertIn("Session &amp; recovery", page)
        self.assertEqual(page.count('value="restart"'), 1)
        self.assertNotIn('<label>restart</label>', page)
        self.assertIn('id="browser-form"', page)
        self.assertEqual(page.count('value="files ls"'), 1)
        self.assertNotIn("List folder", page)
        self.assertIn("Advanced file tools", page)
        self.assertIn('id="command-output"', page)

    def test_json_details_remain_parseable(self):
        import json
        import html
        response = {"ok": True, "entry": {"result": [{"name": "A & B.txt", "path": "/sdcard/A & B.txt", "dir": False}]}}
        page = ui.render_result("List", response, "/")
        encoded = page.split('<pre id="result-data">', 1)[1].split("</pre>", 1)[0]
        self.assertEqual(json.loads(html.unescape(encoded)), response)

    def test_long_results_bound_the_readable_table(self):
        result = [{"name": str(i)} for i in range(120)]
        page = ui.render_result("List", {"ok": True, "result": result}, "/")
        self.assertIn("Showing 100 of 120 items", page)
        self.assertIn('&quot;119&quot;', page)

    def test_font_is_local_and_cdn_free(self):
        import base64
        self.assertEqual(base64.b64decode(ui.FONT_WOFF2)[:4], b"wOF2")
        self.assertIn('new FontFace("IBM Plex Mono", fontBytes.buffer', ui.JS)
        self.assertNotIn("https://fonts", ui.CSS + ui.JS)


if __name__ == "__main__":
    unittest.main()
