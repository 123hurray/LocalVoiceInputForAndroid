package com.example.localvoiceime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.icu.text.BreakIterator
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.max

/** Character tiles with drag-to-select, using Unicode grapheme boundaries. */
class CorrectionSelectionView(context: Context) : View(context) {
    private data class Tile(val start: Int, val end: Int, val box: RectF)
    private val tiles = mutableListOf<Tile>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18 * resources.displayMetrics.scaledDensity }
    var text: String = ""; private set
    var selection: IntRange? = null; private set
    private var anchor: Tile? = null
    private var downX = 0f
    private var dragging = false
    var onSelectionFinished: (() -> Unit)? = null
    var onSelectionChanged: (() -> Unit)? = null

    fun show(value: String) {
        text = value; selection = null; contentDescription = value
        requestLayout(); invalidate(); onSelectionChanged?.invoke()
    }
    fun selectedText(): String = selection?.let { text.substring(it.first, it.last + 1) }.orEmpty()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val gap = dp(5).toFloat()
        val lineHeight = max(dp(42).toFloat(), paint.fontSpacing + dp(16))
        var x = gap; var y = gap
        tiles.clear()
        val iterator = BreakIterator.getCharacterInstance(Locale.CHINA)
        iterator.setText(text)
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val word = text.substring(start, end)
            if (word.isNotBlank()) {
                // Very long unbroken words fall back to character-sized tiles.
                val parts = if (paint.measureText(word) + dp(16) > width - 2 * gap) {
                    val chars = BreakIterator.getCharacterInstance(Locale.CHINA).apply { setText(word) }
                    val ranges = mutableListOf<Pair<Int, Int>>()
                    var a = chars.first(); var b = chars.next()
                    while (b != BreakIterator.DONE) { ranges.add((start + a) to (start + b)); a = b; b = chars.next() }
                    ranges
                } else listOf(start to end)
                for ((a, b) in parts) {
                    val w = (paint.measureText(text, a, b) + dp(16)).coerceAtMost(width - 2 * gap)
                    if (x + w > width - gap) { x = gap; y += lineHeight + gap }
                    tiles.add(Tile(a, b, RectF(x, y, x + w, y + lineHeight)))
                    x += w + gap
                }
            }
            if (word.contains('\n')) { x = gap; y += lineHeight + gap }
            start = end; end = iterator.next()
        }
        setMeasuredDimension(width, (y + lineHeight + gap).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        for (tile in tiles) {
            val selected = selection?.let { tile.start <= it.last && tile.end > it.first } == true
            paint.color = if (selected) VoiceStyle.accent else VoiceStyle.surface
            canvas.drawRoundRect(tile.box, dp(6).toFloat(), dp(6).toFloat(), paint)
            paint.color = VoiceStyle.text
            val baseline = tile.box.centerY() - (paint.ascent() + paint.descent()) / 2
            canvas.drawText(text, tile.start, tile.end, tile.box.left + dp(8), baseline, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        val hit = tiles.firstOrNull { it.box.contains(event.x, event.y) }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                anchor = hit ?: return false
                downX = event.x; dragging = false
                selection = hit.start until hit.end
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && kotlin.math.abs(event.x - downX) > dp(8)) {
                    dragging = true; parent.requestDisallowInterceptTouchEvent(true)
                }
                if (!dragging) return true
                val first = anchor ?: return false
                val last = hit ?: tiles.minByOrNull { kotlin.math.abs(it.box.centerY() - event.y) * 4 + kotlin.math.abs(it.box.centerX() - event.x) }
                if (last != null) selection = minOf(first.start, last.start) until maxOf(first.end, last.end)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                anchor = null; parent.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) { performClick(); onSelectionFinished?.invoke() }
            }
        }
        invalidate(); onSelectionChanged?.invoke()
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    private fun dp(n: Int) = VoiceStyle.dp(context, n)
}
