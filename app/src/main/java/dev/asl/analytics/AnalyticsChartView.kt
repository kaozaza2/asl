package dev.asl.analytics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class AnalyticsChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var samples: List<ResourceSample> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val cpuPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4CAF50"); strokeWidth = 4f; style = Paint.Style.STROKE }
    private val ramPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2196F3"); strokeWidth = 4f; style = Paint.Style.STROKE }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; strokeWidth = 2f }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f || samples.size < 2) return
        canvas.drawLine(0f, h, w, h, axisPaint)
        canvas.drawLine(0f, 0f, 0f, h, axisPaint)

        val maxRam = samples.maxOf { it.ramMb }.coerceAtLeast(1f)
        val maxCpu = samples.maxOf { it.cpuPercent }.coerceAtLeast(1f)

        fun x(i: Int) = i * w / (samples.size - 1)
        fun yCpu(v: Float) = h - (v / maxCpu) * h
        fun yRam(v: Float) = h - (v / maxRam) * h

        for (i in 1 until samples.size) {
            canvas.drawLine(x(i - 1), yCpu(samples[i - 1].cpuPercent), x(i), yCpu(samples[i].cpuPercent), cpuPaint)
            canvas.drawLine(x(i - 1), yRam(samples[i - 1].ramMb), x(i), yRam(samples[i].ramMb), ramPaint)
        }
    }
}