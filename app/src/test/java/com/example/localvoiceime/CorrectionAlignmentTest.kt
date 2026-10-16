package com.example.localvoiceime

import org.junit.Assert.*
import org.junit.Test

class CorrectionAlignmentTest {
    @Test fun mapsChangedWordWithoutReplacingSurroundingSentence() {
        val text = "明天去北京开会"
        assertEquals("背景", CorrectionAlignment.alternative(text, "明天去背景开会", 3, 5))
    }
    @Test fun handlesDifferentLengthPhrases() {
        assertEquals("Paraformer", CorrectionAlignment.alternative("使用帕拉模型", "使用Paraformer模型", 2, 4))
    }
    @Test fun unicodeBeforeSelectionDoesNotShiftTheRange() {
        val text = "😀去北京"
        assertEquals("背景", CorrectionAlignment.alternative(text, "😀去背景", 3, 5))
    }
    @Test fun boundsLongInputsToAvoidLargeAlignmentAllocations() {
        assertNull(CorrectionAlignment.alternative("字".repeat(1201), "字", 0, 1))
    }
}
