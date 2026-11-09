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
 * the upload rate as a thinner line. Redraws only when a sample
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
        samples.forEachIndexed { i, sample ->
            val v = if (isDownload) sample.download else sample.upload
            val x = startX + stepX * i
            val y = 2f + usableHeight * (1f - v.toFloat() / maxValue.toFloat())
            if (i == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
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
