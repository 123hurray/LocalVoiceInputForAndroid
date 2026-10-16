package com.example.localvoiceime

import org.junit.Assert.*
import org.junit.Test

class PendingSpeechTest {
    @Test fun utteranceRemainsAvailableUntilModelIsReadyAndCanOnlyBeConsumedOnce() {
        val audio = floatArrayOf(0.1f, -0.2f, 0.3f)
        val pending = PendingSpeech(audio)
        assertFalse(pending.cancelled)
        assertSame(audio, pending.takeSamples())
        assertNull(pending.takeSamples())
    }
    @Test fun leavingInputViewDiscardsAudioBeforeQueuedDecodeStarts() {
        val pending = PendingSpeech(floatArrayOf(0.1f))
        pending.cancel()
        assertTrue(pending.cancelled)
        assertNull(pending.takeSamples())
        pending.cancel()
        assertNull(pending.takeSamples())
    }
    @Test fun cancellationAfterDecodeStartedStillSuppressesResult() {
        val pending = PendingSpeech(floatArrayOf(0.1f))
        assertNotNull(pending.takeSamples())
        pending.cancel()
        assertTrue(pending.cancelled)
    }
}
