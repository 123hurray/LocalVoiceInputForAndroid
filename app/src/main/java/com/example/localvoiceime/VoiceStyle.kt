package com.example.localvoiceime

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.Button

object VoiceStyle {
    val background = Color.rgb(44, 44, 44)
    val surface = Color.rgb(72, 72, 72)
    val text = Color.rgb(245, 245, 245)
    val muted = Color.rgb(170, 170, 170)
    val accent = Color.rgb(96, 153, 244)
    val danger = Color.rgb(255, 155, 159)
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun rounded(context: Context, color: Int, radius: Int = 18) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(context, radius).toFloat()
    }
    fun button(context: Context, label: String, primary: Boolean = false) = Button(context).apply {
        text = label; isAllCaps = false; textSize = 16f
        setTextColor(VoiceStyle.text)
        background = rounded(context, if (primary) accent else surface)
        minHeight = dp(context, 52)
        setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10))
    }
}
