#!/usr/bin/env python3
"""ghostctl core daemon.

Device-facing WebSocket listener (default 0.0.0.0:8443) + admin JSON-RPC
listener on localhost (default 127.0.0.1:8444, used by ghostctl.py CLI).

Device protocol (JSON messages over WebSocket):
  client -> server: {"type":"hello","device_id":..,"battery":..,"ip":..,
                     "version_code":..,"enroll_key":..,"token":..}
                     (version_code + last two optional)
                    {"type":"ping"}
                    {"type":"info","battery":..,"ip":..,"version_code":..}
                    {"type":"result","cmd_id":..,"ok":true,"result":..}
                    {"type":"stream_start","stream":..,"encoding":"mjpeg","meta":..}
                    {"type":"stream_end","stream":..,"frames":N,"reason":..}
                    binary: "<json header>\\n<payload>" (MediaBus framing —
                    {"type":"frame","stream":..,"seq":..,"len":N} for live
                    media; file_chunk/file_end for device->server file streams)
  server -> client: {"type":"hello_ack","status":"approved|pending|denied",
                     "token":..}   (token echoed/issued on hello)
                    {"type":"cmd","cmd_id":..,"action":..,"args":[..]}
                    binary: file_chunk frames + text file_end (files put —
                    the FileReceiver protocol, 64 KB chunks + sha256)

Device authentication: a hello carrying a valid "token" for an APPROVED
device is trusted (status "approved", token echoed back). Otherwise a
correct "enroll_key" enrolls/re-enrolls the device as PENDING with a
fresh 32-hex token (status "pending") — pending devices may ping and
send command results but receive no commands until `ghostctl approve`.
An approved device may not re-enroll with the enroll key; that requires
an explicit revoke first. Anything else is denied and the connection closed.

Admin authentication: every admin request must carry "token" matching
GHOSTCTL_ADMIN_TOKEN or server/admin.key (created mode 600 on first use).
A non-loopback admin bind additionally requires GHOSTCTL_ADMIN_TOKEN.
list/pending responses redact device bearer tokens; history results are
summarised/redacted before persistence.

Flood protection: at most MAX_PENDING pending devices are kept (new
enrollments beyond that are denied + logged), at most MAX_PER_IP
concurrent device connections per source IP (the oldest socket is evicted
at the cap), and all denials are logged to server/daemon.log.

State: server/state.json   (device registry: approved + token per device)
        server/history.json (command history, capped)
        server/apks/        (APK store for `update` pushes, gitignored)
        server/uploads/     (source files for admin files_put)
        server/enroll.key   (enrollment key, chmod 600, gitignored)
        server/admin.key    (admin token, chmod 600, gitignored)
        server/daemon.log    (rolling daemon log incl. denials)
"""
import argparse
import asyncio
import base64
import contextlib
import hashlib
import hmac
import json
import math
import os
import re
import secrets
import sys
import time
import uuid
from collections import deque
from pathlib import Path

import envfile

try:
    import websockets
except ImportError:
    sys.stderr.write("missing dependency: pip install --user websockets\n")
    raise

BASE = Path(__file__).resolve().parent
STATE_FILE = BASE / "state.json"
HISTORY_FILE = BASE / "history.json"
APK_DIR = BASE / "apks"
UPLOAD_DIR = BASE / "uploads"
ENROLL_KEY_FILE = BASE / "enroll.key"
ADMIN_KEY_FILE = BASE / "admin.key"
CONFIG_FILE = BASE / "config.json"
ENV_FILE = BASE / ".env"
LOG_FILE = BASE / "daemon.log"

# Flood protection limits (editable live via `ghostctl config set`;
# startup overrides via .env GHOST_MAX_PENDING / GHOST_MAX_PER_IP):
MAX_PENDING = 50    # total pending (unapproved) devices kept in state
MAX_PER_IP = 5      # concurrent device connections per source IP
LOG_MAX_BYTES = 512 * 1024   # daemon.log is trimmed beyond this (rolling)
MAX_CONFIG_VALUE = 10000

# Bounds requested by the server audit.
MAX_HISTORY = 1000
MAX_DEVICE_ID_LEN = 128
MAX_NAME_LEN = 128
MAX_ARG_LEN = 4096
MAX_ARGS = 100
MAX_DEST_LEN = 1024
MAX_ADMIN_LINE = 1024 * 1024
# A 32 MiB download becomes ~43 MiB of base64 inside the JSON response.
MAX_ADMIN_RESPONSE = 64 * 1024 * 1024
MAX_WS_SEND_TIMEOUT = 5.0
PRE_HELLO_TIMEOUT = 10.0
PRE_HELLO_MAX_MESSAGES = 3
LATE_RESULT_TTL = 60.0
BROADCAST_CONCURRENCY = 16

# A self-update replaces the ghostd process, so the command's result normally
# disappears with the old socket. Keep the update pending long enough for the
# replacement process to reconnect and report its new version_code.
SELF_UPDATE_WAIT = 300.0

# A whole `update` op is push + install; bound both stages with one deadline.
UPDATE_PUSH_WAIT = 180.0
UPDATE_TOTAL_WAIT = UPDATE_PUSH_WAIT + SELF_UPDATE_WAIT
SIDEKICK_INSTALL_WAIT = 120.0

# A `destroy` that kills the replaced process never delivers a result.  Keep a
# bounded removal marker, and only purge the record after this grace period has
# elapsed without a fresh hello/reconnect from the device.
REMOVE_GRACE = 15.0
REMOVE_PENDING_TTL = 120.0
MAX_REMOVE_PENDING = 100

# Server -> device push cap (APK or admin upload).  Read before taking the
# global push lock, so a staged source deleted while queued still streams.
MAX_PUSH_BYTES = 64 * 1024 * 1024


def load_dotenv(path=ENV_FILE):
    """Apply server/.env using the shared parser.

    Existing environment variables win (classic dotenv precedence).  Kept as a
    public wrapper because the daemon historically exposed this name.
    """
    envfile.apply_dotenv(Path(path))


# Tests set GHOSTCTL_TEST so they never read a real server/.env.
if not os.environ.get("GHOSTCTL_TEST"):
    load_dotenv()


# Every verb the client CommandHandler (client-main) or the PROJECT.md v1
# command spec defines. Unknown actions are rejected with a clean error.
ACTIONS = {
    # media capture
    "screen", "cam", "mic",
    # system control
    "brightness", "volume", "wake",
    # UI / sidekick-forwarded verbs
    "ui", "tap", "longclick", "type", "key", "swipe", "unlock",
    "arm", "disarm", "sidekick",
    # data
    "files", "sms", "shell", "loc", "device",
    # lifecycle
    "update", "hide", "unhide", "status", "restart",
    # owner-authorized removal of ghostd + ghosta only (never personal data)
    "destroy",
}

# Where `update` pushes land on the device (client SidekickInstaller default
# for the sidekick; ghostd self-update reads its own slot).
APK_DEST = {"sidekick": "/sdcard/Download/ghosta.apk",
            "self": "/sdcard/Download/ghostd.apk"}

# Server->device file push chunk size (client FileReceiver protocol).
PUSH_CHUNK = 64 * 1024

# Device->server `files get` transfer: cap buffered bytes so one download
# cannot exhaust daemon memory.  The same figure bounds the verified result.
MAX_DOWNLOAD_BYTES = 32 * 1024 * 1024

# Stream cache limits.
MAX_FRAME_BYTES = 4 * 1024 * 1024
STREAM_TTL = 15.0
MAX_STREAMS_PER_DEVICE = 2
MAX_STREAMS_GLOBAL = 20


# ---------- validation / sanitisation helpers ----------

