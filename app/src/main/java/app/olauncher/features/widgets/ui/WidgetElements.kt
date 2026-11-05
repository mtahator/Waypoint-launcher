package app.olauncher.features.widgets.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import app.olauncher.features.appearance.ui.Inkable

/** The values of docs/widget-design.md, in one place. */
object WidgetDesign {
    const val PAGE_HEIGHT_DP = 176
    const val GAP_DP = 10
    const val SIDE_DP = 16
    const val RADIUS_DP = 26f
    const val PAD_DP = 16
    const val PAD_SQUARE_DP = 14
    const val HEADER_DP = 18

    const val TITLE_SP = 15f
    const val TITLE_SQUARE_SP = 14f
    const val BODY_SP = 13f
    const val CAPTION_SP = 10.5f

    /** Ink levels: the ink pass keeps these alphas and swaps white / near-black. */
    const val PRIMARY = 0xFFFFFFFF.toInt()
    const val SECONDARY = 0xA8FFFFFF.toInt()
    const val TERTIARY = 0x6BFFFFFF
    const val HAIRLINE = 0x29FFFFFF

    const val ACCENT = 0xFFD71921.toInt()

    fun level(ink: Int, alpha: Int) = (ink and 0x00FFFFFF) or (alpha shl 24)
}

/** Base for the custom elements: draws with [ink], redraws when the ink pass changes it. */
abstract class InkView(context: Context) : View(context), Inkable {
    protected val d = resources.displayMetrics.density
    protected var tone = WidgetDesign.PRIMARY
    override fun setInk(ink: Int) { if (tone != ink) { tone = ink; invalidate() } }
    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)
}

/** A row of dots lit by value (humidity, UV, rain, progress). */
class DotMeter(context: Context, private val value: Float, private val count: Int = 10, private val accent: Boolean = false) : InkView(context) {
    override fun onDraw(canvas: Canvas) {
        val step = width.toFloat() / count
        val r = minOf(step * 0.32f, height / 2f)
        val lit = (value.coerceIn(0f, 1f) * count).let { if (value > 0f && it < 1f) 1 else it.toInt() }
        for (i in 0 until count) {
            paint.color = if (i < lit) (if (accent) WidgetDesign.ACCENT else tone) else WidgetDesign.level(tone, 0x2E)
            canvas.drawCircle(step * (i + 0.5f), height / 2f, r, paint)
        }
    }
}

/**
 * Columns of dots, one per value (0..1), lit from the bottom: the next hours' temperature. An optional
 * second row of small dots under the columns shows rain chance (0..1) in red.
 */
class DotBars(context: Context, private val values: List<Float>, private val rain: List<Float> = emptyList(), private val rows: Int = 7) : InkView(context) {
    override fun onDraw(canvas: Canvas) {
        if (values.isEmpty()) return
        val cols = values.size
        val rainH = if (rain.isNotEmpty()) 10 * d else 0f
        val cell = minOf(width.toFloat() / cols / 1.6f, (height - rainH) / rows)
        val r = cell * 0.36f
        val colW = width.toFloat() / cols
        for (c in 0 until cols) {
            val lit = (values[c].coerceIn(0f, 1f) * (rows - 1)).toInt() + 1
            val cx = colW * (c + 0.5f)
            for (k in 0 until rows) {
                val cy = height - rainH - cell * (k + 0.5f)
                paint.color = if (k < lit) (if (c == 0) tone else WidgetDesign.level(tone, 0xD9)) else WidgetDesign.level(tone, 0x1F)
                canvas.drawCircle(cx, cy, r, paint)
            }
            rain.getOrNull(c)?.let { p ->
                paint.color = if (p >= 0.2f) (WidgetDesign.ACCENT and 0x00FFFFFF) or (((0.35f + 0.65f * p) * 255).toInt() shl 24) else WidgetDesign.level(tone, 0x1F)
                canvas.drawCircle(cx, height - rainH / 2, r * 0.8f, paint)
            }
        }
    }
}

/** A week's range: a dotted track over the week's min..max, a solid segment for the day, a red dot for now. */
class RangeBar(context: Context, private val min: Float, private val max: Float, private val lo: Float, private val hi: Float, private val now: Float? = null) : InkView(context) {
    private val r = RectF()
    override fun onDraw(canvas: Canvas) {
        val span = (max - min).takeIf { it > 0.01f } ?: 1f
        fun x(v: Float) = ((v - min) / span).coerceIn(0f, 1f) * (width - 6 * d) + 3 * d
        val y = height / 2f
        paint.color = WidgetDesign.level(tone, 0x29)
        var cx = 3 * d
        while (cx <= width - 3 * d) { canvas.drawCircle(cx, y, 1.1f * d, paint); cx += 5 * d }
        paint.color = tone
        r.set(x(lo) - 2.5f * d, y - 2.5f * d, x(hi) + 2.5f * d, y + 2.5f * d)
        canvas.drawRoundRect(r, 2.5f * d, 2.5f * d, paint)
        now?.let { paint.color = WidgetDesign.ACCENT; canvas.drawCircle(x(it), y, 3.6f * d, paint) }
    }
}

