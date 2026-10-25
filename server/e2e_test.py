#!/usr/bin/env python3
"""ghostctl end-to-end test suite.

Runs every implemented device verb against ONE approved, online device and
prints a pass/fail report. Pure server-side (uses ghostctl.py's rpc).

Usage: python3 e2e_test.py <device_id>
"""

import json
import subprocess
import sys
import time

DEV = sys.argv[1] if len(sys.argv) > 1 else sys.exit("usage: e2e_test.py <device_id>")
BASE = "/home/admin/code/hack/projects/ghostctl/server"


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
check("files ls", "files", ["ls", "/sdcard"],
      validate=lambda r: (isinstance(r, list) and len(r) >= 0, "") if isinstance(r, list) else (False, str(r)[:60]))
check("files put", "files", ["put", "/sdcard/e2e-test.txt", __file__],
      validate=lambda r: (isinstance(r, dict) and r.get("bytes", 0) > 0, str(r)[:60]))
ok, res, dt = run("files", ["get", "/sdcard/e2e-test.txt"])
got = None
if ok and isinstance(res, dict) and res.get("b64"):
    import base64
    try:
        got = base64.b64decode(res["b64"]).decode(errors="replace")
    except Exception:
        got = None
results.append(("files get+verify", ok and got is not None and "e2e-test.py" in got,
                 f"{dt:.1f}s", "ok" if got and "e2e-test.py" in got else f"got={str(got)[:60]}"))
print(f"{'PASS' if (ok and got and 'e2e-test.py' in got) else 'FAIL':4}  files get+verify")

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

# ------------------------------------------------------------------ media
check("cam snap", "cam", ["snap"], wait=60,
      validate=lambda r: (isinstance(r, dict) and (r.get("jpeg_b64") or r.get("png_b64")), str(r)[:80]))
check("mic record 3s", "mic", ["record", "3"], wait=45,
      validate=lambda r: (isinstance(r, dict) and (r.get("bytes") or r.get("b64") or r.get("m4a_b64")), str(r)[:80]))
check("screen shot", "screen", ["shot"], wait=110,
      validate=lambda r: (isinstance(r, dict) and r.get("png_b64"), str(r)[:80]))
check("screen live 4s", "screen", ["live", "--secs", "4", "--fps", "2", "--quality", "40"], wait=60)

# streams should show cached frames after live
ok, res, dt = run("streams")
frames = isinstance(res, dict) and res.get("streams")
# (list op, no device needed — rerun via rpc directly)
p = subprocess.run(["python3", "ghostctl.py", "streams"], capture_output=True, text=True, cwd=BASE)
sr = json.loads(p.stdout)
n = len(sr.get("streams") or [])
results.append(("streams relay cached", n > 0, "0.1s", f"{n} streams"))
print(f"{'PASS' if n > 0 else 'FAIL':4}  streams relay cached      ({n} streams)")

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
