package app.olauncher.features.worldclock.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.databinding.ItemWorldClockPageBinding
import app.olauncher.features.appearance.ui.GlassStyler
import app.olauncher.features.worldclock.domain.DayOffset
import app.olauncher.features.worldclock.domain.WorldClockEntry
import app.olauncher.helper.getColorFromAttr
import java.time.Instant
import java.time.ZoneId

/**
 * Home world clock: one city per page, swipe between them, dots show the position. The slot has a fixed
 * height, so adding cities or swiping never moves anything else on Home. Times are system-driven
 * TextClocks (they stop ticking when not visible); only day-offset labels are refreshed by us, on
 * minute ticks and time/zone/date changes, with the receiver registered between onStart and onStop.
 */
class WorldClockSlider(
    private val layout: LinearLayout,
    private val pager: RecyclerView,
    private val dots: LinearLayout,
    private val onOpenPanel: () -> Unit,
) {
    private val context: Context get() = layout.context
    private val adapter = PageAdapter()
    private val snap = PagerSnapHelper()
    private var signature: String? = null
    private var receiver: BroadcastReceiver? = null
    private var current = 0

    init {
        pager.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        pager.setHasFixedSize(true)
        pager.itemAnimator = null
        pager.adapter = adapter
        snap.attachToRecyclerView(pager)
        pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                val view = snap.findSnapView(recyclerView.layoutManager) ?: return
                val position = recyclerView.getChildAdapterPosition(view)
                if (position != RecyclerView.NO_POSITION && position != current) {
                    current = position
                    renderDots()
                }
            }
        })
    }

    /**
     * Binds only when something actually changed ([rawKey] = stored list + alignment + style), so returning
     * to Home never rebuilds views. @return whether the slider is visible.
     */
    fun bind(entries: List<WorldClockEntry>, gravity: Int, glass: Boolean, rawKey: String): Boolean {
        val key = "$rawKey|$gravity|$glass|${entries.size}"
        if (key == signature) {
            refreshOffsets()
            return layout.isVisible
        }
        signature = key
        layout.isVisible = entries.isNotEmpty()
        adapter.submit(entries, gravity, glass)
        current = current.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
        dots.gravity = gravity or Gravity.CENTER_VERTICAL
        renderDots()
        return layout.isVisible
    }

    fun refreshOffsets() {
        if (adapter.itemCount > 0) adapter.notifyItemRangeChanged(0, adapter.itemCount, PAYLOAD_OFFSET)
    }

    private fun renderDots() {
        dots.removeAllViews()
        val count = adapter.itemCount
        dots.isVisible = count > 1
        if (count <= 1) return
        val density = context.resources.displayMetrics.density
        val primary = context.getColorFromAttr(R.attr.primaryColor)
        for (i in 0 until count) {
            val active = i == current
            val size = ((if (active) 6 else 5) * density).toInt()
            dots.addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (active && adapter.glass) GlassStyler.ACCENT_RED else primary)
                }
                alpha = if (active) 1f else 0.35f
            }, LinearLayout.LayoutParams(size, size).apply { marginEnd = (7 * density).toInt() })
        }
    }

    fun onStart() {
        if (adapter.itemCount == 0 || receiver != null) return
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

    fun onStop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {
        var entries: List<WorldClockEntry> = emptyList()
        var gravity = Gravity.START
        var glass = false

        fun submit(list: List<WorldClockEntry>, gravity: Int, glass: Boolean) {
            entries = list
            this.gravity = gravity
            this.glass = glass
            @Suppress("NotifyDataSetChanged") notifyDataSetChanged()
        }

        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val b = ItemWorldClockPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            b.root.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            b.root.setOnClickListener { onOpenPanel() }
            if (glass) {
                GlassStyler.styleClock(b.wcTime)
                b.wcTime.textSize = 30f
                GlassStyler.styleCaption(b.wcLabel)
                GlassStyler.styleCaption(b.wcOffset)
            }
            return PageHolder(b)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.contains(PAYLOAD_OFFSET)) holder.bindOffset(entries[position]) else onBindViewHolder(holder, position)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) = holder.bind(entries[position], gravity)
    }

    private inner class PageHolder(val b: ItemWorldClockPageBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(entry: WorldClockEntry, gravity: Int) {
            b.wcPage.gravity = gravity or Gravity.CENTER_VERTICAL
            b.wcTimeRow.gravity = gravity
            b.wcLabel.gravity = gravity
            val valid = DayOffset.isValid(entry.zoneId)
            b.wcTime.isVisible = valid
            if (valid) b.wcTime.timeZone = entry.zoneId
            b.wcLabel.text = if (valid) entry.label else context.getString(R.string.wp_unknown_zone, entry.label)
            b.root.contentDescription = entry.label
            bindOffset(entry)
        }

        fun bindOffset(entry: WorldClockEntry) {
            val days = DayOffset.daysBetween(entry.zoneId, Instant.now(), ZoneId.systemDefault())
            b.wcOffset.isVisible = days != null && days != 0
            if (days != null && days != 0) b.wcOffset.text = formatOffset(context, days)
        }
    }

    companion object {
        private const val PAYLOAD_OFFSET = "offset"

        fun formatOffset(context: Context, days: Int): String =
            context.resources.getQuantityString(
                if (days > 0) R.plurals.wp_day_ahead else R.plurals.wp_day_behind, kotlin.math.abs(days), kotlin.math.abs(days)
            )
    }
}
