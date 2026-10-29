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
        set(value) {
            field = value
            if (selected >= (value?.lines?.size ?: 0)) selected = -1
            pinned = null
            invalidate()
        }

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

    /** Curve the user tapped: drawn bolder, with its roots, extrema and y-intercept. */
    private var selected = -1
    /** Point the user tapped, shown with its coordinates until the next tap. */
    private var pinned: Plotter.Poi? = null
    /** POIs drawn in the last frame, for hit-testing taps. */
    private val drawnPois = ArrayList<Plotter.Poi>()

    private val regionPaint = Paint().apply { style = Paint.Style.FILL }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.4f * resources.displayMetrics.density
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f * resources.displayMetrics.density, 7f * resources.displayMetrics.density), 0f)
    }
    private val poiPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val poiRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * resources.displayMetrics.density }

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
        if (interactive) drawPois(canvas)
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
                val lx = sx(x) - w / 2
                if (lx >= pad && lx + w <= width - pad) canvas.drawText(label, lx, ty, labelPaint)
            }
            x += step
        }
        var y = ceil(y0 / step) * step
        while (y <= y1) {
            if (abs(y) > step / 2) {
                val label = label(y)
                val w = labelPaint.measureText(label)
                val tx = if (ax - pad - w < pad) ax + pad else ax - pad - w
                val ly = sy(y) - (fm.ascent + fm.descent) / 2
                if (ly + fm.ascent >= pad && ly + fm.descent <= height - pad) canvas.drawText(label, tx, ly, labelPaint)
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
            curvePaint.strokeWidth = (if (i == selected) 3.6f else 2.6f) * density
            dashPaint.color = color
            val stroke = if (line.strict) dashPaint else curvePaint
            when (line.kind) {
                MathProgram.Kind.GRAPH_Y, MathProgram.Kind.REGION_Y -> {
                    val polys = Plotter.explicit(line.curve!!, x0, x1, max(64, width / 2), y0, y1)
                    if (line.kind == MathProgram.Kind.REGION_Y) {
                        regionPaint.color = withAlpha(color, 0.22f)
                        val edge = if (line.side > 0) 0f else height.toFloat()
                        for (poly in polys) {
                            path.reset()
                            path.moveTo(sx(poly[0]), edge)
                            var k = 0
                            while (k < poly.size) { path.lineTo(sx(poly[k]), sy(poly[k + 1])); k += 2 }
                            path.lineTo(sx(poly[poly.size - 2]), edge)
                            path.close()
                            canvas.drawPath(path, regionPaint)
                        }
                    }
                    for (poly in polys) canvas.drawPath(polyline(poly, swap = false), if (line.kind == MathProgram.Kind.REGION_Y) stroke else curvePaint)
                }
                MathProgram.Kind.GRAPH_X, MathProgram.Kind.REGION_X -> {
                    val polys = Plotter.explicit(line.curve!!, y0, y1, max(64, height / 2), x0, x1)
                    if (line.kind == MathProgram.Kind.REGION_X) {
                        regionPaint.color = withAlpha(color, 0.22f)
                        val edge = if (line.side > 0) width.toFloat() else 0f
                        for (poly in polys) {
                            path.reset()
                            path.moveTo(edge, sy(poly[0]))
                            var k = 0
                            while (k < poly.size) { path.lineTo(sx(poly[k + 1]), sy(poly[k])); k += 2 }
                            path.lineTo(edge, sy(poly[poly.size - 2]))
                            path.close()
                            canvas.drawPath(path, regionPaint)
                        }
                    }
                    for (poly in polys) canvas.drawPath(polyline(poly, swap = true), if (line.kind == MathProgram.Kind.REGION_X) stroke else curvePaint)
                }
                MathProgram.Kind.IMPLICIT, MathProgram.Kind.REGION -> {
                    val f = line.implicit!!
                    if (line.kind == MathProgram.Kind.REGION) {
                        // shade cells where the inequality holds
                        regionPaint.color = withAlpha(color, 0.22f)
                        val cell = 5 * density
                        var py = 0f
                        while (py < height) {
                            var px = 0f
                            var runStart = -1f
                            val wy0 = wy(py + cell / 2)
                            while (px <= width) {
                                val inside = px < width && Plotter.safe({ xx -> f(xx, wy0) }, wx(px + cell / 2)) > 0
                                if (inside && runStart < 0) runStart = px
                                if (!inside && runStart >= 0) { canvas.drawRect(runStart, py, px, py + cell, regionPaint); runStart = -1f }
                                px += cell
                            }
                            py += cell
                        }
                    }
                    val cell = 7 * density
                    val nx = (width / cell).toInt().coerceIn(24, 160)
                    val ny = (height / cell).toInt().coerceIn(24, 240)
                    val seg = Plotter.implicit(f, x0, x1, y0, y1, nx, ny)
                    val paint = if (line.kind == MathProgram.Kind.REGION && line.strict) dashPaint else curvePaint
                    var k = 0
                    while (k + 3 < seg.size) {
                        canvas.drawLine(sx(seg[k]), sy(seg[k + 1]), sx(seg[k + 2]), sy(seg[k + 3]), paint)
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

    private fun polyline(poly: DoubleArray, swap: Boolean): Path {
        path.reset()
        if (swap) path.moveTo(sx(poly[1]), sy(poly[0])) else path.moveTo(sx(poly[0]), sy(poly[1]))
        var k = 2
        while (k < poly.size) {
            if (swap) path.lineTo(sx(poly[k + 1]), sy(poly[k])) else path.lineTo(sx(poly[k]), sy(poly[k + 1]))
            k += 2
        }
        return path
    }

    /** Explicit curves y = f(x) (incl. inequality boundaries) that can intersect, by line index. */
    private fun explicitCurves(p: MathProgram): List<Pair<Int, (Double) -> Double>> =
        p.lines.mapIndexedNotNull { i, l ->
            if (i in hidden) null
            else if ((l.kind == MathProgram.Kind.GRAPH_Y || l.kind == MathProgram.Kind.REGION_Y) && l.curve != null) i to l.curve
            else null
        }

    /** x = c lines (constant in y), for intersections with curves. */
    private fun verticalLines(p: MathProgram): List<Double> = p.lines.mapIndexedNotNull { i, l ->
        if (i in hidden || l.kind != MathProgram.Kind.GRAPH_X || l.curve == null) return@mapIndexedNotNull null
        val a = Plotter.safe(l.curve, -1.0); val b = Plotter.safe(l.curve, 1.0)
        if (a.isFinite() && a == b) a else null
    }

    /** Grey dots like Desmos: intersections always; roots, extrema and y-intercept of the selected curve. */
    private fun drawPois(canvas: Canvas) {
        drawnPois.clear()
        val p = program ?: return
        val x0 = wx(0f); val x1 = wx(width.toFloat())
        val n = max(80, width / 3)
        val curves = explicitCurves(p)
        for (a in curves.indices) for (b in a + 1 until curves.size) {
            drawnPois += Plotter.crossings(curves[a].second, curves[b].second, x0, x1, n, Plotter.PoiKind.INTERSECTION)
        }
        for (c in verticalLines(p)) for ((_, f) in curves) {
            val y = Plotter.safe(f, c)
            if (y.isFinite()) drawnPois += Plotter.Poi(c, y, Plotter.PoiKind.INTERSECTION)
        }
        curves.firstOrNull { it.first == selected }?.second?.let { f ->
            drawnPois += Plotter.crossings(f, { 0.0 }, x0, x1, n, Plotter.PoiKind.ROOT)
            drawnPois += Plotter.extrema(f, x0, x1, n)
            val y0 = Plotter.safe(f, 0.0)
            if (y0.isFinite()) drawnPois += Plotter.Poi(0.0, y0, Plotter.PoiKind.Y_INTERCEPT)
        }
        if (drawnPois.size > 60) drawnPois.subList(60, drawnPois.size).clear()
        poiPaint.color = if (dark) 0xFF9A9A9A.toInt() else 0xFF6E6E6E.toInt()
        poiRing.color = if (dark) 0xFF101010.toInt() else Color.WHITE
        for (poi in drawnPois) {
            val px = sx(poi.x); val py = sy(poi.y)
            if (px < -20 || px > width + 20 || py < -20 || py > height + 20) continue
            canvas.drawCircle(px, py, 5f * density, poiPaint)
            canvas.drawCircle(px, py, 5f * density, poiRing)
        }
        pinned?.let { poi ->
            val title = when (poi.kind) {
                Plotter.PoiKind.INTERSECTION -> null
                Plotter.PoiKind.ROOT -> "root"
                Plotter.PoiKind.MAXIMUM -> "max"
                Plotter.PoiKind.MINIMUM -> "min"
                Plotter.PoiKind.Y_INTERCEPT -> "y-int"
            }
            val px = sx(poi.x); val py = sy(poi.y)
            dotPaint.color = tracePaint.color
            canvas.drawCircle(px, py, 6.5f * density, dotPaint)
            bubble(canvas, px, py, (title?.let { "$it " } ?: "") + "(${label(poi.x)}, ${label(poi.y)})")
        }
    }

    private fun bubble(canvas: Canvas, px: Float, py: Float, text: String) {
        val w = tracePaint.measureText(text)
        val pad = 8 * density
        var bx = px + 12 * density
        if (bx + w + pad * 2 > width) bx = px - 12 * density - w - pad * 2
        if (bx < 0) bx = 0f
        var by = py - 40 * density
        if (by < 0) by = py + 16 * density
        rect.set(bx, by, bx + w + pad * 2, by + 28 * density)
        canvas.drawRoundRect(rect, 14 * density, 14 * density, traceBg)
        canvas.drawText(text, bx + pad, by + 19 * density, tracePaint)
    }

    /** Tap: a dot shows its coordinates; a curve becomes selected; empty space clears both. */
    private fun onTap(tx: Float, ty: Float) {
        val hit = drawnPois.minByOrNull { (sx(it.x) - tx).let { d -> d * d } + (sy(it.y) - ty).let { d -> d * d } }
        val reach = 24 * density
        if (hit != null && abs(sx(hit.x) - tx) < reach && abs(sy(hit.y) - ty) < reach) {
            pinned = hit; invalidate(); return
        }
        val p = program
        val x = wx(tx)
        val near = p?.let { explicitCurves(it) }
            ?.map { (i, f) -> i to Plotter.safe(f, x) }
            ?.filter { it.second.isFinite() }
            ?.minByOrNull { abs(sy(it.second) - ty) }
        pinned = null
        selected = if (near != null && abs(sy(near.second) - ty) < reach) near.first else -1
        invalidate()
    }

    private fun drawTrace(canvas: Canvas) {
        if (traceX.isNaN()) return
        val p = program ?: return
        val x = wx(traceX)
        var best: Pair<Int, Double>? = null
        var bestDist = Float.MAX_VALUE
        p.lines.forEachIndexed { i, line ->
            if (i in hidden || !(line.kind == MathProgram.Kind.GRAPH_Y || line.kind == MathProgram.Kind.REGION_Y)) return@forEachIndexed
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
        bubble(canvas, px, py, "(${label(x)}, ${label(y)})")
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
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onTap(e.x, e.y); performClick(); return true }
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
