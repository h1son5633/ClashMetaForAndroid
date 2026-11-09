package com.github.kr328.clash.design.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.github.kr328.clash.design.R
import kotlin.math.acos
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
 * While the marker is shown, a glowing great-circle arc runs from a
 * nominal local anchor (Beijing — the app never reads the device's
 * real location) to the marker, with light dots flowing along it.
 *
 * The view doubles as the home screen's full-screen background layer:
 * while disconnected the globe sits small in the map slot at the top;
 * [setConnected] smoothly zooms it up (centre sinks, radius grows)
 * until it fills the screen and reaches behind the bottom navigation
 * bar, and shrinks it back on disconnect.
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

        // Nominal local end of the route arc (Beijing). Not a measured
        // position: the app has no location access, this is just the
        // anchor the arc starts from, mirroring the capital pinning
        // used for the node end.
        private const val LOCAL_ANCHOR_LAT = 39.9
        private const val LOCAL_ANCHOR_LON = 116.4
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
    private val arcGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.home_accent)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.home_accent)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val flowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.home_accent)
        style = Paint.Style.FILL
    }
    private val localRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.home_text_secondary)
        style = Paint.Style.STROKE
    }
    private val arcPath = Path()

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
    private var projKeyCy = -1

    // Background zoom state: 0 = small in the top map slot,
    // 1 = enlarged full-screen backdrop (connected).
    private var zoomFraction = 0f
    private var zoomConnected = false
    private var zoomAnimator: ValueAnimator? = null

    /** Status-bar inset in px, fed in by the hosting design so the
     * small globe sits exactly in the (now empty) map slot. */
    var topInsetPx: Int = 0
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

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
        val cyKey = cy.toInt()
        if (projKeyLat == centerLat && projKeyLon == centerLon && projKeySize == sizeKey && projKeyCy == cyKey) return
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
        projKeyCy = cyKey
    }

    // Unit vector of a lat/lon point in view space (x right, y up, z toward viewer).
    private fun viewVec(lat: Double, lon: Double, out: FloatArray) {
        val phi = Math.toRadians(lat)
        val phi0 = Math.toRadians(centerLat)
        val dLon = Math.toRadians(lon - centerLon)
        out[0] = (cos(phi) * sin(dLon)).toFloat()
        out[1] = (cos(phi0) * sin(phi) - sin(phi0) * cos(phi) * cos(dLon)).toFloat()
        out[2] = (sin(phi0) * sin(phi) + cos(phi0) * cos(phi) * cos(dLon)).toFloat()
    }

    /**
     * Glowing great-circle arc from the nominal local anchor to the node
     * marker, lifted above the surface, plus light dots flowing along it.
     * Only the front-facing part is drawn; alpha follows the marker fade,
     * so the arc disappears together with the marker on disconnect.
     */
    private fun drawArc(canvas: Canvas, cx: Float, cy: Float, r: Float, baseRadius: Float) {
        val a = FloatArray(3)
        val b = FloatArray(3)
        viewVec(LOCAL_ANCHOR_LAT, LOCAL_ANCHOR_LON, a)
        viewVec(markerLat, markerLon, b)
        val dot = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).toDouble().coerceIn(-1.0, 1.0)
        val omega = acos(dot)
        if (omega < 0.03) return
        val sinOmega = sin(omega)
        if (sinOmega < 1e-4) return
        val maxLift = 0.30 * sin(omega / 2.0)
        val alpha = (markerAlpha * 255).toInt()

        fun slerp(t: Double, out: FloatArray) {
            val s0 = sin((1.0 - t) * omega) / sinOmega
            val s1 = sin(t * omega) / sinOmega
            val lift = 1.0 + maxLift * sin(Math.PI * t)
            out[0] = ((s0 * a[0] + s1 * b[0]) * lift).toFloat()
            out[1] = ((s0 * a[1] + s1 * b[1]) * lift).toFloat()
            out[2] = (s0 * a[2] + s1 * b[2]).toFloat()
        }

        // Local anchor dot (the arc's nominal origin)
        val tmp = FloatArray(3)
        if (project(LOCAL_ANCHOR_LAT, LOCAL_ANCHOR_LON, cx, cy, r, tmp)) {
            localRingPaint.alpha = (alpha * 0.9f).toInt()
            localRingPaint.strokeWidth = baseRadius * 0.22f
            canvas.drawCircle(tmp[0], tmp[1], baseRadius * 0.5f, localRingPaint)
            corePaint.alpha = (alpha * 0.9f).toInt()
            canvas.drawCircle(tmp[0], tmp[1], baseRadius * 0.22f, corePaint)
        }

        // Arc body: consecutive front-facing samples only
        val v = FloatArray(3)
        arcPath.reset()
        var penDown = false
        val steps = 48
        for (i in 0..steps) {
            val t = i.toDouble() / steps
            slerp(t, v)
            if (v[2] < 0f) {
                penDown = false
                continue
            }
            val px = cx + v[0] * r
            val py = cy - v[1] * r
            if (penDown) {
                arcPath.lineTo(px, py)
            } else {
                arcPath.moveTo(px, py)
                penDown = true
            }
        }
        arcGlowPaint.alpha = (alpha * 0.20f).toInt()
        arcGlowPaint.strokeWidth = (r * 0.030f).coerceAtLeast(3f)
        canvas.drawPath(arcPath, arcGlowPaint)
        arcPaint.alpha = (alpha * 0.85f).toInt()
        arcPaint.strokeWidth = (r * 0.011f).coerceAtLeast(1.5f)
        canvas.drawPath(arcPath, arcPaint)

        // Flowing light dots riding the arc
        for (k in 0 until 3) {
            val t = (pulsePhase.toDouble() + k / 3.0) % 1.0
            slerp(t, v)
            if (v[2] < 0f) continue
            val px = cx + v[0] * r
            val py = cy - v[1] * r
            flowPaint.alpha = (alpha * 0.30f).toInt()
            canvas.drawCircle(px, py, baseRadius * 0.85f, flowPaint)
            flowPaint.alpha = alpha
            canvas.drawCircle(px, py, baseRadius * 0.42f, flowPaint)
            corePaint.alpha = alpha
            canvas.drawCircle(px, py, baseRadius * 0.18f, corePaint)
        }
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

    /**
     * Zoom the globe up (connected) or back down into the map slot
     * (disconnected) over ~750 ms. Only the disc's centre and radius
     * interpolate; the layout around it never moves.
     */
    fun setConnected(value: Boolean) {
        if (value == zoomConnected) return
        zoomConnected = value
        val from = zoomFraction
        val to = if (value) 1f else 0f
        zoomAnimator?.cancel()
        zoomAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = 750
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                zoomFraction = it.animatedValue as Float
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
        zoomAnimator?.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        // Disc geometry: small and centred in the top map slot while
        // disconnected; enlarged into a full-screen backdrop while
        // connected (centre sinks, radius grows until the rim reaches
        // behind the bottom navigation bar). zoomFraction interpolates.
        val density = resources.displayMetrics.density
        val smallR = 104f * density
        val smallCy = topInsetPx + 162f * density
        val bigR = height * 0.40f
        val bigCy = height * 0.45f
        val cx = width / 2f
        val cy = smallCy + (bigCy - smallCy) * zoomFraction
        val r = smallR + (bigR - smallR) * zoomFraction

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

        // Route arc + flowing dots, tied to the marker's visibility
        if (!markerLat.isNaN() && markerAlpha > 0f) {
            drawArc(canvas, cx, cy, r, r * 0.045f)
        }

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
