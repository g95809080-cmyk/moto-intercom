package com.kuma.motointercom

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

internal class VisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.motocom_accent_green_alt)
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val path = Path()
    private var connected = false
    private var amplitude = 0f
    private var phase = 0f
    private var animator: ValueAnimator? = null

    fun setConnected(value: Boolean) {
        connected = value
        if (value && animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = WAVE_ANIMATION_MS
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    phase += 0.32f
                    amplitude *= 0.92f
                    invalidate()
                }
                start()
            }
        } else if (!value) {
            stop()
        }
    }

    fun setAmplitude(level: Float) {
        if (!connected) return
        amplitude = max(amplitude, level.coerceIn(0f, 1f))
        invalidate()
    }

    fun stop() {
        animator?.cancel()
        animator = null
        connected = false
        amplitude = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rows = 4
        val spacing = height / (rows + 1f)
        val amp = if (connected) amplitude * height * 0.24f else height * 0.04f
        repeat(rows) { row ->
            val centerY = spacing * (row + 1)
            path.reset()
            path.moveTo(0f, centerY)
            var x = 0f
            while (x <= width) {
                val wave = sin((x / width.coerceAtLeast(1).toFloat() * PI * 4.0) + phase + row)
                path.lineTo(x, centerY + (wave * amp).toFloat())
                x += 18f
            }
            canvas.drawPath(path, paint)
        }
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val WAVE_ANIMATION_MS = 420L
    }
}
