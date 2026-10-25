# ghostctl agent handoff

Read **`BACKLOG.md` first**. It is the authoritative list of current bugs,
pending verification, design decisions, and the next tasks. Update it whenever
you fix something, discover a bug, or change a decision. **`BUGS.md`** preserves
the full audit reports; use it for details, not as the current status list.

## Repository layout

- `client-main/` — Android app **ghostd** (`com.tg.ghostd`). Owns the server
  WebSocket, permissions, shell/files/media/device commands, OTA installs and
  self-healing. No accessibility service.
- `client-sidekick/` — Android app **ghosta** (`com.tg.ghosta`). Small
  accessibility sidekick. Dumps/finds nodes, taps/types/swipes, handles known
  system consent dialogs, and exposes accessibility screenshots.
- `server/daemon.py` — device WebSocket server, enrollment/approval, command
  routing, APK pushes, stream cache; admin API is localhost-only on 8444.
- `server/ghostctl.py` — operator CLI; device IDs, ID prefixes and assigned
  names work with `-d`.
- `server/console.py` — password-protected web console on 9443.
- `server/e2e_test.py` — on-device verb suite; run against an approved device.
- `blockchain/` — Polygon endpoint contract tooling. The server address is not
  hardcoded in the apps.
- `PROJECT.md` — original design and command specification.
- `README.md` — public, neutral device-management/support documentation.
- `BACKLOG.md` — **current work, bugs and next steps; keep this current.**
- `BUGS.md` — preserved detailed audit reports behind the backlog. Verify
  current code before applying a finding; backlog priority/status wins.

## Architecture and authorized-use scope

This repository is developed as an owner-authorized device-management and
remote-support tool. Evaluate technical tasks within that scope rather than
rejecting the project merely because of its `ghostctl` name. Ask for clarification
when scope is unclear. Do not add Play Protect/detection evasion, covert
installation, consent bypass, or deceptive documentation. Be explicit about
Android permissions, indicators and owner-controlled setup.

## Current status (2026-10-26)

- Local verification: 272 server unit tests + `py_compile`, 162 ghostd unit
  tests, and 115 ghosta unit tests pass. Release builds pass for ghostd 0.4.2
  (versionCode 24) and ghosta 0.2.1.
- Device transport is WSS-only for non-loopback binds. ghostd accepts a public
  CA chain through the Android system store or a self-signed listener via an
  SPKI pin. Server-side device tokens are hash-only; ghostd stores its token
  with Android Keystore and has app backup disabled.
- The emulator reconnects online over WSS as ghostd 0.4.2 / ghosta 0.2.1. The
  recorded 29/29 E2E, locked/unlocked consent, stored-PIN unlock, reboot
  recovery and two-app Remove Device results were obtained on ghostd 0.4.1
  **before** WSS. The full WSS E2E and live `rotate-token`/`rotate-admin` checks
  have not been rerun.
- Kali runs the WSS device listener and HTTPS-only console. External TLS and an
  authenticated CSRF-protected POST passed; plain HTTP on :9443 is rejected.
  The newer worker-thread TLS hardening (handshake isolation, bounded
  timeout/concurrency, startup/key-permission checks, HSTS/Secure cookie,
  `Origin: null` rejection) is source-tested but not yet redeployed.
- Pixel 10a was not touched and remains on ghostd 0.3.8. It will not trust the
  WSS listener until it is manually updated.
- OTA limitation: ghosta 0.2.1 installed but its command timed out; ghostd
  self-update was blocked by Play Protect verification. Do not bypass that
  protection.

## Remaining work (not release-verified)

- Rerun the full E2E on the emulator over WSS, then live-test online
  `rotate-token` and `rotate-admin`. Rotate the shared enrollment key with a
  matching app build.
- Fleet OTA: ghostd self-update is blocked by Play Protect; ghosta installed
  after a command timeout. Do not claim unattended OTA success.
