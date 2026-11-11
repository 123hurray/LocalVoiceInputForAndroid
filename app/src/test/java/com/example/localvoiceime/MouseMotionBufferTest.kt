package com.example.localvoiceime

import org.junit.Assert.*
import org.junit.Test

class MouseMotionBufferTest {
    @Test fun scrollBurstsBecomeEvenSingleStepsAndReverseInputCancelsPendingSteps() {
        val buffer = MouseMotionBuffer()
        buffer.add(0, 0, 0, 5)
        assertEquals(1, buffer.take()!!.wheel)
        buffer.add(0, 0, 0, -3)
        assertEquals(1, buffer.take()!!.wheel)
        assertNull(buffer.take())
    }
    @Test fun slowTransportCannotBuildAnUnboundedScrollQueue() {
        val buffer = MouseMotionBuffer()
        repeat(100) { buffer.add(0, 0, 0, 5) }
        var total = 0
        while (true) {
            val delta = buffer.take() ?: break
            assertEquals(1, delta.wheel)
            total += delta.wheel
        }
        assertEquals(24, total)
        buffer.add(0, 10, 20, 2)
        buffer.clear()
        assertNull(buffer.take())
    }
}
