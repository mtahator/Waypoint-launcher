package app.olauncher.features.worldclock.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.databinding.ItemWorldClockHomeBinding
import app.olauncher.features.worldclock.domain.DayOffset
import app.olauncher.features.worldclock.domain.WorldClockEntry
import java.time.Instant
import java.time.ZoneId

/**
 * Home-screen world clock rows (max [MAX_INLINE]). Times are system-driven TextClocks, which stop
 * ticking when not visible. Only the day-offset labels are refreshed by us: on minute ticks and
 * time/zone/date changes, with the receiver registered between onStart and onStop.
 */
class WorldClockRowsBinder(private val container: LinearLayout, private val onOpenPanel: () -> Unit) {

    private val rows = mutableListOf<Pair<WorldClockEntry, ItemWorldClockHomeBinding>>()
    private var receiver: BroadcastReceiver? = null

    fun bind(entries: List<WorldClockEntry>, gravity: Int, styleRow: (TextView) -> Unit = {}) {
        container.removeAllViews()
        rows.clear()
        container.isVisible = entries.isNotEmpty()
        if (entries.isEmpty()) return
        container.gravity = gravity
        val inflater = LayoutInflater.from(container.context)
        val ctx = container.context
        entries.take(MAX_INLINE).forEach { entry ->
            val b = ItemWorldClockHomeBinding.inflate(inflater, container, false)
            b.wcLabel.text = entry.label
            if (DayOffset.isValid(entry.zoneId)) {
                b.wcTime.timeZone = entry.zoneId
            } else {
                b.wcTime.isVisible = false
                b.wcLabel.text = ctx.getString(R.string.wp_unknown_zone, entry.label)
            }
            b.root.contentDescription = entry.label
            b.root.setOnClickListener { onOpenPanel() }
            container.addView(b.root)
            rows += entry to b
        }
        if (entries.size > MAX_INLINE) {
            val more = TextView(ctx, null, 0, R.style.TextSmall).apply {
                text = ctx.getString(R.string.wp_more_cities, entries.size - MAX_INLINE)
                minHeight = (48 * resources.displayMetrics.density).toInt()
                gravity = android.view.Gravity.CENTER_VERTICAL
                contentDescription = ctx.getString(R.string.wp_open_world_clock)
                setPaddingRelative((3 * resources.displayMetrics.density).toInt(), 0, 0, 0)
                setOnClickListener { onOpenPanel() }
            }
            styleRow(more)
            container.addView(more)
        }
        refreshOffsets()
    }

    fun refreshOffsets() {
        val now = Instant.now()
        val local = ZoneId.systemDefault()
        for ((entry, b) in rows) {
            val days = DayOffset.daysBetween(entry.zoneId, now, local)
            b.wcOffset.isVisible = days != null && days != 0
            if (days != null && days != 0) b.wcOffset.text = formatOffset(b.root.context, days)
        }
    }

    fun onStart(context: Context) {
        if (rows.isEmpty() || receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = refreshOffsets()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        ContextCompat.registerReceiver(context, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = r
        refreshOffsets()
    }

    fun onStop(context: Context) {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    companion object {
        const val MAX_INLINE = 3

        fun formatOffset(context: Context, days: Int): String =
            context.resources.getQuantityString(
                if (days > 0) R.plurals.wp_day_ahead else R.plurals.wp_day_behind, kotlin.math.abs(days), kotlin.math.abs(days)
            )
    }
}
