# ghostctl backlog, bugs and next steps

This is the working handoff for the next machine/session. Read `AGENTS.md` for
layout and build commands. Keep this file factual: distinguish implemented,
tested, deployed and merely designed.

Last updated: 2026-10-26.

## Current state

- Physical test phone: **Pixel 10a**, approved and controllable. It must remain untouched during current local/emulator work.
- Phone currently reports **ghostd 0.3.8**. Working tree is **ghostd 0.4.2 / ghosta 0.2.1**.
- **Local full verification now passes:** 284 server unit tests + `py_compile`, 162 ghostd unit tests, 115 ghosta unit tests, plus 42 browser layout/interaction checks, and release builds.
- **Emulator:** Runs ghostd 0.4.2 / ghosta 0.2.1 online over WSS. The full
  **29/29 E2E now passes over WSS**. Live `rotate-token` and `rotate-admin`
  passed; the emulator reconnected with the rotated token and the console/admin
  API kept working. Stored-PIN unlock, locked-screen consent, fresh grants,
  reboot recovery and two-app Remove Device were recorded on ghostd 0.4.1
  before WSS and have not all been rerun.
- **Kali server:** WSS daemon and hardened native-HTTPS console are redeployed
  and running. External TLS, authenticated CSRF POST and a real-browser native
  form POST passed; plain HTTP on :9443 is rejected. The console still uses a
  self-signed IP certificate.
- Historical first e2e run (superseded by the 29/29 result above) passed 17/22
  checks. Its failures were: a wrong volume test invocation, an interrupted
  file push, the phone's camera-access policy, and live screen producing no
  frames. Do not treat these as current blockers.
- `device info`, `status`, `sms recent`, `loc once`, `hide`, `unhide`,
  `files abort` and `restart` passed on the emulator. `sms recent` is not yet
  proven on the Pixel. SMS send, contacts and call-log readers remain
  unimplemented. Remove Device passed the complete two-app uninstall flow.
- The console backend and pages are implemented with dashboard, settings,
  file management, CSRF protection, admin auth and secure password handling.
  Browser UI gaps are listed below. The password comes from gitignored
  `server/.env`; never commit it.
- Device rename works in daemon, CLI and console. `-d` accepts a full ID, an
  unambiguous ID prefix, or a device name.

## Immediate next steps — remaining hardening

1. Rotate the shared enrollment key with a matching app build.
2. Finish the camera/mic resource-ownership audit below (close every session,
   reader and worker on stop/timeout).
3. Replace localhost shared-secret-only peer trust with LocalSocket + peer UID
   and package-signature validation without breaking partial upgrades.
4. Console TLS hardening is deployed with the WSS listener and verified over
   external HTTPS, including a real-browser native form POST. Replace the
   self-signed console certificate with a CA-issued certificate and rotate the
   console password.
5. Finish the remaining console UI fixes: populate device model/sidekick
   fields from daemon state and wire live screen start/polling. The console
   visuals work is merged and deployed; upload cancellation and exact-path
   downloads are fixed, and download progress is left to the browser. Review on
   a real phone browser before claiming the UI verified.
6. Add approved-device pagination/output caps to admin list/pending paths and
   finish endpoint-cache/reconnect hardening.
7. OTA limitation is verified: ghosta installed but its command timed out;
   ghostd self-update was blocked by Play Protect verification. Do not bypass
   that protection or claim unattended fleet OTA is ready.

## P0 — correctness / recovery bugs

### File transfer races (implemented and emulator-verified)

Files: `FileReceiver.kt`, `CommandHandler.kt`, `GhostService.kt`,
`server/daemon.py`, `FileReceiverTest.kt`.

Required invariants:

- Every chunk/end belongs to the initiating `cmd_id`; path alone is unsafe when
  retrying the same destination.
- Cleanup after an old socket dies must not abort a transfer on a new socket.
  Bind transfers to their owning WebSocket/connection generation.
- The slot is prepared before the WebSocket callback returns. Worker-side
  preparation reintroduces the early-chunk/file_end loss race.
- Stop streaming after device rejection; do not send an entire APK to a slot
  that refused it.
- Never delete the old destination before a failed rename. Use atomic move
  where supported, with a safe replace fallback.
