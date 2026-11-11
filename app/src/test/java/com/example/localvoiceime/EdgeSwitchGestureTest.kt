package com.example.localvoiceime

import org.junit.Assert.*
import org.junit.Test

class EdgeSwitchGestureTest {
    @Test fun theRightEdgeContinuesSteppingAndCanReverseBeforeFingerUp() {
        val g = EdgeSwitchGesture(24f, 40f)
        assertFalse(g.begin(100f, 100f, 400f))
        assertNull(g.move(100f, 200f, 1))
        assertTrue(g.begin(390f, 100f, 400f))
        assertNull(g.move(390f, 80f, 1))
        assertEquals(true, g.move(390f, 50f, 1))
        assertEquals(false, g.move(390f, 160f, 1))
        assertNull(g.move(390f, 180f, 1))
        assertEquals(false, g.move(390f, 210f, 1))
        g.cancel()
        assertNull(g.move(390f, 100f, 1))
        g.begin(390f, 100f, 400f)
        assertEquals(false, g.move(390f, 150f, 1))
    }
    @Test fun multipleFingersOrAHorizontalGestureDoNotSwitchApplications() {
        val g = EdgeSwitchGesture(24f, 40f)
        g.begin(390f, 100f, 400f)
        assertNull(g.move(390f, 160f, 2))
        assertNull(g.move(390f, 200f, 1))
        g.begin(390f, 100f, 400f)
        assertNull(g.move(330f, 110f, 1))
        assertNull(g.move(330f, 200f, 1))
    }
}
