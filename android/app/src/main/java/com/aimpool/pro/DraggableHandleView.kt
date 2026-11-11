package com.aimpool.pro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

/** A small, movable overlay window. Only its own bounds receive touches. */
@SuppressLint("ViewConstructor")
class DraggableHandleView(
    context: Context,
    private val windowManager: WindowManager,
    private val params: WindowManager.LayoutParams,
    private val label: String,
    color: Int,
    private val onMoved: () -> Unit,
    private val onTapped: () -> Unit = {}
) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.argb(210, 14, 26, 39) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 12f * resources.displayMetrics.scaledDensity
        isFakeBoldText = true
    }
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false

    val centerX: Float get() = params.x + params.width / 2f
    val centerY: Float get() = params.y + params.height / 2f

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) * 0.43f
        canvas.drawCircle(cx, cy, radius, fill)
        canvas.drawCircle(cx, cy, radius, ring)
        canvas.drawText(label, cx, cy - (text.ascent() + text.descent()) / 2f, text)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                startX = params.x
                startY = params.y
                moved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (abs(dx) + abs(dy) > 8f * resources.displayMetrics.density) moved = true
                if (moved) {
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
                    windowManager.updateViewLayout(this, params)
                    onMoved()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) onTapped()
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
