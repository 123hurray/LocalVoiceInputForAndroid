package com.example.localvoiceime

import org.junit.Assert.*
import org.junit.Test

class HidReportProtocolTest {
    @Test fun optimizedReportsPreserveEveryKeyIncludingConsecutiveDuplicates() {
        val keys = HidTextFrame.encode("测试 Android 999")
        val reports = HidReportProtocol.reportUsages(keys)
        assertArrayEquals(keys, reports.filter { it != 0 }.toIntArray())
        assertEquals(0, reports.last())
        assertFalse(reports.toList().zipWithNext().any { (first, second) -> first == second })
        assertTrue(reports.size < keys.size * 2)
    }

    @Test fun compositeDescriptorUsesIndependentReportIds() {
        fun fields(): Map<Pair<Int, Int>, Int> {
            val bytes = HidReportProtocol.descriptor()
            val bits = mutableMapOf<Pair<Int, Int>, Int>()
            var offset = 0; var id = 0; var size = 0; var count = 0
            while (offset < bytes.size) {
                val prefix = bytes[offset++].toInt() and 0xff
                val length = intArrayOf(0, 1, 2, 4)[prefix and 3]
                var value = 0
                repeat(length) { i -> value = value or ((bytes[offset++].toInt() and 0xff) shl (8 * i)) }
                when (val item = prefix and 0xfc) {
                    0x84 -> id = value
                    0x74 -> size = value
                    0x94 -> count = value
                    0x80, 0x90 -> {
                        val key = id to item
                        bits[key] = bits.getOrDefault(key, 0) + size * count
                    }
                }
            }
            return bits
        }
        assertEquals(1, HidReportProtocol.KEYBOARD_ID)
        assertEquals(mapOf((1 to 0x80) to 64, (2 to 0x80) to 32, (3 to 0x90) to 8), fields())
    }

    @Test fun appSwitchKeysUseCommandAndTabWithoutControl() {
        assertArrayEquals(byteArrayOf(8, 0, 0x2b, 0, 0, 0, 0, 0), HidReportProtocol.appSwitchReport(false))
        assertArrayEquals(byteArrayOf(10, 0, 0x2b, 0, 0, 0, 0, 0), HidReportProtocol.appSwitchReport(true))
        assertArrayEquals(byteArrayOf(8, 0, 0, 0, 0, 0, 0, 0), HidReportProtocol.appSwitchHold(false))
        assertArrayEquals(byteArrayOf(10, 0, 0, 0, 0, 0, 0, 0), HidReportProtocol.appSwitchHold(true))
    }

    @Test fun mouseReportsUseIndependentIdAndSignedRelativeAxes() {
        assertEquals(2, HidReportProtocol.MOUSE_ID)
        assertArrayEquals(byteArrayOf(3, 127, -127, -1), HidReportProtocol.mouseReport(3, 400, -400, -1))
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), HidReportProtocol.mouseReport(0, 0, 0, 0))
    }

    @Test fun onlyReportThreeWithOneRecognizedByteIsAnAcknowledgement() {
        assertEquals(HidReportProtocol.Attempt.ACCEPTED, HidReportProtocol.parseAck(3, byteArrayOf(1)))
        assertEquals(HidReportProtocol.Attempt.REJECTED, HidReportProtocol.parseAck(3, byteArrayOf(2)))
        assertNull(HidReportProtocol.parseAck(2, byteArrayOf(1)))
        assertNull(HidReportProtocol.parseAck(3, byteArrayOf(3, 1)))
        assertNull(HidReportProtocol.parseAck(3, byteArrayOf(0)))
    }

    @Test fun retriesOnlyExplicitRejectionAndStopsAtTheFirstAcknowledgement() {
        val attempts = mutableListOf<Int>()
        fun deliver(replies: List<HidReportProtocol.Attempt>) = HidReportProtocol.deliver {
            attempts += it
            replies[it]
        }
        val reject = HidReportProtocol.Attempt.REJECTED
        val accept = HidReportProtocol.Attempt.ACCEPTED
        val timeout = HidReportProtocol.Attempt.TIMEOUT
        assertEquals(InputDelivery.CONFIRMED, deliver(listOf(reject, reject, accept)))
        assertEquals(listOf(0, 1, 2), attempts)
        attempts.clear()
        assertEquals(InputDelivery.CHECKSUM_FAILED, deliver(List(4) { reject }))
        assertEquals(listOf(0, 1, 2, 3), attempts)
        attempts.clear()
        assertEquals(InputDelivery.UNCONFIRMED, deliver(listOf(reject, timeout)))
        assertEquals(listOf(0, 1), attempts)
    }
}
