package com.github.kr328.clash.design.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.github.kr328.clash.design.R

/**
 * Bar chart of the last seven days' total traffic, fed by
 * [setData] with real per-day byte counts from TrafficHistory.
 * Older days are drawn in a dim mint, today in the full accent;
 * days with no recorded traffic show only their weekday label.
 */
class DailyBarsView @JvmOverloads constructor(
    context: Context,
    attributeSet: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attributeSet, defStyleAttr) {

    private var totals: List<Long> = emptyList()
    private var labels: List<String> = emptyList()

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.home_map_dot)
        style = Paint.Style.FILL
    }
    private val todayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.home_accent)
        style = Paint.Style.FILL
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.home_text_secondary)
        textSize = 9.5f * scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.home_text_secondary)
        textSize = 10f * scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.home_surface_stroke)
        style = Paint.Style.FILL
    }

    fun setData(totals: List<Long>, labels: List<String>) {
        this.totals = totals
        this.labels = labels
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (totals.isEmpty() || width == 0 || height == 0) return

        val n = totals.size
        val labelBand = 18f * density
        val valueBand = 15f * scaledDensity
        val baseY = height - labelBand
        val topY = valueBand
        val maxValue = (totals.maxOrNull() ?: 0L).coerceAtLeast(1L)
        val slot = width.toFloat() / n
        val barWidth = minOf(slot * 0.52f, 30f * density)
        val radius = 4f * density
        val minBar = 2.5f * density
        val rect = RectF()

        canvas.drawRect(0f, baseY, width.toFloat(), baseY + 1f * density, basePaint)

        for (i in 0 until n) {
            val cx = slot * i + slot / 2f
            val v = totals[i]
            if (v > 0L) {
                val barH = maxOf((baseY - topY) * (v.toFloat() / maxValue.toFloat()), minBar)
                rect.set(cx - barWidth / 2f, baseY - barH, cx + barWidth / 2f, baseY)
                canvas.drawRoundRect(rect, radius, radius, if (i == n - 1) todayPaint else barPaint)
                canvas.drawText(compact(v), cx, baseY - barH - 4f * density, valuePaint)
            }
            val label = labels.getOrNull(i) ?: ""
            if (label.isNotEmpty()) {
                canvas.drawText(label, cx, height - 4f * density, labelPaint)
            }
        }
    }

    private fun compact(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> trim(bytes / 1024.0 / 1024.0 / 1024.0) + "G"
        bytes >= 1024L * 1024L -> trim(bytes / 1024.0 / 1024.0) + "M"
        bytes >= 1024L -> trim(bytes / 1024.0) + "K"
        else -> bytes.toString()
    }

    private fun trim(v: Double): String =
        if (v >= 100) v.toInt().toString()
        else String.format("%.1f", v).trimEnd('0').trimEnd('.')
}
