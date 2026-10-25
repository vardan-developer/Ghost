package com.tg.ghostd

import org.json.JSONObject

/**
 * Online device-token rotation.
 *
 * The daemon sends the new bearer token over the already authenticated WSS
 * connection. The device must persist it before acknowledging; if it cannot,
 * it acknowledges false and the server keeps the old token valid.
 */
object TokenRotation {
    const val TYPE = "auth_rotate"
    const val ACK_TYPE = "auth_rotate_ack"

    private val TOKEN_RE = Regex("[0-9a-f]{32}")

    /** Returns the new token, or null for any other/malformed message. */
    fun parse(text: String): String? {
        val msg = try {
            JSONObject(text)
        } catch (_: Exception) {
            return null
        }
        if (msg.optString("type") != TYPE) return null
        val token = msg.optString("token")
        return if (TOKEN_RE.matches(token)) token else null
    }

    fun ack(stored: Boolean): String = JSONObject()
        .put("type", ACK_TYPE)
        .put("ok", stored)
        .toString()
}
