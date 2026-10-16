package com.example.localvoiceime

import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CorrectionSelectionViewTest {
    @Test fun nonemptyDraftCreatesSelectableCharacterTiles() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val words = CorrectionSelectionView(context)
            FrameLayout(context).addView(words)
            words.show("明天去北京")
            words.setViewportHeight(VoiceStyle.dp(context, 180))
            val width = VoiceStyle.dp(context, 360)
            words.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            words.layout(0, 0, width, words.measuredHeight)
            val xy = VoiceStyle.dp(context, 16).toFloat()
            val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, xy, xy, 0)
            try {
                assertTrue(words.onTouchEvent(event))
                assertEquals("明", words.selectedText())
            } finally {
                event.recycle()
                val cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, xy, xy, 0)
                words.onTouchEvent(cancel)
                cancel.recycle()
            }
        }
    }
}
