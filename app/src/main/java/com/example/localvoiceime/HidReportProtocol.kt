package com.example.localvoiceime

internal enum class InputDelivery { CONFIRMED, SENT_WITHOUT_ACK, FAILED, UNCONFIRMED, CHECKSUM_FAILED }

internal enum class HidProfile(val keyboardId: Int, val composite: Boolean) {
    LEGACY_KEYBOARD(0, false),
    COMPOSITE(1, true),
}

internal object HidReportProtocol {
    const val MOUSE_ID = 2
    const val ACK_ID = 3
    const val MAX_RETRIES = 3
    enum class Attempt { ACCEPTED, REJECTED, TIMEOUT, WRITE_FAILED }

    /** Legacy has no Report ID item anywhere, so Android sends exactly eight keyboard bytes. */
    fun descriptor(mode: HidProfile): ByteArray {
        val header = intArrayOf(0x05, 0x01, 0x09, 0x06, 0xa1, 0x01)
        val keyboard = intArrayOf(
            0x05, 0x07,
            0x19, 0xe0, 0x29, 0xe7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
            0x75, 0x08, 0x95, 0x01, 0x81, 0x01,
            0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x73, 0x19, 0x00, 0x29, 0x73, 0x81, 0x00,
        )
        val items = if (!mode.composite) header + keyboard + 0xc0 else {
            val output = intArrayOf(
                0x06, 0x00, 0xff, 0x09, 0x01, 0x85, ACK_ID,
                0x15, 0x00, 0x26, 0xff, 0x00, 0x75, 0x08, 0x95, 0x01, 0x91, 0x02, 0xc0,
            )
            val mouse = intArrayOf(
                // Mouse application, pointer physical collection, three buttons and signed X/Y/wheel.
                0x05, 0x01, 0x09, 0x02, 0xa1, 0x01, 0x85, MOUSE_ID,
                0x09, 0x01, 0xa1, 0x00,
                0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01,
                0x75, 0x01, 0x95, 0x03, 0x81, 0x02,
                0x75, 0x05, 0x95, 0x01, 0x81, 0x03,
                0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,
                0x15, 0x81, 0x25, 0x7f, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06, 0xc0, 0xc0,
            )
            header + intArrayOf(0x85, mode.keyboardId) + keyboard + output + mouse
        }
        return items.map(Int::toByte).toByteArray()
    }

    fun mouseReport(buttons: Int, dx: Int, dy: Int, wheel: Int): ByteArray =
        byteArrayOf((buttons and 0x07).toByte(), dx.coerceIn(-127, 127).toByte(),
            dy.coerceIn(-127, 127).toByte(), wheel.coerceIn(-127, 127).toByte())

    fun parseAck(id: Int, payload: ByteArray): Attempt? =
        if (id != ACK_ID || payload.size != 1) null else when (payload[0].toInt()) {
            1 -> Attempt.ACCEPTED
            2 -> Attempt.REJECTED
            else -> null
        }

    /** A new single key also releases the previous key. Identical neighbors need a release in between. */
    fun reportUsages(keys: IntArray): IntArray {
        if (keys.isEmpty()) return intArrayOf()
        val result = IntArray(keys.size * 2)
        var length = 0
        var previous = 0
        for (key in keys) {
            if (key == previous) result[length++] = 0
            result[length++] = key
            previous = key
        }
        result[length++] = 0
        return result.copyOf(length)
    }

    fun deliver(attempt: (retry: Int) -> Attempt): InputDelivery {
        for (retry in 0..MAX_RETRIES) {
            when (attempt(retry)) {
                Attempt.ACCEPTED -> return InputDelivery.CONFIRMED
                Attempt.REJECTED -> Unit
                Attempt.TIMEOUT -> return InputDelivery.UNCONFIRMED
                Attempt.WRITE_FAILED -> return InputDelivery.FAILED
            }
        }
        return InputDelivery.CHECKSUM_FAILED
    }
}
