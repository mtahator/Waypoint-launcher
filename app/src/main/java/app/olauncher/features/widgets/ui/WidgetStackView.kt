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
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.features.appearance.domain.Ink
import app.olauncher.features.appearance.platform.BackdropSampler
import app.olauncher.features.appearance.ui.DotMatrixView
import app.olauncher.features.widgets.domain.AgendaEvent
import app.olauncher.features.widgets.domain.WidgetItem
import app.olauncher.features.widgets.domain.WidgetPage
import app.olauncher.features.widgets.platform.NowPlaying
import app.olauncher.features.widgets.platform.WeatherSource
import app.olauncher.helper.getColorFromAttr
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import app.olauncher.features.widgets.ui.WidgetDesign as D

/** Everything a widget needs to draw, refreshed by Home (each minute, and when something changes). */
data class WidgetData(
    val events: List<AgendaEvent>? = null,
    val calendarAllowed: Boolean = false,
    val unsyncedCalendars: Int = 0,
    val todos: List<Todo> = emptyList(),
    val todayCount: Int = 0,
    val overdue: Int = 0,
    val weather: WeatherSource.Now? = null,
    val weatherAllowed: Boolean = false,
    val track: NowPlaying.Track? = null,
    val habits: List<Habit> = emptyList(),
    val projects: List<Project> = emptyList(),
    val notes: List<NoteLine> = emptyList(),
) {
    data class Todo(val id: Long, val title: String, val due: Long, val repeating: Boolean)
    data class Habit(val id: Long, val title: String, val done: Int, val target: Int)
    data class Project(val id: Long, val title: String, val percent: Int, val expected: Int, val daysLeft: Int)
    data class NoteLine(val id: Long, val title: String, val kind: String, val created: Long)
}

/** What the widgets ask Home to do. */
interface WidgetActions {
    fun openCalendar()
    fun openSpace()
    fun completeTodo(id: Long)
    fun checkIn(goalId: Long)
    fun openGoals()
    fun record()
    fun write()
    fun addTodo()
    fun openNote(id: Long, kind: String)
    fun askCalendar()
    fun askLocation()
    fun editStack()
    fun fixCalendars()
}

/**
 * The Home widget stack (docs/widget-design.md): one stack of pages, swiped sideways round and round; a page
 * holds one widget or two squares. Each widget is a frosted glass plaque; its content scrolls inside, and
 * Weather turns pages vertically. Only widgets whose data changed are rebuilt, and scroll positions survive
 * the minute refresh.
 */
