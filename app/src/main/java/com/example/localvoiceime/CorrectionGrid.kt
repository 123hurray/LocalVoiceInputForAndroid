package com.example.localvoiceime

import kotlin.math.roundToInt

object CorrectionGrid {
    data class Columns(val count: Int, val cellWidth: Float)
    fun columns(width: Int, minimum: Float, gap: Float): Columns {
        val count = ((width + gap) / (minimum + gap)).toInt().coerceAtLeast(1)
        return Columns(count, ((width - gap * (count - 1)) / count).coerceAtLeast(1f))
    }
    data class Rows(val pitch: Int, val count: Int, val remainder: Int)
    fun rows(height: Int, desired: Int, minimum: Int, gap: Int, inset: Int): Rows {
        val usable = (height - 2 * inset + gap).coerceAtLeast(1)
        val maximum = (usable / (minimum + gap).coerceAtLeast(1)).coerceAtLeast(1)
        val count = (usable.toFloat() / (desired + gap)).roundToInt().coerceIn(1, maximum)
        return Rows((usable / count).coerceAtLeast(1), count, usable % count)
    }
    fun snap(offset: Int, pitch: Int, maximum: Int): Int =
        ((offset.toFloat() / pitch.coerceAtLeast(1)).roundToInt() * pitch).coerceIn(0, maximum.coerceAtLeast(0))
}
