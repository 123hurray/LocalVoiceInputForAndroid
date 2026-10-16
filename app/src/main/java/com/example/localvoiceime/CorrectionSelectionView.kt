package com.example.localvoiceime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.icu.text.BreakIterator
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Unicode character grid: swipe horizontally or hold then drag across rows to select. */
class CorrectionSelectionView(context: Context) : View(context) {
    private data class Tile(val start: Int, val end: Int, val row: Int, val box: RectF)
    private val tiles = mutableListOf<Tile>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18 * resources.displayMetrics.scaledDensity }
    var text = ""; private set
    var selection: IntRange? = null; private set
    var isRangeSelecting = false; private set
    var rowPitch = dp(46); private set
    private var viewportHeight = 0
    private var rowHeight = dp(42)
    private var anchor: Tile? = null
    private var previousSelection: IntRange? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var viewportY = 0f
    private var scrolling = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    var onSelectionFinished: (() -> Unit)? = null
    var onSelectionChanged: (() -> Unit)? = null
    private val held = Runnable {
        if (anchor != null && !scrolling) {
            isRangeSelecting = true
            parent.requestDisallowInterceptTouchEvent(true)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }
    private val autoScroll = object : Runnable {
        override fun run() {
            val scroll = parent as? CorrectionScrollView ?: return
            if (!isRangeSelecting || anchor == null) return
            val edge = minOf(dp(32), scroll.height / 3).coerceAtLeast(1)
            val delta = when {
                viewportY < edge -> -dp(8)
                viewportY > scroll.height - edge -> dp(8)
                else -> return
            }
            val before = scroll.scrollY
            scroll.scrollBy(0, delta)
            updateRange(lastX, viewportY + scroll.scrollY - top)
            if (scroll.scrollY != before) postDelayed(this, 16)
        }
    }

    fun setViewportHeight(value: Int) {
        if (value > 0 && value != viewportHeight) { viewportHeight = value; forceLayout() }
    }
    fun show(value: String) {
        removeCallbacks(held); removeCallbacks(autoScroll)
        anchor = null; isRangeSelecting = false
        text = value; selection = null; contentDescription = value
        requestLayout(); invalidate(); onSelectionChanged?.invoke()
        (parent as? CorrectionScrollView)?.scrollTo(0, 0)
    }
    fun selectedText() = selection?.let { text.substring(it.first, it.last + 1) }.orEmpty()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val gap = dp(4)
        val inset = dp(2)
        val rows = CorrectionGrid.rows(viewportHeight.coerceAtLeast(dp(46)),
            max(dp(42), (paint.fontSpacing + dp(12)).toInt()),
            (paint.fontSpacing + dp(6)).toInt(), gap, inset)
        rowPitch = rows.pitch; rowHeight = (rowPitch - gap).coerceAtLeast(1)
        val columns = CorrectionGrid.columns((width - 2 * inset).coerceAtLeast(1),
            max(dp(32).toFloat(), paint.textSize + dp(12)), gap.toFloat())
        tiles.clear()
        val iterator = BreakIterator.getCharacterInstance(Locale.CHINA)
        iterator.setText(text)
        var start = iterator.first(); var end = iterator.next()
        var row = 0; var column = 0
        while (end != BreakIterator.DONE) {
            val value = text.substring(start, end)
            if (value.contains('\n')) { row++; column = 0 }
            else if (value.isNotBlank()) {
                if (column == columns.count) { row++; column = 0 }
                val x = inset + column * (columns.cellWidth + gap)
                val y = inset + row * rowPitch
                tiles.add(Tile(start, end, row, RectF(x, y.toFloat(), x + columns.cellWidth, (y + rowHeight).toFloat())))
                column++
            }
            start = end; end = iterator.next()
        }
        setMeasuredDimension(width, (row + 1) * rowPitch - gap + 2 * inset + rows.remainder)
    }
    override fun onDraw(canvas: Canvas) {
        for (tile in tiles) {
            val selected = selection?.let { tile.start <= it.last && tile.end > it.first } == true
            paint.color = if (selected) VoiceStyle.accent else VoiceStyle.surface
            canvas.drawRoundRect(tile.box, dp(6).toFloat(), dp(6).toFloat(), paint)
            paint.color = VoiceStyle.text
            val value = text.substring(tile.start, tile.end)
            canvas.drawText(value, tile.box.centerX() - paint.measureText(value) / 2,
                tile.box.centerY() - (paint.ascent() + paint.descent()) / 2, paint)
        }
    }
    private fun updateRange(x: Float, y: Float) {
        val first = anchor ?: return
        val row = ((y - dp(2)) / rowPitch).toInt().coerceAtLeast(0)
        val rowTiles = tiles.filter { it.row == row }.ifEmpty { tiles }
        val last = rowTiles.minByOrNull { abs(it.box.centerY() - y) * 4 + abs(it.box.centerX() - x) } ?: return
        selection = minOf(first.start, last.start) until maxOf(first.end, last.end)
        invalidate(); onSelectionChanged?.invoke()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                anchor = tiles.firstOrNull { it.box.contains(event.x, event.y) } ?: return false
                previousSelection = selection
                downX = event.x; downY = event.y; scrolling = false; isRangeSelecting = false
                parent.requestDisallowInterceptTouchEvent(true)
                selection = anchor!!.start until anchor!!.end
                postDelayed(held, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.x - downX); val dy = abs(event.y - downY)
                if (!isRangeSelecting && max(dx, dy) > slop) {
                    removeCallbacks(held)
                    if (dy > dx) {
                        scrolling = true; selection = previousSelection
                        parent.requestDisallowInterceptTouchEvent(false)
                    } else isRangeSelecting = true
                }
                if (isRangeSelecting) {
                    lastX = event.x
                    val scroll = parent as? CorrectionScrollView
                    viewportY = event.y + top - (scroll?.scrollY ?: 0)
                    updateRange(event.x, event.y)
                    removeCallbacks(autoScroll); post(autoScroll)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(held); removeCallbacks(autoScroll)
                val completed = event.actionMasked == MotionEvent.ACTION_UP && !scrolling
                if (!completed) selection = previousSelection
                anchor = null; isRangeSelecting = false
                parent.requestDisallowInterceptTouchEvent(false)
                (parent as? CorrectionScrollView)?.snapRows()
                if (completed) { performClick(); onSelectionFinished?.invoke() }
            }
        }
        invalidate(); onSelectionChanged?.invoke()
        return true
    }
    override fun onDetachedFromWindow() {
        removeCallbacks(held); removeCallbacks(autoScroll); super.onDetachedFromWindow()
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    private fun dp(n: Int) = VoiceStyle.dp(context, n)
}
