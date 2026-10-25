package com.tg.ghostd

import org.json.JSONObject

/**
 * Server reply to the "hello" registration message (device authentication).
 *
 * Protocol (matches server/daemon.py):
 *   in:  {"type":"hello", ..., "enroll_key":"..", "token":".."(if stored)}
 *   out: {"type":"hello_ack","status":"approved"|"pending"|"denied",
 *         "token":"<32hex>"}
 *
 * - approved/pending: store the token (Prefs.deviceToken) and continue;
 *   "pending" just means commands flow only after operator approval —
 *   keep heartbeating as normal.
 * - denied: clear the stored token and drop the connection; the reconnect
 *   loop retries later with normal backoff.
 */
data class HelloAck(val status: String, val token: String?) {

    val isApproved: Boolean get() = status == STATUS_APPROVED
    val isPending: Boolean get() = status == STATUS_PENDING
    val isDenied: Boolean get() = status == STATUS_DENIED

    companion object {
        const val TYPE = "hello_ack"
        const val STATUS_APPROVED = "approved"
        const val STATUS_PENDING = "pending"
        const val STATUS_DENIED = "denied"

        /** Parses a hello_ack message; null for anything else / bad JSON. */
        fun parse(text: String): HelloAck? {
            val msg = try {
                JSONObject(text)
            } catch (_: Exception) {
                return null
            }
            if (msg.optString("type") != TYPE) return null
            return HelloAck(msg.optString("status"), msg.optString("token").ifEmpty { null })
        }

        /**
         * Pure builder for the "hello" registration message (device auth
         * fields: enroll_key always, token only when stored).
         */
        fun buildHello(
            deviceId: String, model: String, androidVersion: String, sdk: Int,
            appVersion: String, battery: Int, enrollKey: String, token: String?
        ): String {
            val hello = JSONObject()
                .put("type", "hello")
                .put("device_id", deviceId)
                .put("model", model)
                .put("android_version", androidVersion)
                .put("version", sdk)
                .put("app_version", appVersion)
                .put("battery", battery)
                .put("enroll_key", enrollKey)
            if (token != null) hello.put("token", token)
            return hello.toString()
        }
    }
}
