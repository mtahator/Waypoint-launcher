package app.olauncher.features.appearance.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.text.format.DateFormat
import android.view.View
import androidx.core.content.ContextCompat
import app.olauncher.features.appearance.domain.DotMatrixFont
import java.util.Calendar

/**
 * Draws text as round dots on a 5×7 grid, like Nothing's dot-matrix clock. With [isClock] it shows the
 * current time and updates on minute ticks only while its window is visible.
 */
class DotMatrixView(context: Context, private val isClock: Boolean = false) : View(context) {

    var text: String = ""
        set(value) {
            if (field == value) return
            field = value
            contentDescription = value
            requestLayout()
            invalidate()
        }

    /** Distance between dot centres, px. */
    var cellPx: Float = 10f * resources.displayMetrics.density
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    /** Dot diameter as a fraction of the cell (Nothing's dots nearly touch). */
    var fill: Float = 0.82f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var receiver: BroadcastReceiver? = null

    var color: Int
        get() = paint.color
        set(value) {
            paint.color = value
            invalidate()
        }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        if (isClock) updateTime()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (DotMatrixFont.width(text) * cellPx).toInt() + paddingLeft + paddingRight
        val h = (DotMatrixFont.ROWS * cellPx).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val r = cellPx * fill / 2f
        var x0 = paddingLeft.toFloat()
        val y0 = paddingTop.toFloat()
        for (c in text) {
            val glyph = DotMatrixFont.glyph(c) ?: continue
            for (row in glyph.indices) {
                val line = glyph[row]
                for (col in line.indices) if (line[col] == '#')
                    canvas.drawCircle(x0 + (col + 0.5f) * cellPx, y0 + (row + 0.5f) * cellPx, r, paint)
            }
            x0 += (glyph[0].length + 1) * cellPx
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (!isClock) return
        if (visibility == VISIBLE) register() else unregister()
    }

    override fun onDetachedFromWindow() {
        unregister()
        super.onDetachedFromWindow()
    }

    private fun register() {
        updateTime()
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = updateTime()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = r
    }

    private fun unregister() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    private fun updateTime() {
        val now = Calendar.getInstance()
        val h = now.get(Calendar.HOUR_OF_DAY)
        val m = now.get(Calendar.MINUTE)
        text = if (DateFormat.is24HourFormat(context)) "%02d:%02d".format(java.util.Locale.ROOT, h, m)
        else "%d:%02d".format(java.util.Locale.ROOT, if (h % 12 == 0) 12 else h % 12, m)
    }
}
