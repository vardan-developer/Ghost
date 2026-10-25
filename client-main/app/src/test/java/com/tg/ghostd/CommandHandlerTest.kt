package com.tg.ghostd

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandHandlerTest {

    // ------------------------------------------------------ result envelope

    @Test
    fun `explicit operation failure propagates to the wire envelope`() {
        val inner = JSONObject()
            .put("ok", false)
            .put("error", "tap failed")
            .put("node", "abc")
        val envelope = CommandHandler.resultEnvelope(inner)
        assertFalse("explicit ok=false must fail the envelope", envelope.getBoolean("ok"))
        assertEquals("error", envelope.getString("status"))
        assertEquals(inner, envelope.getJSONObject("result"))
    }

    @Test
    fun `successful object result keeps ok envelope`() {
        val inner = JSONObject().put("ok", true).put("value", 42)
        val envelope = CommandHandler.resultEnvelope(inner)
        assertTrue(envelope.getBoolean("ok"))
        assertEquals("ok", envelope.getString("status"))
        assertEquals(inner, envelope.getJSONObject("result"))
    }

    @Test
    fun `object without ok field keeps ok envelope`() {
        val inner = JSONObject().put("value", 42)
        val envelope = CommandHandler.resultEnvelope(inner)
        assertTrue(envelope.getBoolean("ok"))
        assertEquals("ok", envelope.getString("status"))
        assertEquals(inner, envelope.getJSONObject("result"))
    }

    @Test
    fun `non-object result keeps ok envelope`() {
        val envelope = CommandHandler.resultEnvelope("plain text")
        assertTrue(envelope.getBoolean("ok"))
        assertEquals("ok", envelope.getString("status"))
        assertEquals("plain text", envelope.getString("result"))
    }

    // --------------------------------------------------------------- shell

    @Test(timeout = 10_000)
    fun `shell drains stderr concurrently so a full pipe cannot deadlock`() {
        // Write ~100KB to stderr before writing anything to stdout. Reading
        // stdout to EOF first hangs forever because the child is blocked on
        // the full stderr pipe and never closes stdout.
        val res = CommandHandler.shell("yes err | head -c 1000000 >&2; echo done")
        assertEquals(0, res.getInt("rc"))
        assertEquals("done\n", res.getString("stdout"))
        assertTrue("stderr should be captured", res.getString("stderr").contains("err"))
    }

    @Test
    fun `shell captures stdout and stderr separately and rc`() {
        val res = CommandHandler.shell("echo out; echo err >&2; exit 7")
        assertEquals(7, res.getInt("rc"))
        assertEquals("out\n", res.getString("stdout"))
        assertEquals("err\n", res.getString("stderr"))
        assertFalse(res.has("timed_out"))
        assertFalse(res.has("truncated"))
    }

    @Test
    fun `shell caps captured output and reports truncation`() {
        val res = CommandHandler.shell(
            "yes x | head -c 1000",
            maxOutputBytes = 100
        )
        assertEquals(0, res.getInt("rc"))
        assertEquals(100, res.getString("stdout").length)
        assertTrue("truncated flag should be set", res.getBoolean("truncated"))
    }

    @Test(timeout = 10_000)
    fun `shell timeout destroys the process and reports timed_out`() {
        val started = System.currentTimeMillis()
        val res = CommandHandler.shell("sleep 10", timeoutMs = 500)
        val elapsed = System.currentTimeMillis() - started
        assertTrue("should return promptly after timeout", elapsed < 5_000)
        assertEquals(-1, res.getInt("rc"))
        assertTrue("timed_out flag should be set", res.getBoolean("timed_out"))
    }

    // -------------------------------------------------- shell option parsing

    @Test
    fun `shell parses flags joined into args 0 by the CLI`() {
        val req = CommandHandler.parseShellArgs(
            JSONArray().put("--timeout 500 --max-bytes 4096 echo hi")
        )
        assertEquals("echo hi", req.command)
        assertEquals(500L, req.timeoutMs)
        assertEquals(4096, req.maxOutputBytes)
    }

    @Test
    fun `shell parses separate flag arguments`() {
        val req = CommandHandler.parseShellArgs(
            JSONArray()
                .put("--timeout").put("250")
                .put("--max-bytes").put("2048")
                .put("echo").put("hi")
        )
        assertEquals("echo hi", req.command)
        assertEquals(250L, req.timeoutMs)
        assertEquals(2048, req.maxOutputBytes)
    }

    @Test
    fun `shell parses equals-form flags`() {
        val req = CommandHandler.parseShellArgs(
            JSONArray().put("--timeout=400 --max-bytes=512 echo ok")
        )
        assertEquals("echo ok", req.command)
        assertEquals(400L, req.timeoutMs)
        assertEquals(512, req.maxOutputBytes)
    }

    @Test
    fun `shell flag values are clamped to safe bounds`() {
        val tiny = CommandHandler.parseShellArgs(
            JSONArray().put("--timeout 1 --max-bytes -5 echo hi")
        )
        assertEquals(100L, tiny.timeoutMs)
        assertEquals(1, tiny.maxOutputBytes)

        val huge = CommandHandler.parseShellArgs(
            JSONArray().put("--timeout 99999999 --max-bytes 99999999 echo hi")
        )
        assertEquals(600_000L, huge.timeoutMs)
        assertEquals(10_000_000, huge.maxOutputBytes)
    }

    @Test
    fun `shell without flags keeps the default timeout max bytes and command`() {
        val req = CommandHandler.parseShellArgs(JSONArray().put("echo hi"))
        assertEquals("echo hi", req.command)
        assertEquals(30_000L, req.timeoutMs)
        assertEquals(1_000_000, req.maxOutputBytes)
    }

    @Test
    fun `shell leaves non-leading or unknown flags in the command`() {
        val unknown = CommandHandler.parseShellArgs(JSONArray().put("--foo bar echo hi"))
        assertEquals("--foo bar echo hi", unknown.command)

        val midCommand = CommandHandler.parseShellArgs(
            JSONArray().put("echo --timeout 5")
        )
        assertEquals("echo --timeout 5", midCommand.command)
    }

    @Test(timeout = 10_000)
    fun `shell executes parsed flags and preserves the result shape`() {
        val res = CommandHandler.shell(
            JSONArray().put("--timeout 2000 --max-bytes 20 printf 123456789012345678901234567890")
        )
        assertEquals(0, res.getInt("rc"))
        assertEquals("12345678901234567890", res.getString("stdout"))
        assertTrue("truncated flag should be set", res.getBoolean("truncated"))
    }

    @Test(timeout = 10_000)
    fun `shell parses and enforces the timeout flag`() {
        val started = System.currentTimeMillis()
        val res = CommandHandler.shell(JSONArray().put("--timeout 300 sleep 10"))
        val elapsed = System.currentTimeMillis() - started
        assertTrue("parsed timeout should kill the child", elapsed < 5_000)
        assertEquals(-1, res.getInt("rc"))
        assertTrue(res.getBoolean("timed_out"))
    }
}