- Validate closed streams, byte/chunk counts, SHA-256 and consistent totals.
- Regression tests must cover early frames, same-path/wrong-cmd_id isolation,
  owner-specific abort, immediate abort/retry, and corrupt end markers.

Root cause of the original wedge: the daemon could stream chunks before the
worker created `pending`; early frames and file_end were discarded. Recovery
then depended on the waiting command thread, so disconnect/service restart
could leave the static slot blocked. Timeouts alone do not fix the ordering.

Integration review also found an OTA-specific double-receive bug: the daemon
pushed the APK and then sent `update self` without its path, causing the app to
wait for another upload. The daemon now sends the uploaded destination
explicitly, and `AppUpdater` uses an already-pushed default APK instead of
opening a second receiver. Verify the complete OTA path on the phone.

### Camera/mic resource leaks and stale capture threads

Files: `CameraCapturer.kt`, `MicCapturer.kt`, `MediaBus.kt`.

Highest-value fixes from the audit:

- Close `CameraDevice`, `CameraCaptureSession` and `ImageReader` in all
  success/error/late-callback paths. A late `onOpened` after stop/timeout must
  close immediately instead of creating a session.
- `cam snap` currently leaks its reader; live camera does not reliably close
  device/session. These leaks keep the green indicator on and break the next
  capture.
- `mic stop` must call `AudioRecord.stop()` to unblock `read()`. `mic record`
  and `cam snap` must honor stop, not only live streams.
- `MediaBus.forceEnd()` releases only the logical slot, not necessarily the
  hardware. A stale thread can later send into a new socket. Use a per-session
  generation/token and signal the owning capturer to stop.
- `MediaBus.begin()` must roll back the busy slot if wake-lock acquisition or
  thread startup fails. Only release a wake lock when the ending session owns
  it.
- Always emit one `stream_end`, including exceptional exits. Audio's top-level
  encoding must be PCM, not hardcoded MJPEG.
- Handle `AudioRecord.read()` errors/zero reads without spinning; protect
  `release()` calls. Prefer elapsed-realtime for deadlines.
- `stop()` should briefly join the capture worker after signaling it, so an
  immediate restart does not report a stale `busy` slot. Bound the wait and
  retain a force-stop path for genuinely wedged callbacks.
- Add command/session identity to `stream_start`/frames/end where required by
  the wire protocol. Do not let a late start/end from session A describe
  session B. Long recordings need a size cap or file-path result instead of
  building an unbounded WAV + base64 + JSON copy in memory.
- Lower-priority correctness: choose camera sizes at least as large as the
  requested preset when available, use the preset JPEG quality for snaps,
  bound single-shot image queues, and report `socket closed` rather than a
  misleading normal stop after send failure.

Do not attempt to suppress Android's camera/mic privacy indicators. Fixing
resource ownership shortens them legitimately; hiding them is not an app-side
supported capability.

### Localhost sidekick link lifecycle

Files: `SidekickLink.kt`, `LocalLinkServer.kt`, sidekick babysitter.

- `LocalLinkServer.start()` can swallow bind failure while leaving
  `running=true`, preventing future retries. Ensure bind/accept failure closes
  the socket and resets state; make socket state thread-visible.
- Add a pre-auth read timeout and a small connection cap. Track and close
  accepted sockets in `stop()`; check `running` after reads so no command runs
  during teardown.
- `SidekickLink.send()` currently retries after a request may have been written
  and executed. Only retry before a successful write, or add request IDs and
  dedupe. Otherwise taps/types/unlocks can execute twice.
- The local liveness beacon must not depend on the C2 heartbeat. During a C2
  outage the auto-arm timer should send local `ping`, so a healthy ghostd is
  not force-restarted every 15 minutes.
- The static localhost shared secret and lack of peer UID/signature validation
  are weaknesses. Preferred long-term fix: `LocalSocket` plus peer UID/package
  signature verification. Do not rotate only one app's literal secret; that
  breaks partial upgrades.
- `startAutoArm()` on an already-connected link may never send `arm` because
  its connection callback does not fire again. Re-arm once when appropriate,
  while preserving an explicit operator `disarm` across routine pings.
- Lifecycle `close()` must not block the Android main thread behind a 10–20 s
  localhost request. Use best-effort socket closure/short try-lock semantics.
