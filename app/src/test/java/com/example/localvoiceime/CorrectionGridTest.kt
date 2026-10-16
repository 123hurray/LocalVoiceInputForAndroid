package com.example.localvoiceime

import org.junit.Assert.*
import org.junit.Test

class CorrectionGridTest {
    @Test fun fullRowsUseAllAvailableWidth() {
        for (width in listOf(240, 321, 343, 387)) {
            val grid = CorrectionGrid.columns(width, 32f, 4f)
            assertEquals(width.toFloat(), grid.count * grid.cellWidth + (grid.count - 1) * 4f, 0.001f)
        }
    }
    @Test fun completeRowsExactlyFillViewportIncludingPixelRemainder() {
        for (height in listOf(36, 160, 180, 199, 241)) {
            val rows = CorrectionGrid.rows(height, 42, 28, 4, 2)
            assertEquals(height, rows.count * rows.pitch + rows.remainder)
            val scrollRange = (rows.count + 5) * rows.pitch + rows.remainder - height
            assertEquals(5 * rows.pitch, scrollRange)
        }
    }
    @Test fun scrollingSettlesAtNearestWholeRowAndClampsAtEnds() {
        assertEquals(46, CorrectionGrid.snap(30, 46, 230))
        assertEquals(0, CorrectionGrid.snap(-15, 46, 230))
        assertEquals(230, CorrectionGrid.snap(999, 46, 230))
    }
}
