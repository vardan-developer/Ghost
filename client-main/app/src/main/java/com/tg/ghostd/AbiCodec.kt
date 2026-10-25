package com.tg.ghostd

/**
 * Minimal ABI decoding for the single contract function we use:
 * `endpoint() view returns (string)`.
 *
 * eth_call result layout (hex, 0x-prefixed) — NO selector echo:
 *   32 bytes  offset (0x20)
 *   32 bytes  string length
 *   N bytes   string data, zero-padded to 32-byte words
 *
 * Pure JVM, no Android imports — unit-testable.
 */
object AbiCodec {

    fun decodeString(hex: String?): String? {
        if (hex == null) return null
        val h = hex.removePrefix("0x")
        if (h.length < 128 || h.length % 2 != 0) return null
        val offset = h.substring(0, 64).toLongOrNull(16) ?: return null
        if (offset != 32L) return null
        val len = h.substring(64, 128).toIntOrNull(16) ?: return null
        if (len < 0 || h.length < 128 + len * 2) return null
        val sb = StringBuilder(len)
        for (i in 0 until len) {
            val b = h.substring(128 + i * 2, 130 + i * 2).toInt(16)
            if (b == 0) break // stop at NUL padding
            sb.append(b.toChar())
        }
        return sb.toString()
    }
}
