package com.example.localvoiceime

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot

internal class HidMousePanel(
    context: Context,
    private val send: (buttons: Int, dx: Int, dy: Int, wheel: Int, click: Int) -> Unit,
    private val release: () -> Unit,
    openVoice: () -> Unit,
    openSettings: () -> Unit,
) : LinearLayout(context) {
    private val connection = label("未连接")
    private val pad = label("滑动移动指针 · 轻点左键")
    private val left = VoiceStyle.button(context, "左键")
    private val right = VoiceStyle.button(context, "右键")
    private val wheel = VoiceStyle.button(context, "滚轮 ↕")
    private var available = false
    private var buttons = 0
    private var dx = 0f
    private var dy = 0f
    private var wheelSteps = 0f
    private val density = resources.displayMetrics.density
    private var motionPending = false
    private val motion = Runnable { flushMotion() }

    init {
        orientation = VERTICAL
        isMotionEventSplittingEnabled = true
        setBackgroundColor(VoiceStyle.background)
        setPadding(0, dp(8), 0, 0)
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            connection.maxLines = 1
            connection.ellipsize = android.text.TextUtils.TruncateAt.END
            connection.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
            addView(connection, LayoutParams(0, dp(48), 1f))
            addView(icon(R.drawable.ic_settings, "设置", openSettings), LayoutParams(dp(48), dp(48)))
        })
        pad.apply {
            gravity = Gravity.CENTER
            setBackground(VoiceStyle.rounded(context, VoiceStyle.surface))
            setOnClickListener { click(1) }
        }
        addView(pad, LayoutParams(-1, 0, 1f).apply { setMargins(dp(16), dp(8), dp(16), dp(8)) })
        bindPad()
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            isMotionEventSplittingEnabled = true
            setPadding(dp(8), 0, dp(8), 0)
            addView(icon(R.drawable.ic_hid_voice, "切换到语音输入", openVoice), LayoutParams(dp(48), dp(48)))
            for (button in listOf(left, wheel, right)) {
                button.textSize = 14f
                button.minWidth = 0
                button.setPadding(dp(4), 0, dp(4), 0)
                addView(button, LayoutParams(0, dp(52), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
            }
        }, LayoutParams(-1, dp(64)))
        bindButton(left, 1)
        bindButton(right, 2)
        bindWheel()
        setConnection("未连接", false)
    }

    fun setConnection(message: String, ready: Boolean) {
        if (available && !ready) reset()
        available = ready
        connection.text = message
        pad.text = if (ready) "滑动移动指针 · 轻点左键" else "连接 Mac 后即可使用触控板"
        for (view in listOf(pad, left, right, wheel)) view.isEnabled = ready
    }

    fun reset() {
        removeCallbacks(motion)
        motionPending = false
        buttons = 0; dx = 0f; dy = 0f; wheelSteps = 0f
        left.isPressed = false; right.isPressed = false; wheel.isPressed = false
        release()
    }

    private fun bindPad() {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var pointer = -1
        var startX = 0f
        var startY = 0f
        var x = 0f
        var y = 0f
        var moved = false
        pad.setOnTouchListener { view, event ->
            if (!available) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pointer = event.getPointerId(0)
                    startX = event.x; startY = event.y
                    x = startX; y = startY; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val index = event.findPointerIndex(pointer)
                    if (index >= 0) {
                        val nextX = event.getX(index)
                        val nextY = event.getY(index)
                        if (!moved) moved = hypot(nextX - startX, nextY - startY) > slop
                        if (moved) {
                            dx += (nextX - x) * 1.4f / density
                            dy += (nextY - y) * 1.4f / density
                            scheduleMotion()
                        }
                        x = nextX; y = nextY
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN -> moved = true
                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.getPointerId(event.actionIndex) == pointer) {
                        val index = if (event.actionIndex == 0) 1 else 0
                        pointer = event.getPointerId(index)
                        x = event.getX(index); y = event.getY(index)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && event.eventTime - event.downTime < 280) view.performClick()
                    pointer = -1
                }
                MotionEvent.ACTION_CANCEL -> pointer = -1
            }
            true
        }
    }

    private fun bindButton(button: Button, bit: Int) {
        button.setOnClickListener { click(bit) }
        button.setOnTouchListener { view, event ->
            if (!available) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { setButton(bit, true); view.isPressed = true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { setButton(bit, false); view.isPressed = false }
            }
            true
        }
    }

    private fun bindWheel() {
        var startY = 0f
        var y = 0f
        var moved = false
        wheel.contentDescription = "滚轮，上下滑动滚动"
        wheel.setOnClickListener { scrollByStep(-1) }
        wheel.setOnTouchListener { view, event ->
            if (!available) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = event.y; y = startY; moved = false; view.isPressed = true }
                MotionEvent.ACTION_MOVE -> {
                    moved = moved || abs(event.y - startY) > dp(5)
                    if (moved) {
                        wheelSteps += (y - event.y) / dp(12)
                        scheduleMotion()
                    }
                    y = event.y
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.isPressed = false
            }
            true
        }
    }

    private fun setButton(bit: Int, down: Boolean) {
        flushMotion()
        val next = if (down) buttons or bit else buttons and bit.inv()
        if (next == buttons) return
        buttons = next
        send(buttons, 0, 0, 0, 0)
    }

    private fun click(bit: Int) {
        if (!available || buttons and bit != 0) return
        flushMotion()
        send(buttons, 0, 0, 0, bit)
    }

    private fun scrollByStep(step: Int) {
        if (!available) return
        wheelSteps += step
        scheduleMotion()
    }

    private fun scheduleMotion() {
        if (motionPending) return
        motionPending = true
        postDelayed(motion, 8L)
    }

    private fun flushMotion() {
        removeCallbacks(motion)
        motionPending = false
        if (!available) return
        val x = dx.toInt().coerceIn(-127, 127)
        val y = dy.toInt().coerceIn(-127, 127)
        val w = wheelSteps.toInt().coerceIn(-127, 127)
        if (x == 0 && y == 0 && w == 0) return
        dx -= x; dy -= y; wheelSteps -= w
        send(buttons, x, y, w, 0)
        if (abs(dx) >= 1f || abs(dy) >= 1f || abs(wheelSteps) >= 1f) scheduleMotion()
    }

    private fun dp(value: Int) = VoiceStyle.dp(context, value)
    private fun label(value: String) = TextView(context).apply {
        text = value; textSize = 14f; setTextColor(VoiceStyle.muted)
        gravity = Gravity.CENTER_VERTICAL
    }
    private fun icon(resource: Int, description: String, click: () -> Unit) = ImageButton(context).apply {
        setImageResource(resource)
        imageTintList = android.content.res.ColorStateList.valueOf(VoiceStyle.muted)
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        contentDescription = description
        setOnClickListener { click() }
    }
}
