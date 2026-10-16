package com.example.localvoiceime

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DownloadIntegrityTest {
    @Test fun verifiesExactSizeAndSha256() {
        val file = Files.createTempFile("voice", ".model").toFile()
        try {
            file.writeText("abc")
            val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            assertTrue(DownloadIntegrity.verify(file, 3, hash))
            assertFalse(DownloadIntegrity.verify(file, 4, hash))
            file.writeText("abd")
            assertFalse(DownloadIntegrity.verify(file, 3, hash))
        } finally { file.delete() }
    }
    @Test fun rejectsWrongResumeOffsetAndTotal() {
        assertTrue(DownloadIntegrity.validRange("bytes 10-99/100", 10, 100))
        assertFalse(DownloadIntegrity.validRange("bytes 0-99/100", 10, 100))
        assertFalse(DownloadIntegrity.validRange("bytes 10-99/200", 10, 100))
        assertFalse(DownloadIntegrity.validRange("bytes 10-100/100", 10, 100))
        assertFalse(DownloadIntegrity.validRange(null, 10, 100))
        assertFalse(DownloadIntegrity.validRange("bytes 9999999999999999999999-99/100", 10, 100))
    }
    @Test fun senseUsesAutomaticLanguageAndInverseNormalization() {
        val sense = createAsrConfig(AsrModel.SENSE_VOICE) { it }
        assertEquals("auto", sense.senseVoice.language)
        assertTrue(sense.senseVoice.useInverseTextNormalization)
    }
    @Test fun settingsUpgradeUsesDefaultAndSafeWindow() {
        assertEquals(AsrModel.SENSE_VOICE, AsrModel.fromId(null))
        assertEquals(AsrModel.SENSE_VOICE, AsrModel.fromId("removed-model"))
        assertEquals(60, AsrModel.SENSE_VOICE.recordingLimitSeconds)
    }
}
