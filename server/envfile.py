"""Shared, minimal .env parsing for daemon, CLI and console.

KEY=VALUE lines only. Existing process environment wins in apply_dotenv().
Comments start at line start or at an unquoted ``#`` preceded by whitespace.
A matching single/double quote pair around the value is removed.
"""
import os
from pathlib import Path


def parse_dotenv(path):
    path = Path(path)
    values = {}
    try:
        lines = path.read_text().splitlines()
    except FileNotFoundError:
        return values
    for raw in lines:
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        key, value = key.strip(), value.strip()
        if not key:
            continue
        if value.startswith(("'", '"')):
            quote = value[0]
            end = value.find(quote, 1)
            if end > 0 and (end == len(value) - 1 or
                            value[end + 1:].lstrip().startswith("#")):
                value = value[1:end]
        else:
            for i, char in enumerate(value):
                if char == "#" and i > 0 and value[i - 1].isspace():
                    value = value[:i].rstrip()
                    break
        values[key] = value
    return values


def apply_dotenv(path):
    for key, value in parse_dotenv(path).items():
        os.environ.setdefault(key, value)
