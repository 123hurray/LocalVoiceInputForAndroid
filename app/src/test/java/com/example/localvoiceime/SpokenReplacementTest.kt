package com.example.localvoiceime

import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenReplacementTest {
    @Test fun selectedCharactersAreReplacedWithoutChangingSurroundings() {
        assertEquals("明天去上海开会。", SpokenReplacement.apply("明天去北京开会。", 3..4, "上海。"))
    }
    @Test fun fullRerecordingKeepsSentencePunctuation() {
        assertEquals("明天出发。", SpokenReplacement.apply("今天出发。", 0..4, "明天出发。"))
    }
    @Test fun supportsDifferentLengthAndUnicodePrefix() {
        assertEquals("😀使用Paraformer识别。", SpokenReplacement.apply("😀使用帕拉识别。", 4..5, "Paraformer。"))
    }
    @Test fun emptyDraftCanBeRerecorded() {
        assertEquals("新的内容。", SpokenReplacement.apply("", 0 until 0, "新的内容。"))
    }
}
