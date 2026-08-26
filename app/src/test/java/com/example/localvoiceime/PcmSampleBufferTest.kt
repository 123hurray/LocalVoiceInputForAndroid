package com.example.localvoiceime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmSampleBufferTest {

    @Test
    fun appendsNormalizesAndStopsAtCapacity() {
        val buffer = PcmSampleBuffer(capacity = 3)

        assertEquals(2, buffer.append(shortArrayOf(Short.MIN_VALUE, 0), 2))
        assertEquals(1, buffer.append(shortArrayOf(Short.MAX_VALUE, 123), 2))
        assertEquals(3, buffer.size())
        assertArrayEquals(
            floatArrayOf(-1f, 0f, Short.MAX_VALUE / 32768f),
            buffer.toNormalizedFloatArray(),
            0f,
        )

        buffer.clear()
        assertEquals(0, buffer.size())
    }
}