/** Seven days: a letter each, a dot under the days with events, today in red. */
class DayStrip(context: Context, private val letters: List<String>, private val busy: List<Int>, private val today: Int = 0) : InkView(context) {
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.MONOSPACE }
    override fun onDraw(canvas: Canvas) {
        val n = letters.size.coerceAtLeast(1)
        val step = width.toFloat() / n
        text.textSize = 9.5f * d
        for (i in letters.indices) {
            val cx = step * (i + 0.5f)
            text.color = if (i == today) WidgetDesign.ACCENT else WidgetDesign.level(tone, 0xA8)
            canvas.drawText(letters[i], cx, 10 * d, text)
            val count = busy.getOrElse(i) { 0 }.coerceAtMost(3)
            for (k in 0 until 3) {
                paint.color = if (k < count) (if (i == today) WidgetDesign.ACCENT else tone) else WidgetDesign.level(tone, 0x1A)
                canvas.drawCircle(cx, 18 * d + k * 5.5f * d, 1.7f * d, paint)
            }
        }
    }
}

/** Habit progress for the period; tap to check in. */
class Ring(context: Context, private val fraction: Float, private val label: String) : InkView(context) {
    private val box = RectF()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.MONOSPACE }
    override fun onDraw(canvas: Canvas) {
        val sw = 4 * d
        paint.style = Paint.Style.STROKE; paint.strokeWidth = sw; paint.strokeCap = Paint.Cap.ROUND
        val s = minOf(width, height).toFloat()
        box.set((width - s) / 2 + sw, (height - s) / 2 + sw, (width + s) / 2 - sw, (height + s) / 2 - sw)
        paint.color = WidgetDesign.level(tone, 0x29)
        canvas.drawArc(box, 0f, 360f, false, paint)
        if (fraction > 0f) { paint.color = WidgetDesign.ACCENT; canvas.drawArc(box, -90f, 360f * fraction.coerceIn(0f, 1f), false, paint) }
        paint.style = Paint.Style.FILL
        text.color = tone; text.textSize = 12.5f * d
        canvas.drawText(label, width / 2f, height / 2f - (text.descent() + text.ascent()) / 2, text)
    }
}

/** Project progress with the even-pace tick. */
class Bar(context: Context, private val fraction: Float, private val expected: Float) : InkView(context) {
    private val r = RectF()
    override fun onDraw(canvas: Canvas) {
        val h = 5 * d; val top = (height - h) / 2
        paint.color = WidgetDesign.level(tone, 0x29)
        r.set(0f, top, width.toFloat(), top + h); canvas.drawRoundRect(r, h / 2, h / 2, paint)
        if (fraction > 0f) { paint.color = tone; r.set(0f, top, width * fraction.coerceIn(0f, 1f), top + h); canvas.drawRoundRect(r, h / 2, h / 2, paint) }
        if (expected in 0f..1f) { paint.color = WidgetDesign.ACCENT; val x = width * expected; canvas.drawRect(x - d, top - 3 * d, x + d, top + h + 3 * d, paint) }
    }
}

/** Where you are among pages or in a list: dots in a row (the stack) or a thin track on the edge (inside a widget). */
class PageDots(context: Context, private val vertical: Boolean) : InkView(context) {
    var count = 0; set(v) { field = v; visibility = if (v > 1) VISIBLE else INVISIBLE; invalidate() }
    var current = 0; set(v) { field = v; invalidate() }
    override fun onDraw(canvas: Canvas) {
        if (count < 2) return
        val step = 9 * d; val r = 2.4f * d
        val total = step * (count - 1)
        for (i in 0 until count) {
            paint.color = if (i == current) (if (vertical) tone else WidgetDesign.ACCENT) else WidgetDesign.level(tone, 0x5C)
            val rr = if (i == current && vertical) r * 1.15f else r
            if (vertical) canvas.drawCircle(width / 2f, (height - total) / 2 + i * step, rr, paint)
            else canvas.drawCircle((width - total) / 2 + i * step, height / 2f, rr, paint)
        }
    }
}

/** A thin bar on the right edge of a scrolling list: how far down you are. */
class ScrollTrack(context: Context) : InkView(context) {
    var fraction = 0f; set(v) { field = v; invalidate() }
    var visibleFraction = 1f; set(v) { field = v; visibility = if (v < 0.99f) VISIBLE else INVISIBLE; invalidate() }
    private val r = RectF()
    override fun onDraw(canvas: Canvas) {
        val w = 2.5f * d; val x = (width - w) / 2
        paint.color = WidgetDesign.level(tone, 0x1F); r.set(x, 0f, x + w, height.toFloat()); canvas.drawRoundRect(r, w, w, paint)
        val h = (height * visibleFraction).coerceAtLeast(12 * d); val top = (height - h) * fraction
        paint.color = WidgetDesign.level(tone, 0x99); r.set(x, top, x + w, top + h); canvas.drawRoundRect(r, w, w, paint)
    }
}
