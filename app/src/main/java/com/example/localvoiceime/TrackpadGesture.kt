package com.example.localvoiceime

import kotlin.math.hypot

/** Pointer IDs keep two-finger gestures stable when one finger lifts first. */
internal class TrackpadGesture(private val slop: Float, private val density: Float) {
    data class Finger(val id: Int, val x: Float, val y: Float)
    data class Effect(val dx: Float = 0f, val dy: Float = 0f, val wheel: Float = 0f, val click: Int = 0)
    private var previous = emptyList<Finger>()
    private var origin = emptyList<Finger>()
    private var beganAt = 0L
    private var peakCount = 0
    private var moved = false
    private var ending = false
    private var cancelled = false

    fun start(fingers: List<Finger>, time: Long) {
        reset()
        previous = fingers
        origin = fingers
        peakCount = fingers.size
        beganAt = time
    }

    fun add(fingers: List<Finger>) {
        peakCount = maxOf(peakCount, fingers.size)
        if (fingers.size > 2 || ending) cancelled = true
        previous = fingers
        origin = fingers
    }

    fun move(fingers: List<Finger>): Effect {
        if (cancelled || ending || fingers.isEmpty() || fingers.size != previous.size) return Effect()
        val old = previous.associateBy { it.id }
        val starts = origin.associateBy { it.id }
        if (fingers.any { it.id !in old || it.id !in starts }) return Effect()
        moved = moved || fingers.any { finger ->
            val start = starts.getValue(finger.id)
            hypot(finger.x - start.x, finger.y - start.y) > slop
        }
        val dx = fingers.sumOf { (it.x - old.getValue(it.id).x).toDouble() }.toFloat() / fingers.size
        val dy = fingers.sumOf { (it.y - old.getValue(it.id).y).toDouble() }.toFloat() / fingers.size
        previous = fingers
        if (!moved) return Effect()
        return if (peakCount == 2) Effect(wheel = -dy / (6f * density))
        else Effect(dx = dx * POINTER_GAIN / density, dy = dy * POINTER_GAIN / density)
    }

    fun lift(remaining: List<Finger>, time: Long): Effect {
        if (remaining.isNotEmpty()) {
            ending = true
            previous = remaining
            return Effect()
        }
        val click = if (!cancelled && !moved && time - beganAt < 280L) {
            when (peakCount) { 1 -> 1; 2 -> 2; else -> 0 }
        } else 0
        reset()
        return Effect(click = click)
    }

    fun reset() {
        previous = emptyList(); origin = emptyList()
        peakCount = 0; moved = false; ending = false; cancelled = false
    }

    companion object {
        // Increase the former 1.4 gain by exactly 30%, without changing wheel sensitivity.
        const val POINTER_GAIN = 1.4f * 1.3f
    }
}
