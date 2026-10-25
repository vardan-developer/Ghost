# ghostctl backlog, bugs and next steps

This is the working handoff for the next machine/session. Read `AGENTS.md` for
layout and build commands. Keep this file factual: distinguish implemented,
tested, deployed and merely designed.

Last updated: 2026-10-25.

## Current state

- Physical test phone: **Pixel 10a**, approved and controllable.
- Phone currently reports **ghostd 0.3.8**. Working tree is **ghostd 0.3.9 /
  ghosta 0.1.9**; these builds have not completed deployment or full e2e.
- **52 main-app and 16 sidekick unit tests pass after the transfer-integrity
  edits**; Python compile checks pass. Full on-device e2e has not been rerun.
- The original e2e run passed 17/22 checks. Failures were:
  - incorrect test invocation for volume (test fixed to `volume set media N`);
  - interrupted/wedged file push and dependent file get;
  - camera blocked by the phone's global camera-access policy;
  - live screen produced no cached frames after projection consent/reuse failed.
- New `device info`, `status`, `sms recent`, `loc once`, `hide`, `unhide`,
  `files abort`, and `restart` verbs exist in the working tree, but most still
  need on-device verification. `sms recent` is implemented, **not yet proven**
  on the Pixel. Do not describe SMS send, contacts, call log or auto-destroy as
  implemented.
- Console is reachable on port 9443 after the firewall change. Password comes
  from gitignored `server/.env`; never commit it.
- Device rename works in daemon, CLI and console. `-d` accepts a full ID, an
  unambiguous ID prefix, or a device name.

## Immediate next steps — do these before new features

1. Inspect `git status` and the latest commit. The transfer-integrity change
   and the OTA path fix are implemented and unit-tested; do not start a
   parallel rewrite.
2. Re-run sidekick tests/build, `refreshSidekickAsset`, main tests/build, and
   Python `py_compile`. Confirm no runtime logs or secrets are staged.
3. Deploy ghosta 0.1.9, then ghostd 0.3.9 to the test phone. Keep the phone
   unlocked for this deployment if consent automation is not yet verified.
   Verify actual versions through `device info` and `sidekick status`.
4. Verify upload integrity repeatedly with both a small file and an APK:
   normal put/get, interrupted push then retry to the **same path**, retry to a
   different path, disconnect/reconnect during push, `files abort`, and
   `restart`. Compare SHA-256, not only byte counts.
5. Run `server/e2e_test.py` and record the new pass/fail result here. Re-test
   `sms recent 5`, `loc once`, screen shot/live, files, UI verbs and restart.
6. Complete consent-per-capture behavior below, then repeat screen tests with
   the phone unlocked and locked. Do not keep a persistent projection.

## P0 — correctness / recovery bugs

### File transfer races (implemented in working tree; device verification pending)

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
- Never auto-tap matching words in arbitrary application windows.
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
- Add exception handling to boot start. Package visibility for ghosta was added
  to the main manifest because `sidekick_version` was null despite the service
  running; verify this on device.
- Server connection replacement, pending-command cleanup, stream-cache cleanup
  and console exposure were queued for a separate audit. Review its results
  before changing these areas; do not duplicate the file-transfer review.

## Next feature after stabilization: keep the screen awake during control

Not implemented yet; the user explicitly said not to start it during handoff.

Implement in **ghostd**, not ghosta (ghostd already has wake-lock permission):

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

- Verify `sms recent [N]` and its permission/error path on the Pixel. SMS send
  is not implemented. Contacts and call-log readers are not implemented.
- Implement or remove CLI/console stubs that still reach `unknown action`
  (`destroy`, `ping`, and any others found by e2e).
- Camera test requires the phone's global **Camera access** toggle enabled.
  Return the actionable error already added for `CAMERA_DISABLED`.
- Location may legitimately return no fix when location/services are off; e2e
  should report SKIP with the reason rather than pretending a fix exists.
- Add console editing for flood caps; CLI `config set` already works.
- WhatsApp per-contact export remains unimplemented original project scope.
- Consider accessibility-based low-rate live frames only after still capture
  and current live projection are stable. Do not promise invisible live capture.

## Web console overhaul (backlog only — not started)

The current console is functional but difficult to use. After device stability
is verified, redesign it as an operator dashboard rather than a collection of
raw command forms:

- Responsive device cards/table with assigned name, model, online/approval
  state, app/sidekick versions, battery, IP and last-seen age.
- Search/filter and clear pending-approval actions; keep rename prominent.
- A device page with grouped actions (Files, Screen, UI, Media, System), recent
  command history, elapsed/pending state, and readable success/error output.
- Live screen view with explicit start/stop and frame/stream health; never show
  a stale cached frame as though it were live.
- File upload/download progress and transfer errors, including cancellation.
- Safe confirmation for revoke/restart/update/destroy-like actions; no PIN or
  bearer-token display in pages, logs or browser history.
- Settings page for enrollment flood caps and console preferences, using the
  existing daemon config API. Preserve HTTP Basic authentication and CSRF
  protection for state-changing routes.
- Keep the implementation simple (small server-rendered pages plus vanilla JS
  unless a framework is genuinely justified). Add route/API tests and verify
  the console on a phone-sized viewport as well as desktop.

## Documentation / release checklist

- [ ] All current changes committed; no daemon/console logs or secrets staged.
- [ ] Unit tests and Python compile checks pass after final edits.
- [ ] ghosta and ghostd versions staged match the tested builds.
- [ ] Physical phone runs those versions and reconnects after update.
- [ ] Full e2e result recorded above; negatives and skipped checks noted.
- [ ] README command list matches actual implemented verbs and limitations.
- [ ] `AGENTS.md` and this backlog updated for any design/lifecycle changes.
- [ ] User handles GitHub push unless explicitly asking the agent to push.
