package com.example.localvoiceime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

internal class HidPointerControls(
    context: Context,
    private val send: (buttons: Int, dx: Int, dy: Int, wheel: Int, click: Int) -> Unit,
    private val release: () -> Unit,
    private val switchApp: (previous: Boolean) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val gesture = TrackpadGesture(
        ViewConfiguration.get(context).scaledTouchSlop.toFloat(), context.resources.displayMetrics.density,
        ViewConfiguration.get(context).scaledDoubleTapSlop.toFloat(),
    )
    private val density = context.resources.displayMetrics.density
    private var buttons = 0
    private var available = false
    private var dx = 0f
    private var dy = 0f
    private var wheelSteps = 0f
    private var motionPending = false
    private val motion = Runnable { flushMotion() }

    fun setAvailable(ready: Boolean) {
        if (available && !ready) {
            discardPointerMotion()
            release()
        }
        available = ready
    }

    fun discardPointerMotion() {
        main.removeCallbacks(motion)
        motionPending = false
        dx = 0f; dy = 0f; wheelSteps = 0f
        gesture.reset()
        if (buttons != 0) { buttons = 0; release() }
    }

    fun bindPad(pad: View, canMove: () -> Boolean) {
        pad.contentDescription = "单指移动，单指轻点左键，轻点后再次按住滑动可拖拽，双指轻点右键，双指滑动滚动，右侧上滑或下滑切换应用"
        pad.setOnClickListener {
            if (available && canMove()) send(0, 0, 0, 0, 1)
        }
        val edge = EdgeSwitchGesture(24f * density, 40f * density)
        var edgeCaptured = false
        pad.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                edgeCaptured = available && canMove() && edge.begin(event.x, event.y, view.width.toFloat())
                if (edgeCaptured) {
                    discardPointerMotion()
                    view.parent.requestDisallowInterceptTouchEvent(true)
                }
            }
            if (edgeCaptured) {
                if (!available || !canMove()) edge.cancel()
                when (event.actionMasked) {
                    MotionEvent.ACTION_POINTER_DOWN -> edge.cancel()
                    MotionEvent.ACTION_MOVE -> edge.move(event.x, event.y, event.pointerCount)?.let { switchApp(it) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        edgeCaptured = false
                        edge.cancel()
                        view.parent.requestDisallowInterceptTouchEvent(false)
                    }
                }
                return@setOnTouchListener true
            }
            if (!available || !canMove()) {
                discardPointerMotion()
                return@setOnTouchListener false
            }
            fun fingers(skip: Int = -1) = (0 until event.pointerCount).filter { it != skip }.map {
                TrackpadGesture.Finger(event.getPointerId(it), event.getX(it), event.getY(it))
            }
            val effect = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gesture.start(fingers(), event.eventTime)
                    view.parent.requestDisallowInterceptTouchEvent(true)
                    TrackpadGesture.Effect()
                }
                MotionEvent.ACTION_POINTER_DOWN -> gesture.add(fingers())
                MotionEvent.ACTION_MOVE -> gesture.move(fingers())
                MotionEvent.ACTION_POINTER_UP -> gesture.lift(fingers(event.actionIndex), event.eventTime)
                MotionEvent.ACTION_UP -> {
                    view.parent.requestDisallowInterceptTouchEvent(false)
                    gesture.lift(emptyList(), event.eventTime)
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.parent.requestDisallowInterceptTouchEvent(false)
                    discardPointerMotion()
                    TrackpadGesture.Effect()
                }
                else -> TrackpadGesture.Effect()
            }
            effect.heldButtons?.let { mask ->
                flushMotion()
                buttons = mask
                send(buttons, 0, 0, 0, 0)
            }
            dx += effect.dx; dy += effect.dy; wheelSteps += effect.wheel
            if (effect.click != 0) {
                flushMotion()
                if (effect.click == 1) view.performClick() else send(0, 0, 0, 0, effect.click)
            } else if (effect.dx != 0f || effect.dy != 0f || effect.wheel != 0f) scheduleMotion()
            true
        }
    }

    private fun scheduleMotion() {
        if (motionPending) return
        motionPending = true
        main.postDelayed(motion, 8L)
    }

    private fun flushMotion() {
        main.removeCallbacks(motion)
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
}
