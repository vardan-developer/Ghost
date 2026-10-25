# ghostctl — original design brief (historical)

> **This is the superseded design brief, not current status.** It is preserved
> for protocol and product context. Read `README.md`, `AGENTS.md` and
> `BACKLOG.md` for implemented behavior, verification and remaining work.
>
> Current implementation constraints:
> - Owner-authorized device management only; no stealth, detection evasion or
>   consent bypass.
> - ghostd and ghosta stay separate. MediaProjection consent is requested per
>   capture/session; it is not persistent.
> - Camera/microphone and screen-sharing indicators are Android behavior and
>   are not hidden.
> - Unattended fleet OTA is not release-ready. Play Protect verification must
>   not be bypassed.
> - Device transport is now WSS-only for non-loopback binds. ghostd pins the
>   listener SPKI (or accepts a public CA chain) and normalises endpoints to
>   `wss://`; plaintext is migration-only. Server-side device tokens are
>   SHA-256 verifiers, and online rotation waits for `auth_rotate_ack`.
> - SMS send, contacts, call-log readers and WhatsApp export are not
>   implemented. The current test target is an emulator plus the approved
>   Pixel 10a, not the iQOO Z5 described below.

The original brief described a stealthy Android client (2 apps) plus server
(daemon, web console, CLI) for remote control of enrolled phones. The historical
target was Android 8–16 on an iQOO Z5 (FunTouch OS 13 / Android 13); current
work targets an emulator and the approved Pixel 10a.

## Requirements (agreed with owner)

### Client — main app (ghostd)
- Installed manually via file manager (sideload). One APK install.
- Setup wizard: walks through all permissions once, then hides launcher icon
  via PackageManager component disable. App never visible again except
  Settings → Apps.
- Permissions:
  - normal: INTERNET, FOREGROUND_SERVICE, WAKE_LOCK, RECEIVE_BOOT_COMPLETED,
    REQUEST_INSTALL_PACKAGES
  - runtime: READ_SMS, RECEIVE_SMS, READ_CONTACTS, READ_CALL_LOG, CAMERA,
    RECORD_AUDIO, READ_MEDIA_IMAGES/VIDEO/AUDIO, ACCESS_FINE_LOCATION,
    ACCESS_BACKGROUND_LOCATION
  - special (Settings screens): MANAGE_EXTERNAL_STORAGE, notification access,
    battery-optimization exemption
- Persistent: foreground service (dataSync/remoteMessaging type), START_STICKY,
  boot receiver, battery exemption. Survives reboot/lock/Doze.
- Battery: idle target ~1–2%/day. One WebSocket to server, heartbeat 30–60 s,
  event-driven; no polling, no GPS unless requested.
- Auto-update: silently installs its own updates + updates of sidekick app
  (installer-of-record, same signing key).
- Capabilities (on command): list/read/upload any file; SMS + notification
  reading; contacts/call log; precise location (on demand or schedule);
  shell command execution; screen capture (MediaProjection); device info.
- Auto-destroy command: wipe own data/files → uninstall sidekick (silent) →
  self-uninstall (sidekick auto-taps confirm dialog). Fallback: wipe+disable.

### Client — sidekick app (ghosta)
- Installed BY main app during setup (never handled manually).
- Only special capability: accessibility service (no notification, stealthy).
- Accepts commands only from main app (shared secret baked in at build).
- Jobs: tap/click anything on screen (system dialogs, cast confirmations,
  permission banners), type text (incl. PIN on lockscreen), read screen
  content, auto-confirm main app's uninstall dialog.
- Restart: system auto-rebinds accessibility service; also sticky + boot
  receiver.

### Dynamic UI driving (no hardcoded flows)
The sidekick app is GENERIC: it does not hardcode any app-specific flows.
It exposes a remote element-inspection API:
- `ui dump` — returns the current accessibility node tree as JSON (element id,
  text, description, bounds, clickable, class)
- `ui find --text/--id` — search the live tree
- `tap <id|x,y>`, `type <text>`, `key <code>`, `swipe <x1,y1,x2,y2>`,
  `unlock <pin>` (lockscreen PIN entry)
The operator (human or AI agent) inspects the tree and decides what to tap —
so new apps/flows (2FA prompts, exports, logins) need zero code changes.
`auth approve`, `passwords dump`, `wa dump`, `wa link` are all just
sequences of these generic verbs driven from the server.

### Sidekick local link
Sidekick listens on a localhost TCP socket (127.0.0.1) with a shared
secret; ghostd connects and forwards the generic UI verbs. No internet
exposure.

### Night-control flow (screen stealth)
- Wake screen, start MediaProjection (dialog auto-tapped by sidekick),
- brightness → minimum (AMOLED near-invisible in dark), force dark theme,
- stream screen to operator, type PIN via accessibility to unlock,
- full remote control; restore brightness/lock on finish.

