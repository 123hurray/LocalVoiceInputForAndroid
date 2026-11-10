package com.example.localvoiceime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HidTextFrameTest {
    @Test fun emitsPhysicalMarkersAndKnownUtf8CrcVector() {
        val usages = intArrayOf(0x27, 0x1e, 0x1f, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26,
            0x04, 0x05, 0x06, 0x07, 0x08, 0x09)
        val body = "41e4b8adf09f99820a28d71a81"
        assertArrayEquals(
            intArrayOf(0x6c) + body.map { usages[it.digitToInt(16)] }.toIntArray() + 0x6d,
            HidTextFrame.encode("A中🙂\n"),
        )
    }

    @Test fun rejectsEmptyOversizedAndMalformedUtf16() {
        assertThrows(IllegalArgumentException::class.java) { HidTextFrame.encode("") }
        assertThrows(IllegalArgumentException::class.java) { HidTextFrame.encode("中".repeat(342)) }
        assertThrows(IllegalArgumentException::class.java) { HidTextFrame.encode("\uD800") }
    }
}
