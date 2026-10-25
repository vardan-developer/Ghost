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


if __name__ == "__main__":
    unittest.main()