- Reset sidekick liveness state when a new local server starts; a stale
  timestamp from the previous instance must not suppress or trigger recovery.

## P0/P1 — consent and screen capture

User decision: **request MediaProjection consent for each capture/session**;
do not build a persistent projection engine. ghosta should automatically
complete the known system flow using bounded rules.

- Keep the existing whole-display request on API 34+.
- Handle the full-screen/default selection and the affirmative button
  (`Start now`, `Share screen`, OEM equivalents) only inside recognized system
  dialog packages.
- Prefer node clicks; use nearest clickable ancestor, then bounded coordinate
  fallback for non-clickable labels. Add retries for multi-step sheets and
  refresh the tree after each step.
- Never auto-tap matching words in arbitrary application windows. In
  particular, `com.android.systemui` also contains keyguard/shade UI: require
  MediaProjection/PackageInstaller/permission activity **class markers**, not
  only a package prefix, and skip generic auto-confirm while keyguard is locked.
- Prefer clickable candidates before text/title nodes. Bound attempts per
  consent window (about three), reset on window changes, and do not let the old
  5 s same-node suppression swallow the next legitimate consent sheet.
- The MediaProjection path must not depend on a racy asynchronous `arm`
  arriving after the dialog. Scope its always-on behavior strictly to the
  capture consent class; installer/permission confirmation remains armed-only.
- Release the projection after each shot/stream without interrupting its own
  worker or wedging `ProjectionHub`. The next capture must obtain a fresh grant.
- `ProjectionHub.awaitGrant` currently leaves stale latch/cancel state after
  timeout, interruption or stop, so later consent attempts can wait forever or
  fail immediately. Add grant-request tokens, clear latch/waiter state on every
  exit, ignore late results, move activity start outside the lock, and bound
  follower waits. `ScreenCaptureActivity` needs an `onDestroy` cancellation
  result. These are verified code defects, not OEM speculation.
- MediaProjection/MediaBus cleanup needs session ownership: an old worker must
  not stop, clear or send into a new capture after force-end.
- A user stopping sharing through Android's cast UI must also stop the capture
  loop. At present an open-ended live worker can poll forever after the
  projection callback. The no-first-frame error path must release worker,
  MediaBus and projection before throwing.
- `screen stop` must release the projection as well as the virtual display;
  `screen off` can remain an alias. Service destruction needs a non-blocking
  capture force-stop, and a stopped shot must wake its command waiter.
- Recycle shot bitmaps and drain/close queued images. Accessibility screenshots
  must close `HardwareBuffer` in `finally` and recycle a bitmap delivered after
  timeout. Retry a throttled accessibility shot once rather than immediately
  opening a projection consent flow.
- Locked phones suppress consent UI. Wake/unlock first when a stored PIN is
  available; otherwise return a clear actionable error instead of a long
  timeout.
- `screen shot` should prefer ghosta accessibility `takeScreenshot` (working
  tree implementation, needs device verification) and fall back to
  MediaProjection. Live projection streaming retains Android's indicator.

## P1 — security and reconnect robustness

### Transport and credentials

- Device C2 currently defaults to plaintext `ws://`; hello contains the enroll
  key and device bearer token. Deploy TLS before switching the client default
  to `wss://`; otherwise all devices lose connectivity. Use a valid certificate,
  update endpoint normalization/docs together, then rotate exposed credentials.
  Do not make a client-only `wss` change.
- `android:allowBackup="true"` exposes preferences containing the device token
  and an encrypted PIN blob whose Keystore key is not backed up. Disable backup
  or exclude `ghostd.xml`; encrypt the token; self-heal undecryptable PIN blobs;
  delete the Keystore entry on `unlock --forget`; report storage failure.
- `HelloAck.parse` must not convert JSON null into the literal token `"null"`.
  Trim/validate status and add parser tests.

### Endpoint and local recovery

- Cache only an endpoint that actually connected, not merely one returned by an
  RPC. Add cache timestamp/TTL and consider token endpoint binding.
- Fresh RPC resolution can delay reconnect by roughly 80 seconds before trying
  the cache. Use a known-good cache immediately and refresh in the background,
  or enforce a short overall deadline. A two-RPC agreement check is a later
  hardening option, not a prerequisite for the current release.