class WidgetStackView(context: Context, private val sampler: BackdropSampler?, private val actions: WidgetActions) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    /** Built with the theme's ink; the ink pass then gives each element its own. */
    private val base = context.getColorFromAttr(R.attr.primaryColor)
    private val pager = RecyclerView(context)
    private val dots = PageDots(context, vertical = false)
    private val snap = PagerSnapHelper()
    private var pages: List<WidgetPage> = emptyList()
    private var appViews: Map<Int, AppWidgetHostView> = emptyMap()
    private var data = WidgetData()
    private var current = 0
    private val adapter = Adapter()
    /** Scroll offset (lists) or page (Weather) of each widget, by key. */
    private val scrolls = HashMap<String, Int>()

    /** Called when the stack settles or redraws its content, so Home can re-run the ink pass. */
    var onSettled: () -> Unit = {}

    val pageHeight = (D.PAGE_HEIGHT_DP * d).toInt()

    init {
        orientation = VERTICAL
        pager.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        pager.adapter = adapter
        pager.itemAnimator = null
        pager.overScrollMode = OVER_SCROLL_NEVER
        pager.isNestedScrollingEnabled = false
        snap.attachToRecyclerView(pager)
        pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            // the glass shows the backdrop fixed to the screen: redraw it as the cards slide
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dx == 0) return
                for (i in 0 until rv.childCount) (rv.getChildAt(i) as? ViewGroup)?.let { page ->
                    for (k in 0 until page.childCount) page.getChildAt(k).background?.invalidateSelf()
                }
            }
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE || pages.isEmpty()) return
                val v = snap.findSnapView(rv.layoutManager) ?: return
                val p = rv.getChildAdapterPosition(v)
                if (p != RecyclerView.NO_POSITION && p % pages.size != current) { current = p % pages.size; dots.current = current }
                onSettled()
            }
        })
        addView(pager, LayoutParams(-1, pageHeight))
        addView(dots, LayoutParams(-1, (16 * d).toInt()).apply { topMargin = (4 * d).toInt() })
    }

    fun setPages(list: List<WidgetPage>, views: Map<Int, AppWidgetHostView>) {
        val keep = pages.getOrNull(current)?.items?.firstOrNull()?.key
        pages = list; appViews = views
        isVisible = list.isNotEmpty()
        adapter.notifyDataSetChanged()
        current = list.indexOfFirst { p -> p.items.any { it.key == keep } }.coerceAtLeast(0)
        if (list.size > 1) (pager.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(LOOPS / 2 * list.size + current, 0)
        dots.count = list.size; dots.current = current
    }

    /** Shows the first page (Music jumps to the front when playback starts). */
    fun showFirst() {
        if (pages.size > 1) (pager.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(LOOPS / 2 * pages.size, 0)
        current = 0; dots.current = 0
    }

    fun update(newData: WidgetData) {
        data = newData
        adapter.notifyItemRangeChanged(0, adapter.itemCount, PAYLOAD)
        post { onSettled() }
    }

    // ---- pages and cells

    private inner class Holder(val page: LinearLayout) : RecyclerView.ViewHolder(page) { var keys: String? = null }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = if (pages.size > 1) pages.size * LOOPS else pages.size
        override fun getItemViewType(position: Int) = position % pages.size.coerceAtLeast(1)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(LinearLayout(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(-1, -1)
            setPadding((2 * d).toInt(), 0, (2 * d).toInt(), 0)
            setTag(R.id.wp_ink_page, true)
        })
        override fun onBindViewHolder(h: Holder, position: Int) = bindPage(h, pages[position % pages.size], full = true)
        override fun onBindViewHolder(h: Holder, position: Int, payloads: MutableList<Any>) =
            bindPage(h, pages[position % pages.size], full = PAYLOAD !in payloads)
    }

    private fun bindPage(h: Holder, p: WidgetPage, full: Boolean) {
        val page = h.page
        val keys = p.items.joinToString { it.key }
        if (full || h.keys != keys) {
            h.keys = keys
            page.removeAllViews()
            p.items.forEachIndexed { i, item ->
                val cell = FrameLayout(context).apply { clipToOutline = true; setOnLongClickListener { actions.editStack(); true } }
                page.addView(cell, LayoutParams(0, -1, 1f).apply { if (i > 0) marginStart = (D.GAP_DP * d).toInt() })
            }
        }
        p.items.forEachIndexed { i, item ->
            val cell = page.getChildAt(i) as? FrameLayout ?: return@forEachIndexed
            val sig = signature(item)
            if (!full && h.keys == keys && cell.tag == sig) return@forEachIndexed
            cell.tag = sig
            cell.removeAllViews()
            cell.background = FrostedDrawable(cell, sampler, D.RADIUS_DP * d)
            build(cell, item, wide = p.wide)
        }
    }

    /** What a widget shows; when it's unchanged the widget isn't rebuilt. */
    private fun signature(item: WidgetItem): Any = when (item) {
        is WidgetItem.App -> item.key
        is WidgetItem.Own -> when (item.kind) {
            WidgetItem.Kind.CALENDAR -> listOf(data.events, data.calendarAllowed, data.unsyncedCalendars, System.currentTimeMillis() / 60_000)
            WidgetItem.Kind.SPACE -> listOf(data.todos, data.todayCount, data.overdue)
            WidgetItem.Kind.WEATHER -> listOf(data.weather?.at, data.weatherAllowed)
            WidgetItem.Kind.MUSIC -> data.track?.let { listOf(it.title, it.artist, it.playing, it.art, it.position, it.duration) } ?: "none"
            WidgetItem.Kind.GOALS -> listOf(data.habits, data.projects)
            WidgetItem.Kind.CAPTURE -> data.notes
            WidgetItem.Kind.CLOCKS -> "clocks"
        }
    }

    private fun build(c: FrameLayout, item: WidgetItem, wide: Boolean) {
        when (item) {
            is WidgetItem.App -> {
                val v = appViews[item.appWidgetId]
                if (v != null) {
                    (v.parent as? ViewGroup)?.removeView(v)
                    c.addView(v, FrameLayout.LayoutParams(-1, -1))
                    v.setOnLongClickListener { actions.editStack(); true }
                } else c.addView(message(context.getString(R.string.wp_widget_missing, item.label)))
            }
            is WidgetItem.Own -> when (item.kind) {
                WidgetItem.Kind.CALENDAR -> calendar(c, item.key, wide)
                WidgetItem.Kind.SPACE -> todos(c, item.key, wide)
                WidgetItem.Kind.WEATHER -> weather(c, item.key, wide)
                WidgetItem.Kind.MUSIC -> music(c, wide)
                WidgetItem.Kind.GOALS -> goals(c, item.key, wide)
                WidgetItem.Kind.CAPTURE -> capture(c, item.key, wide)
                WidgetItem.Kind.CLOCKS -> Unit
            }
        }
    }

    // ---- type and parts (§4)

    private fun px(dp: Number) = (dp.toFloat() * d).toInt()
    private fun ink(level: Int) = (base and 0x00FFFFFF) or (level and 0xFF000000.toInt())
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private fun text(value: CharSequence, sp: Float, level: Int = D.PRIMARY, strong: Boolean = false, lines: Int = 1) = TextView(context).apply {
        text = value; setTextColor(ink(level)); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = if (strong) medium else Typeface.DEFAULT
        maxLines = lines; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
    }

    private fun caption(value: String, level: Int = D.SECONDARY, accent: Boolean = false) = TextView(context).apply {
        text = value.uppercase(Locale.getDefault()); setTextColor(if (accent) D.ACCENT else ink(level))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, D.CAPTION_SP); typeface = Typeface.MONOSPACE; letterSpacing = 0.14f
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
    }

    private fun dotText(value: String, cellDp: Float) = DotMatrixView(context).apply { text = value; cellPx = cellDp * d; fill = 0.84f; color = ink(D.PRIMARY) }

    private fun message(value: String, onClick: (() -> Unit)? = null) = text(value, D.BODY_SP, D.SECONDARY, lines = 3).apply {
        gravity = Gravity.CENTER; setPadding(px(20), 0, px(20), 0)
        layoutParams = FrameLayout.LayoutParams(-1, -1)
        onClick?.let { setOnClickListener { _ -> it() } }
    }

    private fun pad(wide: Boolean) = px(if (wide) D.PAD_DP else D.PAD_SQUARE_DP)

    /** The cell's frame: padding, a header row, the body under it. */
    private fun frame(c: FrameLayout, wide: Boolean, title: String?, right: String? = null, rightAccent: Boolean = false, open: () -> Unit): LinearLayout {
        val col = LinearLayout(context).apply {
            orientation = VERTICAL
            val p = pad(wide); setPadding(p, p - px(2), p - px(4), p - px(4))
            setOnClickListener { open() }; setOnLongClickListener { actions.editStack(); true }
        }
        c.addView(col, FrameLayout.LayoutParams(-1, -1))
        if (title != null) {
            val head = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, px(4), 0) }
            head.addView(caption(title, accent = true), LayoutParams(0, -2, 1f))
            right?.let { head.addView(caption(it, accent = rightAccent)) }
            col.addView(head, LayoutParams(-1, px(D.HEADER_DP)))
        }
        return col
    }

    /** A list that scrolls inside the widget, with a thin track on the right and a faded bottom edge (§5). */
    private fun scrolling(parent: ViewGroup, key: String, open: () -> Unit, params: LayoutParams = LayoutParams(-1, 0, 1f)): LinearLayout {
        val box = FrameLayout(context)
        parent.addView(box, params)
        val content = LinearLayout(context).apply {
            orientation = VERTICAL; setPadding(0, 0, px(10), px(6))
            setOnClickListener { open() }; setOnLongClickListener { actions.editStack(); true }
        }
        val track = ScrollTrack(context)
        val scroll = object : ScrollView(context) {
            override fun getTopFadingEdgeStrength() = 0f
        }.apply {
            isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER
            isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(px(22))
            addView(content, FrameLayout.LayoutParams(-1, -2))
            fun sync() {
                val range = (content.height - height).coerceAtLeast(0)
                track.visibleFraction = if (content.height == 0) 1f else (height.toFloat() / content.height).coerceAtMost(1f)
                track.fraction = if (range == 0) 0f else scrollY.toFloat() / range
            }
            setOnScrollChangeListener { _, _, y, _, _ -> scrolls[key] = y; sync() }
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sync() }
            post { scrollTo(0, scrolls[key] ?: 0); sync() }
        }
        box.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        box.addView(track, FrameLayout.LayoutParams(px(6), -1, Gravity.END))
        return content
    }

    /** Pages turned vertically inside a widget (Weather), with dots on the right edge. */
    private fun vertical(c: FrameLayout, key: String, views: List<View>) {
        val rv = RecyclerView(context)
        val ind = PageDots(context, vertical = true).apply { count = views.size; current = (scrolls[key] ?: 0).coerceIn(0, views.size - 1) }
        rv.layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        rv.overScrollMode = OVER_SCROLL_NEVER; rv.itemAnimator = null; rv.isNestedScrollingEnabled = false
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = views.size
            override fun getItemViewType(position: Int) = position
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(views[viewType].apply {
                layoutParams = RecyclerView.LayoutParams(-1, -1); setTag(R.id.wp_ink_page, true)
            }) {}
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
        }
        val snapper = PagerSnapHelper(); snapper.attachToRecyclerView(rv)
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(r: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                val v = snapper.findSnapView(r.layoutManager) ?: return
                val p = r.getChildAdapterPosition(v).takeIf { it >= 0 } ?: return
                scrolls[key] = p; ind.current = p; onSettled()
            }
        })
        (rv.layoutManager as LinearLayoutManager).scrollToPosition(ind.current)
        c.addView(rv, FrameLayout.LayoutParams(-1, -1))
        c.addView(ind, FrameLayout.LayoutParams(px(14), -1, Gravity.END).apply { marginEnd = px(4) })
    }

    private fun time(ms: Long) = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(ms))
    private fun startOfDay(day: LocalDate) = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val en = Locale.ENGLISH

    private fun dayLabel(day: LocalDate, today: LocalDate) = when (day) {
        today -> context.getString(R.string.wp_wd_today)
        today.plusDays(1) -> context.getString(R.string.wp_wd_tomorrow)
        else -> day.format(DateTimeFormatter.ofPattern("EEE d MMM", en))
    }

    private fun section(list: LinearLayout, label: String, accent: Boolean = false, first: Boolean = false) =
        list.addView(caption(label, D.TERTIARY, accent), LayoutParams(-1, -2).apply { topMargin = px(if (first) 4 else 12); bottomMargin = px(2) })

    // ---- Calendar (§7): all calendars, two weeks, grouped by day

    private fun calendar(c: FrameLayout, key: String, wide: Boolean) {
        if (!data.calendarAllowed) { c.addView(message(context.getString(R.string.wp_widget_calendar_connect)) { actions.askCalendar() }); return }
        val today = LocalDate.now()
        val now = System.currentTimeMillis()
        val events = data.events.orEmpty().filter { it.end > now || (it.allDay && it.end > startOfDay(today)) }.sortedBy { it.begin }
        val open = { actions.openCalendar() }
        if (wide) {
            val row = LinearLayout(context).apply {
                val p = pad(true); setPadding(p, p - px(2), p - px(4), p - px(4)); setOnClickListener { open() }
                setOnLongClickListener { actions.editStack(); true }
            }
            c.addView(row, FrameLayout.LayoutParams(-1, -1))
            val left = LinearLayout(context).apply { orientation = VERTICAL }
            left.addView(caption(today.format(DateTimeFormatter.ofPattern("EEEE", en)), accent = true), LayoutParams(-2, px(D.HEADER_DP)))
            left.addView(dotText("%02d".format(today.dayOfMonth), 6.2f), LayoutParams(-2, -2).apply { topMargin = px(4) })
            left.addView(caption(today.format(DateTimeFormatter.ofPattern("MMMM", en))), LayoutParams(-2, -2).apply { topMargin = px(8) })
            val week = (0 until 7).map { today.plusDays(it.toLong()) }
            val busy = week.map { day -> val a = startOfDay(day); val b = startOfDay(day.plusDays(1)); data.events.orEmpty().count { it.begin < b && it.end > a } }
            left.addView(View(context), LayoutParams(1, 0, 1f))
            left.addView(DayStrip(context, week.map { it.format(DateTimeFormatter.ofPattern("EEEEE", en)) }, busy), LayoutParams(px(104), px(32)))
            row.addView(left, LayoutParams(px(112), -1))
            val list = scrolling(row, key, open, LayoutParams(0, -1, 1f))
            agenda(list, events, today, now, wide = true)
        } else {
            val col = frame(c, false, today.format(DateTimeFormatter.ofPattern("EEE d MMM", en)), events.count { it.begin < startOfDay(today.plusDays(1)) }.takeIf { it > 0 }?.toString(), open = open)
            agenda(scrolling(col, key, open), events, today, now, wide = false)
        }
    }

    private fun agenda(list: LinearLayout, events: List<AgendaEvent>, today: LocalDate, now: Long, wide: Boolean) {
        if (data.unsyncedCalendars > 0) list.addView(caption(context.resources.getQuantityString(R.plurals.wp_wd_unsynced, data.unsyncedCalendars, data.unsyncedCalendars), accent = true).apply {
            maxLines = 2; setOnClickListener { actions.fixCalendars() }; setPadding(0, px(4), 0, px(6))
        })
        if (events.isEmpty()) {
            list.addView(text(context.getString(R.string.wp_wd_two_weeks_free), D.TITLE_SP, lines = 2), LayoutParams(-1, -2).apply { topMargin = px(8) })
            list.addView(caption(context.getString(R.string.wp_widget_calendar_free_hint), D.TERTIARY), LayoutParams(-1, -2).apply { topMargin = px(6) })
            return
        }
        var lastDay: LocalDate? = null
        events.take(40).forEach { e ->
            val day = java.time.Instant.ofEpochMilli(maxOf(e.begin, startOfDay(today))).atZone(ZoneId.systemDefault()).toLocalDate()
            if (day != lastDay) { if (!(wide && lastDay == null && day == today)) section(list, dayLabel(day, today), first = lastDay == null); lastDay = day }
            val live = !e.allDay && e.begin <= now && e.end > now
            val line = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(5), 0, px(5)) }
            line.addView(View(context).apply { background = GradientDrawable().apply { cornerRadius = 2 * d; setColor(e.color or 0xFF000000.toInt()) } },
                LayoutParams(px(3), px(30)).apply { marginEnd = px(10) })
            val t = LinearLayout(context).apply { orientation = VERTICAL }
            t.addView(text(e.title, if (wide) D.TITLE_SP else D.TITLE_SQUARE_SP, strong = live))
            val label = when {
                e.allDay -> context.getString(R.string.wp_widget_all_day)
                live -> context.getString(R.string.wp_wd_now) + " · " + context.getString(R.string.wp_wd_until, time(e.end))
                else -> time(e.begin) + " – " + time(e.end)
            } + if (e.location.isNotBlank() && wide) "  ·  " + e.location else ""
            t.addView(caption(label, if (live) D.PRIMARY else D.SECONDARY, accent = live), LayoutParams(-1, -2).apply { topMargin = px(4) })
            line.addView(t, LayoutParams(0, -2, 1f))
            list.addView(line)
        }
    }

    // ---- To-dos: overdue, today, upcoming, someday; tick them off here

    private fun todos(c: FrameLayout, key: String, wide: Boolean) {
        val open = { actions.openSpace() }
        val right = listOfNotNull(context.getString(R.string.wp_wd_today_count, data.todayCount),
            data.overdue.takeIf { it > 0 }?.let { context.getString(R.string.wp_overdue_count, it) }).joinToString(" · ")
        val col = frame(c, wide, context.getString(R.string.wp_wd_todos), right, rightAccent = data.overdue > 0, open = open)
        val body = if (wide) LinearLayout(context).also { col.addView(it, LayoutParams(-1, 0, 1f)) } else col
        if (wide) {
            val left = LinearLayout(context).apply { orientation = VERTICAL; setPadding(0, px(8), 0, 0) }
            left.addView(dotText(data.todayCount.toString(), 6.2f))
            left.addView(caption(context.getString(R.string.wp_wd_today), D.TERTIARY), LayoutParams(-2, -2).apply { topMargin = px(8) })
            body.addView(left, LayoutParams(px(84), -1))
        }
        val list = scrolling(body, key, open, if (wide) LayoutParams(0, -1, 1f) else LayoutParams(-1, 0, 1f))
        if (data.todos.isEmpty()) {
            list.addView(text(context.getString(R.string.wp_widget_space_clear), D.TITLE_SP), LayoutParams(-1, -2).apply { topMargin = px(10) })
            list.addView(caption(context.getString(R.string.wp_widget_space_clear_hint), D.TERTIARY), LayoutParams(-1, -2).apply { topMargin = px(6) })
        }
        val endOfToday = startOfDay(LocalDate.now().plusDays(1)); val now = System.currentTimeMillis()
        var last = ""
        data.todos.forEach { t ->
            val group = when { t.due in 1 until now -> "o"; t.due in 1 until endOfToday -> "t"; t.due > 0 -> "u"; else -> "s" }
            if (group != last) {
                section(list, context.getString(when (group) { "o" -> R.string.wp_wd_overdue; "t" -> R.string.wp_wd_today; "u" -> R.string.wp_wd_upcoming; else -> R.string.wp_wd_someday }),
                    accent = group == "o", first = last.isEmpty()); last = group
            }
            val line = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(2), 0, px(2)) }
            line.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_circle); imageTintList = android.content.res.ColorStateList.valueOf(ink(D.SECONDARY))
                scaleType = ImageView.ScaleType.CENTER; contentDescription = context.getString(R.string.wp_task_done)
                setOnClickListener { actions.completeTodo(t.id) }
            }, LayoutParams(px(30), px(30)).apply { marginEnd = px(6) })
            line.addView(text(t.title, if (wide) D.TITLE_SP else D.TITLE_SQUARE_SP), LayoutParams(0, -2, 1f))
            if (t.due > 0) line.addView(caption((if (t.repeating) "↻ " else "") + if (group == "u")
                java.time.Instant.ofEpochMilli(t.due).atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofPattern("EEE d", en)) else time(t.due),
                accent = group == "o"), LayoutParams(-2, -2).apply { marginStart = px(8) })
            list.addView(line)
        }
        list.addView(text(context.getString(R.string.wp_wd_add_todo), D.BODY_SP, D.SECONDARY).apply {
            setPadding(0, px(10), 0, px(6)); setOnClickListener { actions.addTodo() }
        })
    }

    // ---- Goals: habits (ring, this period as dots) and projects (bar with the even-pace tick)

    private fun goals(c: FrameLayout, key: String, wide: Boolean) {
        val open = { actions.openGoals() }
        if (data.habits.isEmpty() && data.projects.isEmpty()) { c.addView(message(context.getString(R.string.wp_widget_goals_empty), open)); return }
        val col = frame(c, wide, context.getString(R.string.wp_filter_goals), "${data.habits.size + data.projects.size}", open = open)
        val list = scrolling(col, key, open)
        data.habits.forEach { h ->
            val line = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(6), 0, px(6)) }
            val done = h.done >= h.target && h.target > 0
            line.addView(Ring(context, if (h.target > 0) h.done.toFloat() / h.target else 0f, if (done) "✓" else "+1").apply {
                setOnClickListener { actions.checkIn(h.id) }; contentDescription = h.title
            }, LayoutParams(px(38), px(38)).apply { marginEnd = px(10) })
            val t = LinearLayout(context).apply { orientation = VERTICAL }
            t.addView(text(h.title, if (wide) D.TITLE_SP else D.TITLE_SQUARE_SP))
            val meta = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            meta.addView(caption(context.getString(R.string.wp_wd_habit_progress, h.done, h.target)), LayoutParams(-2, -2).apply { marginEnd = px(8) })
            meta.addView(DotMeter(context, if (h.target > 0) h.done.toFloat() / h.target else 0f, h.target.coerceIn(1, if (wide) 10 else 7), accent = done),
                LayoutParams(px(if (wide) 90 else 56), px(7)))
            t.addView(meta, LayoutParams(-1, -2).apply { topMargin = px(5) })
            line.addView(t, LayoutParams(0, -2, 1f))
            list.addView(line)
        }
        data.projects.forEach { p ->
            val t = LinearLayout(context).apply { orientation = VERTICAL; setPadding(0, px(8), 0, px(6)) }
            val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            top.addView(text(p.title, if (wide) D.TITLE_SP else D.TITLE_SQUARE_SP), LayoutParams(0, -2, 1f))
            top.addView(caption("${p.percent}%", accent = p.percent < p.expected - 5), LayoutParams(-2, -2).apply { marginStart = px(6) })
            t.addView(top)
            t.addView(Bar(context, p.percent / 100f, p.expected / 100f), LayoutParams(-1, px(12)).apply { topMargin = px(6) })
            t.addView(caption(context.resources.getQuantityString(R.plurals.wp_goal_days_left, p.daysLeft.coerceAtLeast(0), p.daysLeft.coerceAtLeast(0)), D.TERTIARY),
                LayoutParams(-1, -2).apply { topMargin = px(4) })
            list.addView(t)
        }
    }

    // ---- Capture: record (red), write, to-do; recent captures underneath

    private fun recordButton(sizeDp: Int) = ImageView(context).apply {
        setImageResource(R.drawable.ic_mic); scaleType = ImageView.ScaleType.CENTER
        imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(D.ACCENT) }
        contentDescription = context.getString(R.string.wp_space_record)
        setOnClickListener { actions.record() }
        setTag(R.id.wp_ink_skip, true)
        layoutParams = LayoutParams(px(sizeDp), px(sizeDp))
    }

    private fun chip(label: String, onClick: () -> Unit) = text(label, D.BODY_SP).apply {
        gravity = Gravity.CENTER; setPadding(px(12), px(7), px(12), px(7))
        background = ChipBackground(context)
        setOnClickListener { onClick() }
    }

    private fun capture(c: FrameLayout, key: String, wide: Boolean) {
        val open = { actions.openSpace() }
        val col = frame(c, wide, context.getString(R.string.wp_space), null, open = open)
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(6), 0, px(6)) }
        top.addView(recordButton(if (wide) 56 else 48))
        val chips = LinearLayout(context).apply { orientation = if (wide) HORIZONTAL else VERTICAL }
        chips.addView(chip(context.getString(R.string.wp_widget_capture_write)) { actions.write() }, LayoutParams(-2, -2).apply { marginStart = px(12) })
        chips.addView(chip(context.getString(R.string.wp_quick_todo)) { actions.addTodo() }, LayoutParams(-2, -2).apply {
            marginStart = px(if (wide) 8 else 12); if (!wide) topMargin = px(6)
        })
        top.addView(chips)
        col.addView(top)
        val list = scrolling(col, key, open)
        if (data.notes.isEmpty()) { list.addView(caption(context.getString(R.string.wp_wd_captures_empty), D.TERTIARY).apply { maxLines = 2 }); return }
        data.notes.forEach { n ->
            list.addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(4), 0, px(4))
                addView(caption(when (n.kind) { "VOICE" -> "●"; "SCREENSHOT" -> "▣"; else -> "✎" }, accent = n.kind == "VOICE"), LayoutParams(px(18), -2))
                addView(text(n.title, D.BODY_SP), LayoutParams(0, -2, 1f))
                addView(caption(java.time.Instant.ofEpochMilli(n.created).atZone(ZoneId.systemDefault()).toLocalDate().let {
                    if (it == LocalDate.now()) time(n.created) else it.format(DateTimeFormatter.ofPattern("d MMM", en))
                }, D.TERTIARY), LayoutParams(-2, -2).apply { marginStart = px(6) })
                setOnClickListener { actions.openNote(n.id, n.kind) }
            })
        }
    }

    // ---- Weather: four pages, turned vertically

    private fun weatherIcon(code: Int) = when (code) {
        0 -> R.drawable.ic_w_sun
        1, 2 -> R.drawable.ic_w_partly
        3 -> R.drawable.ic_w_cloud
        45, 48 -> R.drawable.ic_w_fog
        in 51..67, in 80..82 -> R.drawable.ic_w_rain
        in 71..77, 85, 86 -> R.drawable.ic_w_snow
        in 95..99 -> R.drawable.ic_w_storm
        else -> R.drawable.ic_w_cloud
    }

    private fun icon(res: Int, sizeDp: Int, accent: Boolean = false) = ImageView(context).apply {
        setImageResource(res); scaleType = ImageView.ScaleType.FIT_CENTER
        imageTintList = android.content.res.ColorStateList.valueOf(if (accent) D.ACCENT else ink(D.PRIMARY))
        layoutParams = LayoutParams(px(sizeDp), px(sizeDp))
    }

    private fun deg(v: Double) = "${v.roundToInt()}°"

    private fun weather(c: FrameLayout, key: String, wide: Boolean) {
        val w = data.weather
        if (w == null) {
            c.addView(message(context.getString(if (data.weatherAllowed) R.string.wp_widget_weather_loading else R.string.wp_widget_weather_allow)) {
                if (!data.weatherAllowed) actions.askLocation()
            }); return
        }
        vertical(c, key, listOf(weatherNow(w, wide), weatherHours(w, wide), weatherWeek(w, wide), weatherDetails(w, wide)))
    }

    private fun weatherPage(wide: Boolean, title: String?, right: String? = null): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        val p = pad(wide); setPadding(p, p - px(2), p + px(10), p - px(4))
        setOnLongClickListener { actions.editStack(); true }
        if (title != null) {
            val head = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(caption(title, accent = true), LayoutParams(0, -2, 1f))
            right?.let { head.addView(caption(it)) }
            addView(head, LayoutParams(-1, px(D.HEADER_DP)))
        }
    }

    private fun weatherNow(w: WeatherSource.Now, wide: Boolean): View {
        val page = weatherPage(wide, null)
        val row = LinearLayout(context)
        page.addView(row, LayoutParams(-1, -1))
        val left = LinearLayout(context).apply { orientation = VERTICAL }
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(icon(weatherIcon(w.code), 20, accent = true))
        top.addView(caption(app.olauncher.features.assistant.domain.Tools.weatherCode(w.code)), LayoutParams(-2, -2).apply { marginStart = px(8) })
        left.addView(top, LayoutParams(-1, px(D.HEADER_DP)))
        left.addView(dotText(deg(w.temp), if (wide) 7f else 6f), LayoutParams(-2, -2).apply { topMargin = px(12) })
        left.addView(View(context), LayoutParams(1, 0, 1f))
        left.addView(caption("H ${deg(w.max)}  L ${deg(w.min)}"))
        left.addView(caption(context.getString(R.string.wp_wd_feels) + " ${deg(w.feels)}" + if (w.rainChance >= 20) "  ·  ${w.rainChance}%" else "", D.TERTIARY),
            LayoutParams(-2, -2).apply { topMargin = px(5) })
        row.addView(left, LayoutParams(0, -1, 1f))
        if (wide && w.hourly.isNotEmpty()) {
            val right = LinearLayout(context).apply { orientation = VERTICAL }
            right.addView(caption(context.getString(R.string.wp_wd_next_hours), D.TERTIARY), LayoutParams(-1, px(D.HEADER_DP)))
            hoursChart(right, w.hourly.take(6), labelEvery = 1)
            row.addView(right, LayoutParams(px(150), -1).apply { marginStart = px(12) })
        }
        return page
    }

    /** Dot columns for temperature, red dots for rain, the hour under each column. */
    private fun hoursChart(parent: LinearLayout, hours: List<WeatherSource.Hour>, labelEvery: Int) {
        val lo = hours.minOf { it.temp }; val hi = hours.maxOf { it.temp }
        val span = (hi - lo).takeIf { it > 0.5 } ?: 1.0
        fun labels(values: List<String>) = LinearLayout(context).apply {
            values.forEachIndexed { i, v -> addView(caption(if (i % labelEvery == 0) v else "", D.TERTIARY).apply { gravity = Gravity.CENTER; letterSpacing = 0f }, LayoutParams(0, -2, 1f)) }
        }
        parent.addView(labels(hours.map { deg(it.temp) }), LayoutParams(-1, -2).apply { topMargin = px(2) })
        parent.addView(DotBars(context, hours.map { ((it.temp - lo) / span).toFloat() }, hours.map { it.rain / 100f }, rows = 6), LayoutParams(-1, 0, 1f).apply { topMargin = px(4) })
        parent.addView(labels(hours.map { "%02d".format(it.hour) }), LayoutParams(-1, -2).apply { topMargin = px(4) })
    }

    private fun weatherHours(w: WeatherSource.Now, wide: Boolean): View {
        val page = weatherPage(wide, context.getString(R.string.wp_wd_next_hours), "${deg(w.hourly.minOfOrNull { it.temp } ?: w.min)} – ${deg(w.hourly.maxOfOrNull { it.temp } ?: w.max)}")
        val hours = if (wide) w.hourly.take(12) else w.hourly.take(12).filterIndexed { i, _ -> i % 2 == 0 }
        if (hours.isNotEmpty()) hoursChart(page, hours, labelEvery = if (wide) 2 else 1)
        return page
    }

    private fun weatherWeek(w: WeatherSource.Now, wide: Boolean): View {
        val page = weatherPage(wide, context.getString(R.string.wp_wd_week))
        val days = w.days.take(7)
        if (days.isEmpty()) return page
        val lo = days.minOf { it.min }.toFloat(); val hi = days.maxOf { it.max }.toFloat()
        val today = LocalDate.now()
        days.forEach { day ->
            val line = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            line.addView(caption(if (day.date == today) context.getString(R.string.wp_wd_today) else day.date.format(DateTimeFormatter.ofPattern("EEE", en)),
                D.SECONDARY, accent = day.date == today), LayoutParams(px(if (wide) 54 else 44), -2))
            line.addView(icon(weatherIcon(day.code), 14), LayoutParams(px(14), px(14)).apply { marginEnd = px(8) })
            if (wide) line.addView(caption(if (day.rain >= 30) "${day.rain}%" else "", accent = true), LayoutParams(px(34), -2))
            line.addView(caption(deg(day.min), D.TERTIARY).apply { gravity = Gravity.END }, LayoutParams(px(30), -2))
            line.addView(RangeBar(context, lo, hi, day.min.toFloat(), day.max.toFloat(), if (day.date == today) w.temp.toFloat() else null),
                LayoutParams(0, px(10), 1f).apply { marginStart = px(6); marginEnd = px(6) })
            line.addView(caption(deg(day.max)), LayoutParams(px(30), -2))
            page.addView(line, LayoutParams(-1, 0, 1f))
        }
        return page
    }

    private fun weatherDetails(w: WeatherSource.Now, wide: Boolean): View {
        val page = weatherPage(wide, context.getString(R.string.wp_wd_details))
        val sun = sunProgress(w.sunrise, w.sunset)
        val tiles = listOf(
            Triple(R.string.wp_wd_feels, deg(w.feels), ((w.feels + 10) / 50).toFloat()),
            Triple(R.string.wp_wd_humidity, if (w.humidity >= 0) "${w.humidity}%" else "–", w.humidity / 100f),
            Triple(R.string.wp_wd_wind, if (w.wind >= 0) "${w.wind.roundToInt()} km/h" else "–", (w.wind / 60).toFloat()),
            Triple(R.string.wp_wd_uv, if (w.uv >= 0) "%.0f".format(w.uv) else "–", (w.uv / 11).toFloat()),
            Triple(R.string.wp_wd_rain, "${w.rainChance}%", w.rainChance / 100f),
            Triple(R.string.wp_wd_sun, if (w.sunrise.isNotBlank()) "${w.sunrise}–${w.sunset}" else "–", sun),
        )
        val cols = if (wide) 3 else 2
        tiles.chunked(cols).forEach { rowTiles ->
            val row = LinearLayout(context)
            rowTiles.forEach { (label, value, meter) ->
                val t = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL }
                t.addView(caption(context.getString(label), D.TERTIARY).apply { letterSpacing = 0.08f })
                t.addView(text(value, if (wide) D.BODY_SP + 1 else D.BODY_SP - 0.5f, strong = true), LayoutParams(-1, -2).apply { topMargin = px(3) })
                t.addView(DotMeter(context, meter.coerceIn(0f, 1f), 8, accent = label == R.string.wp_wd_rain && meter >= 0.3f), LayoutParams(-1, px(6)).apply { topMargin = px(4); marginEnd = px(10) })
                row.addView(t, LayoutParams(0, -1, 1f))
            }
            page.addView(row, LayoutParams(-1, 0, 1f).apply { topMargin = px(4) })
        }
        return page
    }

    /** How far through the daylight we are (0 before sunrise, 1 after sunset). */
    private fun sunProgress(rise: String, set: String): Float = runCatching {
        val r = java.time.LocalTime.parse(rise).toSecondOfDay(); val s = java.time.LocalTime.parse(set).toSecondOfDay()
        val n = java.time.LocalTime.now().toSecondOfDay()
        ((n - r).toFloat() / (s - r)).coerceIn(0f, 1f)
    }.getOrDefault(0f)

    // ---- Music: the cover, frosted, fills the card; title, artist, live progress, controls

    private var coverKey: Any? = null
    private var coverFrost: Bitmap? = null

    private fun music(c: FrameLayout, wide: Boolean) {
        val t = data.track ?: run { c.addView(message(context.getString(R.string.wp_widget_music_idle))); return }
        c.setOnClickListener { NowPlaying.openApp(context) }
        val art = t.art
        c.setTag(R.id.wp_ink_skip, art != null)
        if (art != null) {
            if (coverKey !== art) { coverKey = art; coverFrost = frost(art) }
            c.addView(ImageView(context).apply { setImageBitmap(coverFrost); scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1))
            c.addView(View(context).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x33000000, 0xA6000000.toInt())) },
                FrameLayout.LayoutParams(-1, -1))
            c.addView(View(context).apply { background = FrostedDrawable(this, null, D.RADIUS_DP * d).apply { light = false } }, FrameLayout.LayoutParams(-1, -1))
        }
        val white = if (art != null) Ink.LIGHT else base
        val col = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.BOTTOM; val p = pad(wide); setPadding(p, p - px(2), p, p - px(2)) }
        c.addView(col, FrameLayout.LayoutParams(-1, -1))
        col.addView(caption(context.getString(if (t.playing) R.string.wp_widget_music_playing else R.string.wp_widget_music_paused), accent = art == null).apply {
            if (art != null) setTextColor(0xCCFFFFFF.toInt())
        })
        col.addView(View(context), LayoutParams(1, 0, 1f))
        val body = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        texts.addView(text(t.title.ifBlank { "—" }, if (wide) 18f else 15f, strong = true).apply { setTextColor(white) })
        texts.addView(text(t.artist, D.BODY_SP).apply { setTextColor((white and 0x00FFFFFF) or 0xB3000000.toInt()) }, LayoutParams(-1, -2).apply { topMargin = px(3) })
        body.addView(texts, LayoutParams(0, -2, 1f))
        if (!wide) body.addView(control(if (t.playing) R.drawable.ic_pause else R.drawable.ic_play, true, white) { NowPlaying.playPause() })
        col.addView(body)
        col.addView(MusicProgress(context, t, white), LayoutParams(-1, px(10)).apply { topMargin = px(10) })
        if (!wide) return
        val controls = LinearLayout(context).apply { gravity = Gravity.CENTER }
        controls.addView(control(R.drawable.ic_skip_previous, false, white) { NowPlaying.previous() })
        controls.addView(control(if (t.playing) R.drawable.ic_pause else R.drawable.ic_play, true, white) { NowPlaying.playPause() },
            LayoutParams(-2, -2).apply { marginStart = px(26); marginEnd = px(26) })
        controls.addView(control(R.drawable.ic_skip_next, false, white) { NowPlaying.next() })
        col.addView(controls, LayoutParams(-1, -2).apply { topMargin = px(6) })
    }

    private fun control(res: Int, main: Boolean, tint: Int, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(res); scaleType = ImageView.ScaleType.CENTER
        imageTintList = android.content.res.ColorStateList.valueOf(if (main) 0xFFFFFFFF.toInt() else tint)
        if (main) background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(D.ACCENT) }
        setOnClickListener { onClick() }
        layoutParams = LayoutParams(px(if (main) 44 else 40), px(if (main) 44 else 40))
    }

    /** The cover, small and heavily blurred: frosted glass over the album art. */
    private fun frost(art: Bitmap): Bitmap? = runCatching {
        val w = 96; val h = 48
        val scale = maxOf(w.toFloat() / art.width, h.toFloat() / art.height)
        val scaled = Bitmap.createScaledBitmap(art, (art.width * scale).roundToInt().coerceAtLeast(w), (art.height * scale).roundToInt().coerceAtLeast(h), true)
        val crop = Bitmap.createBitmap(scaled, (scaled.width - w) / 2, (scaled.height - h) / 2, w, h)
        val px = IntArray(w * h); crop.getPixels(px, 0, w, 0, 0, w, h)
        Ink.blur(px, w, h, 4)
        Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }.getOrNull()

    companion object {
        private const val LOOPS = 1_000
        private const val PAYLOAD = "data"

        /** App widgets get their real size so their layout fits the cell. */
        fun sizeFor(view: AppWidgetHostView, widthDp: Int, heightDp: Int) {
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    view.updateAppWidgetSize(Bundle(), listOf(android.util.SizeF(widthDp.toFloat(), heightDp.toFloat())))
                } else @Suppress("DEPRECATION") view.updateAppWidgetSize(null, widthDp, heightDp, widthDp, heightDp)
            }
        }
    }
}

/** A hairline capsule that follows the ink of the text on it. */
private class ChipBackground(context: Context) : android.graphics.drawable.Drawable() {
    private val d = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = maxOf(1f, d) }
    private val r = android.graphics.RectF()
    override fun draw(canvas: Canvas) {
        val c = (callback as? TextView)?.currentTextColor ?: 0xFFFFFFFF.toInt()
        paint.color = (c and 0x00FFFFFF) or 0x4D000000
        r.set(bounds); r.inset(paint.strokeWidth / 2, paint.strokeWidth / 2)
        canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, paint)
    }
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
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
