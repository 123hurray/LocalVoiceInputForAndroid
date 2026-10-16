package com.example.localvoiceime

/** Maps a selected phrase into a second model's full result; these are suggestions, not N-best. */
object CorrectionAlignment {
    fun alternative(source: String, other: String, start: Int, end: Int): String? {
        require(start in 0..end && end <= source.length)
        val a = source.codePoints().toArray(); val b = other.codePoints().toArray()
        if (a.size > 1200 || b.size > 1200) return null
        val costs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices) costs[i + 1][0] = i + 1
        for (j in b.indices) costs[0][j + 1] = j + 1
        for (i in 1..a.size) for (j in 1..b.size) {
            costs[i][j] = minOf(costs[i - 1][j] + 1, costs[i][j - 1] + 1,
                costs[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        val left = source.codePointCount(0, start); val right = source.codePointCount(0, end)
        val bounds = mutableListOf(a.size to b.size)
        var i = a.size; var j = b.size
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && costs[i][j] == costs[i - 1][j - 1] + (if (a[i - 1] == b[j - 1]) 0 else 1) -> { i--; j-- }
                i > 0 && costs[i][j] == costs[i - 1][j] + 1 -> i--
                else -> j--
            }
            bounds.add(i to j)
        }
        val from = bounds.filter { it.first == left }.minOfOrNull { it.second } ?: return null
        val to = bounds.filter { it.first == right }.maxOfOrNull { it.second } ?: return null
        return other.substring(other.offsetByCodePoints(0, from), other.offsetByCodePoints(0, to)).trim()
    }
}