- Synchronize first-time `deviceId` creation. Use synchronous preference commit
  for setup/token values where immediate process death would lose state.
- Replace the observed failing Polygon RPC fallback only after checking a real
  candidate from the deployment network; log per-RPC failure/success. Do not
  assume every public RPC is reachable from every region.
- Add exception handling to boot start. Package visibility for ghosta was added
  to the main manifest because `sidekick_version` was null despite the service
  running; verify this on device.
- Observed once on 2026-10-26 after a long E2E/media session: the emulator
  WebSocket went half-open, the daemon marked the device offline, and no
  ghostd heartbeat/reconnect log appeared until a force-stop/start. The app
  process and foreground service stayed alive. The final 29/29 E2E passed after
  that restart. Reproduce and fix the wedged send/watchdog path; do not treat
  the passing run as proof that unattended reconnect always recovers.
### Server / daemon / console audit (full details in `BUGS.md`)

The completed server audit is preserved verbatim in `BUGS.md`. Highest-impact
confirmed fixes, in order:

1. **Enrollment/resource abuse:** unauthenticated hello currently creates a
   persistent device record before credentials pass, and the `MAX_PENDING`
   condition is inverted for new devices. Validate/type-limit IDs, create state
   only after authentication, enforce the cap on new pending devices, and
   reject nonpositive cap configuration.
2. **Pending commands and sends:** timed-out commands and failed sends leak
   `self.pending` and can leave history permanently pending. Add `finally`
   cleanup, disconnect cancellation, bounded `ws.send`, and bounded wait values.
   One stalled device must not hang an entire broadcast.
3. **History/state growth and secrets:** history is unbounded, fully rewritten
   repeatedly, and can persist multi-MB base64 screenshots. Cap/truncate it,
   exclude large media payloads, batch persistence, avoid rewriting state on
   every ping, and enforce mode 600 on state/history/key files. Persistence
   failure must not kill command/connection handling.
4. **Connection replacement/revoke:** close the old WebSocket when a device
   reconnects; ignore messages from superseded connections; register state
   before ack races; revoke must close the active socket and drop cached
   streams. Zombie connections must not consume the per-IP limit indefinitely
   or overwrite fresh `last_seen`/stream state.
5. **Admin exposure:** localhost admin has no authentication and list/pending
   responses include bearer tokens. Add admin authentication and redact tokens
   from list/pending/console paths. Restrict arbitrary local APK paths and
   validate request field types/lengths.
6. **Web security:** console POST routes need CSRF protection; inline revoke
   confirmation has a device-ID XSS; quote path components with `safe=""` and
   unquote/validate them; add request/body/concurrency limits and security
   headers. Plain HTTP Basic requires TLS before non-local exposure.
7. **Streams:** fix `stream_mjpg` waiting/thread leak when no frames arrive,
   add idle timeout/heartbeat and client caps, add frame TTL/staleness, and fix
   JPEG-latest selection when the newest cached frame is PCM. Pending or
   revoked devices must not fill/serve the cache.
8. **Broadcast/CLI:** broadcast top-level `ok` must reflect results; remove
   destructive/server-side-only verbs from generic broadcast; bound send
   concurrency; implement or reject `--all update` explicitly. CLI update RPC
   timeout must cover the full push+install flow, and non-command ops should
   return nonzero on RPC errors instead of tracebacks/false success.

See `BUGS.md` for exact functions and failure sequences. These server/console/
CLI fixes are implemented, unit-tested and deployed on Kali. Integration
verification passed on the emulator. The console now runs native HTTPS, but its
IP certificate is self-signed; device-transport TLS, production permissions and
the explicitly listed hardening gaps remain open.

## Keep the screen awake during control — implemented and unit-tested

Implemented in **ghostd**, not ghosta. The controller expires after two minutes
without command/frame activity and releases on the last session, disconnect or
service destruction. JVM tests cover acquisition, sharing and release. The
29/29 E2E run does not include a dedicated two-minute idle-expiry assertion.

- While an operator command/session is active, acquire a bounded screen wake
  lock so consent/UI automation does not race the display turning off.
