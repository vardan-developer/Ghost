package com.tg.ghostd

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileTransferTest {

    private val C = FileTransfer.CHUNK_SIZE.toLong()

    // ------------------------------------------------- chunk framing math

    @Test fun chunkCountBasic() {
        assertEquals(0, FileTransfer.chunkCount(0))
        assertEquals(1, FileTransfer.chunkCount(1))
        assertEquals(1, FileTransfer.chunkCount(C))
        assertEquals(2, FileTransfer.chunkCount(C + 1))
        assertEquals(2, FileTransfer.chunkCount(2 * C))
        assertEquals(100, FileTransfer.chunkCount(100 * C))
        assertEquals(101, FileTransfer.chunkCount(100 * C + 1))
        assertEquals(4, FileTransfer.chunkCount(200_000)) // 3.05 chunks -> 4
    }

    @Test fun chunkCountNegative() {
        assertEquals(0, FileTransfer.chunkCount(-5))
    }

    @Test fun chunkLenPartialLast() {
        // 150000 bytes: 2 full chunks + 16712-byte remainder
        assertEquals(C.toInt(), FileTransfer.chunkLen(150_000, 0))
        assertEquals(C.toInt(), FileTransfer.chunkLen(150_000, 1))
        assertEquals((150_000 - 2 * C).toInt(), FileTransfer.chunkLen(150_000, 2))
        assertEquals(0, FileTransfer.chunkLen(150_000, 3))
        assertEquals(0, FileTransfer.chunkLen(150_000, -1))
        assertEquals(0, FileTransfer.chunkLen(0, 0))
    }

    @Test fun chunkLenMatchesCount() {
        // sum of all chunk lens == total for assorted sizes
        for (total in longArrayOf(0, 1, C - 1, C, C + 1, 5 * C, 5 * C + 12345)) {
            var sum = 0L
            var seq = 0L
            while (true) {
                val n = FileTransfer.chunkLen(total, seq)
                if (n == 0) break
                sum += n
                seq++
            }
            assertEquals("total=$total", total, sum)
            assertEquals("chunks=$total", seq, FileTransfer.chunkCount(total))
        }
    }

    // ------------------------------------------------------- frame build/parse

    @Test fun frameRoundTrip() {
        val header = JSONObject()
            .put("type", "file_chunk")
            .put("seq", 7)
            .put("path", "/sdcard/DCIM/photo.jpg")
            .put("len", 5)
            .put("total", 12345)
        // payload containing newline + NUL bytes (must survive framing)
        val payload = byteArrayOf(10, 0, 65, -1, 10)
        val frame = FileTransfer.buildFrame(header, payload)
        val (h, p) = FileTransfer.parseFrame(frame)!!
        assertEquals("file_chunk", h.optString("type"))
        assertEquals(7, h.optInt("seq"))
        assertEquals("/sdcard/DCIM/photo.jpg", h.optString("path"))
        assertEquals(5, h.optInt("len"))
        assertEquals(12345, h.optLong("total"))
        assertArrayEquals(payload, p)
    }

    @Test fun frameRejectsUnframed() {
        assertNull(FileTransfer.parseFrame(byteArrayOf(1, 2, 3)))
        assertNull(FileTransfer.parseFrame(ByteArray(0)))
        assertNull(FileTransfer.parseFrame("not json\nrest".toByteArray()))
    }

    @Test fun frameEndWithEmptyPayload() {
        val frame = FileTransfer.buildFrame(
            JSONObject().put("type", "file_end").put("path", "/sdcard/Download/ghosta.apk"),
            ByteArray(0)
        )
        val (h, p) = FileTransfer.parseFrame(frame)!!
        assertEquals("file_end", h.optString("type"))
        assertEquals(0, p.size)
    }
}
