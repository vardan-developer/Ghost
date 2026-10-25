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

### Server authentication

- Admin API and CLI require `GHOSTCTL_ADMIN_TOKEN` or `admin.key`. Tokens are
  redacted in logs and outputs.
- The web console uses HTTP Basic auth, CSRF protection for state-changing
  routes, and secure password handling. It binds to localhost by default and
  supports native HTTPS with `--certfile`/`--keyfile`. Non-loopback HTTP is
  refused unless explicitly overridden; use HTTPS for remote access.

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
python3 console.py --host 0.0.0.0 --certfile console.crt --keyfile console.key
# web console on https://0.0.0.0:9443 (password from .env / console.pass)
```

Open the firewall for the device port (8443/tcp) and the console port if you
want remote access to it. All admin surfaces are localhost-bound by default.

### CLI (`server/ghostctl.py`)

```text
list | pending | approve <id> | revoke <id> | rename <id> <name>
config [set k v]
-d <id> shell "cmd"                       run a shell command
-d <id> files ls|tree|get|put|abort ...   file browser + transfer
-d <id> screen shot|live|record|stop|off screen capture (MJPEG live)
-d <id> cam snap|live|stop               camera
-d <id> mic record|live|stop             microphone
-d <id> sms recent [N] | loc once        messages / current location
-d <id> device info | status             device and agent health
-d <id> brightness|volume|wake          device controls
-d <id> ui dump|find                     accessibility tree (JSON)
-d <id> tap|longclick|type|key|swipe     UI automation (node-id or x,y)
-d <id> unlock <pin>                      keyguard PIN entry
-d <id> unlock --forget                   forget the stored keyguard PIN
-d <id> hide | unhide                     launcher icon visibility
-d <id> restart                           restart the device agent
-d <id> arm|disarm                        sidekick dialog auto-confirmation
-d <id> sidekick status|arm|install      helper app management
-d <id> update self|sidekick              OTA updates (hash-verified)
remove <id>                               uninstall both apps + their own data
--all <verb> ...                          broadcast to all approved devices (except update)
```

Everything outputs JSON (scriptable); `--pretty` for humans.

### Web console (`server/console.py`)

A password-protected, CSRF-protected operator dashboard on :9443. The UI is
responsive and includes device cards with model/version/battery/IP/last-seen,
search/filter and pending approvals; grouped Files, Screen, UI, Media and
System actions; readable history/results; live-screen start/stop with frame-age
health; upload/download progress and abort; flood-cap settings; and Remove
Device without a second confirmation. It also has token redaction, CSP,
request limits and stream tokens. Native HTTPS is available with
`--certfile`/`--keyfile`; non-loopback HTTP is refused unless explicitly
overridden. For an IP-only host, use a self-signed certificate and trust it
once in the browser (or put the console behind a CA-issued TLS proxy).

The CSRF token is scoped to the running console process. After a console
restart (or an HTTP-to-HTTPS switch), reload the login/page once; posting from
an old tab can show `CSRF check failed` even though the password is correct.

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

## Development handoff

Agents and contributors should read **`AGENTS.md`** for architecture, build
commands and authorized-use scope, then **`BACKLOG.md`** for current bugs,
verification status and prioritized tasks. Those two files are updated as work
moves; do not rely on old chat history for repository state.

## Reliability design

- **Foreground service** + wake lock + battery-optimization exemption:
  survives Doze and reboots (boot receiver).
- **Connection watchdog**: dead/half-open sockets are detected and recreated
  automatically (no manual restarts after network changes).
- **Self-restart**: if the service can't hold a connection for 10 minutes
  while the network is up, it restarts itself with fresh threads.
- **Babysitter**: ghosta tracks ghostd's localhost heartbeat beacon and
  restarts it if it goes silent for 15 minutes.
- **Restart after update**: `MY_PACKAGE_REPLACED` receiver is implemented for
  package replacement. Actual OTA still depends on Android install eligibility
  and Play Protect verification; it is not guaranteed or fleet-ready.
- **File pushes are serialized** and path-verified end to end (SHA-256 +
  size checked on the device; cross-path chunks are ignored).

## Testing

- `client-main`: `gradle test` (154 JVM unit tests — protocol, transfer,
  lifecycle, consent, update and wake-session behavior).
- `client-sidekick`: `gradle test` (115 unit tests — command router, tree,
  consent, link, unlock and removal state machines).
- `server`: `python3 -m py_compile daemon.py ghostctl.py console.py e2e_test.py`
  and 249 unit tests pass, including native TLS wrapping and CSRF-origin tests.
- Release builds pass. On the emulator, full E2E is 29/29 and unlock,
  per-capture consent, reboot recovery and two-app removal pass.

### Remaining work

- **OTA:** ghosta installed after a command timeout; ghostd self-update was
  blocked by Play Protect verification. Fleet OTA is not ready; do not bypass
  Play Protect.
- **Transport:** the console runs native HTTPS on Kali, but with a self-signed
  IP certificate; swap in a CA-issued certificate and rotate credentials after
  deployment. Device transport still needs TLS/WSS.
- **Hardening:** older camera/mic resource ownership, endpoint cache/reconnect,
  localhost peer authentication and approved-device pagination/caps.
- **Features:** SMS send, contacts, call-log readers and WhatsApp export are
  not implemented. A real phone-sized console review is pending.

## Repository layout

```text
client-main/     ghostd app (Android)
client-sidekick/ ghosta app (Android)
server/          daemon, CLI, web console, fake client, .env
blockchain/      Polygon contract + deploy/update/read scripts
keys/            signing keystore (gitignored)
PROJECT.md       original design notes + full command spec
```