- Use reference counting or a single remote-session controller shared by
  commands and streams. Refresh on activity; release after a short idle period,
  on disconnect, service destruction, and when the last capture ends.
- Preserve CPU wake separately from screen wake. Do not permanently change the
  user's `SCREEN_OFF_TIMEOUT` and do not leave the display on indefinitely.
- Tests: lock/idle during `ui dump` + tap sequence, during consent, during live
  stream, disconnect mid-stream, and release after idle.

## Feature/verification backlog

### Additional review findings — 2026-10-25 (status rechecked)

Update follow-up: the local main manifest now declares
`UPDATE_PACKAGES_WITHOUT_USER_ACTION`; the earlier missing-permission finding
is **addressed in source, deployment unverified**. Both apps target API 36 and
`AppUpdater.installApk` requests `USER_ACTION_NOT_REQUIRED` on API 31+ when
eligible. The current CLI resolves update device aliases, uses a longer update
RPC timeout, and explicitly rejects `--all update`. These local changes do not
prove that the installed phone has them or that a full update succeeds.
See the unattended-update audit below before claiming fleet readiness.

Focused review of file-transfer plumbing, command results and test entry points;
details and verification limits are in the final review section of `BUGS.md`.

- **Downloads and CLI uploads — fixed and emulator-verified.** Device chunks
  and ends are `cmd_id`-bound; the daemon validates path, sequence, counts and
  SHA-256, and both put/get passed with byte and hash comparison.
- **Shell deadlock — fixed, unit-tested and passed the 29/29 emulator E2E.**
  stdout and stderr now drain separately and execution/output are bounded.
- **Failed operation envelopes — fixed with unit coverage:** `resultEnvelope`
  propagates explicit payload `ok:false`. The E2E suite does not include a
  dedicated negative-action assertion.
- **Device aliases — addressed and covered in the CLI:** command/update paths
  resolve IDs, prefixes and assigned names.
- **E2E directory — fixed and device-verified:** `BASE` derives from
  `__file__`, and the portable suite ran 29/29 on the emulator.
- **Late `.part` cleanup — fixed and unit-tested:** terminal-state and
  pending-identity guards protect replacement uploads; no open race is known.

Verification: the historical mocked checks above were followed by the current
unit suites and the 29/29 emulator run. Android unit tests and release builds
pass. Remaining device-dependent limits are listed in the next-step section,
not in this point-in-time review.

### Unattended-update audit — source fixes; Play Protect limitation verified

**User-reported idle disconnect — diagnosis pending:** the phone sometimes
appears to stop the service after sleeping. This is an observed loss of
availability, not yet proof that the process/service was killed. Check elapsed
time, charging state, whether screen-on alone restores connectivity, actual
process/service state, battery-optimization exemption and timestamped lifecycle/
WebSocket logs. Doze/network suspension is a candidate when the device is idle
and unplugged; the wizard requests an exemption but its current grant is unknown.
The socket heartbeat is a regular `Timer`; the CPU wake lock is capture-scoped,
so the heartbeat is not a guaranteed wakeup during device sleep. The tracked
reconnect/local-beacon defects may delay recovery. U7's Android 15+ cumulative
dataSync runtime limit is another candidate; it must not be blamed for every
screen-off disconnect without timing/log evidence. No phone state was inspected
or changed for this symptom report.

**Verdict: eligible unattended installs are supported by the current source,
but reliable end-to-end automatic updates are not release-ready. Emulator OTA
installed ghosta after a timeout, while ghostd was blocked by Play Protect.**
The permission in a new APK does not retroactively grant it to the old
installer process; the transition from an older installation may still need
one owner-confirmed update. Android can require user action when eligibility
conditions are not met.

U1–U8 are addressed in source and unit-tested. Reboot recovery, installer
package/version checks and daemon reconciliation passed their targeted tests;
the actual ghostd OTA attempt remains blocked by Play Protect verification:

- **U1 / P1 — self-update completion is lost across process replacement.**
  `AppUpdater` waits on a dynamically registered receiver in the process being
  replaced. The daemon treats its disconnect as a command error and does not
  reconcile the update with the new version after reconnect. Persist update
  identity/expected version and confirm installation after restart.
