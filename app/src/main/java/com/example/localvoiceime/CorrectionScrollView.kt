package com.example.localvoiceime

import android.content.Context
import android.view.MotionEvent
import android.widget.ScrollView

/** Fits complete tile rows to the viewport and settles scrolling at row boundaries. */
class CorrectionScrollView(context: Context, private val words: CorrectionSelectionView) : ScrollView(context) {
    private val settle = Runnable { snapRows() }
    init {
        isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(words, android.widget.FrameLayout.LayoutParams(-1, -2))
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        words.setViewportHeight(MeasureSpec.getSize(heightMeasureSpec))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        removeCallbacks(settle)
        if (!words.isRangeSelecting) postDelayed(settle, 160)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val result = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            removeCallbacks(settle); postDelayed(settle, 160)
        }
        return result
    }
    fun snapRows() {
        if (words.isRangeSelecting) return
        val target = CorrectionGrid.snap(scrollY, words.rowPitch, words.height - height)
        if (target != scrollY) scrollTo(0, target)
    }
    override fun onDetachedFromWindow() { removeCallbacks(settle); super.onDetachedFromWindow() }
}
