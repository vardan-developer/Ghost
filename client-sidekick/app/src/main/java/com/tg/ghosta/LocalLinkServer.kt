package com.tg.ghosta

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure, thread-visible lifecycle state for [LocalLinkServer].
 *
 * The socket/thread plumbing lives in the server; the decisions that used to
 * wedge it live here so they are JVM-testable:
 *
 *  - a start generation owns `running`/`bound`; a bind or accept failure for
 *    the current generation resets both, so a later [beginStart] can retry
 *    instead of being refused by a stale `running=true`;
 *  - failures from a superseded generation are ignored;
 *  - [stop] invalidates the generation and reports the connection ids that the
 *    server must close;
 *  - [acquire]/[release] enforce a small concurrent-connection cap and refuse
 *    new slots once teardown has begun.
 */
class LocalLinkState(private val maxConnections: Int = LocalLinkServer.MAX_CONNECTIONS) {

    @Volatile
    var running: Boolean = false
        private set

    /** A listener socket is installed for the current start generation. */
    @Volatile
    var bound: Boolean = false
        private set

    private var generation = 0L
    private val active = LinkedHashSet<Int>()

    /**
     * Claim a new start. Returns null when already running (idempotent start),
     * otherwise the generation token used by the accept loop.
     */
    @Synchronized
    fun beginStart(): Long? {
        if (running) return null
        generation++
        running = true
        bound = false
        active.clear()
        return generation
    }

    /** The listener bound successfully; false when this generation is stale. */
    @Synchronized
    fun markBound(gen: Long): Boolean {
        if (!isCurrent(gen)) return false
        bound = true
        return true
    }

    /**
     * The accept loop died (bind failure or unexpected error). Resets running
     * and bound so [beginStart] can retry. False for a superseded generation.
     */
    @Synchronized
    fun failStart(gen: Long): Boolean {
        if (!isCurrent(gen)) return false
        running = false
        bound = false
        active.clear()
        return true
    }

    /** Stop this generation; returns the connection ids that must be closed. */
    @Synchronized
    fun stop(): Set<Int> {
        val ids = LinkedHashSet(active)
        running = false
        bound = false
        active.clear()
        generation++
        return ids
    }

    @Synchronized
    fun isCurrent(gen: Long): Boolean = running && gen == generation

    /** Reserve a connection slot; false when not running or at the cap. */
    @Synchronized
    fun acquire(connectionId: Int): Boolean {
        if (!running) return false
        if (active.size >= maxConnections) return false
        active.add(connectionId)
        return true
    }

    @Synchronized
    fun release(connectionId: Int) {
        active.remove(connectionId)
    }

    @Synchronized
    fun activeConnectionIds(): Set<Int> = LinkedHashSet(active)
}

/**
 * Local link: plain TCP server on 127.0.0.1:7777 for the main app (ghostd).
 *
 * Protocol (newline-delimited JSON, one request per line, one response per
 * line):
 *   1. first line MUST be {"auth":"<SECRET>"} — wrong secret closes the socket
 *   2. then any number of command lines (see [CommandRouter])
 *
 * Loopback only — no internet exposure, no INTERNET permission needed.
 *
 * Lifecycle rules:
 *  - bind/accept failure closes the listener and resets [LocalLinkState], so a
 *    later [start] retries instead of staying dead behind `running=true`;
 *  - the unauthenticated handshake has a short read timeout, so a peer cannot
 *    hold a handler thread or a connection slot forever;
 *  - concurrent links are capped ([MAX_CONNECTIONS]);
 *  - every accepted socket is tracked, and [stop] closes all of them;
 *  - `running` is re-checked after reads, so no command is executed once
 *    teardown has begun.
 */
