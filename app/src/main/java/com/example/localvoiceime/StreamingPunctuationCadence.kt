package com.example.localvoiceime

/** Audio time, rather than wall time, also handles speech buffered during model loading. */
class StreamingPunctuationCadence {
    private var samples = 0
    private var previous = ""

    fun advance(count: Int, text: String): Boolean {
        samples += count
        if (samples < 48_000 || text.isBlank() || text == previous) return false
        samples = 0
        previous = text
        return true
    }
}