_CONTROL_RE = re.compile(r"[\x00-\x08\x0b-\x1f\x7f]")
_B64_CHARS = set(
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=\r\n")


def sanitize_log(value):
    if not isinstance(value, str):
        value = str(value)
    value = value.replace("\n", "\\n").replace("\r", "\\r")
    return _CONTROL_RE.sub("?", value)


def _safe_compare(a, b):
    try:
        return hmac.compare_digest(str(a).encode("utf-8"),
                                   str(b).encode("utf-8"))
    except (AttributeError, TypeError):
        return False


def _valid_device_id(did):
    return (isinstance(did, str) and 1 <= len(did) <= MAX_DEVICE_ID_LEN
            and all(c.isprintable() for c in did))


def _valid_name(name):
    if not isinstance(name, str):
        return False
    name = name.strip()
    return 1 <= len(name) <= MAX_NAME_LEN and all(c.isprintable() for c in name)


def _valid_label(label):
    return (isinstance(label, str) and 1 <= len(label) <= MAX_NAME_LEN
            and all(c.isprintable() for c in label))


def _valid_secret(secret):
    return (isinstance(secret, str) and 1 <= len(secret) <= MAX_NAME_LEN
            and bool(secret.strip())
            and all(c.isprintable() for c in secret))


def _env_secret(name):
    """Non-blank environment secret, or None.

    Whitespace-only values are treated as unset so a malformed .env falls back
    to the mode-600 key file (or a freshly generated key) instead of silently
    authenticating against "   ".
    """
    raw = os.environ.get(name)
    if raw is None:
        return None
    value = raw.strip()
    return value or None


def _valid_wait(wait):
    try:
        w = float(wait)
    except (TypeError, ValueError):
        return False
    return math.isfinite(w) and 0.1 <= w <= 300.0


def _valid_args(args):
    if not isinstance(args, list) or len(args) > MAX_ARGS:
        return False
    for a in args:
        if isinstance(a, str):
            if len(a) > MAX_ARG_LEN:
                return False
        elif isinstance(a, (int, float, bool)) or a is None:
            continue
        else:
            return False
    return True


def _valid_dest(dest):
    return (isinstance(dest, str) and 1 <= len(dest) <= MAX_DEST_LEN
            and all(c.isprintable() for c in dest))


def _parse_version_code(value):
    """Return a numeric Android versionCode, or None when absent/invalid."""
    if isinstance(value, bool) or not isinstance(value, int):
        return None
    return value if value >= 0 else None


def _is_self_update(action, args):
    """True for the `update self [dest]` command whose process is replaced."""
    return (action == "update" and isinstance(args, list) and args
            and args[0] == "self")


def _valid_config_value(key, value):
    try:
        v = int(value)
    except (TypeError, ValueError):
        return None
    if key in ("max_pending", "max_per_ip") and 1 <= v <= MAX_CONFIG_VALUE:
        return v
    return None


def _valid_actor(value):
    return value is None or (isinstance(value, str) and len(value) <= 256)


def _check_admin_host(host):
    """Non-loopback admin binds must supply an explicit environment token."""
    if host in ("127.0.0.1", "::1", "localhost"):
        return True
    return bool(_env_secret("GHOSTCTL_ADMIN_TOKEN"))


_REDACT_KEYS = {"token", "enroll_key", "pin", "password", "secret", "bearer",
                "authorization", "frame_b64", "b64", "image", "screenshot",
                "png", "jpeg"}
_MAX_STR = 4096
_MAX_LIST = 100
_MAX_DEPTH = 5


def _redact_str(s):
    if len(s) > 8192 and all(c in _B64_CHARS for c in s):
        return "[base64 omitted]"
    if len(s) > _MAX_STR:
        return s[:_MAX_STR] + "...[truncated]"
    return s


def _sanitize_for_history(value, depth=0):
    """Recursively summarise/truncate/redact a persisted result value.

    The full result is still returned directly to the CLI by dispatch(); only
    the history copy goes through this function.
    """
    if depth > _MAX_DEPTH:
        return "[truncated]"
    if isinstance(value, dict):
        out = {}
        for k, v in value.items():
            lk = str(k).lower()
            if (lk in _REDACT_KEYS or lk.endswith("_token")
                    or lk.endswith("_key") or "pin" in lk or "secret" in lk):
                out[k] = "***"
            else:
                out[k] = _sanitize_for_history(v, depth + 1)
        return out
    if isinstance(value, (list, tuple)):
        return [_sanitize_for_history(v, depth + 1) for v in value[:_MAX_LIST]]
    if isinstance(value, str):
        return _redact_str(value)
    return value


def _history_entry(entry):
    sanitized = dict(entry)
    action = sanitized.get("action")
    if "args" in sanitized:
        if action == "unlock":
            sanitized["args"] = ["***"]
        else:
            sanitized["args"] = _sanitize_for_history(sanitized["args"])
    if "result" in sanitized:
        sanitized["result"] = _sanitize_for_history(sanitized["result"])
    return sanitized


def new_token():
    """32-hex-char random device token."""
    return secrets.token_hex(16)


# ---------- key files ----------

def _read_key_file(path):
    try:
        st = path.stat()
        if st.st_mode & 0o077:
            with contextlib.suppress(OSError):
                path.chmod(0o600)
        key = path.read_text().strip()
        return key or None
    except (OSError, UnicodeDecodeError):
        return None


def _create_key_file(path):
    key = new_token()
    old_umask = os.umask(0o077)
    try:
        try:
            fd = os.open(str(path), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        except FileExistsError:
            existing = _read_key_file(path)
            if existing:
                return existing
            with contextlib.suppress(OSError):
                path.unlink()
            fd = os.open(str(path), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(key + "\n")
            f.flush()
            os.fsync(f.fileno())
    finally:
        os.umask(old_umask)
    return key


def load_enroll_key():
    """Enrollment key: env GHOST_ENROLL_KEY first, then server/enroll.key.

    A missing or empty existing file is regenerated with a random 32-hex
    value written mode 600."""
    env = _env_secret("GHOST_ENROLL_KEY")
    if env:
        return env
    key = _read_key_file(ENROLL_KEY_FILE)
    if key:
        return key
    with contextlib.suppress(OSError):
        ENROLL_KEY_FILE.unlink()
    return _create_key_file(ENROLL_KEY_FILE)


def load_admin_key():
    """Admin token: env GHOSTCTL_ADMIN_TOKEN first, then server/admin.key."""
    env = _env_secret("GHOSTCTL_ADMIN_TOKEN")
    if env:
        return env
    key = _read_key_file(ADMIN_KEY_FILE)
    if key:
        return key
    with contextlib.suppress(OSError):
        ADMIN_KEY_FILE.unlink()
    return _create_key_file(ADMIN_KEY_FILE)


def log(msg):
    """Timestamped line to stdout + the rolling daemon.log (trimmed)."""
    line = f"{time.strftime('%Y-%m-%d %H:%M:%S')} {sanitize_log(msg)}"
    print(line, flush=True)
    try:
        if LOG_FILE.exists() and LOG_FILE.stat().st_size > LOG_MAX_BYTES:
            lines = LOG_FILE.read_text().splitlines()
            LOG_FILE.write_text("\n".join(lines[-1000:]) + "\n")
        with LOG_FILE.open("a") as f:
            f.write(line + "\n")
    except OSError:
        pass


def is_jpeg(data):
    return bytes(data[:2]) == b"\xff\xd8"


def parse_frame(data):
    """MediaBus framing: UTF-8 JSON header + b'\\n' + payload."""
    if isinstance(data, str):
        return None
    nl = data.find(b"\n")
    if nl <= 0 or nl > 64 * 1024:
        return None
    try:
        header = json.loads(data[:nl].decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None
    if not isinstance(header, dict):
        return None
    return header, data[nl + 1:]


# ---------- stream cache ----------

class StreamCache:
    """Bounded latest-frame cache for live media streams.

    Only approved, currently-connected devices are allowed to populate this
    cache (enforced by the caller). Frames older than TTL are ignored and
    pruned. The newest JPEG across all streams for a device is returned even
    when another stream (e.g. PCM audio) is newer.
    """

    MAX_STREAMS_GLOBAL = MAX_STREAMS_GLOBAL
    MAX_STREAMS_PER_DEVICE = MAX_STREAMS_PER_DEVICE
    FRAMES_PER_STREAM = 2
    TTL = STREAM_TTL
    MAX_FRAME_BYTES = MAX_FRAME_BYTES

    def __init__(self):
        self.streams = {}  # (device_id, stream) -> {encoding, frames deque}

    def _prune(self):
        now = time.time()
        for key in list(self.streams):
            e = self.streams[key]
            e["frames"] = deque(
                ((ts, payload, b64) for ts, payload, b64 in e["frames"]
                 if now - ts <= self.TTL),
                maxlen=self.FRAMES_PER_STREAM)
            if not e["frames"]:
                del self.streams[key]

    def _last_ts(self, key):
        frames = self.streams[key]["frames"]
        return frames[-1][0] if frames else 0.0

    def _evict_oldest(self, device_id=None):
        keys = [k for k in self.streams if device_id is None or k[0] == device_id]
        if not keys:
            return
        oldest = min(keys, key=self._last_ts)
        del self.streams[oldest]

    def _ensure_capacity(self, device_id):
        self._prune()
        while len(self.streams) >= self.MAX_STREAMS_GLOBAL:
            self._evict_oldest()
        device_keys = [k for k in self.streams if k[0] == device_id]
        while len(device_keys) >= self.MAX_STREAMS_PER_DEVICE:
            oldest = min(device_keys, key=self._last_ts)
            del self.streams[oldest]
            device_keys = [k for k in self.streams if k[0] == device_id]

    def set_encoding(self, device_id, stream, encoding):
        if not _valid_label(stream) or not isinstance(encoding, str) or not encoding:
            return
        key = (device_id, stream)
        e = self.streams.get(key)
        if e is None:
            self._ensure_capacity(device_id)
            e = self.streams[key] = {"encoding": None,
                                     "frames": deque(maxlen=self.FRAMES_PER_STREAM)}
        e["encoding"] = encoding

    def put(self, device_id, stream, payload, encoding=None):
        if not _valid_label(stream):
            return
        if (not isinstance(payload, (bytes, bytearray))
                or len(payload) > self.MAX_FRAME_BYTES):
            return
        key = (device_id, stream)
        e = self.streams.get(key)
        if e is None:
            self._ensure_capacity(device_id)
            e = self.streams[key] = {
                "encoding": encoding,
                "frames": deque(maxlen=self.FRAMES_PER_STREAM),
            }
        if encoding:
            e["encoding"] = encoding
        b64 = base64.b64encode(payload).decode() if is_jpeg(payload) else None
        e["frames"].append((time.time(), bytes(payload), b64))

    def latest(self, device_id, stream=None, jpeg_only=True):
        """Newest frame for a device (or a named stream).

        Returns (stream_name, encoding, ts, payload, b64) or None. When
        stream is None and jpeg_only is true, the newest JPEG across all of
        the device's streams wins, even if another stream is newer.
        """
        self._prune()
        if stream is not None:
            key = (device_id, stream)
            if key not in self.streams:
                return None
            e = self.streams[key]
            if not e["frames"]:
                return None
            ts, payload, b64 = e["frames"][-1]
            if jpeg_only and not is_jpeg(payload):
                return None
            return key[1], e.get("encoding"), ts, payload, b64
        best = None
        for key, e in self.streams.items():
            if key[0] != device_id or not e["frames"]:
                continue
            ts, payload, b64 = e["frames"][-1]
            if jpeg_only and not is_jpeg(payload):
                continue
            if best is None or ts > best[2]:
                best = (key[1], e.get("encoding"), ts, payload, b64)
        return best

    def drop(self, device_id, stream):
        self.streams.pop((device_id, stream), None)

    def drop_device(self, device_id):
        for k in [k for k in self.streams if k[0] == device_id]:
            del self.streams[k]

    def listing(self):
        self._prune()
        out = []
        for (did, stream), e in sorted(self.streams.items()):
            out.append({
                "device_id": did, "stream": stream,
                "encoding": e.get("encoding"),
                "last_frame": self._last_ts((did, stream)),
                "frames_cached": len(e["frames"]),
            })
        return out


# ---------- JSON persistence ----------

def _move_bad(path):
    bad = path.with_suffix(path.suffix + ".bad")
    try:
        path.replace(bad)
        log(f"corrupt JSON in {path.name}, moved to {bad.name}")
    except OSError as e:
        log(f"corrupt JSON in {path.name}, could not move to .bad: {e}")


def load_json(path, default):
    try:
        raw = path.read_text()
    except FileNotFoundError:
        return default
    except (OSError, UnicodeDecodeError):
        _move_bad(path)
        return default
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        _move_bad(path)
        return default
    if not isinstance(data, type(default)):
        _move_bad(path)
        return default
    return data


def _secure_write(path, data, mode=0o600):
    """Atomic mode-600 write with an explicit umask and fsync where possible."""
    tmp = path.with_suffix(path.suffix + ".tmp")
    old_umask = os.umask(0o077)
    try:
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(data)
            f.flush()
            os.fsync(f.fileno())
        os.chmod(tmp, mode)
        tmp.replace(path)
        try:
            dfd = os.open(str(path.parent), os.O_RDONLY)
            try:
                os.fsync(dfd)
            finally:
                os.close(dfd)
        except OSError:
            pass
    except OSError:
        with contextlib.suppress(OSError):
            tmp.unlink()
        raise
    finally:
        os.umask(old_umask)


def save_json(path, data):
    """Best-effort atomic save. Persistence failure must not break handling."""
    try:
        _secure_write(path, json.dumps(data, indent=2, sort_keys=True))
        return True
    except OSError as e:
        log(f"failed to save {path.name}: {e}")
        return False


def _load_config():
    cfg = load_json(CONFIG_FILE, {})
    if not isinstance(cfg, dict):
        cfg = {}

    def get(key, default):
        env_name = ("GHOST_MAX_PENDING" if key == "max_pending"
                    else "GHOST_MAX_PER_IP")
        raw = os.environ.get(env_name, cfg.get(key, default))
        try:
            v = int(raw)
        except (TypeError, ValueError):
            v = default
        if not 1 <= v <= MAX_CONFIG_VALUE:
            v = default
        return v

    return {"max_pending": get("max_pending", MAX_PENDING),
            "max_per_ip": get("max_per_ip", MAX_PER_IP)}


def _ensure_private_dir(path):
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    with contextlib.suppress(OSError):
        path.chmod(0o700)


class Daemon:
    def __init__(self):
        _ensure_private_dir(APK_DIR)
        _ensure_private_dir(UPLOAD_DIR)
        self.devices = load_json(STATE_FILE, {})  # device_id -> status dict
        if not isinstance(self.devices, dict):
            self.devices = {}
        # connections die with the process: never trust a persisted "online"
        for d in list(self.devices):
            rec = self.devices[d]
            if not _valid_device_id(d) or not isinstance(rec, dict):
                del self.devices[d]
                continue
            rec["online"] = False
        loaded_history = load_json(HISTORY_FILE, [])
        if not isinstance(loaded_history, list):
            loaded_history = []
        self.history = []
        for raw_entry in loaded_history:
            if not isinstance(raw_entry, dict):
                continue
            entry = _history_entry(raw_entry)
            # No command wait survives a daemon restart.  Anything still
            # pending/reconnecting is historical, not live.
            if entry.get("status") in ("pending", "reconnecting"):
                entry["status"] = "interrupted"
                update = entry.get("update")
                if isinstance(update, dict) and \
                        update.get("state") in ("installing", "reconnecting"):
                    update["state"] = "interrupted"
            self.history.append(entry)
        self.history = self.history[-MAX_HISTORY:]
        self.conns = {}    # device_id -> websocket
        self.pending = {}  # cmd_id -> {"event","result","ok","device_id","ws"}
        # device_id -> bounded removal marker (see admin `remove`).
        self.remove_pending = {}
        # (device_id, cmd_id, ws) -> in-flight `files get` receive state.
        # Bounded by MAX_DOWNLOAD_BYTES and removed on every terminal path.
        self.downloads = {}
        self._late_results = {}  # cmd_id -> (expires, entry, history entry)
        self.streams = StreamCache()
        self.push_lock = asyncio.Lock()  # serialize file pushes (see push_file)
        self.cmd_locks = {}  # device_id -> asyncio.Lock
        self.broadcast_sem = asyncio.Semaphore(BROADCAST_CONCURRENCY)
        # flood-protection caps: config.json base, .env override at start,
        # live-editable via the admin op "config set" (persisted instantly)
        self.config = _load_config()
        self.enroll_key = load_enroll_key()
        self.admin_token = load_admin_key()
        self.ip_conns = {}  # source ip -> {ws: created_ts}
        log(f"enrollment key loaded (caps: {self.config})")

    # ---------- persistence ----------
    def persist_state(self):
        save_json(STATE_FILE, self.devices)

    def persist_history(self):
        save_json(HISTORY_FILE, self.history)

    def _trim_history(self):
        if len(self.history) > MAX_HISTORY:
            del self.history[:-MAX_HISTORY]

    def _prune_late_results(self):
        now = time.time()
        for k in [k for k, (exp, _, _) in self._late_results.items()
                  if now > exp]:
            del self._late_results[k]

    def _device_lock(self, device_id):
        lock = self.cmd_locks.get(device_id)
        if lock is None:
            lock = self.cmd_locks[device_id] = asyncio.Lock()
        return lock

    # ---------- bounded removal marker ----------
    def _drop_remove_pending(self, device_id, cancel_grace=True):
        entry = self.remove_pending.pop(device_id, None)
        if entry is not None and cancel_grace:
            task = entry.get("grace_task")
            if task is not None and not task.done():
                task.cancel()
        return entry

    def _prune_remove_pending(self):
        now = time.time()
        for did, entry in list(self.remove_pending.items()):
            if now - entry.get("created", 0) > REMOVE_PENDING_TTL:
                self._drop_remove_pending(did)
        while len(self.remove_pending) > MAX_REMOVE_PENDING:
            oldest = min(self.remove_pending,
                         key=lambda d: self.remove_pending[d].get("created", 0))
            self._drop_remove_pending(oldest)

    def _begin_remove_pending(self, device_id, ws):
        """Mark a removal as requested before dispatch, bounded in time/count."""
        self._prune_remove_pending()
        self._drop_remove_pending(device_id)
        entry = {"created": time.time(), "device_id": device_id, "ws": ws,
                 "grace_task": None}
        self.remove_pending[device_id] = entry
        self._prune_remove_pending()
        return entry

    def _schedule_remove_grace(self, device_id):
        """After the expected destroy disconnect, finalize only if the device
        does not hello/reconnect within the grace window."""
        entry = self.remove_pending.get(device_id)
        if entry is None:
            return None
        task = entry.get("grace_task")
        if task is not None and not task.done():
            return task
        task = asyncio.create_task(
            self._finalize_remove_after_grace(device_id, entry, REMOVE_GRACE))
        entry["grace_task"] = task
        return task

    async def _finalize_remove_after_grace(self, device_id, entry,
                                           grace=REMOVE_GRACE):
        """Purge a removed device only when the grace window stayed quiet.

        A hello/reconnect clears the marker first (see _clear_remove_pending),
        so a surviving device is never deleted from the server registry just
        because its destroy command disconnected the old socket.
        """
        if grace > 0:
            await asyncio.sleep(grace)
        if self.remove_pending.get(device_id) is not entry:
            return False
        if self.conns.get(device_id) is not None:
            return False
        del self.remove_pending[device_id]
        await self._purge_device(device_id)
        log(f"device {sanitize_log(device_id)} removal finalized after "
            f"{grace:g}s without a reconnect")
        return True

    def _record_remove_failure(self, device_id, reason):
        for entry in reversed(self.history):
            if entry.get("device_id") != device_id:
                continue
            if entry.get("action") != "destroy":
                continue
            if entry.get("status") == "ok":
                return
            entry["status"] = "error"
            entry["result"] = {"error": reason}
            self.persist_history()
            return

    def _clear_remove_pending(self, device_id, reason):
        """A hello arrived before grace expired: the app survived removal."""
        entry = self._drop_remove_pending(device_id)
        if entry is None:
            return False
        for rec in list(self.pending.values()):
            if rec.get("device_id") != device_id:
                continue
            if rec.get("action") != "destroy":
                continue
            if not rec.get("event").is_set():
                rec["ok"] = False
                rec["result"] = {"error": reason}
                rec["event"].set()
        self._record_remove_failure(device_id, reason)
        log(f"device {sanitize_log(device_id)} reconnected during removal; "
            f"not purged ({reason})")
        return True

    # ---------- self-update reconciliation ----------
    def _set_update_state(self, rec, state):
        """Mirror an update lifecycle state into the pending rec/history."""
        rec["update_state"] = state
        update = rec.get("update")
        if isinstance(update, dict):
            update["state"] = state
        hist = rec.get("history")
        if isinstance(hist, dict):
            hist_update = hist.get("update")
            if isinstance(hist_update, dict):
                hist_update["state"] = state
            if state == "reconnecting":
                hist["status"] = "reconnecting"
            elif state == "installed":
                hist["status"] = "ok"

    def _self_update_error(self, rec, wait):
        """Actionable terminal error when version-code reconciliation fails."""
        previous = rec.get("previous_version_code")
        latest = rec.get("last_version_code")
        hint = "Check the install prompt/session on the device and retry."
        if previous is None:
            message = ("self-update not verified: the previous version_code "
                       "is unknown, so a strictly greater installed code "
                       "cannot be proven. " + hint)
        elif rec.get("reconnected") and latest is None:
            message = ("self-update not verified: the device reconnected "
                       f"without a numeric version_code (previous "
                       f"{previous}). " + hint)
        elif rec.get("reconnected"):
            message = (f"self-update failed: the device reconnected with "
                       f"version_code {latest}, not greater than the previous "
                       f"{previous}; the new build was not installed. " + hint)
        else:
            message = (f"self-update timed out after {wait:g}s waiting for the "
                       f"device to reconnect with version_code greater than "
                       f"{previous}. " + hint)
        return {"error": message, "target": "self",
                "previous_version_code": previous,
                "version_code": latest, "reconciled": False}

    def _reconcile_self_update(self, device_id, version_code, ws):
        """Bind/complete a pending self-update from a fresh device hello.

        Only a strictly greater numeric versionCode completes the update.
        Same/lower/missing codes keep waiting for the bounded dispatch
        timeout. Rebinding the rec to the new socket also fences late results
        from the replaced process's old socket.
        """
        matched = False
        for rec in list(self.pending.values()):
            if not rec.get("self_update") or not rec.get("sent"):
                continue
            if rec.get("device_id") != device_id:
                continue
            if rec["event"].is_set():
                continue
            matched = True
            rec["ws"] = ws
            rec["reconnected"] = True
            rec["last_version_code"] = version_code
            update = rec.get("update")
            if isinstance(update, dict):
                update["last_version_code"] = version_code
            hist = rec.get("history")
            if isinstance(hist, dict):
                hist_update = hist.get("update")
                if isinstance(hist_update, dict):
                    hist_update["last_version_code"] = version_code
            previous = rec.get("previous_version_code")
            if (version_code is not None and previous is not None
                    and version_code > previous):
                rec["ok"] = True
                rec["result"] = {
                    "ok": True, "target": "self", "reconciled": True,
                    "previous_version_code": previous,
                    "version_code": version_code,
                    "message": ("self-update installed: version_code "
                                f"{previous} -> {version_code}"),
                }
                self._set_update_state(rec, "installed")
                rec["event"].set()
                log(f"device {sanitize_log(device_id)} self-update "
                    f"reconciled: version_code {previous} -> {version_code}")
            else:
                self._set_update_state(rec, "reconnecting")
                log(f"device {sanitize_log(device_id)} reconnected during "
                    f"self-update but version_code {version_code!r} is not "
                    f"greater than {previous!r}; still waiting")
        if matched:
            self.persist_history()
        return matched

    def _reconcile_history_update(self, device_id, version_code):
        """Complete a durable self-update entry after a daemon restart.

        Only the newest self-update history entry is considered.  It is
        upgraded to installed when the fresh hello reports a strictly greater
        numeric versionCode; a newer update attempt is never bypassed by an
        older interrupted one.
        """
        if version_code is None:
            return False
        for entry in reversed(self.history):
            if entry.get("device_id") != device_id:
                continue
            update = entry.get("update")
            if not isinstance(update, dict) or update.get("target") != "self":
                continue
            if (entry.get("status") == "ok"
                    or update.get("state") == "installed"):
                return False
            # A timed-out/failed attempt can still complete late (the device
            # reconnects after the daemon's wait expired).  Only the newest
            # update entry is eligible, so an older interrupted attempt is
            # never credited over a newer one.
            if entry.get("status") not in (
                    "pending", "reconnecting", "interrupted", "error",
                    "timeout", "failed"):
                return False
            previous = _parse_version_code(
                update.get("previous_version_code"))
            if previous is None or version_code <= previous:
                return False
            entry["status"] = "ok"
            entry["result"] = {
                "ok": True, "target": "self", "reconciled": True,
                "previous_version_code": previous,
                "version_code": version_code,
                "message": ("self-update installed after reconnect: "
                            f"version_code {previous} -> {version_code}"),
            }
            update["state"] = "installed"
            update["last_version_code"] = version_code
            self.persist_history()
            log(f"device {sanitize_log(device_id)} historical self-update "
                f"reconciled: version_code {previous} -> {version_code}")
            return True
        return False

    # ---------- device registry ----------
    def touch(self, device_id, online, extra=None, persist=True):
        d = self.devices.get(device_id)
        if d is None:
            d = self.devices[device_id] = {}
        d.update({"last_seen": time.time()})
        if online is not None:
            d["online"] = online
        if extra:
            clean = {}
            for k, v in extra.items():
                if isinstance(v, str):
                    clean[k] = sanitize_log(v[:256])
                elif isinstance(v, (int, float, bool)) or v is None:
                    clean[k] = v
                else:
                    clean[k] = sanitize_log(str(v)[:256])
            d.update(clean)
        if persist:
            self.persist_state()

    def device_list(self):
        return self.devices

    def _redact_devices(self):
        out = {}
        for did, rec in self.devices.items():
            if isinstance(rec, dict):
                r = dict(rec)
                if "token" in r:
                    r["token"] = "***"
                out[did] = r
            else:
                out[did] = rec
        return out

    def pending_ids(self):
        """device_ids currently in pending (not approved) state."""
        return [d for d, rec in self.devices.items()
                if isinstance(rec, dict) and not rec.get("approved")]

    def approved(self, device_id):
        rec = self.devices.get(device_id)
        return bool(isinstance(rec, dict) and rec.get("approved"))

    # ---------- bounded websocket sends ----------
    async def _send_ws(self, ws, obj, timeout=MAX_WS_SEND_TIMEOUT):
        try:
            await asyncio.wait_for(ws.send(json.dumps(obj)), timeout=timeout)
        except asyncio.TimeoutError:
            with contextlib.suppress(Exception):
                await asyncio.wait_for(ws.close(), timeout=2)
            raise ConnectionError("send timeout")

    async def _send_bytes(self, ws, data, timeout=MAX_WS_SEND_TIMEOUT):
        try:
            await asyncio.wait_for(ws.send(data), timeout=timeout)
        except asyncio.TimeoutError:
            with contextlib.suppress(Exception):
                await asyncio.wait_for(ws.close(), timeout=2)
            raise ConnectionError("send timeout")

    # ---------- command router ----------
    async def dispatch(self, device_id, action, args, wait=10.0, actor=None,
                       source=None, persist=True, update_meta=None):
        """Send one command to one device, wait for its result.

        `update_meta` identifies the pushed artifact for a self-update.  A
        self-update command survives its process replacement: a disconnect
        keeps it pending and a later hello with a strictly greater numeric
        version_code completes it (see _reconcile_self_update).
        """
        if action not in ACTIONS:
            raise ValueError(f"unknown action {action!r}")
        if not _valid_wait(wait):
            raise ValueError("wait must be between 0.1 and 300 seconds")
        if not _valid_args(args):
            raise ValueError("invalid args")
        if device_id not in self.devices:
            raise ValueError(f"unknown device {device_id}")
        if not self.approved(device_id):
            raise ValueError(f"device {device_id} pending approval")
        ws = self.conns.get(device_id)
        if ws is None:
            raise ConnectionError(f"device {device_id} is offline")
        self._prune_late_results()
        cmd_id = uuid.uuid4().hex[:12]
        device_rec = self.devices.get(device_id)
        if not isinstance(device_rec, dict):
            device_rec = {}
        update_info = None
        if _is_self_update(action, args):
            update_info = {
                "cmd_id": cmd_id,
                "target": "self",
                "dest": args[1] if len(args) > 1 else None,
                "previous_version_code": _parse_version_code(
                    device_rec.get("version_code")),
                "previous_version": device_rec.get("app_version"),
                "state": "installing",
            }
            if isinstance(update_meta, dict):
                for k, v in update_meta.items():
                    update_info.setdefault(k, v)
        rec = {"event": asyncio.Event(), "result": None, "ok": False,
               "device_id": device_id, "ws": ws, "action": action,
               "args": args}
        if update_info is not None:
            rec["self_update"] = True
            rec["update"] = update_info
            rec["update_state"] = "installing"
            rec["previous_version_code"] = update_info["previous_version_code"]
        entry = {"cmd_id": cmd_id, "ts": time.time(), "device_id": device_id,
                 "action": action, "args": args, "status": "pending"}
        if update_info is not None:
            entry["update"] = dict(update_info)
        if actor:
            entry["actor"] = actor
        if source:
            entry["source"] = source
        hist_entry = _history_entry(entry)
        rec["history"] = hist_entry
        send_error = None
        async with self._device_lock(device_id):
            self.pending[cmd_id] = rec
            self.history.append(hist_entry)
            self._trim_history()
            if update_info is not None and persist:
                # Make the update identity/previous version durable before the
                # process can be replaced.
                self.persist_history()
            try:
                # Re-check after any await: a reconnect may have replaced ws.
                if self.conns.get(device_id) is not ws:
                    raise ConnectionError(f"device {device_id} connection changed")
                # Mark the command as sent BEFORE the send await.  A disconnect
                # or replacement hello can race the send; it must already see
                # a dispatched self-update/removal rather than fail the wait.
                rec["sent"] = True
                await self._send_ws(ws, {"type": "cmd", "cmd_id": cmd_id,
                                          "action": action, "args": args})
            except asyncio.CancelledError:
                rec["sent"] = False
                entry["status"] = "cancelled"
                entry["result"] = {"error": "cancelled"}
                self._finish_dispatch(cmd_id, device_id, ws, entry, hist_entry,
                                      update_info, persist)
                raise
            except Exception as e:
                rec["sent"] = False
                send_error = e
                entry["status"] = "error"
                entry["result"] = {"error": str(e)}
        # The per-device lock only covers registration + send.  Waiting for
        # the result must not block commands to the same device.
        try:
            if send_error is None:
                try:
                    await asyncio.wait_for(rec["event"].wait(), timeout=wait)
                    entry["status"] = "ok" if rec["ok"] else "error"
                    entry["result"] = rec["result"]
                except asyncio.TimeoutError:
                    if update_info is not None:
                        entry["status"] = "error"
                        entry["result"] = self._self_update_error(rec, wait)
                    else:
                        entry["status"] = "timeout"
                        entry["result"] = {"error": "timeout"}
                        self._late_results[cmd_id] = (
                            time.time() + LATE_RESULT_TTL, entry, hist_entry)
                except asyncio.CancelledError:
                    entry["status"] = "cancelled"
                    entry["result"] = {"error": "cancelled"}
                    raise
        finally:
            self._finish_dispatch(cmd_id, device_id, ws, entry, hist_entry,
                                  update_info, persist)
        return entry

    def _finish_dispatch(self, cmd_id, device_id, ws, entry, hist_entry,
                         update_info, persist):
        self.pending.pop(cmd_id, None)
        self.downloads.pop((device_id, cmd_id, ws), None)
        hist_entry["status"] = entry["status"]
        hist_entry["result"] = _sanitize_for_history(entry.get("result"))
        if update_info is not None:
            hist_update = hist_entry.get("update")
            if isinstance(hist_update, dict):
                hist_update["state"] = (
                    "installed" if entry["status"] == "ok" else "failed")
        if persist:
            self.persist_history()

    async def broadcast(self, action, args, wait=10.0, actor=None, source=None):
        """Send a command to every approved device.

        Offline approved devices are returned as skipped; the top-level ok is
        false when any attempted target fails or times out.
        """
        targets = []
        skipped = {}
        for d, rec in self.devices.items():
            if not isinstance(rec, dict) or not rec.get("approved"):
                continue
            if self.conns.get(d) is None:
                skipped[d] = "offline"
            else:
                targets.append(d)
        results = {}

        async def _run(d):
            async with self.broadcast_sem:
                try:
                    return await self.dispatch(
                        d, action, args, wait=wait, actor=actor, source=source,
                        persist=False)
                except Exception as e:
                    return {"status": "error", "result": {"error": str(e)}}

        if targets:
            entries = await asyncio.gather(
                *(_run(d) for d in targets), return_exceptions=True)
            for d, e in zip(targets, entries):
                if isinstance(e, Exception):
                    results[d] = {"status": "error",
                                  "result": {"error": str(e)}}
                else:
                    results[d] = e
        # Batch persistence once for the whole broadcast.
        self.persist_history()
        out = {}
        ok = True
        for d in targets:
            e = results.get(d)
            if e is None:
                ok = False
                out[d] = {"status": "error",
                          "result": {"error": "no result"}}
            else:
                out[d] = e
                if e.get("status") != "ok":
                    ok = False
        for d, reason in skipped.items():
            out[d] = {"status": "skipped", "result": {"error": reason}}
        return {"ok": ok, "results": out}

    # ---------- server -> device file push (FileReceiver protocol) ----------
    async def push_file(self, device_id, src, dest, wait=180.0, actor=None,
                        source=None, persist=True):
        """Stream a local file to the device: cmd `files put <dest>` followed
        by framed 64 KB file_chunk binaries + text file_end with sha256
        (matches client-main FileReceiver). Blocks until the device ACKs.

        Serialized globally: only one push streams at a time — a rejected
        `files put` still streams chunks, and concurrent pushes could cross-wire
        onto whatever transfer the device holds (seen live: ghosta bytes renamed
        over ghostd.apk).

        The source is read before taking push_lock: the CLI stages uploads in a
        temporary slot and may delete it as soon as the RPC starts, so deferring
        the read until after a queued push would race that cleanup.
        """
        src = Path(src)
        try:
            if src.stat().st_size > MAX_PUSH_BYTES:
                raise ValueError(
                    f"file exceeds {MAX_PUSH_BYTES} byte push limit")
            with src.open("rb") as f:
                data = f.read(MAX_PUSH_BYTES + 1)
        except ValueError:
            raise
        except OSError as e:
            raise FileNotFoundError(f"cannot read source: {e}") from e
        if len(data) > MAX_PUSH_BYTES:
            raise ValueError(f"file exceeds {MAX_PUSH_BYTES} byte push limit")
        async with self.push_lock:
            return await self._push_file(device_id, data, dest, wait, actor,
                                         source, persist)

    async def _push_file(self, device_id, data, dest, wait=180.0, actor=None,
                         source=None, persist=True):
        if not _valid_wait(wait):
            raise ValueError("wait must be between 0.1 and 300 seconds")
        if not _valid_dest(dest):
            raise ValueError("invalid dest")
        if device_id not in self.devices:
            raise ValueError(f"unknown device {device_id}")
        if not self.approved(device_id):
            raise ValueError(f"device {device_id} pending approval")
        ws = self.conns.get(device_id)
        if ws is None:
            raise ConnectionError(f"device {device_id} is offline")
        data = bytes(data)
        dest = str(dest)
        sha256 = hashlib.sha256(data).hexdigest()
        total, chunks = len(data), (len(data) + PUSH_CHUNK - 1) // PUSH_CHUNK
        cmd_id = uuid.uuid4().hex[:12]
        rec = {"event": asyncio.Event(), "result": None, "ok": False,
               "device_id": device_id, "ws": ws,
               "action": "files", "args": ["put", dest]}
        entry = {"cmd_id": cmd_id, "ts": time.time(), "device_id": device_id,
                 "action": "files put", "args": [dest], "status": "pending"}
        if actor:
            entry["actor"] = actor
        if source:
            entry["source"] = source
        hist_entry = _history_entry(entry)
        send_error = None
        async with self._device_lock(device_id):
            self.pending[cmd_id] = rec
            self.history.append(hist_entry)
            self._trim_history()
            try:
                if self.conns.get(device_id) is not ws:
                    raise ConnectionError(f"device {device_id} connection changed")
                rec["sent"] = True
                await self._send_ws(ws, {"type": "cmd", "cmd_id": cmd_id,
                                          "action": "files", "args": ["put", dest]})
                # the client awaits the prepared slot until the frames below
                # complete the transfer
                streaming_aborted = False
                for seq, off in enumerate(range(0, len(data), PUSH_CHUNK)):
                    if rec["event"].is_set():
                        # Device already returned a result/rejection (e.g. slot
                        # busy or aborted): stop streaming instead of blindly
                        # pushing the remaining chunks.
                        streaming_aborted = True
                        break
                    payload = data[off:off + PUSH_CHUNK]
                    header = {"type": "file_chunk", "seq": seq, "path": dest,
                              "cmd_id": cmd_id, "len": len(payload),
                              "total": total}
                    await self._send_bytes(
                        ws, json.dumps(header).encode() + b"\n" + payload)
                if not streaming_aborted:
                    await self._send_ws(ws, {"type": "file_end", "path": dest,
                                              "dest": dest, "cmd_id": cmd_id,
                                              "bytes": total, "chunks": chunks,
                                              "sha256": sha256})
            except asyncio.CancelledError:
                rec["sent"] = False
                entry["status"] = "cancelled"
                entry["result"] = {"error": "cancelled"}
                self._finish_dispatch(cmd_id, device_id, ws, entry, hist_entry,
                                      None, persist)
                raise
            except Exception as e:
                rec["sent"] = False
                send_error = e
                entry["status"] = "error"
                entry["result"] = {"error": str(e)}
        try:
            if send_error is None:
                try:
                    await asyncio.wait_for(rec["event"].wait(), timeout=wait)
                    entry["status"] = "ok" if rec["ok"] else "error"
                    entry["result"] = rec["result"]
                except asyncio.TimeoutError:
                    entry["status"] = "timeout"
                    entry["result"] = {"error": "timeout"}
                    self._late_results[cmd_id] = (
                        time.time() + LATE_RESULT_TTL, entry, hist_entry)
                except asyncio.CancelledError:
                    entry["status"] = "cancelled"
                    entry["result"] = {"error": "cancelled"}
                    raise
        finally:
            self._finish_dispatch(cmd_id, device_id, ws, entry, hist_entry,
                                  None, persist)
        return entry

    # ---------- update flow ----------
    async def update_push(self, device_id, target, apk=None, actor=None,
                          source=None, wait=None):
        """`update self|sidekick [--apk path]`: push the APK to the device
        (files-put flow) then trigger the install verb (sidekick install /
        update self).

        `wait` bounds the whole operation (push + install), not each stage.
        Without it the historical push and install bounds are combined.
        """
        if target not in APK_DEST:
            raise ValueError("update: expected 'self' or 'sidekick'")
        if wait is not None and not _valid_wait(wait):
            raise ValueError("wait must be between 0.1 and 300 seconds")
        if apk:
            src = Path(apk).expanduser().resolve()
            if not src.is_relative_to(APK_DIR.resolve()):
                raise ValueError("apk must resolve inside server/apks")
        else:
            src = APK_DIR / ("ghosta.apk" if target == "sidekick" else "ghostd.apk")
        if not src.is_file():
            raise FileNotFoundError(
                f"apk not found: {src} (place one first: "
                f"ghostctl.py apks add {target} <path>)")
        dest = APK_DEST[target]
        total = float(wait) if wait is not None else UPDATE_TOTAL_WAIT
        deadline = time.monotonic() + total

        def remaining():
            return deadline - time.monotonic()

        push_wait = max(0.1, min(UPDATE_PUSH_WAIT, remaining()))
        push = await self.push_file(device_id, src, dest, wait=push_wait,
                                    actor=actor, source=source)
        if push["status"] != "ok":
            return push
        left = remaining()
        if left < 0.1:
            return {"cmd_id": push.get("cmd_id"), "status": "timeout",
                    "result": {"error": f"update timed out after {total:g}s "
                                        "before install"}}
        if target == "sidekick":
            return await self.dispatch(device_id, "sidekick",
                                      ["install", dest],
                                      wait=max(0.1, min(SIDEKICK_INSTALL_WAIT,
                                                        left)),
                                      actor=actor, source=source)
        return await self.dispatch(device_id, "update", ["self", dest],
                                   wait=max(0.1, min(SELF_UPDATE_WAIT, left)),
                                   actor=actor, source=source,
                                   update_meta={"target": "self",
                                                "dest": dest,
                                                "apk": str(src)})

    # ---------- stream relay ----------
    def on_device_binary(self, device_id, raw, ws=None):
        """Framed binary messages from a device (MediaBus framing)."""
        try:
            parsed = parse_frame(raw)
            if parsed is None:
                return
            header, payload = parsed
            mtype = header.get("type")
            if not isinstance(mtype, str):
                return
            if mtype == "frame":
                stream = header.get("stream")
                if not _valid_label(stream):
                    return
                if len(payload) > StreamCache.MAX_FRAME_BYTES:
                    return
                self.streams.put(device_id, stream, payload)
            elif mtype == "file_chunk" and ws is not None:
                self._on_file_chunk(device_id, ws, header, payload)
            # file_start/file_end text frames are handled in device_handler.
            # `files get` chunks are buffered only in the transfer slot, never
            # in StreamCache (which is for live media).
        except Exception:
            return

    # ---------- hello / authentication ----------
    async def handle_hello(self, ws, msg):
        """Authenticate a hello message. Returns (device_id, ack) where ack
        is the hello_ack dict; device_id None => deny + close."""
        did = msg.get("device_id")
        if not _valid_device_id(did):
            return None, {"type": "hello_ack", "status": "denied"}
        token = msg.get("token")
        enroll = msg.get("enroll_key")
        if token is not None and not _valid_secret(token):
            return None, {"type": "hello_ack", "status": "denied"}
        if enroll is not None and not _valid_secret(enroll):
            return None, {"type": "hello_ack", "status": "denied"}
        rec = self.devices.get(did)
        if rec is not None and not isinstance(rec, dict):
            rec = None
        ip = "?"
        try:
            addr = getattr(ws, "remote_address", None)
            if addr and len(addr) > 0 and addr[0]:
                ip = sanitize_log(addr[0])
        except Exception:
            pass
        # 1. valid token for an approved device -> trusted
        if (isinstance(token, str) and token and rec and rec.get("approved")
                and _safe_compare(rec.get("token", ""), token)):
            return did, {"type": "hello_ack", "status": "approved",
                         "token": token}
        # 2. approved device re-enrolling with the shared key is denied; a
        # lost token requires an explicit revoke first.
        if rec and rec.get("approved"):
            log(f"denied hello from {sanitize_log(did)} ({ip}): "
                f"approved device re-enrollment")
            return None, {"type": "hello_ack", "status": "denied"}
        # 3. valid enroll key -> create/reuse a pending entry, new token
        if isinstance(enroll, str) and enroll and \
                _safe_compare(self.enroll_key, enroll):
            if rec is None:
                # Atomic cap check: no awaits between the check and creation.
                if len(self.pending_ids()) >= self.config["max_pending"]:
                    log(f"denied hello from {sanitize_log(did)} ({ip}): "
                        f"pending device limit "
                        f"({self.config['max_pending']}) reached")
                    return None, {"type": "hello_ack", "status": "denied"}
                rec = {}
                self.devices[did] = rec
            token = new_token()
            rec["approved"] = False
            rec["token"] = token
            return did, {"type": "hello_ack", "status": "pending",
                         "token": token}
        # 4. bad/missing credentials -> deny
        why = "bad token" if token else ("bad enroll key" if enroll
                                         else "no credentials")
        log(f"denied hello from {sanitize_log(did)} ({ip}): {why}")
        return None, {"type": "hello_ack", "status": "denied"}

    def _handle_result(self, device_id, ws, msg):
        cmd_id = msg.get("cmd_id")
        if not isinstance(cmd_id, str) or not cmd_id:
            return
        rec = self.pending.get(cmd_id)
        if (rec and rec.get("device_id") == device_id
                and rec.get("ws") is ws):
            if (rec.get("self_update")
                    and rec.get("update_state") in ("reconnecting", "installed")):
                # The replaced process (or a new process echoing the old
                # command) must not bypass version-code reconciliation.
                return
            state = self.downloads.pop((device_id, cmd_id, ws), None)
            if state is not None:
                if state.get("error") is not None:
                    # The transfer never produced a verifiable payload.  Do
                    # not let the device's optimistic result hide that.
                    msg["ok"] = False
                    msg["result"] = {"error": state["error"]}
                elif state.get("verified"):
                    result = msg.get("result")
                    if not isinstance(result, dict):
                        result = {}
                    result["b64"] = base64.b64encode(
                        state["buffer"]).decode()
                    result["path"] = state["path"]
                    result["dest"] = state["dest"]
                    result["bytes"] = state["bytes_received"]
                    result["chunks"] = state["next_seq"]
                    result["sha256"] = state["sha256"]
                    msg["result"] = result
                else:
                    msg["ok"] = False
                    msg["result"] = {"error": "incomplete file transfer"}
            rec["ok"] = bool(msg.get("ok", True))
            rec["result"] = msg.get("result")
            rec["event"].set()
            return
        late = self._late_results.get(cmd_id)
        if late and time.time() <= late[0]:
            _, entry, hist_entry = late
            entry["status"] = "ok" if msg.get("ok", True) else "error"
            entry["result"] = msg.get("result")
            hist_entry["status"] = entry["status"]
            hist_entry["result"] = _sanitize_for_history(entry["result"])
            self.persist_history()
            del self._late_results[cmd_id]

    # ---------- device -> server file receive (`files get`) ----------

    def _cleanup_download(self, device_id, cmd_id, ws, error=None):
        """Drop a receive slot, optionally leaving a terminal error behind.

        Keeping an error state until the device's result frame arrives lets
        `_handle_result` report an accurate failure instead of the device's
        optimistic success envelope.  The buffer is freed immediately.
        """
        key = (device_id, cmd_id, ws)
        state = self.downloads.get(key)
        if state is None:
            return
        if error is not None and not state.get("verified"):
            state["error"] = error
            state["buffer"] = bytearray()
            state["bytes_received"] = 0
            return
        self.downloads.pop(key, None)

    def _on_file_start(self, device_id, ws, msg):
        """Open a receive slot only for an outstanding `files get` command."""
        cmd_id = msg.get("cmd_id")
        if not isinstance(cmd_id, str) or not cmd_id:
            return
        rec = self.pending.get(cmd_id)
        if (not rec
                or rec.get("device_id") != device_id
                or rec.get("ws") is not ws
                or not self.approved(device_id)):
            return
        if (not isinstance(rec.get("action"), str)
                or rec.get("action") != "files"):
            return
        args = rec.get("args")
        if not isinstance(args, list) or not args or args[0] != "get":
            return
        requested = args[1] if len(args) > 1 else None
        if not isinstance(requested, str) or not _valid_dest(requested):
            return
        expected_dest = args[2] if len(args) > 2 else requested
        if not isinstance(expected_dest, str) or not _valid_dest(expected_dest):
            return
        key = (device_id, cmd_id, ws)
        if key in self.downloads:
            # Duplicate start for the same command: keep the original slot.
            return
        total = msg.get("total")
        chunks = msg.get("chunks")
        chunk = msg.get("chunk")
        path = msg.get("path")
        dest = msg.get("dest")
        if (not _valid_dest(path) or not _valid_dest(dest)):
            self.downloads[key] = {"error": "invalid file transfer path"}
            return
        if path != requested or dest != expected_dest:
            self.downloads[key] = {"error": "file transfer path mismatch"}
            return
        if (isinstance(total, bool) or not isinstance(total, int)
                or total < 0):
            return
        if total > MAX_DOWNLOAD_BYTES:
            self.downloads[key] = {
                "error": f"file exceeds {MAX_DOWNLOAD_BYTES} byte limit"}
            return
        if (isinstance(chunks, bool) or not isinstance(chunks, int)
                or chunks < 0
                or isinstance(chunk, bool) or not isinstance(chunk, int)
                or chunk <= 0 or chunk > MAX_DOWNLOAD_BYTES):
            return
        expected_chunks = (total + chunk - 1) // chunk if total else 0
        if chunks != expected_chunks:
            self.downloads[key] = {
                "error": "file transfer chunk count mismatch"}
            return
        self.downloads[key] = {
            "path": path, "dest": dest, "total": total,
            "chunks": chunks, "chunk": chunk,
            "next_seq": 0, "buffer": bytearray(),
            "bytes_received": 0, "verified": False,
        }

    def _on_file_chunk(self, device_id, ws, header, payload):
        cmd_id = header.get("cmd_id")
        if not isinstance(cmd_id, str) or not cmd_id:
            return
        key = (device_id, cmd_id, ws)
        state = self.downloads.get(key)
        if (state is None or state.get("error") is not None
                or state.get("verified")):
            return
        seq = header.get("seq")
        length = header.get("len")
        path = header.get("path")
        total = header.get("total")
        expected_len = min(state["chunk"],
                           state["total"] - state["bytes_received"])
        if (isinstance(seq, bool) or not isinstance(seq, int)
                or seq != state["next_seq"]
                or isinstance(length, bool) or not isinstance(length, int)
                or length != len(payload)
                or length != expected_len
                or path != state["path"]
                or total != state["total"]
                or state["bytes_received"] + length > MAX_DOWNLOAD_BYTES):
            self._cleanup_download(device_id, cmd_id, ws,
                                   "malformed file chunk")
            return
        state["buffer"].extend(payload)
        state["bytes_received"] += length
        state["next_seq"] += 1

    def _on_file_end(self, device_id, ws, msg):
        cmd_id = msg.get("cmd_id")
        if not isinstance(cmd_id, str) or not cmd_id:
            return
        key = (device_id, cmd_id, ws)
        state = self.downloads.get(key)
        if (state is None or state.get("error") is not None
                or state.get("verified")):
            return
        bytes_sent = msg.get("bytes")
        chunks_sent = msg.get("chunks")
        sha256 = msg.get("sha256")
        if (msg.get("path") != state["path"]
                or (msg.get("dest") is not None
                    and msg.get("dest") != state["dest"])):
            self._cleanup_download(device_id, cmd_id, ws,
                                   "file transfer path mismatch")
            return
        if (isinstance(bytes_sent, bool) or not isinstance(bytes_sent, int)
                or isinstance(chunks_sent, bool)
                or not isinstance(chunks_sent, int)
                or bytes_sent != state["bytes_received"]
                or chunks_sent != state["next_seq"]
                or bytes_sent != state["total"]
                or chunks_sent != state["chunks"]
                or not isinstance(sha256, str)):
            self._cleanup_download(device_id, cmd_id, ws,
                                   "file transfer count mismatch")
            return
        actual = hashlib.sha256(state["buffer"]).hexdigest()
        if actual != sha256.lower():
            self._cleanup_download(device_id, cmd_id, ws,
                                   "file transfer sha256 mismatch")
            return
        state["verified"] = True
        state["sha256"] = actual

    def _disconnect_device(self, device_id, ws):
        # Drop in-flight downloads bound to this exact socket even when a
        # replacement already owns the device slot: an old transfer must not
        # linger, and a fresh socket must never inherit its receive state.
        for key in [k for k in self.downloads
                    if k[0] == device_id and k[2] is ws]:
            del self.downloads[key]
        if self.conns.get(device_id) is not ws:
            return
        del self.conns[device_id]
        self.touch(device_id, online=False)
        self.streams.drop_device(device_id)
        # Wake any dispatch waiting on this device immediately, except a
        # sent self-update: its process is expected to die, and completion is
        # reconciled by the replacement process's hello/version_code.
        retained_update = False
        for cmd_id, rec in list(self.pending.items()):
            if rec.get("device_id") != device_id:
                continue
            if rec.get("event").is_set():
                continue
            if (rec.get("action") == "destroy" and rec.get("sent")
                    and rec.get("ws") is ws
                    and device_id in self.remove_pending):
                # Android normally kills the replaced process instead of
                # delivering a destroy result.  Give it a bounded grace window
                # to stay away before purging anything.
                self._schedule_remove_grace(device_id)
            if (rec.get("self_update") and rec.get("sent")
                    and rec.get("ws") is ws):
                self._set_update_state(rec, "reconnecting")
                retained_update = True
                continue
            rec["ok"] = False
            rec["result"] = {"error": "device disconnected"}
            rec["event"].set()
        if retained_update:
            self.persist_history()

    # ---------- access revocation / device removal ----------

    async def _close_device_socket(self, device_id):
        """Close the current device socket without letting its handler touch
        the registry again (the caller may be deleting the record)."""
        ws = self.conns.pop(device_id, None)
        if ws is not None:
            with contextlib.suppress(Exception):
                await asyncio.wait_for(ws.close(), timeout=2)
        return ws

    def _drop_downloads(self, device_id):
        for key in [k for k in self.downloads if k[0] == device_id]:
            del self.downloads[key]

    def _fail_pending_for(self, device_id, reason):
        """Wake every command waiting on a device without overwriting a
        result that already arrived."""
        for rec in list(self.pending.values()):
            if rec.get("device_id") != device_id:
                continue
            if not rec.get("event").is_set():
                rec["ok"] = False
                rec["result"] = {"error": reason}
                rec["event"].set()

    async def _revoke_access(self, device_id, reason="device revoked"):
        """Keep the device record for retry/audit but make it unusable:
        rotate its bearer token, close the socket, drop streams and transfers,
        and fail outstanding commands."""
        token = new_token()
        await self._close_device_socket(device_id)
        self.streams.drop_device(device_id)
        self._fail_pending_for(device_id, reason)
        self._drop_downloads(device_id)
        self.touch(device_id, online=False,
                   extra={"approved": False, "token": token})
        return token

    async def _purge_device(self, device_id):
        """Delete every server-side trace of a successfully removed device."""
        self._drop_remove_pending(device_id, cancel_grace=False)
        await self._close_device_socket(device_id)
        self._drop_downloads(device_id)
        self.streams.drop_device(device_id)
        for cmd_id, rec in list(self.pending.items()):
            if rec.get("device_id") != device_id:
                continue
            if not rec.get("event").is_set():
                rec["ok"] = False
                rec["result"] = {"error": "device removed"}
                rec["event"].set()
            del self.pending[cmd_id]
        self._late_results = {
            k: v for k, v in self._late_results.items()
            if v[1].get("device_id") != device_id}
        self.history = [h for h in self.history
                        if h.get("device_id") != device_id]
        self.cmd_locks.pop(device_id, None)
        self.devices.pop(device_id, None)
        self.persist_state()
        self.persist_history()

    # ---------- websocket device handler ----------
    async def device_handler(self, ws):
        device_id = None
        ip = "?"
        try:
            addr = getattr(ws, "remote_address", None)
            if addr and len(addr) > 0 and addr[0]:
                ip = sanitize_log(addr[0])
        except Exception:
            pass
        # Track sockets (not just counts) so a flapping NAT client cannot be
        # locked out by its own zombies; evict the oldest at the cap.
        conns = self.ip_conns.setdefault(ip, {})
        if len(conns) >= self.config["max_per_ip"]:
            oldest_ws = min(conns.items(), key=lambda kv: kv[1])[0]
            conns.pop(oldest_ws, None)
            with contextlib.suppress(Exception):
                await asyncio.wait_for(oldest_ws.close(), timeout=2)
            log(f"evicted oldest connection from {ip} to enforce "
                f"per-IP limit ({self.config['max_per_ip']})")
        conns[ws] = time.time()
        try:
            deadline = time.time() + PRE_HELLO_TIMEOUT
            msg_count = 0
            while True:
                if device_id is None and time.time() > deadline:
                    await ws.close()
                    return
                try:
                    if device_id is None:
                        timeout = max(0.1, deadline - time.time())
                        raw = await asyncio.wait_for(ws.recv(), timeout=timeout)
                    else:
                        raw = await ws.recv()
                except asyncio.TimeoutError:
                    if device_id is None:
                        await ws.close()
                        return
                    continue
                except websockets.exceptions.ConnectionClosed:
                    break
                if device_id is None:
                    msg_count += 1
                    if msg_count > PRE_HELLO_MAX_MESSAGES:
                        await ws.close()
                        return
                    if isinstance(raw, (str, bytes, bytearray)) and \
                            len(raw) > 64 * 1024:
                        await ws.close()
                        return
                if isinstance(raw, (bytes, bytearray)):
                    if (device_id is not None
                            and self.conns.get(device_id) is ws
                            and self.approved(device_id)):
                        self.on_device_binary(device_id, raw, ws)
                    continue
                try:
                    msg = json.loads(raw)
                except (json.JSONDecodeError, UnicodeDecodeError):
                    continue
                if not isinstance(msg, dict):
                    continue
                mtype = msg.get("type")
                if not isinstance(mtype, str):
                    continue
                if device_id is None:
                    if mtype != "hello":
                        continue
                    did, ack = await self.handle_hello(ws, msg)
                    if did is None:
                        with contextlib.suppress(Exception):
                            await self._send_ws(ws, ack)
                        await ws.close()
                        return
                    # Register/update state synchronously before the ack so a
                    # concurrent hello cannot race the approved/token state.
                    device_id = did
                    approved = ack["status"] == "approved"
                    token = ack["token"]
                    old = self.conns.get(device_id)
                    self.conns[device_id] = ws
                    if old is not None and old is not ws:
                        asyncio.create_task(old.close())
                    version_code = _parse_version_code(
                        msg.get("version_code"))
                    extra = {"battery": msg.get("battery"),
                             "ip": ip,
                             "version": msg.get("version"),
                             "app_version": msg.get("app_version"),
                             "approved": approved,
                             "token": token}
                    if version_code is not None:
                        # Do not erase a previously known code when an older
                        # client omits the field.
                        extra["version_code"] = version_code
                    self.touch(device_id, online=True, extra=extra)
                    # A hello/reconnect (approved or re-enrolling) means the
                    # app survived removal.  Clear the marker before the ack
                    # send so a failed ack cannot let a stale grace timer
                    # purge it later.
                    self._clear_remove_pending(
                        device_id,
                        "device reconnected after destroy request; "
                        "removal was not verified")
                    try:
                        await self._send_ws(ws, ack)
                    except Exception:
                        if self.conns.get(device_id) is ws:
                            del self.conns[device_id]
                        self.touch(device_id, online=False)
                        await ws.close()
                        return
                    if approved:
                        if not self._reconcile_self_update(
                                device_id, version_code, ws):
                            self._reconcile_history_update(device_id,
                                                           version_code)
                    log(f"device {sanitize_log(device_id)} hello_ack "
                        f"{ack['status']} from {ip}")
                    continue
                # Superseded connections must not mutate fresh state.
                if self.conns.get(device_id) is not ws:
                    continue
                if mtype == "ping":
                    # heartbeats accepted from pending and approved devices
                    self.touch(device_id, online=None, persist=False)
                    await self._send_ws(ws, {"type": "pong"})
                elif mtype == "info":
                    info_extra = {"battery": msg.get("battery"), "ip": ip}
                    info_version_code = _parse_version_code(
                        msg.get("version_code"))
                    if info_version_code is not None:
                        info_extra["version_code"] = info_version_code
                    self.touch(device_id, online=None, extra=info_extra,
                               persist=False)
                elif mtype == "stream_start":
                    if self.approved(device_id):
                        self.streams.set_encoding(device_id, msg.get("stream"),
                                                  msg.get("encoding"))
                elif mtype == "stream_end":
                    if self.approved(device_id):
                        self.streams.drop(device_id, msg.get("stream"))
                elif mtype == "file_start":
                    self._on_file_start(device_id, ws, msg)
                elif mtype == "file_end":
                    self._on_file_end(device_id, ws, msg)
                elif mtype == "result":
                    self._handle_result(device_id, ws, msg)
        except asyncio.CancelledError:
            raise
        except Exception as e:
            log(f"device handler error for {sanitize_log(str(device_id or ip))}: "
                f"{sanitize_log(str(e))}")
        finally:
            conns = self.ip_conns.get(ip)
            if conns is not None:
                conns.pop(ws, None)
                if not conns:
                    self.ip_conns.pop(ip, None)
            if device_id:
                self._disconnect_device(device_id, ws)

    # ---------- admin (CLI) handler: newline-delimited JSON ----------
    @staticmethod
    def _admin_request(line):
        if len(line) > MAX_ADMIN_LINE:
            return None, {"ok": False, "error": "request too large"}
        try:
            req = json.loads(line)
        except (json.JSONDecodeError, UnicodeDecodeError):
            return None, {"ok": False, "error": "bad json"}
        if not isinstance(req, dict):
            return None, {"ok": False, "error": "request must be an object"}
        return req, None

    @staticmethod
    def _admin_response(resp):
        try:
            out = json.dumps(resp)
        except (TypeError, ValueError):
            out = json.dumps({"ok": False, "error": "response not serializable"})
        if len(out) > MAX_ADMIN_RESPONSE:
            out = json.dumps({"ok": False, "error": "response too large"})
        return out

    async def admin_handler(self, reader, writer):
        pending_line = None
        try:
            while True:
                if pending_line is not None:
                    line, pending_line = pending_line, None
                else:
                    try:
                        line = await asyncio.wait_for(reader.readline(),
                                                      timeout=300)
                    except asyncio.TimeoutError:
                        break
                    except (ValueError, asyncio.LimitOverrunError):
                        resp = {"ok": False, "error": "request too large"}
                        writer.write(
                            (self._admin_response(resp) + "\n").encode())
                        with contextlib.suppress(Exception):
                            await asyncio.wait_for(writer.drain(), timeout=10)
                        break
                if not line:
                    break
                req, err = self._admin_request(line)
                if err is not None:
                    resp = err
                else:
                    # A closed CLI must not leave a remove/update running to
                    # completion (and possibly purging state) after the
                    # operator is gone.  Race the dispatch against the next
                    # read: EOF cancels the in-flight op; a real next request
                    # is buffered for the following iteration.
                    task = asyncio.create_task(self.admin_dispatch(req))
                    watcher = asyncio.create_task(reader.readline())
                    try:
                        done, _ = await asyncio.wait(
                            {task, watcher},
                            return_when=asyncio.FIRST_COMPLETED)
                        if watcher in done and task not in done:
                            try:
                                next_line = watcher.result()
                            except Exception:
                                next_line = b""
                            if not next_line:
                                task.cancel()
                                with contextlib.suppress(
                                        asyncio.CancelledError, Exception):
                                    await task
                                break
                            pending_line = next_line
                        resp = await task
                        if watcher in done and pending_line is None:
                            try:
                                pending_line = watcher.result()
                            except Exception:
                                pending_line = None
                        if (pending_line == b"" and isinstance(req, dict)
                                and req.get("op") == "remove"):
                            # The CLI vanished before we could deliver the
                            # response.  In-flight cancellation handled the
                            # normal case; this covers a deferred remove grace
                            # that was scheduled just before completion.
                            did = req.get("device_id")
                            if _valid_device_id(did):
                                self._drop_remove_pending(did)
                    except asyncio.CancelledError:
                        task.cancel()
                        with contextlib.suppress(
                                asyncio.CancelledError, Exception):
                            await task
                        raise
                    finally:
                        if not watcher.done():
                            watcher.cancel()
                        with contextlib.suppress(
                                asyncio.CancelledError, Exception):
                            await watcher
                writer.write((self._admin_response(resp) + "\n").encode())
                try:
                    await asyncio.wait_for(writer.drain(), timeout=10)
                except asyncio.TimeoutError:
                    break
        except (ConnectionResetError, BrokenPipeError):
            pass
        finally:
            with contextlib.suppress(Exception):
                writer.close()

    async def admin_dispatch(self, req):
        if not isinstance(req, dict):
            return {"ok": False, "error": "request must be an object"}
        token = req.get("token")
        if not isinstance(token, str) or not token or \
                not _safe_compare(self.admin_token, token):
            return {"ok": False, "error": "unauthorized"}
        op = req.get("op")
        if not isinstance(op, str):
            return {"ok": False, "error": "missing op"}
        try:
            if op in ("list", "status", "devices"):
                return {"ok": True, "devices": self._redact_devices()}
            if op == "pending":
                red = self._redact_devices()
                out = {d: red[d] for d in self.pending_ids() if d in red}
                return {"ok": True, "pending": out}
            if op == "rename":
                did = req.get("device_id")
                name = req.get("name")
                if not _valid_device_id(did):
                    return {"ok": False, "error": "invalid device_id"}
                if not _valid_name(name):
                    return {"ok": False, "error": "rename: invalid name"}
                if did not in self.devices:
                    return {"ok": False, "error": f"unknown device {did}"}
                name = name.strip()
                self.touch(did, online=None, extra={"name": name})
                log(f"device {sanitize_log(did)} renamed via admin")
                return {"ok": True, "device_id": did, "name": name}
            if op == "approve":
                did = req.get("device_id")
                if not _valid_device_id(did):
                    return {"ok": False, "error": "invalid device_id"}
                if did not in self.devices:
                    return {"ok": False, "error": f"unknown device {did}"}
                self.touch(did, online=None, extra={"approved": True})
                log(f"device {sanitize_log(did)} approved via admin")
                return {"ok": True, "device_id": did, "approved": True}
            if op == "revoke":
                did = req.get("device_id")
                if not _valid_device_id(did):
                    return {"ok": False, "error": "invalid device_id"}
                if did not in self.devices:
                    return {"ok": False, "error": f"unknown device {did}"}
                # Rotate the bearer token: a stolen token dies with the access.
                await self._revoke_access(did, reason="device revoked")
                log(f"device {sanitize_log(did)} revoked via admin "
                    f"(token rotated)")
                return {"ok": True, "device_id": did, "approved": False}
            if op == "remove":
                did = req.get("device_id")
                actor = req.get("actor")
                source = req.get("source")
                wait = req.get("wait", 150)
                if not _valid_device_id(did):
                    return {"ok": False, "removed": False,
                            "error": "invalid device_id"}
                rec = self.devices.get(did)
                if not isinstance(rec, dict):
                    return {"ok": False, "removed": False,
                            "error": f"unknown device {did}"}
                if not _valid_actor(actor):
                    return {"ok": False, "removed": False,
                            "error": "invalid actor"}
                if not _valid_actor(source):
                    return {"ok": False, "removed": False,
                            "error": "invalid source"}
                if not _valid_wait(wait):
                    return {"ok": False, "removed": False,
                            "error": "wait must be between 0.1 and 300 seconds"}
                if not rec.get("approved"):
                    return {"ok": False, "removed": False, "revoked": False,
                            "device_id": did,
                            "error": (f"device {did} is not approved: only an "
                                      "approved online device can remove its "
                                      "apps; use revoke to remove server access")}
                if self.conns.get(did) is None:
                    return {"ok": False, "removed": False, "revoked": False,
                            "device_id": did,
                            "error": (f"device {did} is offline: the app cannot "
                                      "be uninstalled remotely. Use revoke to "
                                      "remove server access only.")}
                ws = self.conns.get(did)
                # Bounded marker before dispatch: a disconnect while destroy
                # runs is the expected removal signal, not a random failure.
                self._begin_remove_pending(did, ws)
                try:
                    entry = await self.dispatch(
                        did, "destroy", [], wait=float(wait), actor=actor,
                        source=source or "admin")
                except asyncio.CancelledError:
                    self._drop_remove_pending(did)
                    raise
                except Exception as e:  # noqa: BLE001 - check result below
                    self._drop_remove_pending(did)
                    err = sanitize_log(str(e))
                    log(f"device {sanitize_log(did)} remove failed: {err}; "
                        f"access preserved for retry")
                    return {"ok": False, "requested": False, "verified": False,
                            "removed": False, "revoked": False,
                            "device_id": did, "error": err}
                result = entry.get("result")
                if isinstance(result, dict):
                    error = result.get("error")
                elif isinstance(result, str):
                    error = result
                else:
                    error = None
                replaced = (isinstance(result, dict)
                            and result.get("replaced") is True)
                if replaced:
                    await self._purge_device(did)
                    log(f"device {sanitize_log(did)} removal verified: "
                        f"process replaced")
                    return {"ok": True, "requested": True, "verified": True,
                            "removed": True, "replaced": True,
                            "device_id": did,
                            "message": ("device removal verified: Android "
                                        "replaced the app; server record, "
                                        "history, streams and pending "
                                        "transfers were deleted")}
                expected_disconnect = (
                    entry.get("status") == "error"
                    and error == "device disconnected")
                if expected_disconnect and did in self.remove_pending:
                    self._schedule_remove_grace(did)
                    log(f"device {sanitize_log(did)} destroy disconnected as "
                        f"expected; finalizing in {REMOVE_GRACE:g}s unless it "
                        f"reconnects")
                    return {"ok": True, "requested": True, "verified": False,
                            "removed": False, "finalizing": True,
                            "expected_disconnect": True,
                            "grace_seconds": REMOVE_GRACE, "device_id": did,
                            "error": ("removal requested; waiting "
                                      f"{REMOVE_GRACE:g}s for the device to "
                                      "stay disconnected before purging"),
                            "message": ("device disconnected before its final "
                                        "result; that is expected when the app "
                                        "uninstalls itself")}
                self._drop_remove_pending(did)
                if entry.get("status") == "ok":
                    # A plain ok / uninstall_requested is not proof that
                    # Android replaced the package.  Never purge on it.
                    return {"ok": False, "requested": True, "verified": False,
                            "removed": False, "revoked": False,
                            "device_id": did,
                            "error": ("destroy reported success without "
                                      "replacement proof (replaced is not "
                                      "true); nothing was purged"),
                            "result": _sanitize_for_history(result)}
                if not isinstance(error, str) or not error:
                    error = "destroy failed"
                # Preconditions and cancellation are not proof of removal and
                # must not lock the owner out. Keep approval for an immediate retry.
                log(f"device {sanitize_log(did)} remove failed: "
                    f"{sanitize_log(error)}; access preserved for retry")
                return {"ok": False, "requested": True, "verified": False,
                        "removed": False, "revoked": False,
                        "device_id": did, "error": error,
                        "result": _sanitize_for_history(result)}
            if op == "config":
                # `config` -> show; `config set <key> <value>` -> live-apply
                rest = req.get("args") or []
                if not isinstance(rest, list):
                    return {"ok": False, "error": "args must be a list"}
                if rest and rest[0] == "set":
                    if len(rest) != 3:
                        return {"ok": False,
                                "error": "config set requires key and value"}
                    key, value = rest[1], rest[2]
                    if not isinstance(key, str) or key not in self.config:
                        return {"ok": False, "error": f"unknown setting {key!r} "
                                f"(known: {', '.join(self.config)})"}
                    v = _valid_config_value(key, value)
                    if v is None:
                        return {"ok": False,
                                "error": f"{key} must be an integer between 1 "
                                         f"and {MAX_CONFIG_VALUE}"}
                    self.config[key] = v
                    persisted = save_json(CONFIG_FILE, self.config)
                    log(f"config set {key}={self.config[key]} via admin")
                    resp = {"ok": True, "applied": True,
                            "persisted": bool(persisted),
                            "config": dict(self.config)}
                    if not persisted:
                        resp["warning"] = ("applied for this daemon process but "
                                           "could not be persisted; it will "
                                           "revert on restart")
                    return resp
                return {"ok": True, "config": dict(self.config)}
            if op == "streams":
                return {"ok": True, "streams": self.streams.listing()}
            if op == "stream_frame":
                did = req.get("device_id")
                if not _valid_device_id(did):
                    return {"ok": False, "error": "invalid device_id"}
                if not self.approved(did) or self.conns.get(did) is None:
                    return {"ok": False,
                            "error": "device not approved or offline"}
                stream = req.get("stream")
                if stream is not None and not _valid_label(stream):
                    return {"ok": False, "error": "invalid stream"}
                fr = self.streams.latest(did, stream)
                if fr is None:
                    return {"ok": False,
                            "error": f"no cached jpeg frame for {did}"
                                     f"{'' if not stream else ' ' + stream}"}
                stream_name, encoding, ts, payload, frame_b64 = fr
                if frame_b64 is None:
                    frame_b64 = base64.b64encode(payload).decode()
                return {"ok": True, "stream": stream_name, "encoding": encoding,
                        "ts": ts, "frame_b64": frame_b64}
            if op == "update":
                did = req.get("device_id")
                target = req.get("target")
                apk = req.get("apk")
                actor = req.get("actor")
                source = req.get("source")
                wait = req.get("wait")
                if not _valid_device_id(did):
                    return {"ok": False, "error": "invalid device_id"}
                if did not in self.devices:
                    return {"ok": False, "error": f"unknown device {did}"}
                if not _valid_actor(actor):
                    return {"ok": False, "error": "invalid actor"}
                if not _valid_actor(source):
                    return {"ok": False, "error": "invalid source"}
                if not isinstance(target, str) or target not in APK_DEST:
                    return {"ok": False,
                            "error": "update: expected 'self' or 'sidekick'"}
                if wait is not None and not _valid_wait(wait):
                    return {"ok": False,
                            "error": "wait must be between 0.1 and 300 seconds"}
                if apk is not None:
                    if not isinstance(apk, str) or not apk:
                        return {"ok": False, "error": "invalid apk"}
                    apk_path = Path(apk).expanduser()
                    if not apk_path.is_absolute():
                        apk_path = APK_DIR / apk_path
                    apk_path = apk_path.resolve()
                    if not apk_path.is_relative_to(APK_DIR.resolve()):
                        return {"ok": False,
                                "error": "apk must resolve inside server/apks"}
                    apk = str(apk_path)
                entry = await self.update_push(
                    did, target, apk, actor=actor, source=source,
                    wait=(float(wait) if wait is not None else None))
                return {"ok": entry.get("status") == "ok", "entry": entry}
            if op == "files_get":
                did = req.get("device_id")
                path = req.get("path")
                actor = req.get("actor")
                source = req.get("source")
                wait = req.get("wait", 60.0)
                if not _valid_device_id(did):
                    return {"ok": False, "error": "invalid device_id"}
                if did not in self.devices:
                    return {"ok": False, "error": f"unknown device {did}"}
                if not _valid_dest(path):
                    return {"ok": False, "error": "invalid path"}
                if not _valid_actor(actor):
                    return {"ok": False, "error": "invalid actor"}
                if not _valid_actor(source):
                    return {"ok": False, "error": "invalid source"}
                if not _valid_wait(wait):
                    return {"ok": False,
                            "error": "wait must be between 0.1 and 300 seconds"}
                entry = await self.dispatch(did, "files", ["get", path],
                                            wait=float(wait), actor=actor,
                                            source=source)
                result = entry.get("result")
                if (entry.get("status") != "ok"
                        or not isinstance(result, dict)
                        or not isinstance(result.get("b64"), str)):
                    err = (result.get("error") if isinstance(result, dict)
                           else "download failed")
                    return {"ok": False, "error": err or "download failed"}
                # `data` is the console download contract; the richer fields
                # mirror the command result for callers that want integrity.
                return {"ok": True, "data": result["b64"],
                        "path": result.get("path", path),
                        "dest": result.get("dest", path),
                        "bytes": result.get("bytes"),
                        "chunks": result.get("chunks"),
                        "sha256": result.get("sha256")}
            if op == "files_put":
                did = req.get("device_id")
                dest = req.get("dest")
                src = req.get("src")
                actor = req.get("actor")
                source = req.get("source")
                wait = req.get("wait", 180.0)
                if not _valid_device_id(did):
                    return {"ok": False, "error": "invalid device_id"}
                if did not in self.devices:
                    return {"ok": False, "error": f"unknown device {did}"}
                if not _valid_actor(actor):
                    return {"ok": False, "error": "invalid actor"}
                if not _valid_actor(source):
                    return {"ok": False, "error": "invalid source"}
                if not _valid_dest(dest):
                    return {"ok": False, "error": "invalid dest"}
                if not _valid_wait(wait):
                    return {"ok": False,
                            "error": "wait must be between 0.1 and 300 seconds"}
                if not isinstance(src, str) or not src:
                    return {"ok": False, "error": "invalid src"}
                src_path = Path(src).expanduser()
                if not src_path.is_absolute():
                    src_path = UPLOAD_DIR / src_path
                src_path = src_path.resolve()
                if not src_path.is_relative_to(UPLOAD_DIR.resolve()) or \
                        not src_path.is_file():
                    return {"ok": False,
                            "error": "src must resolve inside server/uploads"}
                entry = await self.push_file(did, src_path, dest,
                                             wait=float(wait),
                                             actor=actor, source=source)
                return {"ok": entry.get("status") == "ok", "entry": entry}
            if op == "history":
                did = req.get("device_id")
                limit = req.get("limit", 100)
                if did is not None and not _valid_device_id(did):
                    return {"ok": False, "error": "invalid device_id"}
                if isinstance(limit, bool) or not isinstance(limit, int):
                    return {"ok": False, "error": "limit must be an integer"}
                if limit < 1 or limit > 100:
                    return {"ok": False,
                            "error": "limit must be between 1 and 100"}
                hist = [_history_entry(h) for h in self.history
                        if isinstance(h, dict)
                        and (did is None or h.get("device_id") == did)]
                return {"ok": True, "history": hist[-limit:]}
            if op == "command":
                action = req.get("action")
                args = req.get("args", [])
                wait = req.get("wait", 10)
                all_flag = req.get("all", False)
                actor = req.get("actor")
                source = req.get("source")
                if not isinstance(action, str) or action not in ACTIONS:
                    return {"ok": False, "error": f"unknown action {action!r}"}
                if not _valid_args(args):
                    return {"ok": False, "error": "invalid args"}
                if not _valid_wait(wait):
                    return {"ok": False,
                            "error": "wait must be between 0.1 and 300 seconds"}
                if not isinstance(all_flag, bool):
                    return {"ok": False, "error": "all must be a boolean"}
                if not _valid_actor(actor):
                    return {"ok": False, "error": "invalid actor"}
                if not _valid_actor(source):
                    return {"ok": False, "error": "invalid source"}
                if (action == "files" and isinstance(args, list) and args
                        and args[0] == "put"):
                    return {"ok": False,
                            "error": ("files put must use the files_put admin "
                                      "op so the daemon can stream the local "
                                      "source")}
                if all_flag and action in ("update", "destroy"):
                    return {"ok": False,
                            "error": f"--all {action} is not supported"}
                if all_flag:
                    b = await self.broadcast(action, args, wait,
                                             actor=actor, source=source)
                    return {"ok": b["ok"], "broadcast": True,
                            "results": b["results"]}
                did = req.get("device_id")
                if not _valid_device_id(did):
                    return {"ok": False, "error": "invalid device_id"}
                if did not in self.devices:
                    return {"ok": False, "error": f"unknown device {did}"}
                entry = await self.dispatch(did, action, args, wait,
                                            actor=actor, source=source)
                return {"ok": entry["status"] == "ok", "entry": entry}
            return {"ok": False, "error": f"unknown op {op!r}"}
        except Exception as e:  # noqa: BLE001 - report to CLI
            return {"ok": False, "error": sanitize_log(str(e))}

    async def run(self, bind, port, admin_host, admin_port):
        if not _check_admin_host(admin_host):
            raise ValueError("non-loopback admin host requires "
                             "GHOSTCTL_ADMIN_TOKEN")
        admin = await asyncio.start_server(
            self.admin_handler, admin_host, admin_port, limit=MAX_ADMIN_LINE)
        try:
            # max_size 32 MiB: screen-shot PNGs arrive base64-inflated in one
            # command response; the default 1 MiB rejected them.
            async with websockets.serve(self.device_handler, bind, port,
                                        max_size=32 * 1024 * 1024,
                                        ping_interval=20, ping_timeout=10):
                log(f"ghostctl daemon: devices ws://{bind}:{port}, "
                    f"admin {admin_host}:{admin_port}")
                async with admin:
                    await admin.serve_forever()
        except Exception:
            admin.close()
            with contextlib.suppress(Exception):
                await admin.wait_closed()
            raise


def main():
    p = argparse.ArgumentParser(description="ghostctl core daemon")
    p.add_argument("--bind", default="0.0.0.0")
    p.add_argument("--port", type=int, default=8443)
    p.add_argument("--admin-host", default="127.0.0.1")
    p.add_argument("--admin-port", type=int, default=8444)
    a = p.parse_args()
    if not _check_admin_host(a.admin_host):
        sys.exit("non-loopback admin host requires GHOSTCTL_ADMIN_TOKEN")
    asyncio.run(Daemon().run(a.bind, a.port, a.admin_host, a.admin_port))


if __name__ == "__main__":
    main()
