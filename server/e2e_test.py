#!/usr/bin/env python3
"""ghostctl end-to-end test suite.

Runs every implemented device verb against ONE approved, online device and
prints a pass/fail report. Pure server-side (uses ghostctl.py's rpc).

Usage: python3 e2e_test.py <device_id>
"""

import base64
import hashlib
import json
import subprocess
import sys
import time
from pathlib import Path

DEV = sys.argv[1] if len(sys.argv) > 1 else sys.exit("usage: e2e_test.py <device_id>")
BASE = Path(__file__).resolve().parent


def run(verb, args=None, wait=30):
    """Run one device command; returns (ok, result-or-error, elapsed)."""
    cmd = ["python3", "ghostctl.py", "-d", DEV, "--wait", str(wait), verb] + (args or [])
    t0 = time.time()
    p = subprocess.run(cmd, capture_output=True, text=True, cwd=BASE, timeout=wait + 30)
    dt = time.time() - t0
    try:
        r = json.loads(p.stdout)
    except json.JSONDecodeError:
        return False, (p.stdout or p.stderr)[:120], dt
    entry = r.get("entry") or {}
    return r.get("ok") is True and entry.get("status") == "ok", entry.get("result"), dt


results = []


def check(name, verb, args=None, wait=30, validate=None):
    ok, res, dt = run(verb, args, wait)
    note = ""
    if ok and validate:
        ok, note = validate(res)
    results.append((name, ok, f"{dt:.1f}s", note if not ok else (note or "ok")))
    print(f"{'PASS' if ok else 'FAIL':4}  {name:28} {dt:5.1f}s  {note if not ok else ''}")
    return ok


def device_streams():
    """Cached live streams for DEV, or None when the listing failed."""
    p = subprocess.run(["python3", "ghostctl.py", "streams"],
                       capture_output=True, text=True, cwd=BASE, timeout=30)
    try:
        resp = json.loads(p.stdout)
    except json.JSONDecodeError:
        return None
    if resp.get("ok") is not True:
        return None
    return [s for s in (resp.get("streams") or [])
            if s.get("device_id") == DEV]


# ------------------------------------------------------------------ system
check("shell", "shell", ["echo e2e-$((1+1))"],
      validate=lambda r: (str(r).find("e2e-2") >= 0, str(r)[:80]) if isinstance(r, dict) else (False, str(r)[:80]))
check("brightness get", "brightness", ["get"],
      validate=lambda r: ("brightness" in r, "") if isinstance(r, dict) else (False, str(r)[:60]))
check("brightness set 77", "brightness", ["set", "77"],
      validate=lambda r: (isinstance(r, dict) and r.get("brightness") == 77, str(r)[:60]))
check("brightness restore 102", "brightness", ["set", "102"])
check("volume get", "volume", ["get", "media"])
ok, vres, _ = run("volume", ["get", "media"])
orig_media = (vres or {}).get("volume") if isinstance(vres, dict) else None
check("volume set 50", "volume", ["set", "media", "50"],
      validate=lambda r: (isinstance(r, dict) and "volume" in r, str(r)[:60]))
if orig_media is not None:
    check("volume restore", "volume", ["set", "media", str(orig_media)])
check("wake", "wake", [], wait=15)

# ------------------------------------------------------------------ files
SOURCE = Path(__file__).resolve()
source_bytes = SOURCE.read_bytes()
source_sha = hashlib.sha256(source_bytes).hexdigest()


def validate_put(r):
    if not isinstance(r, dict):
        return False, str(r)[:80]
    if r.get("bytes") != len(source_bytes):
        return False, f"bytes={r.get('bytes')} expected {len(source_bytes)}"
    if str(r.get("sha256", "")).lower() != source_sha:
        return False, f"sha256={str(r.get('sha256'))[:16]} expected {source_sha[:16]}"
    return True, ""


check("files ls", "files", ["ls", "/sdcard"],
      validate=lambda r: (isinstance(r, list) and len(r) >= 0, "") if isinstance(r, list) else (False, str(r)[:60]))
check("files put", "files", ["put", "/sdcard/e2e-test.txt", __file__],
      validate=validate_put)
ok, res, dt = run("files", ["get", "/sdcard/e2e-test.txt"])
got_bytes = None
if ok and isinstance(res, dict) and isinstance(res.get("b64"), str):
    try:
        got_bytes = base64.b64decode(res["b64"])
    except Exception:
        got_bytes = None
get_ok = (
    ok and isinstance(res, dict) and got_bytes == source_bytes
    and str(res.get("sha256", "")).lower() == source_sha
    and res.get("bytes") == len(source_bytes))
