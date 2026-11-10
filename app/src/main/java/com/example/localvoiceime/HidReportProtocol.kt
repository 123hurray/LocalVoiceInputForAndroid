package com.example.localvoiceime

internal enum class InputDelivery { CONFIRMED, FAILED, UNCONFIRMED, CHECKSUM_FAILED }

internal object HidReportProtocol {
    const val KEYBOARD_ID = 1
    const val MOUSE_ID = 2
    const val ACK_ID = 3
    const val MAX_RETRIES = 3
    enum class Attempt { ACCEPTED, REJECTED, TIMEOUT, WRITE_FAILED }

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