### Command spec (v1 target verbs)
list/status/devices; device info; files ls/tree/get/put/find/thumb/watch;
sms recent/dump/send; contacts; calls dump; keys dump; screen
live/shot/record/start/stop; tap; type; key; unlock/lock;
passwords dump (Chrome + Google Password Manager via UI automation: open entry,
tap eye, PIN fallback auth, read off screen, repeat; outputs JSON);
auth approve = SERVER-SIDE MACRO, not a client verb: just "ui dump → find the
confirm button → tap" driven from the operator/CLI (no hardcoded client code);
wa link
(WhatsApp linked-devices via phone-number code, no QR); wa dump
[--contact "<name>" | --all] (UI automation of WhatsApp's own
Export chat incl. media, per contact or every chat; contact picked by
name/number match); cam snap/record (front/back); cam live --quality <low|med|high|1080p|720p|480p>
(--quality selects resolution/bitrate, modifiable per stream); mic record;
mic live (real-time audio stream to server); loc
once/track/history/geofence; app list/open; shell; update; destroy;
hide/unhide; brightness get/set (0–255, stealth dimming); volume get/set/mute (streams:
ring/media/alarm).

Note: notification-capture verbs (notif stream/dump) are intentionally
SKIPPED (owner decision: too complex vs value; live read + sms + auto-export
cover the need).

### Media-capture stealth notes
- Camera shutter sound: we capture via the Camera2 API directly (never the
  camera app) → no shutter sound on virtually all devices incl. iQOO.
- Android 12+ shows a small green privacy dot in the status bar while
  camera/mic are in use — cannot be removed without root. Mitigation: keep
  captures short (dot only shows during use), prefer ambient-light/at-night
  capture, capture while screen is off+locked (indicator only shows on
  unlocked screen; dot appears in status bar when screen on).
- Screen brightness 0 + dark theme during night ops; restore after.

### Server (on this VPS)
- Core daemon: device registry (per-device ID), WebSocket listener,
  command router, per-device + broadcast commands, device status
  (online/offline, last seen, battery, IP).
- Web console: MeshCentral-inspired — device list, click device → actions,
  broadcast actions, command history.
- CLI (ghostctl): same feature set as console, JSON output by default,
  --pretty for humans. Scriptable/automatable for agent use.
- One inbound port needs `sudo ufw allow` (e.g. 443/tcp); do NOT open
  preemptively — only when the listener is ready.

### C2 endpoint discovery via blockchain (NO hardcoded server IP)
- The apps never hardcode the server address. Instead, the current server
  endpoint (host[:port]) is stored in a public smart contract; the client
  reads it on connect/reconnect so moving the C2 needs only a contract
  update — no app changes, no re-deploy.
- Contract: tiny EVM contract with a public string endpoint + setter
  (owner-writable). Chain: a cheap public EVM chain readable via multiple
  free public JSON-RPC endpoints (no API keys). Deploy later with the
  owner's wallet.
- Client behavior: `eth_call` to a list of public RPC endpoints (with
  fallbacks); cache last-known-good endpoint locally; re-resolve on every
  connect attempt + periodically (e.g. hourly) while connected so an IP
  change is picked up automatically.
- Only the contract address + chain/RPC list are baked into the app
  (addresses don't change; endpoints do).

### Repo hygiene
- Project is a git repo (init now; owner pushes to GitHub later).
- `.gitignore` must exclude: `keys/` (signing keystore), build outputs
  (`**/build/`, `**/.gradle/`, `**/.idea/`), local SDKs, server secrets
  (`.env`, `*.pem`, wallet keys `*.json` in keys/).

### Stealth constraints
- No accessibility permission in MAIN app (sidekick holds it) — reduces
  Play Protect suspicion profile.
- Avoid known-malware code/signatures; custom build, not a public RAT.
- Play Protect may still false-positive at install; user taps
  "Install anyway (unsafe)" once; app learns.
- FunTouch/iQOO checklist: auto-start enabled, high background power allowed.

## Layout (planned)
```
projects/ghostctl/
├── PROJECT.md          # this file
├── client-main/        # Android app "ghostd"
├── client-sidekick/    # Android app "ghosta" (accessibility)
├── server/            # daemon + console + CLI
└── keys/              # signing keystore (chmod 600, never commit)
```

## Milestones
1. Scaffold + Android SDK toolchain on this box; build empty ghostd APK.
2. ghostd: setup wizard (permissions + hide icon + battery exemption),
   persistent FGS, WebSocket client. Owner tests on own phone.
3. ghosta: accessibility service, local link to ghostd, dialog auto-tap.
4. Screen capture + night-control flow (brightness 0, PIN entry).
5. Server core + CLI + web console; silent updates; auto-destroy.
6. Field test on iQOO Z5 (Android 13).
ghostd 0.2.1+ self-provisions the ghosta sidekick from the bundled asset (client-main/app/src/main/assets/ghosta.apk, refresh via `gradle -p client-main refreshSidekickAsset`).