- **U2 / P1 — install status is not scoped to a session.** All concurrent
  installer calls listen to `UPDATE_STATUS` and accept every status. Background
  bundled provisioning can overlap an operator install, allowing one install's
  success/failure to complete both waiters. Validate session identity.
- **U3 / P1 — install status receiver accepts untrusted broadcasts.** It is
  exported without sender protection and trusts supplied status/intent extras.
  Protect callback delivery and validate session identity before acting.
- **U4 / P2 — failed staging leaks the receiver and installer session.**
  Write/commit errors bypass receiver cleanup; `close()` does not abandon a
  staged session. Cover the whole session lifecycle with cleanup and explicitly
  abandon failed uncommitted sessions; define timeout handling for pending ones.
- **U5 / P2 — update target is not validated against APK package identity.**
  `sessionName` only labels staged bytes. A ghosta APK mistakenly staged as
  ghostd can install the wrong target and report self-update success. Validate
  the expected package before creating/committing a session.
- **U6 / P2 — bundled upgrades compare versionName, not versionCode.** A higher
  versionCode with the same display version is skipped as up-to-date. Compare
  installed/archive version codes; retain versionName only for display.
- **U7 / P1 — persistent dataSync service exceeds Android 15+ runtime limits.**
  API-36 `GhostService` runs indefinitely without `onTimeout`; standard Android
  15+ rules allow six background hours per 24 hours for this service type.
  Handle timeout and use a supported background/management lifecycle.
- **U8 / P1 — reboot recovery starts a prohibited service type on Android 15+.**
  `BOOT_COMPLETED` starts the dataSync FGS directly. Catching the exception alone
  does not restore connectivity; use and verify a supported recovery path.

Verification: ghosta 0.2.1 reached the installed state after its command
timed out. ghostd 0.4.1 returned an accurate
`INSTALL_FAILED_VERIFICATION_FAILURE` instead of false success. Reboot recovery
and versioned daemon reconciliation passed. Fleet OTA remains **not ready**;
do not bypass Play Protect or treat these tests as a successful self-update.

- Verify `sms recent [N]` and its permission/error path on the Pixel. SMS send
  is not implemented. Contacts and call-log readers are not implemented.
- **Implemented:** obsolete top-level `ping` stub removed; `destroy` now backs
  the explicit `remove <device>` flow; `--all update` is rejected.
- Camera test requires the phone's global **Camera access** toggle enabled.
  Return the actionable error already added for `CAMERA_DISABLED`.
- Location may legitimately return no fix when location/services are off; e2e
  should report SKIP with the reason rather than pretending a fix exists.
- Add console editing for flood caps; CLI `config set` already works.
- WhatsApp per-contact export remains unimplemented original project scope.
- Consider accessibility-based low-rate live frames only after still capture
  and current live projection are stable. Do not promise invisible live capture.

## Console visuals — merged and deployed (2026-10-26)

- Branch `codex/console-visuals` was merged into `main` by merge commit
  `f39d675`; the worktree is clean. `main` includes the WSS/rotation work and
  the backend file-path fix from `033500f`.
- Angular dark/red styling, IBM Plex Mono and Lucide icons are bundled into
  existing UI assets, with source/license attribution in `server/ui_assets/`.
  No external asset requests, new asset routes or CSP changes are required.
- Phone layouts collapse tool selection, secondary device information and
  subgroups. Related controls share subgroups; simple actions appear once.
  Desktop uses a sidebar, while phones use bottom navigation.
- Device commands (including System → Terminal shell commands) use the existing
  URL-encoded endpoint/CSRF fields and display results on the same page as
  readable fields, text or tables. Raw JSON is collapsed under Technical details.
  Includes elapsed/pending, failure and duplicate-submit handling. Nothing is
  retried automatically. Without JS, forms retain the normal result-page fallback.
- The file explorer lists folders via existing `files ls`, with breadcrumbs,
  folder/parent navigation, local filtering and per-file attachment downloads.
  The duplicate List directory action is removed. Recursive tree/recovery tools,
  upload and exact-path downloads stay in collapsed sections.
- Upload results remain inline; cancellation now sends URL-encoded abort data.
  Upload progress distinguishes bytes sent to the console from device completion.
  Download progress belongs to the browser; no in-page progress/abort is claimed.
