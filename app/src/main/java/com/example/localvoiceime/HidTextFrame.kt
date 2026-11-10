package com.example.localvoiceime

import java.util.zip.CRC32

/** US keyboard usages. Wire format: F18, lowercase UTF-8 hex, CRC32 hex, F19. */
internal object HidTextFrame {
    const val START = 0x6d
    const val END = 0x6e
    const val MAX_BYTES = 1024
    private const val HEX = "0123456789abcdef"

    fun encode(text: String): IntArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
        require(bytes.toString(Charsets.UTF_8) == text)
        val crc = CRC32().apply { update(bytes) }.value
        val body = buildString(bytes.size * 2 + 8) {
            for (byte in bytes) {
                val value = byte.toInt() and 255
                append(HEX[value shr 4])
                append(HEX[value and 15])
            }
            for (shift in 28 downTo 0 step 4) append(HEX[((crc shr shift) and 15).toInt()])
        }
        return IntArray(body.length + 2) { i ->
            when (i) {
                0 -> START
                body.length + 1 -> END
                else -> when (val c = body[i - 1]) {
                    '0' -> 0x27
                    in '1'..'9' -> 0x1e + (c - '1')
                    else -> 0x04 + (c - 'a')
                }
            }
        }
    }
}