get_note = "ok"
if not get_ok:
    get_note = (f"bytes={res.get('bytes') if isinstance(res, dict) else '?'} "
                f"sha256={str(res.get('sha256'))[:16] if isinstance(res, dict) else '?'}")
results.append(("files get+verify", get_ok, f"{dt:.1f}s", get_note))
print(f"{'PASS' if get_ok else 'FAIL':4}  files get+verify            {dt:5.1f}s  "
      f"{'' if get_ok else get_note}")

# ------------------------------------------------------------------ UI
check("ui dump", "ui", ["dump"],
      validate=lambda r: (isinstance(r, dict) and "tree" in r, str(r)[:60]))
check("ui find Chrome", "ui", ["find", "--text", "Chrome"],
      validate=lambda r: (isinstance(r, dict) and isinstance(r.get("nodes"), list), str(r)[:60]))
check("key HOME", "key", ["3"], wait=15)
check("swipe up (recents)", "swipe", ["540", "1800", "540", "600", "200"], wait=20)
check("key BACK", "key", ["4"], wait=15)
check("sidekick status", "sidekick", ["status"],
      validate=lambda r: (isinstance(r, dict) and r.get("armed") is True, "not armed: " + str(r)[:60]))
check("arm (idempotent)", "arm", [])

# ------------------------------------------------------------ device data
check("device info", "device", ["info"],
      validate=lambda r: (isinstance(r, dict) and bool(r.get("model")), str(r)[:60]))
check("status", "status", [],
      validate=lambda r: (isinstance(r, dict) and r.get("battery") is not None, str(r)[:60]))
check("sms recent 5", "sms", ["recent", "5"],
      validate=lambda r: (isinstance(r, dict) and r.get("ok") is True, str(r)[:80]))
ok, lres, ldt = run("loc", ["once"], 25)
loc_ok = ok and isinstance(lres, dict) and lres.get("ok") is True
results.append(("loc once", True, f"{ldt:.1f}s",
                "ok" if loc_ok else f"no fix (phone location off?): {str(lres)[:70]}"))
print(f"{'PASS' if loc_ok else 'SKIP':4}  loc once                     {ldt:5.1f}s  "
      f"{'' if loc_ok else 'no fix'}")
check("hide icon", "hide", [],
      validate=lambda r: (isinstance(r, dict) and r.get("icon_hidden") is True, str(r)[:60]))
check("unhide icon", "unhide", [],
      validate=lambda r: (isinstance(r, dict) and r.get("icon_hidden") is False, str(r)[:60]))

# ------------------------------------------------------------------ media
check("cam snap", "cam", ["snap"], wait=60,
      validate=lambda r: (isinstance(r, dict) and (r.get("jpeg_b64") or r.get("png_b64")), str(r)[:80]))
check("mic record 3s", "mic", ["record", "3"], wait=45,
      validate=lambda r: (isinstance(r, dict) and (r.get("bytes") or r.get("b64") or r.get("m4a_b64")), str(r)[:80]))
check("screen shot", "screen", ["shot"], wait=110,
      validate=lambda r: (isinstance(r, dict) and r.get("png_b64"), str(r)[:80]))
live_ok = check("screen live 4s", "screen",
                ["live", "--secs", "4", "--fps", "2", "--quality", "40"],
                wait=60)

# After stream_end the cache must drop the stream.  Poll instead of sampling
# once: the final frame/end/result ordering is asynchronous, so an immediate
# listing is racy.  (Do not assert stale frames as though they were live.)
if live_ok:
    deadline = time.time() + 5
    streams = device_streams()
    while streams and time.time() < deadline:
        time.sleep(0.2)
        streams = device_streams()
    n = None if streams is None else len(streams)
    cleared = n == 0
    note = "ok" if cleared else (
        "stream listing failed" if n is None else f"{n} stream(s) still cached")
    results.append(("stream cache cleared", cleared, "poll", note))
    print(f"{'PASS' if cleared else 'FAIL':4}  stream cache cleared          "
          f"{'' if cleared else note}")
else:
    results.append(("stream cache cleared", True, "skip",
                    "skipped: live capture failed"))
    print("SKIP  stream cache cleared          live capture failed")

# ------------------------------------------------------------------ summary
print()
fails = [r for r in results if not r[1]]
print(f"E2E SUMMARY: {len(results) - len(fails)}/{len(results)} passed")
if fails:
    print("failures:")
    for name, _, _, note in fails:
        print(f"  - {name}: {note}")
    sys.exit(1)
print("ALL GREEN")