class LocalLinkServer(
    private val driver: UiDriver,
    private val port: Int = PORT,
    private val preAuthTimeoutMs: Int = PRE_AUTH_TIMEOUT_MS,
    private val maxConnections: Int = MAX_CONNECTIONS,
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() }
) {

    companion object {
        /** Shared secret baked in at build; ghostd must send the same value. */
        const val SECRET = "gh0stA-9fXq2Lm7Pz4t"
        const val PORT = 7777

        /** The auth handshake must complete within this window. */
        const val PRE_AUTH_TIMEOUT_MS = 3_000

        /** Small concurrent-link cap; ghostd normally holds one or two. */
        const val MAX_CONNECTIONS = 4

        private const val BACKLOG = 4
        private const val LOOPBACK = "127.0.0.1"

        /** Consecutive accept errors before the listener is reset as broken. */
        private const val MAX_ACCEPT_FAILURES = 3
        private const val ACCEPT_RETRY_DELAY_MS = 50L

        /** ghostd is alive (beaconed us); ghosta babysitter resets its timer. */
        @Volatile
        var lastAliveMs: Long = 0L
            private set

        fun noteAlive(atMs: Long) {
            lastAliveMs = atMs
        }

        /** New server instance: no liveness data carried from the old one. */
        internal fun resetAlive() {
            lastAliveMs = 0L
        }
    }

    private val state = LocalLinkState(maxConnections)

    @Volatile
    private var server: ServerSocket? = null

    /** Accepted sockets by connection id, closed together on [stop]. */
    private val sockets = ConcurrentHashMap<Int, Socket>()

    /**
     * Identifies authenticated links. The decommission watcher is bound to
     * the connection that carried `decommission_confirmed`, so a routine
     * disconnect or a later link can never be confused with it. Ids are
     * only reported to the driver after a successful auth, and exactly one
     * `connectionOpened`/`connectionClosed` pair is emitted per such link.
     */
    private val nextConnectionId = AtomicInteger(1)

    fun isRunning(): Boolean = state.running

    fun start() {
        val gen = state.beginStart() ?: return
        // A new instance owns a fresh liveness baseline; a timestamp from a
        // previous server instance must neither suppress nor trigger recovery.
        resetAlive()
        val thread = Thread({ acceptLoop(gen) }, "ghosta-link-accept")
        try {
            thread.start()
        } catch (_: Exception) {
            // Could not even start the accept thread: stay retryable.
            state.failStart(gen)
        }
    }

    fun stop() {
        state.stop()
        val ss = server
        server = null
        closeQuietly(ss)
        closeAllSockets()
    }

    private fun acceptLoop(gen: Long) {
        var ss: ServerSocket? = null
        try {
            ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), port), BACKLOG)
            if (!state.markBound(gen)) return
            server = ss
            var acceptFailures = 0
            while (state.isCurrent(gen)) {
                val client = try {
                    ss.accept()
                } catch (_: Exception) {
                    // Stop closes the listener: exit without treating it as a
                    // failure. Other accept errors are retried briefly, then the
                    // loop exits so `finally` resets state and start() can retry
                    // instead of spinning on a broken listener.
                    if (!state.isCurrent(gen)) break
                    acceptFailures++
                    if (acceptFailures >= MAX_ACCEPT_FAILURES) break
                    try {
                        Thread.sleep(ACCEPT_RETRY_DELAY_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }
                acceptFailures = 0
                if (!state.isCurrent(gen)) {
                    closeQuietly(client)
                    break
                }
                val connectionId = nextConnectionId.getAndIncrement()
                if (!state.acquire(connectionId)) {
                    rejectBusy(client)
                    continue
                }
                sockets[connectionId] = client
                try {
                    Thread({ handle(client, connectionId) }, "ghosta-link").start()
                } catch (_: Exception) {
                    sockets.remove(connectionId)
                    state.release(connectionId)
                    closeQuietly(client)
                }
            }
        } catch (_: Exception) {
            // Bind failure or an unexpected accept-loop error: handled below.
        } finally {
            closeQuietly(ss)
            if (server === ss) server = null
            if (state.failStart(gen)) {
                // No listener is left for this generation. Close handler
                // sockets too, so a half-dead instance cannot keep serving;
                // start() is retryable because running is reset.
                closeAllSockets()
            }
        }
    }

    /** Tell an over-cap peer we are busy, then drop it without a handler. */
    private fun rejectBusy(client: Socket) {
        try {
            val out = OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8)
            out.write(Json.stringify(CommandRouter.err("busy: too many local links")) + "\n")
            out.flush()
        } catch (_: Exception) {
        }
        closeQuietly(client)
    }

    private fun handle(client: Socket, connectionId: Int) {
        var driverNotified = false
        try {
            if (!state.running) return
            client.tcpNoDelay = true
            // Pre-auth only: a silent peer must not pin a thread/slot.
            client.soTimeout = preAuthTimeoutMs
            val input = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val output = OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8)

            // --- auth handshake ---
            val authLine = input.readLine() ?: return
            if (!state.running) return
            val authed = try {
                @Suppress("UNCHECKED_CAST")
                (JsonParser.parse(authLine) as? Map<String, Any?>)
                    ?.get("auth") == SECRET
            } catch (_: Exception) {
                false
            }
            if (!authed) {
                output.write(Json.stringify(CommandRouter.err("auth failed")) + "\n")
                output.flush()
                return
            }
            if (!state.running) return
            output.write(Json.stringify(CommandRouter.ok(mapOf("auth" to true))) + "\n")
            output.flush()
            if (!state.running) return
            noteAlive(nowMs())
            driver.connectionOpened(connectionId)
            driverNotified = true

            // --- command loop ---
            // Authenticated links may idle; stop() closes the socket to
            // unblock this read.
            client.soTimeout = 0
            while (state.running) {
                val line = input.readLine() ?: break
                // Teardown may begin while a line is in flight: never execute
                // a command after stop().
                if (!state.running) break
                if (line.isBlank()) continue
                noteAlive(nowMs())
                val response = CommandRouter.handle(line, driver, connectionId)
                if (!state.running) break
                output.write(response + "\n")
                output.flush()
            }
        } catch (_: Exception) {
            // client dropped / timed out / closed during teardown — fine
        } finally {
            closeQuietly(client)
            sockets.remove(connectionId)
            state.release(connectionId)
            if (driverNotified) {
                try {
                    driver.connectionClosed(connectionId)
                } catch (_: Exception) {
                    // a watcher decision must never take down the link thread
                }
            }
        }
    }

    private fun closeAllSockets() {
        for ((id, socket) in ArrayList(sockets.entries)) {
            sockets.remove(id, socket)
            closeQuietly(socket)
        }
    }

    private fun closeQuietly(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {
        }
    }
}
