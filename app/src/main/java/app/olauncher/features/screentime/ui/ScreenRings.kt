package app.olauncher.features.screentime.ui

import android.content.Context
import android.graphics.Canvas
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import app.olauncher.features.appearance.ui.DotMatrixView
import app.olauncher.features.screentime.domain.ScreenScore
import app.olauncher.features.widgets.ui.InkView
import app.olauncher.features.widgets.ui.WidgetDesign
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Three concentric rings of dots, outer to inner: Budget, Focus, Mindful. Lit dots run clockwise from the
 * top; the leading dot is red. A closed ring is a full circle of lit dots.
 */
class ScreenRings(context: Context, private val big: Boolean = false) : InkView(context) {
    private var values = floatArrayOf(0f, 0f, 0f)

    fun set(budget: Float, focus: Float, mindful: Float) { values = floatArrayOf(budget, focus, mindful); invalidate() }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        val cx = width / 2f; val cy = height / 2f
        val gap = size / (if (big) 9.5f else 8.5f)
        val dot = gap * (if (big) 0.17f else 0.2f)
        for (ring in 0 until 3) {
            val r = size / 2 - dot - ring * gap
            if (r <= dot) break
            val n = max(10, (2 * PI * r / (dot * (if (big) 3.4f else 3.1f))).toInt())
            val lit = (values[ring].coerceIn(0f, 1f) * n).let { if (values[ring] > 0f && it < 1f) 1 else it.toInt() }
            for (i in 0 until n) {
                val a = -PI / 2 + 2 * PI * i / n
                val x = cx + (r * cos(a)).toFloat(); val y = cy + (r * sin(a)).toFloat()
                val head = i == lit - 1 && lit < n
                paint.color = when {
                    head -> WidgetDesign.ACCENT
                    i < lit -> WidgetDesign.level(tone, if (ring == 0) 0xFF else if (ring == 1) 0xE0 else 0xC2)
                    else -> WidgetDesign.level(tone, 0x29)
                }
                canvas.drawCircle(x, y, if (head) dot * 1.35f else dot, paint)
            }
        }
    }
}

/** On Home, where the plain screen-time line was: the rings, the score in dots, today's time. */
class ScreenTimeBadge(context: Context, ink: Int) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    private val rings = ScreenRings(context).apply { setInk(ink) }
    private val score = DotMatrixView(context).apply { cellPx = 2.7f * d; fill = 0.84f; color = ink }
    private val time = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f); typeface = android.graphics.Typeface.MONOSPACE; letterSpacing = 0.14f
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
        setTextColor((ink and 0x00FFFFFF) or 0xA8000000.toInt())
    }

    init {
        gravity = Gravity.CENTER_VERTICAL
        val pad = (6 * d).toInt(); setPadding(pad, pad, pad, pad)
        addView(rings, LayoutParams((46 * d).toInt(), (46 * d).toInt()))
        val col = LinearLayout(context).apply { orientation = VERTICAL }
        col.addView(score)
        col.addView(time, LayoutParams(-2, -2).apply { topMargin = (6 * d).toInt() })
        addView(col, LayoutParams(-2, -2).apply { marginStart = (10 * d).toInt() })
    }

    fun bind(s: ScreenScore.Summary) {
        rings.set(s.budget, s.focus, s.mindful)
        score.text = s.score.toString()
        time.text = ScreenScore.duration(s.totalMs).uppercase()
        contentDescription = "Screen time ${ScreenScore.duration(s.totalMs)}, score ${s.score}"
    }
}
