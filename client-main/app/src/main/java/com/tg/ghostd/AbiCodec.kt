package com.tg.ghostd

/**
 * Minimal ABI decoding for the single contract function we use:
 * `endpoint() view returns (string)`.
 *
 * eth_call result layout (hex, 0x-prefixed):
 *   4 bytes   selector echo (ignored)
 *   32 bytes  offset (0x20)
 *   32 bytes  string length
 *   N bytes   string data, zero-padded to 32-byte word
 *
 * Pure JVM, no Android imports — unit-testable.
 */
object AbiCodec {

    fun decodeString(hex: String?): String? {
        if (hex == null) return null
        val h = hex.removePrefix("0x")
        if (h.length < 136 || h.length % 2 != 0) return null
        val body = h.substring(8) // skip selector echo
        if (body.length < 128) return null
        val offset = body.substring(0, 64).toLongOrNull(16) ?: return null
        if (offset != 32L) return null
        val len = body.substring(64, 128).toIntOrNull(16) ?: return null
        if (len < 0 || body.length < 128 + len * 2) return null
        val sb = StringBuilder(len)
        for (i in 0 until len) {
            val b = body.substring(128 + i * 2, 130 + i * 2).toInt(16)
            if (b == 0) break // stop at NUL padding
            sb.append(b.toChar())
        }
        return sb.toString()
    }
}
