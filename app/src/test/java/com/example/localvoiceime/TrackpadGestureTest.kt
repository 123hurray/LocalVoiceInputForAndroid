package com.example.localvoiceime

import org.junit.Assert.*
import org.junit.Test

class TrackpadGestureTest {
    private fun finger(id: Int, x: Float = 0f, y: Float = 0f) = TrackpadGesture.Finger(id, x, y)
    private fun gesture() = TrackpadGesture(4f, 1f)

    @Test fun pointerGainIsThirtyPercentFaster() {
        val g = gesture()
        g.start(listOf(finger(5)), 0)
        val moved = g.move(listOf(finger(5, 20f, 10f)))
        assertEquals(20f * 1.4f * 1.3f, moved.dx, 0.0001f)
        assertEquals(10f * 1.4f * 1.3f, moved.dy, 0.0001f)
        assertEquals(0f, moved.wheel, 0f)
        assertEquals(0, g.lift(emptyList(), 60).click)
    }

    @Test fun aSingleTapIsLeftClickAndTwoFingerTapIsRightClickOnly() {
        val g = gesture()
        g.start(listOf(finger(5)), 0)
        assertEquals(1, g.lift(emptyList(), 50).click)
        g.start(listOf(finger(5)), 100)
        g.add(listOf(finger(5), finger(9, 50f)))
        assertEquals(0, g.lift(listOf(finger(9, 50f)), 150).click)
        assertEquals(2, g.lift(emptyList(), 170).click)
    }

    @Test fun twoFingerScrollNeverMovesThePointerOrClicksWhenOneFingerLifts() {
        val g = gesture()
        g.start(listOf(finger(5)), 0)
        g.add(listOf(finger(5), finger(9, 50f)))
        val scroll = g.move(listOf(finger(5, y = -24f), finger(9, 50f, -24f)))
        assertEquals(4f, scroll.wheel, 0f)
        assertEquals(0f, scroll.dx, 0f)
        assertEquals(0f, scroll.dy, 0f)
        g.lift(listOf(finger(9, 50f, -24f)), 100)
        assertEquals(TrackpadGesture.Effect(), g.move(listOf(finger(9, 70f, -60f))))
        assertEquals(0, g.lift(emptyList(), 150).click)
    }

    @Test fun extraFingersAndPinchingDoNotBecomeAccidentalClicks() {
        val g = gesture()
        g.start(listOf(finger(1)), 0)
        g.add(listOf(finger(1), finger(2), finger(3)))
        assertEquals(0, g.lift(emptyList(), 60).click)
        g.start(listOf(finger(1)), 100)
        g.add(listOf(finger(1), finger(2, 50f)))
        g.move(listOf(finger(1, -10f), finger(2, 60f)))
        assertEquals(0, g.lift(emptyList(), 170).click)
    }
    @Test fun aTapThenImmediateSlideHoldsLeftUntilTheFingerLifts() {
        val g = gesture()
        g.start(listOf(finger(5)), 0)
        assertEquals(1, g.lift(emptyList(), 50).click)
        g.start(listOf(finger(5)), 120)
        val drag = g.move(listOf(finger(5, 20f)))
        assertEquals(1, drag.heldButtons)
        assertTrue(drag.dx > 0)
        val end = g.lift(emptyList(), 200)
        assertEquals(0, end.heldButtons)
        assertEquals(0, end.click)
    }

    @Test fun secondFingerDuringDragReleasesLeftWithoutAContextClick() {
        val g = gesture()
        g.start(listOf(finger(1)), 0)
        g.lift(emptyList(), 50)
        g.start(listOf(finger(1)), 100)
        g.move(listOf(finger(1, 20f)))
        assertEquals(0, g.add(listOf(finger(1, 20f), finger(2, 50f))).heldButtons)
        assertEquals(0, g.lift(emptyList(), 150).click)
    }

}
