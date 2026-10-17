package com.example.localvoiceime

import org.junit.Assert.*
import org.junit.Test

class CorrectionSettingsTest {
    @Test fun textCleanupRequiresBothParentAndChild() {
        assertFalse(VoiceSettings(correctionEnabled = false, polishEnabled = true).shouldPolish)
        assertFalse(VoiceSettings(correctionEnabled = true, polishEnabled = false).shouldPolish)
        assertFalse(VoiceSettings(correctionEnabled = false, polishEnabled = false).shouldPolish)
        assertTrue(VoiceSettings(correctionEnabled = true, polishEnabled = true).shouldPolish)
    }
}
