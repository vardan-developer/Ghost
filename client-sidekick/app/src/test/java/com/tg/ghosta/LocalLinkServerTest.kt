package com.tg.ghosta

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure lifecycle decisions behind [LocalLinkServer]. */
class LocalLinkStateTest {

    @Test
    fun `start is idempotent and binds one generation`() {
        val state = LocalLinkState()
        val gen = state.beginStart()
        assertNotNull(gen)
        assertTrue(state.running)
        assertNull(state.beginStart())
        assertTrue(state.markBound(gen!!))
        assertTrue(state.bound)
        assertTrue(state.isCurrent(gen))
    }

    @Test
    fun `bind or accept failure resets state and allows a retry`() {
        val state = LocalLinkState()
        val gen = state.beginStart()!!
        state.markBound(gen)
        assertTrue(state.acquire(7))
        assertTrue(state.failStart(gen))
        assertFalse(state.running)
        assertFalse(state.bound)
        assertTrue(state.activeConnectionIds().isEmpty())
        // The original wedge: running stayed true and start() was refused.
        val retry = state.beginStart()
        assertNotNull(retry)
        assertNotEquals(gen, retry)
        assertTrue(state.running)
    }

    @Test
    fun `a stale generation cannot fail a newer start`() {
        val state = LocalLinkState()
        val first = state.beginStart()!!
        state.stop()
        val second = state.beginStart()!!
        assertFalse(state.failStart(first))
        assertTrue(state.running)
        assertTrue(state.isCurrent(second))
    }

    @Test
    fun `stop invalidates the generation and reports connections to close`() {
        val state = LocalLinkState()
        val gen = state.beginStart()!!
        state.markBound(gen)
        assertTrue(state.acquire(1))
        assertTrue(state.acquire(2))
        assertEquals(setOf(1, 2), state.stop())
        assertFalse(state.running)
        assertFalse(state.bound)
        assertFalse(state.isCurrent(gen))
        assertTrue(state.activeConnectionIds().isEmpty())
    }

    @Test
    fun `connection slots are capped and released`() {
        val state = LocalLinkState(maxConnections = 2)
        state.beginStart()
        assertTrue(state.acquire(1))
        assertTrue(state.acquire(2))
        assertFalse(state.acquire(3))
        state.release(1)
        assertTrue(state.acquire(3))
    }

    @Test
    fun `slots are refused once teardown starts`() {
        val state = LocalLinkState()
        state.beginStart()
        state.acquire(1)
        state.stop()
        assertFalse(state.acquire(2))
        assertTrue(state.activeConnectionIds().isEmpty())
    }
}

/** Real loopback-server lifecycle tests (no Android calls on this path). */
class LocalLinkServerTest {

    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    private class FakeDriver : UiDriver {
        val calls: MutableList<String> = Collections.synchronizedList(ArrayList<String>())
        val opened: MutableList<Int> = Collections.synchronizedList(ArrayList<Int>())
        val closed: MutableList<Int> = Collections.synchronizedList(ArrayList<Int>())

        private fun rec(s: String): Map<String, Any?> {
            calls.add(s)
            return CommandRouter.ok(mapOf("echo" to s))
        }

        override fun dump() = rec("dump")
        override fun screenshot(format: String, quality: Int) = rec("screenshot")
        override fun find(text: String?, id: String?) = rec("find")
        override fun tap(x: Int?, y: Int?, id: String?) = rec("tap")
        override fun longClick(x: Int?, y: Int?, id: String?) = rec("long")
        override fun type(text: String, id: String?) = rec("type")
        override fun key(code: Int) = rec("key")
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Long) = rec("swipe")
        override fun unlock(pin: String) = rec("unlock")
        override fun setArmed(on: Boolean) = rec("armed:$on")
        override fun expectProjection(ttlMs: Long?) = rec("expect:$ttlMs")
        override fun decommission(
            action: String?,
            targetPackage: String?,
            session: String?,
            connectionId: Int
        ) = rec("decommission")

        override fun connectionOpened(connectionId: Int) {
            opened.add(connectionId)
        }

        override fun connectionClosed(connectionId: Int) {
            closed.add(connectionId)
        }

