package com.github.kr328.clash.design.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.github.kr328.clash.design.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dotted world map drawn as a globe (orthographic projection): the land
 * dots from res/raw/world_dots.txt (a 120x56 equirectangular grid,
 * latitude range [+84, -56], Antarctica omitted) are projected onto a
 * disc, so the visible hemisphere curves like a planet. Dots near the
 * limb fade out, which sells the spherical look.
 *
 * The view is centred on East Asia by default. [setMarker] rotates the
 * globe smoothly until the node's capital faces the viewer and shows
 * the breathing marker there. [clearMarker] fades the marker out.
 * There is deliberately no route arc: the map only marks the node.
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
        private const val DEFAULT_CENTER_LAT = 26.0
        private const val DEFAULT_CENTER_LON = 112.0
    }

    private val spherePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.home_surface)
        style = Paint.Style.FILL
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.home_surface_stroke)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
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

    // Land cells converted to (lat, lon) in degrees, computed once.
    private val landLatLon: List<Pair<Double, Double>> by lazy { loadLandLatLon() }

    // Globe centre currently shown (degrees).
    private var centerLat = DEFAULT_CENTER_LAT
    private var centerLon = DEFAULT_CENTER_LON

    // Projected dot cache for the current centre: x, y, shading per dot.
    private var projX = FloatArray(0)
    private var projY = FloatArray(0)
    private var projShade = FloatArray(0)
    private var projCount = 0
    private var projKeyLat = Double.NaN
    private var projKeyLon = Double.NaN
    private var projKeySize = -1

    // Marker target (degrees); invalid when latitude is NaN.
    private var markerLat = Double.NaN
    private var markerLon = Double.NaN
    private var markerAlpha = 0f
    private var pulsePhase = 0f

    private var rotateAnimator: ValueAnimator? = null
    private var fadeAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null

    private fun loadLandLatLon(): List<Pair<Double, Double>> {
        val cells = ArrayList<Pair<Double, Double>>(2200)
        val lines = resources.openRawResource(R.raw.world_dots)
            .bufferedReader().readLines()
        for ((row, line) in lines.withIndex()) {
            if (row >= GRID_ROWS) break
            val lat = LAT_TOP - (row + 0.5) * (LAT_TOP - LAT_BOTTOM) / GRID_ROWS
            for ((col, ch) in line.withIndex()) {
                if (col >= GRID_COLS) break
                if (ch == '#') {
                    val lon = (col + 0.5) * 360.0 / GRID_COLS - 180.0
                    cells.add(lat to lon)
                }
            }
        }
        return cells
    }

    private fun project(lat: Double, lon: Double, cx: Float, cy: Float, r: Float, out: FloatArray): Boolean {
        val phi = Math.toRadians(lat)
        val phi0 = Math.toRadians(centerLat)
        val dLon = Math.toRadians(lon - centerLon)
        val cosC = sin(phi0) * sin(phi) + cos(phi0) * cos(phi) * cos(dLon)
        if (cosC <= 0.0) return false
        val x = cos(phi) * sin(dLon)
        val y = cos(phi0) * sin(phi) - sin(phi0) * cos(phi) * cos(dLon)
        out[0] = cx + (x * r).toFloat()
        out[1] = cy - (y * r).toFloat()
        out[2] = cosC.toFloat()
        return true
    }

    private fun ensureProjection(cx: Float, cy: Float, r: Float) {
        val sizeKey = (r * 2).toInt()
        if (projKeyLat == centerLat && projKeyLon == centerLon && projKeySize == sizeKey) return
        if (projX.size != landLatLon.size) {
            projX = FloatArray(landLatLon.size)
            projY = FloatArray(landLatLon.size)
            projShade = FloatArray(landLatLon.size)
        }
        val tmp = FloatArray(3)
        var n = 0
        for ((lat, lon) in landLatLon) {
            if (project(lat, lon, cx, cy, r, tmp)) {
                projX[n] = tmp[0]
                projY[n] = tmp[1]
                projShade[n] = tmp[2]
                n++
            }
        }
        projCount = n
        projKeyLat = centerLat
        projKeyLon = centerLon
        projKeySize = sizeKey
    }

    /** Rotate the globe to face [latitude]/[longitude] and show the marker there. */
    fun setMarker(latitude: Double, longitude: Double) {
        markerLat = latitude
        markerLon = longitude
        val startLat = centerLat
        val startLon = centerLon
        var dLon = (longitude - startLon) % 360.0
        if (dLon > 180.0) dLon -= 360.0
        if (dLon < -180.0) dLon += 360.0
        val dLat = latitude - startLat
        if (dLat != 0.0 || dLon != 0.0) {
            rotateAnimator?.cancel()
            rotateAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 900
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val t = it.animatedValue as Float
                    centerLat = startLat + dLat * t
                    centerLon = startLon + dLon * t
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
        markerLat = Double.NaN
        markerLon = Double.NaN
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
        rotateAnimator?.cancel()
        fadeAnimator?.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f * 0.97f

        // Sphere body + rim
        canvas.drawCircle(cx, cy, r, spherePaint)
        canvas.drawCircle(cx, cy, r, rimPaint)

        // Land dots on the facing hemisphere, shaded toward the limb
        ensureProjection(cx, cy, r)
        val dotRadius = (r * 0.017f).coerceAtLeast(1.1f)
        for (i in 0 until projCount) {
            dotPaint.alpha = (40 + 215 * projShade[i]).toInt().coerceIn(0, 255)
            canvas.drawCircle(projX[i], projY[i], dotRadius, dotPaint)
        }
        dotPaint.alpha = 255

        // Marker
        if (markerLat.isNaN() || markerAlpha <= 0f) return
        val tmp = FloatArray(3)
        if (!project(markerLat, markerLon, cx, cy, r, tmp)) return
        val mx = tmp[0]
        val my = tmp[1]
        val baseRadius = r * 0.045f
        val alpha = (markerAlpha * 255).toInt()

        val pulseRadius = baseRadius * (1.6f + 1.8f * pulsePhase)
        ringPaint.alpha = (alpha * (1f - pulsePhase) * 0.8f).toInt()
        ringPaint.strokeWidth = baseRadius * 0.32f
        canvas.drawCircle(mx, my, pulseRadius, ringPaint)

        glowPaint.alpha = (alpha * 0.25f).toInt()
        canvas.drawCircle(mx, my, baseRadius * 1.9f, glowPaint)

        val breathe = 1f + 0.12f * sin(pulsePhase * 2 * Math.PI).toFloat()
        ringPaint.alpha = alpha
        ringPaint.strokeWidth = baseRadius * 0.4f
        canvas.drawCircle(mx, my, baseRadius * breathe, ringPaint)
        glowPaint.alpha = alpha
        canvas.drawCircle(mx, my, baseRadius * 0.55f, glowPaint)
        corePaint.alpha = alpha
        canvas.drawCircle(mx, my, baseRadius * 0.26f, corePaint)
    }
}
