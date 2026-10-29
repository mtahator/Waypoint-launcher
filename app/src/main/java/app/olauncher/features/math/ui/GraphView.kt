package app.olauncher.features.math.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import app.olauncher.features.math.domain.MathProgram
import app.olauncher.features.math.domain.Plotter
import app.olauncher.features.quickanswers.domain.QuickAnswers
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * A Desmos-style plane: drag to pan, pinch to zoom, double-tap to zoom in, touch-and-hold to trace a
 * curve. Draws y = f(x), x = g(y), implicit equations F(x, y) = 0 and points from a [MathProgram].
 * Curves are re-sampled for the visible window every frame (cheap: compiled closures).
 */
class GraphView(context: Context, private val interactive: Boolean = true) : View(context) {

    var program: MathProgram? = null
        set(value) { field = value; invalidate() }

    /** Line indexes the user has hidden (tapping a row's colour dot). */
    var hidden: Set<Int> = emptySet()
        set(value) { field = value; invalidate() }

    var dark: Boolean = true
        set(value) { field = value; applyTheme(); invalidate() }

    /** Panel behind the plane (translucent on glass). */
    var panelColor: Int = Color.TRANSPARENT
        set(value) { field = value; invalidate() }

    // Viewport: world point at the view centre and pixels per unit
    private var cx = 0.0
    private var cy = 0.0
    private var scale = 0.0 // 0 = not laid out yet

