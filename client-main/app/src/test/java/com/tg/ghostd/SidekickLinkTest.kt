package com.tg.ghostd

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * JVM tests for the sidekick link retry policy.
 *
 * The important invariant is at-most-once delivery for non-idempotent verbs:
 * once a request has been written and flushed, a read failure is ambiguous and
 * must NOT be retried. Connection/auth failures before the write stay safely
 * retryable.
 */
class SidekickLinkTest {

    private var server: ServerSocket? = null

    @After
    fun cleanup() {
        SidekickLink.resetForTest()
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        SidekickLink.portForTest = 0
    }

    /** Start a loopback server; [handle] runs on its own thread per connection. */
    private fun startServer(handle: (Socket, Int) -> Unit) {
        val s = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        server = s
        SidekickLink.resetForTest()
        SidekickLink.portForTest = s.localPort
        val acceptThread = Thread {
            val accepted = AtomicInteger()
            try {
                while (!s.isClosed) {
                    val client = s.accept()
                    val n = accepted.incrementAndGet()
                    Thread {
                        try {
                            handle(client, n)
                        } catch (_: Exception) {
                        } finally {
                            try {
                                client.close()
                            } catch (_: Exception) {
                            }
                        }
                    }.apply { isDaemon = true }.start()
                }
            } catch (_: Exception) {
            }
        }
        acceptThread.isDaemon = true
        acceptThread.start()
    }

    private fun reader(socket: Socket) =
        BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))

    private fun writer(socket: Socket) =
        BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

    private fun waitFor(expected: Int, counter: AtomicInteger) {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline && counter.get() < expected) {
            Thread.sleep(10)
        }
    }

    @Test(timeout = 15_000)
    fun `read failure after the request was written is not retried`() {
        val connections = AtomicInteger()
        val requests = AtomicInteger()
        startServer { socket, _ ->
            connections.incrementAndGet()
            val r = reader(socket)
            val w = writer(socket)
            r.readLine() // auth handshake
            w.write("{\"ok\":true}\n")
            w.flush()
            // Count only requests that actually reached the sidekick.
            if (r.readLine() != null) requests.incrementAndGet()
            // Drop the link without answering. The command may have executed.
        }

        val error = try {
            SidekickLink.send(JSONObject().put("cmd", "tap"))
            null
        } catch (e: Exception) {
            e
        }

        assertNotNull("send must fail when the reply is lost", error)
        assertTrue(
            "error should explain that it was not retried: ${error!!.message}",
            error.message!!.contains("not retrying")
        )
        waitFor(1, requests)
        // Any erroneous retry would open another connection and deliver tap twice.
        Thread.sleep(250)
        assertEquals("request must reach the sidekick exactly once", 1, requests.get())
        assertEquals("must not reconnect after a flushed request", 1, connections.get())
    }

    @Test(timeout = 15_000)
    fun `connection failure before the request is written is retried safely`() {
        val connections = AtomicInteger()
        val requests = AtomicInteger()
        startServer { socket, n ->
            connections.incrementAndGet()
            if (n != 1) {
                val r = reader(socket)
                val w = writer(socket)
                r.readLine() // auth handshake
                w.write("{\"ok\":true}\n")
                w.flush()
                if (r.readLine() != null) requests.incrementAndGet()
                w.write("{\"ok\":true,\"value\":1}\n")
                w.flush()
            }
            // n == 1: close before auth/reply; the request was never written.
        }

        val res = SidekickLink.send(JSONObject().put("cmd", "tap"))

        assertTrue(res.getBoolean("ok"))
        assertEquals(1, res.getInt("value"))
        assertEquals("pre-write failure should retry once", 2, connections.get())
        assertEquals("exactly one request delivered", 1, requests.get())
    }

    @Test(timeout = 15_000)
    fun `successful send reuses the live connection`() {
        val connections = AtomicInteger()
        val requests = AtomicInteger()
        startServer { socket, _ ->
            connections.incrementAndGet()
            val r = reader(socket)
            val w = writer(socket)
            r.readLine() // auth handshake
            w.write("{\"ok\":true}\n")
            w.flush()
            while (true) {
                r.readLine() ?: break
                requests.incrementAndGet()
                w.write("{\"ok\":true,\"seen\":$requests}\n")
                w.flush()
            }
        }

        assertEquals(1, SidekickLink.send(JSONObject().put("cmd", "tap")).getInt("seen"))
        assertEquals(2, SidekickLink.send(JSONObject().put("cmd", "type")).getInt("seen"))
        assertEquals("the live link should be reused", 1, connections.get())
        assertEquals(2, requests.get())
    }
}
