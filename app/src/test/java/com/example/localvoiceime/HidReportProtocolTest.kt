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
