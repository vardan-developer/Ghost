package com.tg.ghosta

/**
 * UI operations the sidekick exposes, implemented by GhostAccessibilityService
 * and faked in JVM unit tests. Every method returns a complete JSON-ready
 * result map (contains "ok" plus payload or "error").
 */
interface UiDriver {

    /** Full accessibility node tree as nested JSON map. */
    fun dump(): Map<String, Any?>

    /**
     * Silent screenshot through the accessibility capability (API 30+):
     * NO MediaProjection consent sheet and NO screen-sharing indicator.
     * [format] is "png" or "jpeg"; returns width/height + base64 payload.
     */
    fun screenshot(format: String, quality: Int): Map<String, Any?>
    /** Substring search on text/desc (text=...) or view resource id (id=...). */
    fun find(text: String?, id: String?): Map<String, Any?>

    /** Tap by node id path, or by screen coordinates. */
    fun tap(x: Int?, y: Int?, id: String?): Map<String, Any?>

    /** Long click by node id path, or by screen coordinates. */
    fun longClick(x: Int?, y: Int?, id: String?): Map<String, Any?>

    /** Type text: ACTION_SET_TEXT on the given/focused editable node,
     *  key-by-key fallback when no editable node is available. */
    fun type(text: String, id: String?): Map<String, Any?>

    /** Send a single key event (KEYCODE_*). */
    fun key(code: Int): Map<String, Any?>

    /** Swipe gesture from (x1,y1) to (x2,y2) over duration ms. */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Long): Map<String, Any?>

    /** Type the PIN into the keyguard PIN field and press enter. */
    fun unlock(pin: String): Map<String, Any?>

    /** Toggle auto-confirm of common dialogs. */
    fun setArmed(on: Boolean): Map<String, Any?>

    fun status(): Map<String, Any?>
}

/**
 * Routes one line of newline-delimited JSON to a [UiDriver] and returns the
 * one-line JSON response. Pure logic, unit-tested against a fake driver.
 *
 * Commands (JSON, one per line):
 *   {"cmd":"ping"}
 *   {"cmd":"status"}
 *   {"cmd":"dump"}
 *   {"cmd":"find","text":"Allow"}           or {"cmd":"find","id":"btn_ok"}
 *   {"cmd":"tap","id":"root/0/3"}           or {"cmd":"tap","x":540,"y":1800}
 *   {"cmd":"longclick","id":"..."}          or {"cmd":"longclick","x":..,"y":..}
 *   {"cmd":"type","text":"hello","id":"root/1"}   (id optional)
 *   {"cmd":"key","code":66}
 *   {"cmd":"swipe","x1":..,"y1":..,"x2":..,"y2":..,"duration":300}
 *   {"cmd":"unlock","pin":"1234"}
 *   {"cmd":"arm"}  /  {"cmd":"disarm"}
 */
object CommandRouter {

    fun handle(line: String, driver: UiDriver): String {
        val msg = try {
            @Suppress("UNCHECKED_CAST")
            JsonParser.parse(line) as? Map<String, Any?>
                ?: return Json.stringify(err("expected a JSON object"))
        } catch (e: Exception) {
            return Json.stringify(err("bad json: ${e.message}"))
        }
        val cmd = (msg["cmd"] as? String)?.lowercase()?.trim()
            ?: return Json.stringify(err("missing cmd"))
        val result = try {
            route(cmd, msg, driver)
        } catch (e: Exception) {
            err("${e.message ?: e.javaClass.simpleName}")
        }
        return Json.stringify(result)
    }

    private fun route(cmd: String, msg: Map<String, Any?>, d: UiDriver): Map<String, Any?> =
        when (cmd) {
            "ping" -> mapOf("ok" to true, "pong" to true)
            "status" -> d.status()
            "dump", "ui" -> d.dump()
            "screenshot", "shot" -> d.screenshot(
                str(msg, "format") ?: "png", int(msg, "quality") ?: 90
            )
            "find" -> d.find(str(msg, "text"), str(msg, "id"))
            "tap" -> d.tap(int(msg, "x"), int(msg, "y"), str(msg, "id"))
            "longclick", "long" -> d.longClick(int(msg, "x"), int(msg, "y"), str(msg, "id"))
            "type" -> {
                val t = str(msg, "text") ?: throw IllegalArgumentException("missing text")
                d.type(t, str(msg, "id"))
            }
            "key" -> {
                val c = int(msg, "code") ?: throw IllegalArgumentException("missing code")
                d.key(c)
            }
            "swipe" -> {
                val x1 = int(msg, "x1") ?: throw IllegalArgumentException("missing x1")
                val y1 = int(msg, "y1") ?: throw IllegalArgumentException("missing y1")
                val x2 = int(msg, "x2") ?: throw IllegalArgumentException("missing x2")
                val y2 = int(msg, "y2") ?: throw IllegalArgumentException("missing y2")
                d.swipe(x1, y1, x2, y2, long(msg, "duration") ?: 300L)
            }
            "unlock" -> {
                val pin = str(msg, "pin") ?: throw IllegalArgumentException("missing pin")
                d.unlock(pin)
            }
            "arm" -> d.setArmed(true)
            "disarm" -> d.setArmed(false)
            else -> err("unknown cmd: $cmd")
        }

    // ---- msg field helpers (JSON numbers arrive as Long) ----

    private fun str(m: Map<String, Any?>, k: String): String? {
        val v = m[k] ?: return null
        if (v !is String) throw IllegalArgumentException("$k must be a string")
        return v
    }

    private fun int(m: Map<String, Any?>, k: String): Int? = long(m, k)?.toInt()

    private fun long(m: Map<String, Any?>, k: String): Long? {
        val v = m[k] ?: return null
        if (v !is Number) throw IllegalArgumentException("$k must be a number")
        return v.toLong()
    }

    fun ok(payload: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        out["ok"] = true
        out.putAll(payload)
        return out
    }

    fun err(message: String): Map<String, Any?> = mapOf("ok" to false, "error" to message)
}
