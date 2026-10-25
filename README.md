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
| **server** | `server/` | Python daemon (WSS device listener + localhost admin RPC), `ghostctl.py` CLI, and an operator web console. |

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

### Device transport and authentication

- The device listener is **WSS (TLS)**. A non-loopback plaintext bind is
  refused unless the operator explicitly passes `--allow-plaintext-ws`; that
  switch exists only for migration and is not for production use.
- The client always normalises the resolved endpoint to `wss://`, refuses a
  non-WSS endpoint, and upgrades cached or on-chain `ws://` values.
- Publicly trusted certificates are accepted through the Android system trust
  store. A self-signed listener certificate is accepted only when its
  SubjectPublicKeyInfo SHA-256 matches a pin in
  `client-main/.../C2Config.kt` → `C2.TLS_SPKI_SHA256`. Reusing the listener key
  when renewing a self-signed certificate keeps existing installs working; a
  new key requires a new pin and an app update.
- Every device hello carries an **enrollment key** (shared secret set in
  `server/.env` → `GHOST_ENROLL_KEY`, mirrored in
  `client-main/.../C2Config.kt` → `C2.ENROLL_KEY`). Wrong key = instant
  disconnect. The enrollment key only permits a **pending** enrollment; it
  does not grant command access.
- New devices land in a **pending** list — they heartbeat but receive no
  commands until you run `approve`.
- Each approved device holds a unique bearer token. The server stores only a
  SHA-256 verifier and does not return the token in an approved `hello_ack`.
  The client stores it encrypted with Android Keystore, and app backup is
  disabled.
- `revoke <id>` rotates the token. `rotate-token <id>` performs an online
  rotation while the device is connected: the old token remains valid until
  the device acknowledges and persists the new token. If the acknowledgement
  never arrives, the old token stays valid and the device can promote the new
  token on its next hello.
- Flood protection: caps on pending devices and per-IP connections
  (configurable live: `ghostctl.py config set max_pending 50`).
- Residual risk: `C2.ENROLL_KEY` is still a shared value compiled into the app.
  It is not a command credential, but it should be rotated together with a
  matching app build when that is operationally safe.

### Server authentication

- Admin API and CLI require `GHOSTCTL_ADMIN_TOKEN` or `admin.key`. Tokens are
  redacted in logs and outputs.
- `rotate-admin` generates a new `server/admin.key` online and switches the
  running daemon to it. If `GHOSTCTL_ADMIN_TOKEN` is set, rotate that value in
  the environment/`.env` and restart instead.
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

python3 daemon.py --tls-cert device-tls.crt --tls-key device-tls.key
# devices wss://0.0.0.0:8443, admin 127.0.0.1:8444
GHOST_CONSOLE_PASS=... python3 console.py --host 0.0.0.0 \
  --certfile console.crt --keyfile console.key
# web console at https://SERVER_IP:9443 (non-loopback requires an explicit password)
```

The device certificate must have a SAN for the host or IP clients use. For a
temporary self-signed pair:

```bash
openssl req -x509 -newkey rsa:3072 -sha256 -nodes -days 3650 \
  -keyout device-tls.key -out device-tls.crt \
  -subj "/CN=your-host" \
  -addext "subjectAltName=DNS:your-host,IP:203.0.113.10"
chmod 600 device-tls.key
```

For a publicly trusted certificate, no app-side pin is needed. For a self-signed
listener, add its SPKI SHA-256 to `C2.TLS_SPKI_SHA256` and rebuild ghostd:

```bash
openssl x509 -in device-tls.crt -pubkey -noout |
  openssl pkey -pubin -outform DER |
  openssl dgst -sha256 -binary |
  openssl base64 -A
```

Use the resulting base64 value (with padding) as the pin. Keep `device-tls.key`
mode 600. Do not reuse the console certificate/key for the device listener.

Open the firewall for the device port (8443/tcp) and the console port only if
you need remote access to it; do not expose the daemon admin port. All admin
surfaces are localhost-bound by default, and the private key must be mode 600.

### CLI (`server/ghostctl.py`)

```text
list | pending | approve <id> | revoke <id> | rename <id> <name>
rotate-token <id>                         online device-token rotation
rotate-admin                              rotate server/admin.key online
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

A password-protected, CSRF-protected operator dashboard on :9443. The backend
provides device cards, search/filter, pending approvals, grouped
command pages, recent history, flood-cap settings and Remove Device without a
second confirmation, with token redaction, CSP, request limits and stream
tokens. Native HTTPS is available with `--certfile`/`--keyfile`; non-loopback
HTTP is refused unless explicitly overridden. For an IP-only host, a
self-signed certificate is a temporary option; replace it with a CA-issued
certificate for normal use.

