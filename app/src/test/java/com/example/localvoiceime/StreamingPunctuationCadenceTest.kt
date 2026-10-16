package com.example.localvoiceime

import org.junit.Assert.*
import org.junit.Test

class StreamingPunctuationCadenceTest {
    @Test fun refreshesEveryThreeSecondsOfAudio() {
        val cadence = StreamingPunctuationCadence()
        assertFalse(cadence.advance(32_000, "今天"))
        assertTrue(cadence.advance(16_000, "今天很好"))
        assertFalse(cadence.advance(16_000, "今天很好我们"))
        assertTrue(cadence.advance(32_000, "今天很好我们出去"))
    }

    @Test fun avoidsRepeatedInferenceForSilenceOrUnchangedText() {
        val cadence = StreamingPunctuationCadence()
        assertFalse(cadence.advance(48_000, ""))
        assertTrue(cadence.advance(1, "你好"))
        assertFalse(cadence.advance(48_000, "你好"))
        assertTrue(cadence.advance(1, "你好世界"))
    }
}
