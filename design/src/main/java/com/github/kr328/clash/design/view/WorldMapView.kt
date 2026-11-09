package com.github.kr328.clash.design.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.github.kr328.clash.design.R

/**
 * Dotted world map with a single glowing location marker.
 *
 * The land dots come from res/raw/world_dots.txt: a 120x56 grid sampled
 * from public-domain world geography in equirectangular projection,
 * latitude range [+84, -56] (Antarctica omitted). The static dot layer
 * is rendered once per size into a cached bitmap; only the marker is
 * redrawn while it pulses, and the pulse stops when the view leaves
 * the window.
 *
 * There is deliberately no route arc: per the product decision the map
 * only marks the current node's capital location.
 */
class WorldMapView @JvmOverloads constructor(
    context: Context,
    attributeSet: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attributeSet, defStyleAttr) {

    companion object {
        private const val GRID_COLS = 120
        private const val GRID_ROWS = 56
        private const val LAT_TOP = 84.0
        private const val LAT_BOTTOM = -56.0
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.home_map_dot)
        style = Paint.Style.FILL
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.home_accent)
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.home_accent)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.home_text_primary)
        style = Paint.Style.FILL
    }

    private val landCells: List<Pair<Int, Int>> by lazy { loadLandCells() }

    private var dotsBitmap: Bitmap? = null
    private var gridLeft = 0f
    private var gridTop = 0f
    private var cellSize = 0f

    // Marker position as grid fractions (x: 0..1 over lon, y: 0..1 over lat range)
    private var markerX = -1f
    private var markerY = -1f
    private var markerAlpha = 0f
    private var pulsePhase = 0f

    private var moveAnimator: ValueAnimator? = null
    private var fadeAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null

    private fun loadLandCells(): List<Pair<Int, Int>> {
        val cells = ArrayList<Pair<Int, Int>>(2200)
        val lines = resources.openRawResource(R.raw.world_dots)
            .bufferedReader().readLines()
        for ((row, line) in lines.withIndex()) {
            if (row >= GRID_ROWS) break
            for ((col, ch) in line.withIndex()) {
                if (col >= GRID_COLS) break
                if (ch == '#') cells.add(col to row)
            }
        }
        return cells
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        cellSize = minOf(w.toFloat() / GRID_COLS, h.toFloat() / GRID_ROWS)
        gridLeft = (w - cellSize * GRID_COLS) / 2f
        gridTop = (h - cellSize * GRID_ROWS) / 2f
        renderDotsBitmap(w, h)
    }

    private fun renderDotsBitmap(w: Int, h: Int) {
        dotsBitmap?.recycle()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = cellSize * 0.30f
        for ((col, row) in landCells) {
            val cx = gridLeft + (col + 0.5f) * cellSize
            val cy = gridTop + (row + 0.5f) * cellSize
            canvas.drawCircle(cx, cy, radius, dotPaint)
        }
        dotsBitmap = bitmap
    }

    /** Show the marker at [latitude]/[longitude], animating from the old spot. */
    fun setMarker(latitude: Double, longitude: Double) {
        val targetX = ((longitude + 180.0) / 360.0).toFloat()
        val targetY = ((LAT_TOP - latitude) / (LAT_TOP - LAT_BOTTOM)).toFloat()
        if (markerX < 0f) {
            markerX = targetX
            markerY = targetY
        } else if (markerX != targetX || markerY != targetY) {
            val startX = markerX
            val startY = markerY
            moveAnimator?.cancel()
            moveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 600
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val t = it.animatedValue as Float
                    markerX = startX + (targetX - startX) * t
                    markerY = startY + (targetY - startY) * t
                    invalidate()
                }
                start()
            }
        }
        if (markerAlpha < 1f) {
            fadeAnimator?.cancel()
            fadeAnimator = ValueAnimator.ofFloat(markerAlpha, 1f).apply {
                duration = 400
                addUpdateListener {
                    markerAlpha = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
        startPulse()
    }

    /** Fade the marker out (disconnected or unknown location). */
    fun clearMarker() {
        if (markerAlpha <= 0f) return
        fadeAnimator?.cancel()
        fadeAnimator = ValueAnimator.ofFloat(markerAlpha, 0f).apply {
            duration = 400
            addUpdateListener {
                markerAlpha = it.animatedValue as Float
                if (markerAlpha <= 0f) stopPulse()
                invalidate()
            }
            start()
        }
    }

    private fun startPulse() {
        if (pulseAnimator?.isRunning == true) return
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                pulsePhase = it.animatedValue as Float
                if (isShown) invalidate()
            }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopPulse()
        moveAnimator?.cancel()
        fadeAnimator?.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        dotsBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        if (markerX < 0f || markerAlpha <= 0f || cellSize == 0f) return

        val cx = gridLeft + markerX * cellSize * GRID_COLS
        val cy = gridTop + markerY * cellSize * GRID_ROWS
        val baseRadius = cellSize * 1.1f
        val alpha = (markerAlpha * 255).toInt()

        // Expanding pulse ring
        val pulseRadius = baseRadius * (1.6f + 1.8f * pulsePhase)
        ringPaint.alpha = (alpha * (1f - pulsePhase) * 0.8f).toInt()
        ringPaint.strokeWidth = cellSize * 0.35f
        canvas.drawCircle(cx, cy, pulseRadius, ringPaint)

        // Soft glow
        glowPaint.alpha = (alpha * 0.25f).toInt()
        canvas.drawCircle(cx, cy, baseRadius * 1.9f, glowPaint)

        // Breathing ring + core
        val breathe = 1f + 0.12f * kotlin.math.sin(pulsePhase * 2 * Math.PI).toFloat()
        ringPaint.alpha = alpha
        ringPaint.strokeWidth = cellSize * 0.45f
        canvas.drawCircle(cx, cy, baseRadius * breathe, ringPaint)
        glowPaint.alpha = alpha
        canvas.drawCircle(cx, cy, baseRadius * 0.55f, glowPaint)
        corePaint.alpha = alpha
        canvas.drawCircle(cx, cy, baseRadius * 0.26f, corePaint)
    }
}
