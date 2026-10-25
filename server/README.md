# ghostctl server

Core daemon + CLI for the ghostctl Android remote-control toolset (see ../PROJECT.md).

## Files
- `daemon.py` — core daemon: WebSocket device listener (default `0.0.0.0:8443`),
  device registry (`state.json`), command router + history (`history.json`),
  admin JSON-RPC on `127.0.0.1:8444` (CLI transport).
- `ghostctl.py` — CLI (symlink-friendly, e.g. `ln -s .../ghostctl.py ~/.local/bin/ghostctl`).
- `console.py` — web console (see below).
- `fake_client.py` — mock Android client for testing.

## Run
```sh
python3 daemon.py                     # defaults: 0.0.0.0:8443, admin 127.0.0.1:8444
python3 daemon.py --port 9000 --admin-port 9001
```

## CLI
```sh
python3 ghostctl.py list                                   # device registry (JSON)
python3 ghostctl.py -d test-1 shell "echo hi"               # per-device command
python3 ghostctl.py -d test-1 files ls /sdcard
python3 ghostctl.py -d test-1 files get /sdcard/x.jpg
python3 ghostctl.py -d test-1 sms recent 20
python3 ghostctl.py -d test-1 screen start
python3 ghostctl.py -d test-1 loc once
python3 ghostctl.py -d test-1 update
python3 ghostctl.py -d test-1 destroy
python3 ghostctl.py --all update                            # broadcast
python3 ghostctl.py --pretty list                           # human-readable
```
Admin endpoint configurable via `GHOSTCTL_ADMIN_HOST` / `GHOSTCTL_ADMIN_PORT`.

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
    files ls/get, sms recent, screen start/stop, loc once, update,
    destroy w/ confirm) + per-device command history. `cam`/`mic`/
    `brightness`/`volume` buttons will be added when the daemon/CLI gain
    those actions.
  - `/broadcast` — pick a verb, run it with `--all` semantics.
  - `/stream/<id>` — MJPEG display; the daemon does not relay binary media
    streams yet, so `/stream/<id>/feed` answers 503 and the page shows a
    waiting state until a stream op exists.
- POST `/device/<id>/cmd` (form fields `verb`, optional `arg`) is the form
  endpoint; results render as a JSON page. Verbs map exactly to ghostctl.py
  (`shell` sends one string arg like the CLI does).
Actions routed: `files ls/get`, `sms recent`, `shell <cmd>`, `screen start/stop`,
`loc once`, `update`, `destroy` (client-side implementations land with the Android app).

## Test
```sh
python3 daemon.py &                 # or use --bind 127.0.0.1
python3 fake_client.py test-1 &      # mock device
python3 ghostctl.py list
python3 ghostctl.py -d test-1 shell "echo hi"
GHOST_CONSOLE_PASS=pw python3 console.py &   # then browse http://127.0.0.1:9443
```

Requires `websockets` (`pip install --user websockets`). Do not commit
`state.json` / `history.json` (runtime artifacts, see `.gitignore`).
