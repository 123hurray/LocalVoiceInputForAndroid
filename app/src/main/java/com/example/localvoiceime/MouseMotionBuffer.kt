package com.example.localvoiceime

/** Bounded relative movement; one wheel pulse at a time avoids large scroll jumps. */
internal class MouseMotionBuffer {
    data class Delta(val buttons: Int, val x: Int, val y: Int, val wheel: Int)
    private var buttons = 0
    private var x = 0
    private var y = 0
    private var wheel = 0
    @Synchronized fun add(mask: Int, dx: Int, dy: Int, scroll: Int) {
        buttons = mask
        x = (x + dx).coerceIn(-2048, 2048)
        y = (y + dy).coerceIn(-2048, 2048)
        wheel = (wheel + scroll).coerceIn(-24, 24)
    }
    @Synchronized fun take(): Delta? {
        if (x == 0 && y == 0 && wheel == 0) return null
        val amount = Delta(buttons, x.coerceIn(-127, 127), y.coerceIn(-127, 127), wheel.coerceIn(-1, 1))
        x -= amount.x; y -= amount.y; wheel -= amount.wheel
        return amount
    }
    @Synchronized fun clear() { x = 0; y = 0; wheel = 0; buttons = 0 }
}
