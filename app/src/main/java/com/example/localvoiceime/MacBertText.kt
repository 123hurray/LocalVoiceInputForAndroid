package com.example.localvoiceime

import java.text.Normalizer
import java.util.Locale

/** BERT basic/WordPiece tokenizer with original UTF-16 spans. Never reconstruct user text by decoding. */
class MacBertText(val vocabulary: List<String>) {
    data class Token(val id: Long, val start: Int, val end: Int)
    private val ids = vocabulary.withIndex().associate { it.value to it.index.toLong() }
    val cls = ids.getValue("[CLS]")
    val sep = ids.getValue("[SEP]")
    private val unknown = ids.getValue("[UNK]")

    fun tokenize(text: String): List<Token> {
        val result = ArrayList<Token>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val end = i + Character.charCount(cp)
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0 || cp == 0xfffd ||
                Character.getType(cp) in listOf(Character.CONTROL.toInt(), Character.FORMAT.toInt())) { i = end; continue }
            if (isCjk(cp) || punctuation(cp)) {
                result.add(Token(ids[text.substring(i, end)] ?: unknown, i, end)); i = end; continue
            }
            var stop = end
            while (stop < text.length) {
                val next = text.codePointAt(stop)
                if (Character.isWhitespace(next) || Character.isSpaceChar(next) || isCjk(next) || punctuation(next) ||
                    Character.getType(next) in listOf(Character.CONTROL.toInt(), Character.FORMAT.toInt())) break
                stop += Character.charCount(next)
            }
            val word = Normalizer.normalize(text.substring(i, stop).lowercase(Locale.ROOT), Normalizer.Form.NFD)
                .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            // Non-Chinese spans are always protected, including words whose normalization changes length.
            val pieces = ArrayList<Long>()
            var offset = 0
            if (word.length <= 100) while (offset < word.length) {
                var last = word.length
                var found: Long? = null
                while (last > offset) {
                    found = ids[(if (offset == 0) "" else "##") + word.substring(offset, last)]
                    if (found != null) break
                    last--
                }
                if (found == null) { pieces.clear(); break }
                pieces.add(found); offset = last
            }
            if (pieces.isEmpty()) pieces.add(unknown)
            pieces.forEach { result.add(Token(it, i, stop)) }
            i = stop
        }
        return result
    }

    fun replacement(source: String, token: Token, predicted: Int): Char? {
        if (token.end - token.start != 1 || !isEditableChinese(source[token.start]) ||
            vocabulary.getOrNull(token.id.toInt()) != source.substring(token.start, token.end)) return null
        val word = vocabulary.getOrNull(predicted) ?: return null
        return word.singleOrNull()?.takeIf { isEditableChinese(it) && it != source[token.start] }
    }

    companion object {
        data class Window(val core: Int, val coreEnd: Int, val start: Int, val end: Int)
        fun windows(count: Int): List<Window> = (0 until count step 96).map { core ->
            val end = minOf(core + 96, count)
            Window(core, end, maxOf(0, core - 24), minOf(count, end + 24))
        }
        fun isEditableChinese(c: Char) = c.code in 0x3400..0x4dbf || c.code in 0x4e00..0x9fff
        private fun isCjk(cp: Int) = cp in 0x3400..0x4dbf || cp in 0x4e00..0x9fff || cp in 0x20000..0x2fa1f ||
            cp in 0xf900..0xfaff || cp in 0x30000..0x323af
        private fun punctuation(cp: Int) = cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126 ||
            Character.getType(cp) in listOf(Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
                Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
                Character.FINAL_QUOTE_PUNCTUATION.toInt(), Character.OTHER_PUNCTUATION.toInt())
    }
}
