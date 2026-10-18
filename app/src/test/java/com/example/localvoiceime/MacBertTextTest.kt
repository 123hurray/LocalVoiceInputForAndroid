package com.example.localvoiceime

import org.junit.Assert.*
import org.junit.Test

class MacBertTextTest {
    private val vocabulary = javaClass.getResourceAsStream("/macbert-vocab.txt")!!.bufferedReader().readLines()
    private val text = MacBertText(vocabulary)

    @Test fun tokenizerMatchesPinnedBertReferenceForMixedTextAndUnicode() {
        val fixtures = javaClass.getResourceAsStream("/macbert-tokenizer.tsv")!!.bufferedReader().readLines()
        assertEquals(42, fixtures.size)
        fixtures.forEach { fixture ->
            val (source, expected) = fixture.split('\t')
            assertEquals(source, expected.split(',').map { it.toLong() }, text.tokenize(source).map { it.id })
        }
    }

    @Test fun replacementsOnlyTouchKnownSingleChineseCharacters() {
        val source = "测式 Android 300 元，😀𠀀"
        val tokens = text.tokenize(source)
        val target = vocabulary.indexOf("试")
        assertEquals('试', text.replacement(source, tokens.first { source.substring(it.start, it.end) == "式" }, target))
        tokens.filter { source.substring(it.start, it.end) !in listOf("测", "式", "元") }.forEach {
            assertNull(source.substring(it.start, it.end), text.replacement(source, it, target))
        }
        assertNull(text.replacement(source, tokens.first(), vocabulary.indexOf("[MASK]")))
        assertNull(text.replacement(source, tokens.first(), vocabulary.size + 1))
    }

    @Test fun longDraftWindowsCoverEachTokenExactlyOnceWithBoundedContext() {
        for (count in listOf(0, 1, 95, 96, 97, 192, 350, 4096)) {
            val windows = MacBertText.windows(count)
            assertEquals((0 until count).toList(), windows.flatMap { (it.core until it.coreEnd).toList() })
            windows.forEach {
                assertTrue(it.start <= it.core && it.end >= it.coreEnd)
                assertTrue(it.core - it.start <= 24 && it.end - it.coreEnd <= 24)
                assertTrue(it.end - it.start <= 144)
            }
        }
    }
}