- Full PWA support remains explicitly deferred. No manifest, service worker or
  offline caching was added. Preview/test data is synthetic; no Kali account,
  enrollment, physical-device access or deployment was needed.
- Pre-integration verification: 83 console/UI Python tests and 42 browser layout
  checks pass, along with inline success/error/CSRF/network states, confirmations,
  duplicate-submit prevention, folder navigation/filter/download URLs, upload/
  cancellation contracts, no-JS fallback, and font loading under the production
  CSP. Browser tests are reproducible with `server/test_console_ui_browser.cjs`
  using an installed Playwright + Chrome. Real-phone Safari remains untested.
- Known limits: the server still splits folder-list command arguments at
  spaces; the explorer explains this and preserves the prior listing.
  Exact-path downloads accept spaces, punctuation and non-ASCII after commit
  `033500f`. Device model/sidekick persistence and live-screen start/polling
  remain unresolved. No Android logic was authored by this UI change.
- Final verification after merging `main` at `033500f`: 284 server unit tests,
  42 browser layout/interaction checks, and Python compilation pass. Low-priority
  UI follow-ups: saved-history results lack raw JSON details, multi-field input
  IDs would collide if a multi-field verb is added, and the `aria-live` elapsed
  status updates every second. Real-phone Safari remains untested.
- Merged into `main` by `f39d675`, pushed, and deployed to Kali. Follow-up
  commit `9a87243` fixed native-form CSRF by changing `Referrer-Policy` to
  `same-origin`; Chromium no longer sends `Origin: null` for same-origin forms.
  A real-browser native form POST now returns 200. The other agent's uncommitted
  Android/server work was not included or touched.

## Web console — backend and merged UI deployed

Kali serves the console HTTPS-only and returns HTTP 200 over TLS; external TLS
plus an authenticated CSRF-protected device command passed. Plain HTTP on :9443
is rejected. Backend route/API tests pass and dashboard/device/settings/remove
smoke checks passed. Security includes admin auth, token redaction, HTTP Basic
over TLS, CSRF (now rejecting `Origin: null` even with a valid token), request
limits, CSP and stream tokens. Native TLS supports `--certfile`/`--keyfile`,
worker-thread handshakes, a bounded handshake timeout/concurrency cap, a
mode-600 key check, HSTS and a `Secure` CSRF cookie. The hardened backend and
merged UI are deployed and externally re-verified.

The HTTP backend is implemented; the browser UI is not fully verified:

- Device cards render, but model and sidekick version stay `-` because the
  daemon does not persist those hello fields.
- The live-screen page does not start polling, and its Start action posts to a
  result page instead of a usable live view.
- The console visuals work is merged and deployed. It fixes Upload Cancel to
  use URL-encoded data for `/files/abort`; exact-path downloads now accept
  spaces, punctuation and non-ASCII; and native form CSRF works after the
  `Referrer-Policy: same-origin` fix. In-page download progress/abort is not
  implemented or claimed.
- A real phone-sized browser and accessibility review remains.

Working backend/browser pieces:

- Search/filter and pending-approval actions, with rename.
- Device pages with grouped actions (Files, Screen, UI, Media, System), recent
  command history, elapsed/pending state, and readable success/error output.
- Safe confirmation for revoke/restart/update/destroy-like actions; no PIN or
  bearer-token display in pages, logs or browser history.
- Settings page for enrollment flood caps, using the daemon config API.
- Server-rendered pages plus vanilla JS, with no framework or CDN.

The console UI/JS/markup and its browser tests now live on `main`; run
`test_console_ui_browser.cjs` with Playwright + Chrome before claiming browser
workflows verified.

## Documentation / release checklist

- [x] All current changes committed; no daemon/console logs or secrets staged.
- [x] Unit tests and Python compile checks pass after final edits.
- [x] ghosta and ghostd versions staged match the tested builds.
- [x] Emulator runs those versions and reconnects after restart.
- [x] Full e2e result recorded above; OTA/Play Protect limitation noted.
- [x] README command list matches actual implemented verbs and limitations.
- [x] `AGENTS.md`, this backlog and `BUGS.md` updated for design/lifecycle
  changes and newly confirmed audit findings.
- [x] User explicitly asked the agent to push; Kali pulled main successfully.