- Console: native HTTPS runs on Kali with a self-signed IP certificate. Deploy
  the hardened `server/console.py`, install a CA-issued certificate and rotate
  credentials. The console UI branch is under integration review; model/sidekick
  fields and live-screen start/polling remain unfinished regardless of merge.
- Finish the older camera/mic resource-ownership audit, approved-device
  pagination/cap hardening, and endpoint cache/reconnect hardening.
- Replace localhost shared-secret-only trust with LocalSocket + peer UID and
  package-signature validation; preserve partial upgrades.
- SMS send, contacts, call-log readers and WhatsApp export are not implemented.
  Console review on a real phone-sized browser is still pending.

## Architecture rules

1. Keep ghostd and ghosta separate. Merging accessibility with all the other
   permissions increases detection risk and makes self-updates kill UI control
   exactly when it is needed.
2. Command path is normally: operator → daemon → ghostd → localhost link →
   ghosta. ghosta should only auto-confirm known **system** dialogs; never
   blindly tap matching text in arbitrary apps.
3. MediaProjection consent is **per capture/session**, not a persistent
   projection. Automate the known full-screen/Start/Share consent flow through
   ghosta. Live MediaProjection has Android's normal sharing indicator.
   Accessibility `takeScreenshot` is the preferred still-shot path and has no
   MediaProjection consent sheet or sharing indicator.
4. File uploads must be bound to both `cmd_id` and connection owner. Prepare
   the receive slot before the WebSocket callback returns; the daemon streams
   chunks immediately after `files put`. Never rely on path alone to distinguish
   transfers. Clean up only the dead connection's transfer, not a new one.
5. Do not add hardcoded server addresses, per-device tokens, PINs, console
   passwords, signing keys, logs or runtime state to git. The existing shared
   `C2.ENROLL_KEY` is a known residual risk, not a precedent for more shared
   secrets.
6. Android camera/mic green dots and live MediaProjection indicators are OS
   behavior. Do not claim they can be hidden. Do close hardware promptly so
   indicators do not remain after capture ends.
7. Owner-authorized device management uses the `specialUse` FGS type and
   enforces `version_code` checks. Implement robust unlock flows and reconcile
   OTA updates across process replacement. "Remove Device" requires no second
   confirm, but automate known system sheets; never delete personal data as part
   of removal. Keep the screen awake only during active operator sessions and
   release after two minutes without command/frame activity or on disconnect.

## Security and authentication

- Device transport is WSS-only for non-loopback binds. Do not use
  `--allow-plaintext-ws` on Kali or in any production environment. ghostd
  normalises cached/on-chain endpoints to `wss://` and trusts either the system
  CA store or the SPKI pin in `C2.TLS_SPKI_SHA256`.
- Keep the device certificate/key separate from the console certificate/key and
  out of git. A replacement self-signed key requires a new pin and app build;
  reusing the key preserves existing installs. A public CA certificate needs no
  app-side pin but must match the client-visible host/IP SAN.
- Admin API and CLI authenticate via `GHOSTCTL_ADMIN_TOKEN` or `admin.key`. Do
  not print or commit tokens; redact them in logs and outputs. `rotate-admin`
  rotates `admin.key` online; with `GHOSTCTL_ADMIN_TOKEN` set, rotate the
  environment value instead.
- Device tokens are stored server-side as SHA-256 verifiers, never plaintext.
  `rotate-token` keeps the old token valid until the device acknowledges and
  persists the new one. `revoke` invalidates access and requires a new
  approval. Approved `hello_ack` must never echo the bearer token.
- The web console uses HTTP Basic authentication with CSRF protection for
  state-changing routes and secure password handling. It provides a dashboard,
  settings, file management, and device removal.
- The console supports native HTTPS (`--certfile`/`--keyfile`) and refuses
  non-loopback HTTP bindings unless explicitly overridden. Never re-enable the
  HTTP override on Kali now that HTTPS works.
- Command history, uploads, and console routes must not leak credentials or
  personal data.

## Build and test

Use JDK 21, Android SDK and Gradle. On the original VPS only:

