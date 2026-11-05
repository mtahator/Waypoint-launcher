package app.olauncher.features.widgets.ui

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.features.appearance.domain.ReededGlass
import app.olauncher.features.appearance.ui.DotMatrixView
import app.olauncher.features.appearance.ui.GlassStyler
import app.olauncher.features.widgets.domain.Agenda
import app.olauncher.features.widgets.domain.AgendaEvent
import app.olauncher.features.widgets.domain.WidgetItem
import app.olauncher.features.widgets.platform.NowPlaying
import app.olauncher.features.widgets.platform.WeatherSource
import app.olauncher.helper.getColorFromAttr
import kotlin.math.roundToInt

/** Everything a widget needs to draw, refreshed by Home (each minute, and when something changes). */
data class WidgetData(
    val events: List<AgendaEvent>? = null,
    val calendarAllowed: Boolean = false,
    val todos: List<Todo> = emptyList(),
    val overdue: Int = 0,
    val weather: WeatherSource.Now? = null,
    val weatherAllowed: Boolean = false,
    val track: NowPlaying.Track? = null,
) {
    data class Todo(val id: Long, val title: String, val due: Long, val repeating: Boolean)
}

/** What the widgets ask Home to do. */
interface WidgetActions {
    fun openCalendar()
    fun openSpace()
    fun completeTodo(id: Long)
    fun askCalendar()
    fun askLocation()
    fun editStack()
}

/**
 * The Home widget stack: one card at a time, swipe sideways to the next (round and round), dots show where
 * you are, long-press to arrange. App widgets sit in the same card, so the stack looks like one object.
 */