Known console UI gaps: device model/sidekick fields do not populate because the
daemon does not persist them, and live-screen start/polling are not fully wired
through. The console visuals branch is merged into `main`; it fixes upload
cancellation, removes the inaccurate download progress/abort claims, and
accepts exact-path downloads containing spaces, punctuation and non-ASCII
characters. Backend API tests alone do not prove browser workflows, and a
real phone-sized browser review remains pending.

The CSRF token is scoped to the running console process. After a console
restart (or an HTTP-to-HTTPS switch), reload the login/page once; posting from
an old tab can show `CSRF check failed` even though the password is correct.
`Origin: null` is rejected even with a valid token.

The current Kali deployment is HTTPS-only on :9443 with a self-signed
certificate for its IP. External TLS and an authenticated CSRF-protected POST
were verified; plain HTTP to that port is rejected. The newer worker-thread TLS
hardening is source-tested but still needs to be redeployed. Trust the
certificate once in the browser temporarily, and replace it with a CA-issued
certificate before treating the console as production-ready.

The device listener on the same host is WSS-only with a self-signed certificate
pinned in ghostd; the emulator reconnects over WSS. The certificate is separate
from the console certificate. A public CA certificate can replace it without an
app update, while replacing a self-signed key requires a new pin and app build
(see the transport section above).

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

JSON over WSS (device port 8443; plaintext is refused for non-loopback binds):

- `hello` (device_id, model, battery, enroll_key, token?) →
  `hello_ack` (approved | pending | denied). A pending acknowledgement returns
  a new token; an approved acknowledgement does not echo the bearer token.
- `auth_rotate` (new bearer token) → `auth_rotate_ack` (online rotation; the
  old token remains valid until acknowledgement)
- `ping` → `pong` (45 s heartbeat; the client also force-reconnects if no
  inbound traffic for ~135 s)
- `{"type":"cmd","cmd_id","action","args"}` → `{"type":"result",...}`
- binary frames: `file_chunk` / `file_end` (transfers), MJPEG `frame`
  (live streams)

The client authenticates the listener with the system CA store or, for a
self-signed certificate, an SPKI pin compiled into `C2.TLS_SPKI_SHA256`. It
normalises cached and on-chain endpoints to `wss://` and refuses plaintext.
Server-side bearer tokens are stored only as SHA-256 verifiers; the client
encrypts its token with Android Keystore.

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

- `client-main`: `gradle test` (162 JVM unit tests — protocol, WSS pinning,
  token storage/rotation, transfer, lifecycle, consent, update and wake-session
  behavior).
- `client-sidekick`: `gradle test` (115 unit tests — command router, tree,
  consent, link, unlock and removal state machines).
- `server`: `python3 -m py_compile daemon.py ghostctl.py console.py
  console_ui.py e2e_test.py` and 284 unit tests pass on `main`, including TLS
  handshake, worker-thread isolation, startup validation, token
  hashing/migration, rotation, CSRF-origin and console UI presentation tests.
  The Playwright browser suite passes 42 responsive layouts and interaction
  checks; it requires an installed Playwright + Chrome and is not run by the
  Python suite.
- Release builds pass for ghostd 0.4.2 / versionCode 24 and ghosta 0.2.1. The
  prior 29/29 E2E, unlock, per-capture consent, reboot recovery and two-app
  removal results were recorded on ghostd 0.4.1 **before** WSS was introduced.
  The emulator now reconnects over WSS on 0.4.2; the full WSS E2E and live
  token/admin rotation still need to be rerun.

### Remaining work

- **OTA:** ghosta installed after a command timeout; ghostd self-update was
  blocked by Play Protect verification. Fleet OTA is not ready; do not bypass
  Play Protect.
- **Transport:** device WSS is deployed with a self-signed pinned certificate
  and the emulator reconnects over it. The full WSS E2E suite and live
  `rotate-token`/`rotate-admin` checks are still pending. Pixel 10a remains on
  ghostd 0.3.8 and must be updated manually before it can use the WSS-only
  listener.
- **Console:** native HTTPS on Kali still uses a self-signed IP certificate;
  deploy the hardened backend, install a CA-issued certificate and rotate
  credentials. Device transport is now WSS; the console certificate is separate.
- **Console UI:** model/sidekick metadata and live-screen start/polling still
  need fixes and a real phone-sized browser review. Do not claim those are
  complete.
- **Hardening:** older camera/mic resource ownership, endpoint cache/reconnect,
  localhost peer authentication and approved-device pagination/caps.
- **Features:** SMS send, contacts, call-log readers and WhatsApp export are
  not implemented.

## Repository layout

```text
client-main/     ghostd app (Android)
client-sidekick/ ghosta app (Android)
server/          daemon, CLI, web console, fake client, .env
blockchain/      Polygon contract + deploy/update/read scripts
keys/            signing keystore (gitignored)
PROJECT.md       original design notes + full command spec
```
