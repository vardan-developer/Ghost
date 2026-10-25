import tempfile
import unittest
from pathlib import Path

from envfile import parse_dotenv


class EnvFileTests(unittest.TestCase):
    def test_quotes_comments_and_hashes(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / ".env"
            path.write_text(
                'QUOTED="quoted-token"   # rotated\n'
                "SINGLE='other value' # comment\n"
                'HASH="value # inside"\n'
                "PLAIN=value # comment\n"
                "LITERAL=abc#def\n"
            )
            values = parse_dotenv(path)
        self.assertEqual(values["QUOTED"], "quoted-token")
        self.assertEqual(values["SINGLE"], "other value")
        self.assertEqual(values["HASH"], "value # inside")
        self.assertEqual(values["PLAIN"], "value")
        self.assertEqual(values["LITERAL"], "abc#def")


if __name__ == "__main__":
    unittest.main()
