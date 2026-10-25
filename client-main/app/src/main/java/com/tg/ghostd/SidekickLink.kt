package com.tg.ghostd

import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Timer
import java.util.TimerTask

/**
 * Client side of the sidekick (ghosta) local link.
 *
 * TCP to 127.0.0.1:7777, newline-delimited JSON, one response per request
 * (see client-sidekick/README.md "Local link"). First line sent after connect
 * is the shared-secret auth handshake. Connection is lazy (established on
 * first use) and transparently re-established on error; [send] is
 * thread-safe (commands are serialized — the sidekick serves them one at a
 * time anyway). Connect + read timeout ~10 s.
 */
object SidekickLink {

    /**
     * Shared secret — MUST equal LocalLinkServer.SECRET in client-sidekick
     * (the single place it is defined in this app; rotate in both apps).
     */
    const val SECRET = "gh0stA-9fXq2Lm7Pz4t"

    private const val HOST = "127.0.0.1"
    private const val PORT = 7777
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    /** Auto-arm link watch interval (sidekick resets armed state on reboot). */
    private const val WATCH_INTERVAL_MS = 60_000L

    private val lock = Any()
    private var socket: Socket? = null
    private var input: BufferedReader? = null
    private var output: BufferedWriter? = null

    /** Test-only port override (0 keeps the fixed [PORT]). */
    @Volatile internal var portForTest: Int = 0

    /** Invoked (on the connecting thread) every time a NEW link is up. */
    @Volatile private var onConnected: (() -> Unit)? = null
    private var watchTimer: Timer? = null

    fun isConnected(): Boolean = synchronized(lock) { aliveLocked() }

    /**
     * Send one JSON request, return the sidekick's JSON response verbatim.
     *
     * A connection/auth failure before the request is written is retried once
     * (safe: the request never reached the sidekick). Once [BufferedWriter.write]
     * and [BufferedWriter.flush] have both returned, however, the sidekick may
     * already have executed a non-idempotent command (tap/type/unlock). Any
     * subsequent read error is ambiguous, so close the link and fail instead
     * of resending and executing the command twice.
     */
    fun send(request: JSONObject): JSONObject {
        val line = synchronized(lock) {
            val payload = request.toString() + "\n"
            var response: String? = null
            var failure: Exception? = null
            for (attempt in 0..1) {
                var wroteRequest = false
                try {
                    if (!aliveLocked() && !connectLocked()) {
                        throw Exception("cannot connect to sidekick at $HOST:$PORT")
                    }
                    val o = output ?: throw Exception("no link")
                    val i = input ?: throw Exception("no link")
                    o.write(payload)
                    o.flush()
                    // From here on the request may have been delivered.
                    wroteRequest = true
                    response = i.readLine() ?: throw Exception("sidekick closed the link")
                    break
                } catch (e: Exception) {
                    closeLocked()
                    failure = e
                    if (wroteRequest) {
                        throw Exception(
                            "sidekick link error after request was sent; not retrying: ${e.message}"
                        )
                    }
                }
            }
            response ?: throw Exception("sidekick link error: ${failure?.message ?: "unknown"}")
        }
        return try {
            JSONObject(line)
        } catch (_: Exception) {
            JSONObject().put("ok", false).put("error", "bad sidekick response")
        }
    }

    /**
     * Auto-arm: watch the link while the service runs. Every time the link
     * (re)connects — including the sidekick rebooting / service rebind — the
     * [onConnected] hook re-sends `arm` (fire-and-forget). No-op until the
     * first successful connection; retries every [WATCH_INTERVAL_MS].
     */
    fun startAutoArm() {
        stopAutoArm()
        onConnected = {
            // fire-and-forget: never blocks or breaks the connection flow
            try {
                send(JSONObject().put("cmd", "arm"))
            } catch (_: Exception) {
            }
        }
        watchTimer = Timer("ghostd-sidekick-arm", true).apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    try {
                        ensureConnected()
                    } catch (_: Exception) {
                    }
                }
            }, 2_000L, WATCH_INTERVAL_MS)
        }
    }

    fun stopAutoArm() {
        watchTimer?.cancel()
        watchTimer = null
        onConnected = null
    }

    /** Connect if not connected (used by the auto-arm watcher). */
    fun ensureConnected(): Boolean = synchronized(lock) {
        if (aliveLocked()) return true
        closeLocked()
        connectLocked()
    }

    fun close() {
        synchronized(lock) { closeLocked() }
    }

    /** Drop all state between JVM tests (no live sidekick in unit tests). */
    internal fun resetForTest() {
        synchronized(lock) {
            closeLocked()
            onConnected = null
        }
    }

    // ------------------------------------------------------------- internal

    /** Caller must hold [lock]. Connects and performs the auth handshake. */
    private fun connectLocked(): Boolean {
        val port = portForTest.takeIf { it > 0 } ?: PORT
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(HOST, port), CONNECT_TIMEOUT_MS)
            s.soTimeout = READ_TIMEOUT_MS
            val i = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val o = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
            // auth handshake: first line MUST be {"auth":"<SECRET>"}
            o.write(JSONObject().put("auth", SECRET).toString() + "\n")
            o.flush()
            val resp = i.readLine() ?: throw Exception("link closed during auth")
            if (!try {
                    JSONObject(resp).optBoolean("ok", false)
                } catch (_: Exception) {
                    false
                }
            ) throw Exception("sidekick rejected auth")
            socket = s
            input = i
            output = o
        } catch (_: Exception) {
            try {
                s.close()
            } catch (_: Exception) {
            }
            return false
        }
        // monitor is reentrant: the hook may call send() right here (auto-arm)
        onConnected?.let { hook ->
            try {
                hook()
            } catch (_: Exception) {
            }
        }
        return true
    }

    private fun aliveLocked(): Boolean {
        val s = socket ?: return false
        return s.isConnected && !s.isClosed
    }

    private fun closeLocked() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        input = null
        output = null
    }
}
