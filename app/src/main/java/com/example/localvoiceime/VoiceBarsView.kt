package com.example.localvoiceime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.sin

class VoiceBarsView(context: Context) : View(context) {
    var level = 0f
    var recording = false
    var cancelling = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = if (cancelling) VoiceStyle.danger else if (recording) VoiceStyle.accent else VoiceStyle.muted
        val gap = VoiceStyle.dp(context, 11).toFloat()
        val thickness = VoiceStyle.dp(context, 4).toFloat()
        val phase = android.os.SystemClock.uptimeMillis() / 140.0
        for (i in 0..16) {
            val envelope = if (recording) (0.25 + 0.75 * kotlin.math.abs(sin(phase + i * 0.6))).toFloat() else 0.08f
            val bar = VoiceStyle.dp(context, 6) + height * 0.72f * level * envelope
            val x = width / 2f + (i - 8) * gap
            canvas.drawRoundRect(x - thickness / 2, (height - bar) / 2, x + thickness / 2, (height + bar) / 2, thickness, thickness, paint)
        }
    }
}
