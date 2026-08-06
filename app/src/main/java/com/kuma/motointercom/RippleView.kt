package com.kuma.motointercom

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

internal class RippleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = context.getColor(R.color.motocom_accent_green_alt)
    }
    private var progress = 0f
    private var animator: ValueAnimator? = null

    init {
        visibility = INVISIBLE
    }

    fun setRunning(running: Boolean) {
        if (running && animator == null) {
            visibility = VISIBLE
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = RIPPLE_ANIMATION_MS
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else if (!running) {
            stop()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
        visibility = INVISIBLE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (animator == null) return

        val cx = width / 2f
        val cy = height / 2f
        val base = minOf(width, height) * 0.32f
        val spread = minOf(width, height) * 0.18f
        repeat(3) { index ->
            val phase = (progress + index / 3f) % 1f
            paint.alpha = ((1f - phase) * 80).toInt().coerceIn(0, 80)
            canvas.drawCircle(cx, cy, base + spread * phase, paint)
        }
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val RIPPLE_ANIMATION_MS = 1_800L
    }
}
