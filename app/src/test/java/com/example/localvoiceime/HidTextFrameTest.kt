package com.example.localvoiceime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HidTextFrameTest {
    @Test fun emitsPhysicalMarkersAndKnownUtf8CrcVector() {
        val usages = intArrayOf(0x27, 0x1e, 0x1f, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26,
            0x04, 0x05, 0x06, 0x07, 0x08, 0x09)
        val body = "41e4b8adf09f99820a28d71a81"
        assertArrayEquals(
            intArrayOf(0x6d) + body.map { usages[it.digitToInt(16)] }.toIntArray() + 0x6e,
            HidTextFrame.encode("A中🙂\n"),
        )
    }

    @Test fun singleCharactersKeepCompleteUtf8CrcAndFrameMarkers() {
        val fixtures = mapOf("好" to "e5a5bd36c84cc4", "是" to "e698afaddfcb6b",
            "不" to "e4b88deebf7a43", "A" to "41d3d99e8b", "1" to "3183dcefb7")
        fun hexUsage(c: Char) = when (c) {
            '0' -> 0x27
            in '1'..'9' -> 0x1e + c.code - '1'.code
            else -> 0x04 + c.code - 'a'.code
        }
        for ((source, body) in fixtures) {
            val expected = intArrayOf(0x6d) + body.map(::hexUsage).toIntArray() + 0x6e
            val encoded = HidTextFrame.encode(source)
            assertArrayEquals(source, expected, encoded)
            val seen = mutableListOf<Int>()
            var previous = 0
            for (usage in HidReportProtocol.reportUsages(encoded)) {
                if (usage != 0 && usage != previous) seen += usage
                previous = usage
            }
            assertEquals(source, expected.toList(), seen)
        }
    }

    @Test fun rejectsEmptyOversizedAndMalformedUtf16() {
        assertThrows(IllegalArgumentException::class.java) { HidTextFrame.encode("") }
        assertThrows(IllegalArgumentException::class.java) { HidTextFrame.encode("中".repeat(342)) }
        assertThrows(IllegalArgumentException::class.java) { HidTextFrame.encode("\uD800") }
    }
}