        override fun status() = rec("status")
    }

    private class AuthLink(private val socket: Socket) {
        val out = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
        val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))

        fun send(line: String) {
            out.write(line + "\n")
            out.flush()
        }

        fun close() {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun freePort(): Int = ServerSocket(0, 1, loopback).use { it.localPort }

    private fun connect(port: Int): Socket {
        val s = Socket()
        s.connect(InetSocketAddress(loopback, port), 1_000)
        s.soTimeout = 2_000
        return s
    }

    private fun connectRetry(port: Int, timeoutMs: Long = 3_000): Socket {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                return connect(port)
            } catch (e: Exception) {
                last = e
                Thread.sleep(20)
            }
        }
        throw AssertionError("server did not accept on port $port", last)
    }

    private fun await(timeoutMs: Long = 3_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(20)
        }
        assertTrue("condition not met within ${timeoutMs}ms", cond())
    }

    private fun authenticate(port: Int): AuthLink {
        val link = AuthLink(connectRetry(port))
        link.send("""{"auth":"${LocalLinkServer.SECRET}"}""")
        assertEquals("""{"ok":true,"auth":true}""", link.input.readLine())
        return link
    }

    private fun server(
        driver: UiDriver,
        port: Int,
        preAuthTimeoutMs: Int = LocalLinkServer.PRE_AUTH_TIMEOUT_MS,
        maxConnections: Int = LocalLinkServer.MAX_CONNECTIONS
    ) = LocalLinkServer(
        driver = driver,
        port = port,
        preAuthTimeoutMs = preAuthTimeoutMs,
        maxConnections = maxConnections,
        nowMs = { 0L }
    )

    @Test
    fun `authenticated link serves commands and stop closes it`() {
        val driver = FakeDriver()
        val port = freePort()
        val linkServer = server(driver, port)
        linkServer.start()
        try {
            val link = authenticate(port)
            link.send("""{"cmd":"ping"}""")
            assertEquals("""{"ok":true,"pong":true}""", link.input.readLine())
            await { driver.opened.size == 1 }
            val id = driver.opened[0]

            linkServer.stop()
            // The tracked socket is closed, so the peer sees EOF promptly.
            assertNull(link.input.readLine())
            await { driver.closed.contains(id) }
            link.close()
        } finally {
            linkServer.stop()
            driver.calls.clear()
        }
        assertFalse(linkServer.isRunning())
    }

    @Test
    fun `bind failure resets running so a later start can retry`() {
        val driver = FakeDriver()
        val port = freePort()
        val blocker = ServerSocket(port, 1, loopback)
        val linkServer = server(driver, port)
        try {
            linkServer.start()
            // Bind fails: the accept loop must clear running instead of
            // leaving a dead server that refuses every future start().
            await { !linkServer.isRunning() }

            blocker.close()
            linkServer.start()
            assertTrue(linkServer.isRunning())
            val link = authenticate(port)
            link.send("""{"cmd":"ping"}""")
            assertEquals("""{"ok":true,"pong":true}""", link.input.readLine())
            link.close()
        } finally {
            blocker.close()
            linkServer.stop()
        }
    }

    @Test
    fun `a silent pre-auth peer is dropped by the read timeout`() {
        val driver = FakeDriver()
        val port = freePort()
        val linkServer = server(driver, port, preAuthTimeoutMs = 150)
        linkServer.start()
        try {
            val socket = connectRetry(port)
            socket.soTimeout = 3_000
            val started = System.currentTimeMillis()
            val line = BufferedReader(
                InputStreamReader(socket.getInputStream(), Charsets.UTF_8)
            ).readLine()
            val elapsed = System.currentTimeMillis() - started
            assertNull("timed-out peer must be closed", line)
            assertTrue("pre-auth timeout took ${elapsed}ms", elapsed < 2_500)
            assertTrue("unauth peer is never reported as opened", driver.opened.isEmpty())
            socket.close()
        } finally {
            linkServer.stop()
        }
    }

    @Test
    fun `the connection cap rejects extra links then frees a slot`() {
        val driver = FakeDriver()
        val port = freePort()
        val linkServer = server(driver, port, maxConnections = 1)
        linkServer.start()
        try {
            val first = authenticate(port)
            val second = AuthLink(connectRetry(port))
            val rejection = second.input.readLine()
            assertNotNull(rejection)
            assertTrue("expected busy rejection, got $rejection", rejection!!.contains("busy"))
            second.close()

            first.close()
            await { driver.closed.isNotEmpty() }

            val third = authenticate(port)
            third.send("""{"cmd":"ping"}""")
            assertEquals("""{"ok":true,"pong":true}""", third.input.readLine())
            third.close()
        } finally {
            linkServer.stop()
        }
    }

    @Test
    fun `a wrong secret is rejected without opened or closed callbacks`() {
        val driver = FakeDriver()
        val port = freePort()
        val linkServer = server(driver, port)
        linkServer.start()
        try {
            val link = AuthLink(connectRetry(port))
            link.send("""{"auth":"not-the-secret"}""")
            val response = link.input.readLine()
            assertNotNull(response)
            assertTrue("expected auth failure, got $response", response!!.contains("auth failed"))
            // A rejected handshake never becomes a decommission-tracked link.
            Thread.sleep(200)
            assertTrue(driver.opened.isEmpty())
            assertTrue(driver.closed.isEmpty())
            link.close()
            assertTrue(linkServer.isRunning())
        } finally {
            linkServer.stop()
        }
    }

    @Test
    fun `stop closes an accepted pre-auth socket too`() {
        val driver = FakeDriver()
        val port = freePort()
        val linkServer = server(driver, port)
        linkServer.start()
        try {
            val socket = connectRetry(port)
            socket.soTimeout = 3_000
            // No auth line: the handler is parked in the pre-auth read.
            linkServer.stop()
            val reader = BufferedReader(
                InputStreamReader(socket.getInputStream(), Charsets.UTF_8)
            )
            // A closed socket surfaces as EOF or a reset; both prove closure.
            val closed = try {
                reader.readLine() == null
            } catch (_: java.io.IOException) {
                true
            }
            assertTrue("tracked pre-auth socket must be closed", closed)
            socket.close()
        } finally {
            linkServer.stop()
        }
    }

    @Test
    fun `a new server start resets liveness from the previous instance`() {
        LocalLinkServer.noteAlive(123_456L)
        assertEquals(123_456L, LocalLinkServer.lastAliveMs)
        val driver = FakeDriver()
        val linkServer = server(driver, freePort())
        try {
            linkServer.start()
            // A stale timestamp must neither suppress nor trigger recovery.
            assertEquals(0L, LocalLinkServer.lastAliveMs)
        } finally {
            linkServer.stop()
        }
    }

    @Test
    fun `no command executes after stop`() {
        val driver = FakeDriver()
        val port = freePort()
        val linkServer = server(driver, port)
        linkServer.start()
        try {
            val link = authenticate(port)
            linkServer.stop()
            // A line already buffered (or written just after teardown) must be
            // discarded: running is re-checked after every read.
            try {
                link.send("""{"cmd":"dump"}""")
            } catch (_: Exception) {
            }
            Thread.sleep(300)
            assertFalse("command ran during teardown", driver.calls.contains("dump"))
            link.close()
        } finally {
            linkServer.stop()
        }
    }
}
