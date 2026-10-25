package com.tg.ghostd

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class FileReceiverTest {
    private val dir: File = Files.createTempDirectory("ghost-receiver-test").toFile()
    private val owner = Any()

    @After
    fun cleanup() {
        FileReceiver.abortAll("test cleanup")
        dir.deleteRecursively()
    }

    private fun sha256Of(payload: ByteArray): String =
        FileTransfer.toHex(MessageDigest.getInstance("SHA-256").digest(payload))

    /** Invoke the private fail(t, msg) hook to simulate an obsolete waiter. */
    private fun invokeFail(t: FileReceiver.Transfer, msg: String) {
        val method = FileReceiver::class.java.getDeclaredMethod(
            "fail", FileReceiver.Transfer::class.java, String::class.java
        )
        method.isAccessible = true
        method.invoke(FileReceiver, t, msg)
    }

    private fun endHeader(
        dest: File,
        cmdId: String,
        payload: ByteArray,
        chunks: Long = 1,
        bytes: Long = payload.size.toLong(),
        sha: String = sha256Of(payload)
    ): JSONObject = JSONObject()
        .put("path", dest.path)
        .put("cmd_id", cmdId)
        .put("bytes", bytes)
        .put("chunks", chunks)
        .put("sha256", sha)

    @Test
    fun `chunks and end may arrive before worker starts waiting`() {
        // Regression: CommandHandler used to create the slot on its worker.
        // The WebSocket could deliver the entire small file before that worker
        // ran, dropping every frame and leaving a transfer stuck forever.
        val dest = File(dir, "early.bin")
        val payload = "early frames must not be lost".toByteArray()
        val cmdId = "cmd-early"
        val transfer = FileReceiver.prepare(dest.path, cmdId, owner)
        FileReceiver.onChunk(
            JSONObject().put("path", dest.path).put("cmd_id", cmdId)
                .put("total", payload.size), payload
        )
        FileReceiver.onEnd(endHeader(dest, cmdId, payload))

        val result = FileReceiver.await(transfer)
        assertEquals(payload.size.toLong(), result.getLong("bytes"))
        assertArrayEquals(payload, dest.readBytes())
    }

    @Test
    fun `same-path wrong cmd_id is ignored then correct cmd_id accepted`() {
        val dest = File(dir, "cmd-id.bin")
        val good = "good payload".toByteArray()
        val bad = "bad payload!".toByteArray()
        val cmdId = "cmd-good"
        val transfer = FileReceiver.prepare(dest.path, cmdId, owner)

        // Same path, wrong cmd_id: neither chunk nor end may touch the slot.
        FileReceiver.onChunk(
            JSONObject().put("path", dest.path).put("cmd_id", "cmd-wrong")
                .put("total", bad.size), bad
        )
        FileReceiver.onEnd(
            endHeader(dest, "cmd-wrong", bad, sha = "deadbeef")
        )
        FileReceiver.onChunk(
            JSONObject().put("path", dest.path).put("cmd_id", cmdId)
                .put("total", good.size), good
        )
        FileReceiver.onEnd(endHeader(dest, cmdId, good))

        val result = FileReceiver.await(transfer)
        assertEquals(good.size.toLong(), result.getLong("bytes"))
        assertEquals(1L, result.getLong("chunks"))
        assertArrayEquals(good, dest.readBytes())
    }

    @Test
    fun `abortOwner does not abort another owners transfer`() {
        val dest = File(dir, "owner.bin")
        val payload = "owned".toByteArray()
        val cmdId = "cmd-owner"
        val transfer = FileReceiver.prepare(dest.path, cmdId, owner)

        val res = FileReceiver.abortOwner(Any(), "wrong owner")
        assertFalse(
            "abortOwner(other) must not abort our transfer",
            res.getBoolean("aborted")
        )

        FileReceiver.onChunk(
            JSONObject().put("path", dest.path).put("cmd_id", cmdId)
                .put("total", payload.size), payload
        )
        FileReceiver.onEnd(endHeader(dest, cmdId, payload))

        val result = FileReceiver.await(transfer)
        assertEquals(payload.size.toLong(), result.getLong("bytes"))
        assertArrayEquals(payload, dest.readBytes())
    }

    @Test
    fun `abortAll releases slot for the next upload`() {
        val first = FileReceiver.prepare(
            File(dir, "first.bin").path, "cmd-first", owner
        )
        val res = FileReceiver.abortAll("connection lost")
        assertTrue(res.getBoolean("aborted"))
        try {
            FileReceiver.await(first)
            fail("aborted transfer should report an error")
        } catch (_: Exception) {
            // expected
        }
        val second = FileReceiver.prepare(
            File(dir, "second.bin").path, "cmd-second", owner
        )
        assertEquals(File(dir, "second.bin").path, second.path)
    }

    @Test
    fun `bad sha deletes part and preserves existing destination`() {
        val dest = File(dir, "bad-sha.bin")
        dest.writeText("old destination")
        val payload = "new payload".toByteArray()
        val cmdId = "cmd-bad-sha"
        val transfer = FileReceiver.prepare(dest.path, cmdId, owner)
        val part = File(dest.path + ".part")
        assertTrue("part file should exist after prepare", part.exists())

        FileReceiver.onChunk(
            JSONObject().put("path", dest.path).put("cmd_id", cmdId)
                .put("total", payload.size), payload
        )
        FileReceiver.onEnd(endHeader(dest, cmdId, payload, sha = "deadbeef"))

        try {
            FileReceiver.await(transfer)
            fail("bad sha should report an error")
        } catch (_: Exception) {
            // expected
        }
        assertFalse("part file must be deleted on failure", part.exists())
        assertEquals("old destination", dest.readText())
    }

    @Test
    fun `size mismatch deletes part and errors`() {
        val dest = File(dir, "bad-size.bin")
        val payload = "size".toByteArray()
        val cmdId = "cmd-bad-size"
        val transfer = FileReceiver.prepare(dest.path, cmdId, owner)
        val part = File(dest.path + ".part")

        FileReceiver.onChunk(
            JSONObject().put("path", dest.path).put("cmd_id", cmdId)
                .put("total", payload.size), payload
        )
        FileReceiver.onEnd(
            endHeader(dest, cmdId, payload, bytes = payload.size + 1L)
        )

        try {
            FileReceiver.await(transfer)
            fail("size mismatch should report an error")
        } catch (_: Exception) {
            // expected
        }
        assertFalse("part file must be deleted on failure", part.exists())
        assertFalse("destination must not be created on failure", dest.exists())
    }

    @Test
    fun `chunks mismatch deletes part and errors`() {
        val dest = File(dir, "bad-chunks.bin")
        val payload = "chunks".toByteArray()
        val cmdId = "cmd-bad-chunks"
        val transfer = FileReceiver.prepare(dest.path, cmdId, owner)
        val part = File(dest.path + ".part")

        FileReceiver.onChunk(
            JSONObject().put("path", dest.path).put("cmd_id", cmdId)
                .put("total", payload.size), payload
        )
        FileReceiver.onEnd(
            endHeader(dest, cmdId, payload, chunks = 2)
        )

        try {
            FileReceiver.await(transfer)
            fail("chunks mismatch should report an error")
        } catch (_: Exception) {
            // expected
        }
        assertFalse("part file must be deleted on failure", part.exists())
        assertFalse("destination must not be created on failure", dest.exists())
    }

    @Test
    fun `obsolete waiter cannot delete replacement part after completed transfer`() {
        val dest = File(dir, "completed-replace.bin")
        val first = "first payload".toByteArray()
        val second = "second payload".toByteArray()
        val cmd1 = "cmd-first"
        val cmd2 = "cmd-second"

        // Complete transfer 1 and move its .part to dest.
        val t1 = FileReceiver.prepare(dest.path, cmd1, owner)
        FileReceiver.onChunk(
            JSONObject().put("path", dest.path).put("cmd_id", cmd1)
                .put("total", first.size), first
        )
        FileReceiver.onEnd(endHeader(dest, cmd1, first))
        assertArrayEquals(first, dest.readBytes())

        // Replacement on the same path creates a new .part at the same path.
        val t2 = FileReceiver.prepare(dest.path, cmd2, owner)
        val part = File(dest.path + ".part")
        assertTrue("replacement .part should exist", part.exists())

        // An obsolete waiter now times out/interrupts for the completed t1.
        // It must not delete t2's shared .part or mark t1 failed.
        invokeFail(t1, "timed out after completion")

        assertTrue("replacement .part must survive obsolete cleanup", part.exists())
        assertEquals("completed transfer must not gain an error", null, t1.error)
        val r1 = FileReceiver.await(t1)
        assertEquals(first.size.toLong(), r1.getLong("bytes"))

        // The replacement still completes normally and wins the destination.
        FileReceiver.onChunk(
            JSONObject().put("path", dest.path).put("cmd_id", cmd2)
                .put("total", second.size), second
        )
        FileReceiver.onEnd(endHeader(dest, cmd2, second))
        val r2 = FileReceiver.await(t2)
        assertEquals(second.size.toLong(), r2.getLong("bytes"))
        assertArrayEquals(second, dest.readBytes())
    }

    @Test
    fun `obsolete waiter cannot delete replacement part after superseded transfer`() {
        val dest = File(dir, "superseded-replace.bin")
        val second = "second payload".toByteArray()
        val cmd1 = "cmd-first"
        val cmd2 = "cmd-second"

        val t1 = FileReceiver.prepare(dest.path, cmd1, owner)
        // Make t1 stale so prepare() reclaims the slot for t2.
        t1.lastActivity = System.currentTimeMillis() - 60_000L
        val t2 = FileReceiver.prepare(dest.path, cmd2, owner)
        val part = File(dest.path + ".part")
        assertTrue("replacement .part should exist", part.exists())
        assertTrue(
            "superseded transfer should be failed",
            t1.error != null && t1.error!!.contains("superseded")
        )

        // An obsolete waiter for t1 now times out/interrupts.
        invokeFail(t1, "timed out after supersede")

        assertTrue("replacement .part must survive obsolete cleanup", part.exists())
        assertTrue(
            "supersede error must be preserved",
            t1.error != null && t1.error!!.contains("superseded")
        )

        FileReceiver.onChunk(
            JSONObject().put("path", dest.path).put("cmd_id", cmd2)
                .put("total", second.size), second
        )
        FileReceiver.onEnd(endHeader(dest, cmd2, second))
        val r2 = FileReceiver.await(t2)
        assertEquals(second.size.toLong(), r2.getLong("bytes"))
        assertArrayEquals(second, dest.readBytes())
    }
}
