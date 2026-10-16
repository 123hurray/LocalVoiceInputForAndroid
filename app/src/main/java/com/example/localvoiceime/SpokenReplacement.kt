package com.example.localvoiceime

/** Replace a fixed selection without changing the surrounding draft. */
object SpokenReplacement {
    fun apply(source: String, range: IntRange, recognized: String): String {
        val start = range.first
        val end = range.last + 1
        require(start in 0..source.length && end in start..source.length)
        val whole = start == 0 && end == source.length
        val text = if (whole) recognized.trim() else recognized.trim()
            .trim('。', '，', '！', '？', '.', ',', '!', '?', '；', ';', '：', ':').trim()
        return source.replaceRange(start, end, text)
    }
}
