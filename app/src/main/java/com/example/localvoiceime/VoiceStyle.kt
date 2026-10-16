package com.example.localvoiceime

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.Button

object VoiceStyle {
    val background = Color.rgb(17, 20, 26)
    val surface = Color.rgb(29, 34, 43)
    val text = Color.rgb(242, 244, 248)
    val muted = Color.rgb(163, 174, 192)
    val accent = Color.rgb(145, 225, 191)
    val danger = Color.rgb(255, 155, 159)
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun rounded(context: Context, color: Int, radius: Int = 18) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(context, radius).toFloat()
    }
    fun button(context: Context, label: String, primary: Boolean = false) = Button(context).apply {
        text = label; isAllCaps = false; textSize = 16f
        setTextColor(if (primary) VoiceStyle.background else VoiceStyle.text)
        background = rounded(context, if (primary) accent else surface)
        minHeight = dp(context, 52)
        setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10))
    }
}
