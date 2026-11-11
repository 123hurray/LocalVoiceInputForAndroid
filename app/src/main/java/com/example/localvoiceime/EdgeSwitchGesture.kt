package com.example.localvoiceime

import kotlin.math.abs

internal class EdgeSwitchGesture(private val edgeWidth: Float, private val distance: Float) {
    private var startX = 0f
    private var startY = 0f
    private var captured = false
    private var finished = false

    fun begin(x: Float, y: Float, width: Float): Boolean {
        startX = x; startY = y
        captured = x >= width - edgeWidth
        finished = false
        return captured
    }

    /** true = Command+Shift+Tab; false = Command+Tab; null = no shortcut. */
    fun move(x: Float, y: Float, fingers: Int): Boolean? {
        if (!captured || finished) return null
        if (fingers != 1) { finished = true; return null }
        val dx = x - startX
        val dy = y - startY
        if (abs(dx) >= distance && abs(dx) > abs(dy)) { finished = true; return null }
        if (abs(dy) < distance || abs(dy) <= abs(dx)) return null
        finished = true
        return dy < 0
    }
    fun cancel() { finished = true }
}