    private val density = resources.displayMetrics.density
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.4f * density }
    private val majorPaint = Paint().apply { strokeWidth = 1f * density }
    private val minorPaint = Paint().apply { strokeWidth = 1f }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.5f * density; typeface = Typeface.MONOSPACE }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.6f * density; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12.5f * density; typeface = Typeface.MONOSPACE }
    private val traceBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    private var traceX = Float.NaN
    private var traceY = Float.NaN

    init {
        applyTheme()
        isClickable = interactive
    }

    private fun applyTheme() {
        val ink = if (dark) Color.WHITE else Color.BLACK
        axisPaint.color = withAlpha(ink, 0.85f)
        majorPaint.color = withAlpha(ink, 0.16f)
        minorPaint.color = withAlpha(ink, 0.07f)
        labelPaint.color = withAlpha(ink, 0.62f)
        tracePaint.color = ink
        traceBg.color = if (dark) 0xE6151515.toInt() else 0xF2FFFFFF.toInt()
    }

    fun resetView() {
        cx = 0.0; cy = 0.0
        scale = if (width > 0) width / 20.0 else 0.0 // x from −10 to 10
        invalidate()
    }

    fun zoom(factor: Float) {
        scale = (scale * factor).coerceIn(1e-6, 1e9)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (scale == 0.0 || oldw == 0) { scale = w / 20.0 } else scale *= w.toDouble() / oldw
    }

    // ---- coordinates
    private fun sx(x: Double) = (width / 2.0 + (x - cx) * scale).toFloat()
    private fun sy(y: Double) = (height / 2.0 - (y - cy) * scale).toFloat()
    private fun wx(px: Float) = cx + (px - width / 2.0) / scale
    private fun wy(py: Float) = cy - (py - height / 2.0) / scale

    override fun onDraw(canvas: Canvas) {
        if (scale == 0.0) return
        if (panelColor != Color.TRANSPARENT) {
            panelPaint.color = panelColor
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, 22 * density, 22 * density, panelPaint)
        }
        canvas.save()
        canvas.clipRect(0, 0, width, height)
        drawGrid(canvas)
        drawPlots(canvas)
        drawTrace(canvas)
        canvas.restore()
    }

    private fun drawGrid(canvas: Canvas) {
        val x0 = wx(0f); val x1 = wx(width.toFloat())
        val y0 = wy(height.toFloat()); val y1 = wy(0f)
        val step = Plotter.gridStep(x1 - x0, max(4, (width / (density * 70)).toInt()))
        val minor = step / 5
        fun lines(stepW: Double, paint: Paint) {
            var x = floor(x0 / stepW) * stepW
            while (x <= x1) { val p = sx(x); canvas.drawLine(p, 0f, p, height.toFloat(), paint); x += stepW }
            var y = floor(y0 / stepW) * stepW
            while (y <= y1) { val p = sy(y); canvas.drawLine(0f, p, width.toFloat(), p, paint); y += stepW }
        }
        if (minor * scale > 6 * density) lines(minor, minorPaint)
        lines(step, majorPaint)

        // axes (pinned to the edge when off-screen, so labels stay visible)
        val ax = sx(0.0).coerceIn(0f, width.toFloat())
        val ay = sy(0.0).coerceIn(0f, height.toFloat())
        canvas.drawLine(ax, 0f, ax, height.toFloat(), axisPaint)
        canvas.drawLine(0f, ay, width.toFloat(), ay, axisPaint)

        val pad = 4 * density
        val fm = labelPaint.fontMetrics
        var x = ceil(x0 / step) * step
        while (x <= x1) {
            if (abs(x) > step / 2) {
                val label = label(x)
                val w = labelPaint.measureText(label)
                val ty = (ay + pad - fm.ascent).coerceAtMost(height - pad)
                canvas.drawText(label, sx(x) - w / 2, ty, labelPaint)
            }
            x += step
        }
        var y = ceil(y0 / step) * step
        while (y <= y1) {
            if (abs(y) > step / 2) {
                val label = label(y)
                val w = labelPaint.measureText(label)
                val tx = if (ax - pad - w < pad) ax + pad else ax - pad - w
                canvas.drawText(label, tx, sy(y) - (fm.ascent + fm.descent) / 2, labelPaint)
            }
            y += step
        }
    }

    private fun label(v: Double): String = QuickAnswers.format(v, 6).replace(",", "")

    private fun drawPlots(canvas: Canvas) {
        val p = program ?: return
        val x0 = wx(0f); val x1 = wx(width.toFloat())
        val y0 = wy(height.toFloat()); val y1 = wy(0f)
        p.lines.forEachIndexed { i, line ->
            if (i in hidden || !line.plottable) return@forEachIndexed
            val color = colorFor(i)
            curvePaint.color = color
            when (line.kind) {
                MathProgram.Kind.GRAPH_Y -> {
                    val n = max(64, width / 2)
                    for (poly in Plotter.explicit(line.curve!!, x0, x1, n, y0, y1)) {
                        path.reset()
                        path.moveTo(sx(poly[0]), sy(poly[1]))
                        var k = 2
                        while (k < poly.size) { path.lineTo(sx(poly[k]), sy(poly[k + 1])); k += 2 }
                        canvas.drawPath(path, curvePaint)
                    }
                }
                MathProgram.Kind.GRAPH_X -> {
                    val n = max(64, height / 2)
                    for (poly in Plotter.explicit(line.curve!!, y0, y1, n, x0, x1)) {
                        path.reset()
                        path.moveTo(sx(poly[1]), sy(poly[0]))
                        var k = 2
                        while (k < poly.size) { path.lineTo(sx(poly[k + 1]), sy(poly[k])); k += 2 }
                        canvas.drawPath(path, curvePaint)
                    }
                }
                MathProgram.Kind.IMPLICIT -> {
                    val cell = 7 * density
                    val nx = (width / cell).toInt().coerceIn(24, 160)
                    val ny = (height / cell).toInt().coerceIn(24, 240)
                    val seg = Plotter.implicit(line.implicit!!, x0, x1, y0, y1, nx, ny)
                    var k = 0
                    while (k + 3 < seg.size) {
                        canvas.drawLine(sx(seg[k]), sy(seg[k + 1]), sx(seg[k + 2]), sy(seg[k + 3]), curvePaint)
                        k += 4
                    }
                }
                MathProgram.Kind.POINT -> {
                    val (px, py) = line.point!!
                    dotPaint.color = color
                    canvas.drawCircle(sx(px), sy(py), 5.5f * density, dotPaint)
                    if (interactive) {
                        val t = "(${label(px)}, ${label(py)})"
                        canvas.drawText(t, sx(px) + 8 * density, sy(py) - 8 * density, labelPaint)
                    }
                }
                else -> Unit
            }
        }
    }

    private fun drawTrace(canvas: Canvas) {
        if (traceX.isNaN()) return
        val p = program ?: return
        val x = wx(traceX)
        var best: Pair<Int, Double>? = null
        var bestDist = Float.MAX_VALUE
        p.lines.forEachIndexed { i, line ->
            if (i in hidden || line.kind != MathProgram.Kind.GRAPH_Y) return@forEachIndexed
            val y = Plotter.safe(line.curve!!, x)
            if (!y.isFinite()) return@forEachIndexed
            val d = abs(sy(y) - traceY)
            if (d < bestDist) { bestDist = d; best = i to y }
        }
        val (i, y) = best ?: return
        val px = sx(x); val py = sy(y)
        dotPaint.color = colorFor(i)
        canvas.drawCircle(px, py, 7 * density, dotPaint)
        dotPaint.color = traceBg.color
        canvas.drawCircle(px, py, 3 * density, dotPaint)
        val text = "(${label(x)}, ${label(y)})"
        val w = tracePaint.measureText(text)
        val pad = 8 * density
        var bx = px + 12 * density
        if (bx + w + pad * 2 > width) bx = px - 12 * density - w - pad * 2
        var by = py - 40 * density
        if (by < 0) by = py + 16 * density
        rect.set(bx, by, bx + w + pad * 2, by + 28 * density)
        canvas.drawRoundRect(rect, 14 * density, 14 * density, traceBg)
        canvas.drawText(text, bx + pad, by + 19 * density, tracePaint)
    }

    fun colorFor(index: Int): Int = PALETTE[index % PALETTE.size]

    // ---- gestures
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val fx = d.focusX; val fy = d.focusY
            val wxBefore = wx(fx); val wyBefore = wy(fy)
            scale = (scale * d.scaleFactor).coerceIn(1e-6, 1e9)
            cx += wxBefore - wx(fx); cy += wyBefore - wy(fy)
            invalidate()
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!traceX.isNaN()) return false
            cx += dx / scale; cy -= dy / scale
            invalidate()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val wxBefore = wx(e.x); val wyBefore = wy(e.y)
            scale *= 2
            cx += wxBefore - wx(e.x); cy += wyBefore - wy(e.y)
            invalidate()
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            traceX = e.x; traceY = e.y
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean { performClick(); return true }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        if (!traceX.isNaN() && event.actionMasked == MotionEvent.ACTION_MOVE) {
            traceX = event.x; traceY = event.y; invalidate()
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (!traceX.isNaN()) { traceX = Float.NaN; invalidate() }
        }
        if (!scaleDetector.isInProgress) gestures.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    companion object {
        /** Desmos-like palette, tuned to read on glass and on black. */
        val PALETTE = intArrayOf(
            0xFFE5483F.toInt(), 0xFF3D8BFF.toInt(), 0xFF34C759.toInt(), 0xFFB36BFF.toInt(),
            0xFFFF9F0A.toInt(), 0xFF00C7BE.toInt(), 0xFFFF5FA2.toInt(), 0xFFA2845E.toInt(),
        )

        private fun withAlpha(c: Int, a: Float) = (c and 0x00FFFFFF) or ((a * 255).toInt() shl 24)

    }
}
