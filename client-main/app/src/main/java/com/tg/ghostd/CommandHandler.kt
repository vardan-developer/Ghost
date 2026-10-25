package com.tg.ghostd

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
 */
object CommandHandler {

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
                execute(msg).put("cmd_id", cmdId).put("id", cmdId)
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

    private fun execute(msg: JSONObject): JSONObject {
        val action = msg.optString("action")
        val args = msg.optJSONArray("args") ?: JSONArray()
        val result: Any = when (action) {
            "shell" -> shell(args.optString(0, "id"))
            "files" -> filesLs(args)
            else -> throw Exception("unknown action: $action")
        }
        return JSONObject().put("type", "result")
            .put("status", "ok").put("ok", true).put("result", result)
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
