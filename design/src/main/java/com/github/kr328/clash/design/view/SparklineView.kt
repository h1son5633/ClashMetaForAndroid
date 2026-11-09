package com.github.kr328.clash.design.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import java.util.ArrayDeque
import android.view.View
import androidx.core.content.ContextCompat
import com.github.kr328.clash.design.R

/**
 * Small real-time traffic curve. Fed with genuine per-second samples
 * via [pushSample]; draws the download rate as a filled mint curve and
 * the upload rate as a thinner line. The traces are drawn as
 * smooth Catmull-Rom curves, not straight segments. Redraws only
 * when a sample
 * arrives, never on a timer of its own.
 */
class SparklineView @JvmOverloads constructor(
    context: Context,
    attributeSet: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attributeSet, defStyleAttr) {

    companion object {
        private const val MAX_SAMPLES = 40
    }

    private data class Sample(val upload: Long, val download: Long)

    private val samples = ArrayDeque<Sample>()

    private val downloadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.home_accent)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val uploadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.home_text_secondary)
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        alpha = 160
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val linePath = Path()
    private val fillPath = Path()

    /** Rates in bytes per second, straight from the core traffic query. */
    fun pushSample(uploadPerSec: Long, downloadPerSec: Long) {
        samples.addLast(Sample(uploadPerSec, downloadPerSec))
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
        invalidate()
    }

    fun clear() {
        samples.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (samples.size < 2 || width == 0 || height == 0) return

        val maxValue = samples.maxOf { maxOf(it.upload, it.download) }.coerceAtLeast(1L)
        val stepX = width.toFloat() / (MAX_SAMPLES - 1)
        val startX = width - stepX * (samples.size - 1)
        val usableHeight = height.toFloat() - 4f

        drawSeries(canvas, startX, stepX, usableHeight, maxValue, isDownload = true)
        drawSeries(canvas, startX, stepX, usableHeight, maxValue, isDownload = false)
    }

    private fun drawSeries(
        canvas: Canvas,
        startX: Float,
        stepX: Float,
        usableHeight: Float,
        maxValue: Long,
        isDownload: Boolean,
    ) {
        linePath.reset()
        val n = samples.size
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        samples.forEachIndexed { i, sample ->
            val v = if (isDownload) sample.download else sample.upload
            xs[i] = startX + stepX * i
            ys[i] = 2f + usableHeight * (1f - v.toFloat() / maxValue.toFloat())
        }
        // Catmull-Rom spline through the samples, emitted as cubic
        // Beziers, so the trace is a smooth curve instead of straight
        // segments. Control-point Y is clamped to the view bounds so
        // the spline cannot overshoot above/below the plot area.
        val maxY = 2f + usableHeight
        linePath.moveTo(xs[0], ys[0])
        for (i in 0 until n - 1) {
            val p0x = xs[maxOf(i - 1, 0)]; val p0y = ys[maxOf(i - 1, 0)]
            val p1x = xs[i]; val p1y = ys[i]
            val p2x = xs[i + 1]; val p2y = ys[i + 1]
            val p3x = xs[minOf(i + 2, n - 1)]; val p3y = ys[minOf(i + 2, n - 1)]
            val c1x = p1x + (p2x - p0x) / 6f
            val c1y = (p1y + (p2y - p0y) / 6f).coerceIn(2f, maxY)
            val c2x = p2x - (p3x - p1x) / 6f
            val c2y = (p2y - (p3y - p1y) / 6f).coerceIn(2f, maxY)
            linePath.cubicTo(c1x, c1y, c2x, c2y, p2x, p2y)
        }
        if (isDownload) {
            fillPath.set(linePath)
            fillPath.lineTo(startX + stepX * (samples.size - 1), height.toFloat())
            fillPath.lineTo(startX, height.toFloat())
            fillPath.close()
            fillPaint.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                ContextCompat.getColor(context, R.color.home_accent_glow),
                ContextCompat.getColor(context, R.color.home_background),
                Shader.TileMode.CLAMP,
            )
            canvas.drawPath(fillPath, fillPaint)
            canvas.drawPath(linePath, downloadPaint)
        } else {
            canvas.drawPath(linePath, uploadPaint)
        }
    }
}
