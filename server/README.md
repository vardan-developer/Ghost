# ghostctl server

Core daemon + CLI for the ghostctl Android remote-control toolset (see ../PROJECT.md).

## Files
- `daemon.py` — core daemon: WebSocket device listener (default `0.0.0.0:8443`),
  device registry (`state.json`), command router + history (`history.json`),
  admin JSON-RPC on `127.0.0.1:8444` (CLI transport).
- `ghostctl.py` — CLI (symlink-friendly, e.g. `ln -s .../ghostctl.py ~/.local/bin/ghostctl`).
- `console.py` — web console stub (later milestone).
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
Actions routed: `files ls/get`, `sms recent`, `shell <cmd>`, `screen start/stop`,
`loc once`, `update`, `destroy` (client-side implementations land with the Android app).

## Test
```sh
python3 daemon.py &                 # or use --bind 127.0.0.1
python3 fake_client.py test-1 &      # mock device
python3 ghostctl.py list
python3 ghostctl.py -d test-1 shell "echo hi"
```

Requires `websockets` (`pip install --user websockets`). Do not commit
`state.json` / `history.json` (runtime artifacts, see `.gitignore`).