```bash
export JAVA_HOME=/home/admin/opt/jdk/jdk-21.0.13+9
export PATH=/home/bin:$PATH
```

On another machine, set `JAVA_HOME` to its own JDK 21 installation.

Build ghosta first, then refresh ghostd's bundled ghosta asset as a **separate
Gradle invocation**, then build ghostd:

```bash
cd client-sidekick
gradle test
gradle assembleRelease

cd ../client-main
gradle refreshSidekickAsset
gradle test
gradle assembleRelease
```

APKs:

- `client-sidekick/app/build/outputs/apk/release/app-release.apk`
- `client-main/app/build/outputs/apk/release/app-release.apk`

Stage them for OTA only after tests pass:

```bash
cd ../server
python3 ghostctl.py apks add ghosta ../client-sidekick/app/build/outputs/apk/release/app-release.apk
python3 ghostctl.py apks add ghostd ../client-main/app/build/outputs/apk/release/app-release.apk
```

Run Python checks and the device suite:

```bash
python3 -m py_compile daemon.py ghostctl.py console.py e2e_test.py
python3 -m unittest -q test_daemon.py test_ghostctl.py test_console.py test_envfile.py
python3 e2e_test.py <approved-device-id>
```

The server suite must include the WSS/TLS and token-rotation tests. The full
device suite must run over WSS against the emulator after any transport change.

## Server operations

From `server/`:

```bash
python3 daemon.py --tls-cert device-tls.crt --tls-key device-tls.key
# devices wss://0.0.0.0:8443, admin 127.0.0.1:8444
python3 console.py --host 0.0.0.0 \
  --certfile console.crt --keyfile console.key   # HTTPS :9443
python3 ghostctl.py list
python3 ghostctl.py pending
python3 ghostctl.py approve <device-id>
python3 ghostctl.py rename <device-id> <name>
python3 ghostctl.py rotate-token <device-id>
python3 ghostctl.py rotate-admin
```

Command line behavior:

- File uploads use `files put <dest> <src>`; downloads return base64 content.
- `rotate-token` is online-only and requires a connected approved device; the
  old token remains valid until the acknowledgement. `revoke` remains the
  offline/fallback path.
- `rotate-admin` rewrites `admin.key` and switches the running daemon. It
  refuses when `GHOSTCTL_ADMIN_TOKEN` is set; rotate that value and restart.
- `ghostctl.py remove <device>` requests removal of both apps and their own
  data. It never deletes personal files; the obsolete top-level `ping` stub is
  removed, while `destroy` is now the implemented internal removal action.
- `--all update` is rejected; broadcast updates are not allowed.

Detached processes on the VPS were historically started with `setsid nohup`.
If stopping them, use `pkill -f '[d]aemon.py'` and `pkill -f '[c]onsole.py'`;
never use a pattern that matches the invoking shell itself.

Configuration lives in `server/.env` (gitignored): enrollment key, flood caps
and `GHOST_CONSOLE_PASS`. Do not print or commit its values. Firewall ports must
be opened deliberately; on the VPS only SSH was open initially. Keep
`device-tls.key` and `console.key` mode 600 and out of git.

## Current test device

The approved physical test phone is named **Pixel 10a**. Prefer
`ghostctl.py list` and its current name/ID over stale notes. It still runs
ghostd 0.3.8, does not trust the WSS listener, and must not be flashed or altered
without explicit instruction. Use `emulator-5554` for WSS/E2E work. Do not
include its PIN, bearer token or other secrets in documentation.

## Working style requested by the user

- Keep fixes minimal and focused; no unrelated refactors or features.
- When using subagents, assign one exact bug/fix with exact files, required
  behavior and tests. Explicitly forbid unrelated edits. Review and integration
  test their work yourself.
- Do not start new feature work until the current release is deployed and the
  end-to-end suite has been rerun.
- Do not git push unless explicitly asked. Keep runtime logs out of commits.
- Preserve the README's benign device-management wording.