class WidgetStackView(context: Context, private val glass: Boolean, private val actions: WidgetActions) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    private val ink = context.getColorFromAttr(R.attr.primaryColor)
    private val faint = (ink and 0x00FFFFFF) or 0x99000000.toInt()
    private val pager = RecyclerView(context)
    private val dots = LinearLayout(context)
    private val snap = PagerSnapHelper()
    private var items: List<WidgetItem> = emptyList()
    private var appViews: Map<Int, AppWidgetHostView> = emptyMap()
    private var data = WidgetData()
    private var current = 0
    private val adapter = Adapter()

    val cardHeight = (172 * d).toInt()

    init {
        orientation = VERTICAL
        pager.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        pager.adapter = adapter
        pager.itemAnimator = null
        pager.overScrollMode = OVER_SCROLL_NEVER
        pager.isNestedScrollingEnabled = false
        snap.attachToRecyclerView(pager)
        pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE || items.isEmpty()) return
                val v = snap.findSnapView(rv.layoutManager) ?: return
                val p = rv.getChildAdapterPosition(v)
                if (p != RecyclerView.NO_POSITION && p % items.size != current) { current = p % items.size; renderDots() }
            }
        })
        addView(pager, LayoutParams(-1, cardHeight))
        dots.gravity = Gravity.CENTER
        addView(dots, LayoutParams(-1, (16 * d).toInt()))
    }

    /** New list of widgets (or app widget views); keeps the current card when it's still there. */
    fun setItems(list: List<WidgetItem>, views: Map<Int, AppWidgetHostView>) {
        val keep = items.getOrNull(current)?.key
        items = list; appViews = views
        isVisible = list.isNotEmpty()
        adapter.notifyDataSetChanged()
        current = list.indexOfFirst { it.key == keep }.coerceAtLeast(0)
        if (list.size > 1) (pager.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(LOOPS / 2 * list.size + current, 0)
        renderDots()
    }

    /** Shows the first card (Music jumps to the front when playback starts). */
    fun showFirst() {
        if (items.size > 1) (pager.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(LOOPS / 2 * items.size, 0)
        current = 0; renderDots()
    }

    fun update(newData: WidgetData) { data = newData; adapter.notifyItemRangeChanged(0, adapter.itemCount, "data") }

    private fun renderDots() {
        dots.removeAllViews()
        dots.isVisible = items.size > 1
        items.indices.forEach { i ->
            dots.addView(View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (i == current) GlassStyler.ACCENT_RED else ink) }
                alpha = if (i == current) 1f else 0.35f
            }, LayoutParams((5 * d).toInt(), (5 * d).toInt()).apply { marginStart = (3.5f * d).toInt(); marginEnd = (3.5f * d).toInt() })
        }
    }

    // ---- pages

    private inner class Holder(val frame: FrameLayout) : RecyclerView.ViewHolder(frame)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = if (items.size > 1) items.size * LOOPS else items.size
        override fun getItemViewType(position: Int) = position % items.size.coerceAtLeast(1)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(FrameLayout(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(-1, -1)
            setPadding((2 * d).toInt(), 0, (2 * d).toInt(), 0)
        })
        override fun onBindViewHolder(h: Holder, position: Int) = bind(h.frame, items[position % items.size])
        override fun onBindViewHolder(h: Holder, position: Int, payloads: MutableList<Any>) = onBindViewHolder(h, position)
    }

    private fun card(): FrameLayout = FrameLayout(context).apply {
        background = if (glass) GlassStyler.card(context, 26f) else GradientDrawable().apply {
            cornerRadius = 26 * d
            setColor((if (isDark()) 0x66000000 else 0x80FFFFFF.toInt()))
            setStroke((1 * d).toInt().coerceAtLeast(1), (ink and 0x00FFFFFF) or 0x26000000)
        }
        clipToOutline = true
        setOnLongClickListener { actions.editStack(); true }
    }

    private fun isDark() = (ink and 0xFF) > 0x80

    private fun bind(frame: FrameLayout, item: WidgetItem) {
        frame.removeAllViews()
        val c = card()
        frame.addView(c, FrameLayout.LayoutParams(-1, -1))
        when (item) {
            is WidgetItem.App -> {
                val v = appViews[item.appWidgetId]
                if (v != null) {
                    (v.parent as? ViewGroup)?.removeView(v)
                    c.addView(v, FrameLayout.LayoutParams(-1, -1))
                    // a long-press anywhere on an app widget still arranges the stack
                    v.setOnLongClickListener { actions.editStack(); true }
                } else c.addView(message(context.getString(R.string.wp_widget_missing, item.label)))
            }
            is WidgetItem.Own -> when (item.kind) {
                WidgetItem.Kind.CALENDAR -> calendar(c)
                WidgetItem.Kind.SPACE -> space(c)
                WidgetItem.Kind.WEATHER -> weather(c)
                WidgetItem.Kind.MUSIC -> music(c)
                WidgetItem.Kind.CLOCKS -> Unit
            }
        }
    }

    // ---- small parts

    private fun text(value: CharSequence, size: Float, color: Int = ink, bold: Boolean = false, mono: Boolean = false) =
        TextView(context).apply {
            text = value; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            typeface = when { mono -> Typeface.MONOSPACE; bold -> Typeface.DEFAULT_BOLD; else -> Typeface.DEFAULT }
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
            if (glass) GlassStyler.softShadow(this)
        }

    private fun caption(value: String, color: Int = faint) = text(value.uppercase(), 11f, color, mono = true).apply { letterSpacing = 0.14f }

    private fun dotText(value: String, cell: Float) = DotMatrixView(context).apply { text = value; cellPx = cell * d; fill = 0.84f; color = ink }

    private fun message(value: String, onClick: (() -> Unit)? = null) = text(value, 14f, faint).apply {
        maxLines = 3; gravity = Gravity.CENTER; setPadding((20 * d).toInt(), 0, (20 * d).toInt(), 0)
        layoutParams = FrameLayout.LayoutParams(-1, -1)
        onClick?.let { setOnClickListener { _ -> it() } }
    }

    private fun column(parent: FrameLayout) = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding((18 * d).toInt(), (16 * d).toInt(), (18 * d).toInt(), (14 * d).toInt())
    }.also { parent.addView(it, FrameLayout.LayoutParams(-1, -1)) }

    private fun time(ms: Long) = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(ms))

    // ---- Calendar: the date on the left, the next events on the right

    private fun calendar(c: FrameLayout) {
        c.setOnClickListener { actions.openCalendar() }
        if (!data.calendarAllowed) { c.addView(message(context.getString(R.string.wp_widget_calendar_connect)) { actions.askCalendar() }); return }
        val row = LinearLayout(context).apply { setPadding((18 * d).toInt(), (16 * d).toInt(), (16 * d).toInt(), (14 * d).toInt()) }
        c.addView(row, FrameLayout.LayoutParams(-1, -1))
        val today = java.time.LocalDate.now()
        val left = LinearLayout(context).apply { orientation = VERTICAL }
        left.addView(caption(today.format(java.time.format.DateTimeFormatter.ofPattern("EEE", java.util.Locale.ENGLISH)), GlassStyler.ACCENT_RED))
        left.addView(dotText("%02d".format(today.dayOfMonth), 6.4f), LayoutParams(-2, -2).apply { topMargin = (6 * d).toInt() })
        left.addView(caption(today.format(java.time.format.DateTimeFormatter.ofPattern("MMM", java.util.Locale.ENGLISH))).apply { setPadding(0, (6 * d).toInt(), 0, 0) })
        row.addView(left, LayoutParams((86 * d).toInt(), -1))
        val list = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(list, LayoutParams(0, -1, 1f))
        val now = System.currentTimeMillis()
        val end = today.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val events = Agenda.upcoming(data.events.orEmpty(), now, end, 3)
        if (events.isEmpty()) {
            list.addView(text(context.getString(R.string.wp_widget_calendar_free), 16f))
            list.addView(caption(context.getString(R.string.wp_widget_calendar_free_hint)).apply { setPadding(0, (6 * d).toInt(), 0, 0) })
            return
        }
        events.forEachIndexed { i, e ->
            val line = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, if (i == 0) 0 else (9 * d).toInt(), 0, 0) }
            line.addView(View(context).apply { background = GradientDrawable().apply { cornerRadius = 2 * d; setColor(e.color or 0xFF000000.toInt()) } },
                LayoutParams((3 * d).toInt(), (30 * d).toInt()).apply { marginEnd = (10 * d).toInt() })
            val t = LinearLayout(context).apply { orientation = VERTICAL }
            t.addView(text(e.title, 15f))
            val tomorrow = !e.allDay && e.begin >= end
            val label = when {
                e.allDay -> context.getString(R.string.wp_widget_all_day)
                tomorrow -> context.getString(R.string.wp_task_tomorrow) + " " + time(e.begin)
                else -> time(e.begin) + "–" + time(e.end)
            } + (if (i == 0 && !e.allDay && !tomorrow) "  ·  " + Agenda.nextLabel(e, now) else "")
            t.addView(caption(label).apply { setPadding(0, (3 * d).toInt(), 0, 0) })
            line.addView(t, LayoutParams(0, -2, 1f))
            list.addView(line)
        }
    }

    // ---- Space: today's to-dos, tick them off here

    private fun space(c: FrameLayout) {
        c.setOnClickListener { actions.openSpace() }
        val col = column(c)
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(caption(context.getString(R.string.wp_widget_space_title), GlassStyler.ACCENT_RED), LayoutParams(0, -2, 1f))
        if (data.overdue > 0) top.addView(caption(context.getString(R.string.wp_overdue_count, data.overdue), GlassStyler.ACCENT_RED))
        col.addView(top)
        if (data.todos.isEmpty()) {
            col.addView(text(context.getString(R.string.wp_widget_space_clear), 17f).apply { setPadding(0, (22 * d).toInt(), 0, 0) })
            col.addView(caption(context.getString(R.string.wp_widget_space_clear_hint)).apply { setPadding(0, (6 * d).toInt(), 0, 0) })
            return
        }
        data.todos.take(3).forEach { t ->
            val line = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, (8 * d).toInt(), 0, 0) }
            line.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_circle); imageTintList = android.content.res.ColorStateList.valueOf(ink)
                scaleType = ImageView.ScaleType.CENTER
                setOnClickListener { actions.completeTodo(t.id) }
                contentDescription = context.getString(R.string.wp_task_done)
            }, LayoutParams((32 * d).toInt(), (32 * d).toInt()).apply { marginEnd = (6 * d).toInt() })
            line.addView(text(t.title, 15f), LayoutParams(0, -2, 1f))
            if (t.due > 0) line.addView(caption((if (t.repeating) "↻ " else "") + time(t.due)))
            col.addView(line)
        }
    }

    // ---- Weather: big dot-matrix temperature, the day's range and the next hours

    private fun weather(c: FrameLayout) {
        val w = data.weather
        if (w == null) {
            c.addView(message(context.getString(if (data.weatherAllowed) R.string.wp_widget_weather_loading else R.string.wp_widget_weather_allow)) {
                if (!data.weatherAllowed) actions.askLocation()
            })
            return
        }
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding((18 * d).toInt(), (14 * d).toInt(), (18 * d).toInt(), (14 * d).toInt()) }
        c.addView(row, FrameLayout.LayoutParams(-1, -1))
        val left = LinearLayout(context).apply { orientation = VERTICAL }
        left.addView(caption(app.olauncher.features.assistant.domain.Tools.weatherCode(w.code), GlassStyler.ACCENT_RED))
        left.addView(dotText("${w.temp.roundToInt()}°", 6f), LayoutParams(-2, -2).apply { topMargin = (8 * d).toInt(); bottomMargin = (8 * d).toInt() })
        left.addView(caption("↑ ${w.max.roundToInt()}°  ↓ ${w.min.roundToInt()}°" + if (w.rainChance > 0) "  ☂ ${w.rainChance}%" else ""))
        row.addView(left, LayoutParams(0, -2, 1f))
        val hours = LinearLayout(context).apply { gravity = Gravity.BOTTOM }
        val temps = w.hourly.map { it.second }
        val lo = (temps.minOrNull() ?: 0.0) - 1; val hi = (temps.maxOrNull() ?: 1.0) + 1
        w.hourly.forEach { (h, t) ->
            hours.addView(LinearLayout(context).apply {
                orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                addView(caption("${t.roundToInt()}°", ink))
                addView(View(context).apply { background = GradientDrawable().apply { cornerRadius = 3 * d; setColor((ink and 0x00FFFFFF) or 0x66000000) } },
                    LayoutParams((6 * d).toInt(), ((14 + 46 * ((t - lo) / (hi - lo))) * d).toInt()).apply { topMargin = (5 * d).toInt(); bottomMargin = (5 * d).toInt() })
                addView(caption("%02d".format(h)))
            }, LayoutParams((32 * d).toInt(), -2))
        }
        row.addView(hours)
    }

    // ---- Music: the cover through reeded glass behind, controls in front

    private var coverKey: Any? = null
    private var coverGlass: Bitmap? = null

    private fun music(c: FrameLayout) {
        val t = data.track ?: run { c.addView(message(context.getString(R.string.wp_widget_music_idle))); return }
        c.setOnClickListener { NowPlaying.openApp(context) }
        val art = t.art
        if (art != null) {
            if (coverKey !== art) { coverKey = art; coverGlass = reed(art) }
            c.addView(ImageView(context).apply { setImageBitmap(coverGlass); scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1))
            c.addView(View(context).apply { setBackgroundColor(0x40000000) }, FrameLayout.LayoutParams(-1, -1))
        }
        val white = if (art != null) 0xFFFFFFFF.toInt() else ink
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding((16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt()) }
        c.addView(row, FrameLayout.LayoutParams(-1, -1))
        row.addView(ImageView(context).apply {
            if (art != null) setImageBitmap(art) else setImageResource(R.drawable.ic_mic)
            scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = 16 * d; setColor(0x33000000) }
            elevation = 6 * d
        }, LayoutParams((104 * d).toInt(), (104 * d).toInt()).apply { marginEnd = (16 * d).toInt() })
        val col = LinearLayout(context).apply { orientation = VERTICAL }
        row.addView(col, LayoutParams(0, -2, 1f))
        col.addView(caption(if (t.playing) context.getString(R.string.wp_widget_music_playing) else context.getString(R.string.wp_widget_music_paused),
            if (art != null) 0xCCFFFFFF.toInt() else GlassStyler.ACCENT_RED))
        col.addView(text(t.title.ifBlank { "—" }, 17f, white, bold = true).apply { setPadding(0, (6 * d).toInt(), 0, 0); setShadowLayer(8 * d, 0f, 0f, 0x66000000) })
        col.addView(text(t.artist, 14f, (white and 0x00FFFFFF) or 0xCC000000.toInt()).apply { setPadding(0, (3 * d).toInt(), 0, 0); setShadowLayer(8 * d, 0f, 0f, 0x66000000) })
        col.addView(MusicProgress(context, t, white), LayoutParams(-1, (14 * d).toInt()).apply { topMargin = (10 * d).toInt() })
        val controls = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        fun button(label: String, size: Float, main: Boolean, onClick: () -> Unit) = controls.addView(text(label, size, if (main) 0xFFFFFFFF.toInt() else white).apply {
            gravity = Gravity.CENTER
            if (main) background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(GlassStyler.ACCENT_RED) }
            setOnClickListener { onClick() }
        }, LayoutParams(((if (main) 44 else 40) * d).toInt(), ((if (main) 44 else 40) * d).toInt()).apply { marginEnd = (10 * d).toInt() })
        button("⏮", 18f, false) { NowPlaying.previous() }
        button(if (t.playing) "⏸" else "▶", 18f, true) { NowPlaying.playPause() }
        button("⏭", 18f, false) { NowPlaying.next() }
        col.addView(controls, LayoutParams(-2, -2).apply { topMargin = (4 * d).toInt() })
    }

    /** The cover, blown up and seen through vertical glass ribs, like the launcher's backdrop. */
    private fun reed(art: Bitmap): Bitmap? = runCatching {
        val w = 220; val h = 110
        val scaled = Bitmap.createScaledBitmap(art, w, (art.height * w.toFloat() / art.width).roundToInt().coerceAtLeast(h), true)
        val crop = Bitmap.createBitmap(scaled, 0, ((scaled.height - h) / 2).coerceAtLeast(0), w, h)
        val px = IntArray(w * h); crop.getPixels(px, 0, w, 0, 0, w, h)
        val out = ReededGlass.apply(px, w, h, ReededGlass.Params(ribPx = 9, darken = 0.28f))
        Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }.getOrNull()

    companion object {
        private const val LOOPS = 1_000

        /** App widgets get their real size so their layout fits the card. */
        fun sizeFor(view: AppWidgetHostView, widthDp: Int, heightDp: Int) {
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    view.updateAppWidgetSize(Bundle(), listOf(android.util.SizeF(widthDp.toFloat(), heightDp.toFloat())))
                } else @Suppress("DEPRECATION") view.updateAppWidgetSize(null, widthDp, heightDp, widthDp, heightDp)
            }
        }
    }
}

/** A thin progress line that moves on its own while playing. */
private class MusicProgress(context: Context, private val t: NowPlaying.Track, private val color: Int) : View(context) {
    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = (color and 0x00FFFFFF) or 0x40000000; strokeWidth = 3 * d; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = 3 * d; strokeCap = Paint.Cap.ROUND }

    override fun onDraw(canvas: Canvas) {
        if (t.duration <= 0) return
        val pos = if (t.playing) t.position + (android.os.SystemClock.elapsedRealtime() - t.updatedAt) else t.position
        val f = (pos.toFloat() / t.duration).coerceIn(0f, 1f)
        val y = height / 2f
        canvas.drawLine(2 * d, y, width - 2 * d, y, track)
        canvas.drawLine(2 * d, y, 2 * d + (width - 4 * d) * f, y, fill)
        if (t.playing) postInvalidateDelayed(1_000)
    }
}
