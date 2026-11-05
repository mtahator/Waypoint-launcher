package app.olauncher.features.widgets.ui

import android.appwidget.AppWidgetHostView
import android.content.Intent
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.olauncher.R
import app.olauncher.core.waypoint
import app.olauncher.features.assistant.ui.ChatActivity
import app.olauncher.features.widgets.domain.WidgetItem
import app.olauncher.features.widgets.domain.WidgetStack
import app.olauncher.features.widgets.platform.CalendarSource
import app.olauncher.features.widgets.platform.NowPlaying
import app.olauncher.features.widgets.platform.WeatherSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs the Home widget stack: builds it from settings, keeps its data fresh while Home is visible (every
 * minute, and at once when music starts or stops), and hosts other apps' widgets.
 */
class HomeWidgets(private val fragment: Fragment, private val container: FrameLayout, private val glass: Boolean, private val openSettings: () -> Unit) : WidgetActions {
    private val ctx = fragment.requireContext()
    private val wp = ctx.waypoint
    private val d = ctx.resources.displayMetrics.density
    /** Rows of stacks: one full-width stack, or two squares side by side (a lone square on the right). */
    private val rowsView = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL }
    private val stacks = ArrayList<WidgetStackView>()
    private var loop: Job? = null
    private var music: Job? = null
    private var playing = false
    private var wasPlaying = false
    private var signature = ""

    init {
        container.removeAllViews()
        container.addView(rowsView, FrameLayout.LayoutParams(-1, -2))
    }

    fun onStart() {
        val on = wp.prefs.widgetsOnHome
        container.isVisible = on
        if (!on) return
        wp.widgetHost.start()
        NowPlaying.start(ctx)
        rebuild(force = true)
        loop = fragment.viewLifecycleOwner.lifecycleScope.launch { while (true) { refresh(); delay(60_000) } }
        music = fragment.viewLifecycleOwner.lifecycleScope.launch {
            NowPlaying.track.collect { t ->
                // a paused session keeps its card; it goes when the app ends the session
                val now = t != null
                val started = t?.playing == true && !wasPlaying
                wasPlaying = t?.playing == true
                if (now != playing) { playing = now; rebuild() }
                if (started) stacks.firstOrNull()?.showFirst()
                refresh(onlyMusic = true)
            }
        }
    }

    fun onStop() {
        loop?.cancel(); music?.cancel()
        wp.widgetHost.stop()
    }

    /** Re-reads the arranged stacks; app widget views are created for the size of their stack. */
    fun rebuild(force: Boolean = false) {
        val slots = WidgetStack.withMusic(WidgetStack.decodeLayout(wp.prefs.widgetStackRaw), playing)
        val rows = WidgetStack.rows(slots)
        val key = rows.joinToString("|") { r -> r.joinToString(";") { s -> "${s.wide}:" + s.items.joinToString { it.key } } }
        if (!force && key == signature) return
        signature = key
        rowsView.removeAllViews(); stacks.clear()
        val screenDp = (ctx.resources.displayMetrics.widthPixels / d).toInt()
        for ((index, row) in rows.withIndex()) {
            val line = android.widget.LinearLayout(ctx).apply { gravity = android.view.Gravity.END }
            for ((k, slot) in row.withIndex()) {
                val view = WidgetStackView(ctx, glass, this, wide = slot.wide)
                val widthDp = if (slot.wide) screenDp - 32 else (screenDp - 44) / 2
                val views = HashMap<Int, AppWidgetHostView>()
                slot.items.filterIsInstance<WidgetItem.App>().forEach { a ->
                    val info = runCatching { wp.widgetHost.manager.getAppWidgetInfo(a.appWidgetId) }.getOrNull() ?: return@forEach
                    runCatching { wp.widgetHost.host.createView(ctx.applicationContext, a.appWidgetId, info) }.getOrNull()?.let { v ->
                        WidgetStackView.sizeFor(v, widthDp, 164); views[a.appWidgetId] = v
                    }
                }
                view.setItems(slot.items, views)
                view.update(last)
                stacks += view
                line.addView(view, if (slot.wide) android.widget.LinearLayout.LayoutParams(-1, -2)
                else android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply { if (k == 0 && row.size == 2) marginEnd = (12 * d).toInt() })
                // a lone square keeps its half width, on the right
                if (!slot.wide && row.size == 1) line.weightSum = 2f
            }
            rowsView.addView(line, android.widget.LinearLayout.LayoutParams(-1, -2).apply { if (index > 0) topMargin = (8 * d).toInt() })
        }
    }

    private var last = WidgetData()

    private suspend fun refresh(onlyMusic: Boolean = false) {
        val track = NowPlaying.track.value
        if (onlyMusic) { last = last.copy(track = track); stacks.forEach { it.update(last) }; return }
        val zone = java.time.ZoneId.systemDefault()
        val start = java.time.LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val calendar = CalendarSource.hasPermission(ctx)
        val events = if (calendar) CalendarSource.events(ctx, start, start + 2 * 86_400_000L, wp.prefs.hiddenCalendars) else null
        val now = System.currentTimeMillis()
        val endOfToday = start + 86_400_000L
        val tasks = runCatching { wp.space.notes.tasks() }.getOrDefault(emptyList()).filter { it.parent == 0L && !it.done }
        val todos = tasks.filter { it.due in 1 until endOfToday || it.due == 0L }
            .sortedWith(compareBy({ it.due == 0L }, { it.due }))
            .map { WidgetData.Todo(it.id, it.title, it.due, it.rule.repeats) }
        val located = listOf(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION).any {
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        val weather = if (located && WidgetStack.decodeLayout(wp.prefs.widgetStackRaw).any { WidgetItem.Own(WidgetItem.Kind.WEATHER) in it.items }) WeatherSource.now(ctx) else null
        val goals = runCatching { wp.space.notes.goals() }.getOrDefault(emptyList())
        val habits = goals.filter { it.kind == app.olauncher.features.space.data.Goal.Kind.HABIT }.map {
            val p = app.olauncher.features.space.domain.Goals.progress(it.period, it.target, it.checkins, now)
            WidgetData.Habit(it.id, it.title, p.done, p.target)
        }
        val projects = goals.filter { it.kind == app.olauncher.features.space.data.Goal.Kind.PROJECT && it.percent < 100 }.map {
            val st = app.olauncher.features.space.domain.Projects.status(it.created, it.deadline, it.percent, now)
            WidgetData.Project(it.id, it.title, st.percent, st.expected, st.daysLeft)
        }.sortedBy { it.daysLeft }
        val notes = runCatching { wp.space.notes.all() }.getOrDefault(emptyList()).take(3).map { WidgetData.NoteLine(it.id, it.displayTitle, it.kind.name, it.created) }
        last = WidgetData(events, calendar, todos, tasks.count { it.due in 1 until now }, weather, located, track, habits, projects, notes)
        stacks.forEach { it.update(last) }
    }

    // ---- actions

    override fun openCalendar() {
        val uri = android.net.Uri.parse("content://com.android.calendar/time/${System.currentTimeMillis()}")
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    override fun openSpace() = ChatActivity.open(ctx, space = true)

    override fun completeTodo(id: Long) {
        fragment.viewLifecycleOwner.lifecycleScope.launch { wp.space.setTaskDone(id, true); refresh() }
    }

    override fun checkIn(goalId: Long) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val g = wp.space.notes.goals().firstOrNull { it.id == goalId }
            if (g?.kind == app.olauncher.features.space.data.Goal.Kind.PROJECT) ChatActivity.open(ctx, space = true) else wp.space.checkIn(goalId)
            refresh()
        }
    }

    override fun openGoals() = ChatActivity.open(ctx, space = true)
    override fun record() = ChatActivity.open(ctx, record = true)
    override fun write() = ctx.startActivity(ChatActivity.intent(ctx).putExtra(ChatActivity.EXTRA_PAGE, ChatActivity.PAGE_WRITE))
    override fun addTodo() = ctx.startActivity(ChatActivity.intent(ctx).putExtra(ChatActivity.EXTRA_PAGE, ChatActivity.PAGE_TODO))
    override fun openNote(id: Long, kind: String) = ctx.startActivity(ChatActivity.intent(ctx).putExtra(ChatActivity.EXTRA_PAGE, ChatActivity.PAGE_NOTE)
        .putExtra(ChatActivity.EXTRA_NOTE, id).putExtra(ChatActivity.EXTRA_NOTE_KIND, kind))

    override fun askCalendar() = openSettings()
    override fun askLocation() = openSettings()
    override fun editStack() = openSettings()

    companion object {
        fun label(ctx: android.content.Context, item: WidgetItem): String = when (item) {
            is WidgetItem.App -> item.label
            is WidgetItem.Own -> ctx.getString(when (item.kind) {
                WidgetItem.Kind.CALENDAR -> R.string.wp_widget_calendar
                WidgetItem.Kind.SPACE -> R.string.wp_widget_space
                WidgetItem.Kind.WEATHER -> R.string.wp_widget_weather
                WidgetItem.Kind.MUSIC -> R.string.wp_widget_music
                WidgetItem.Kind.GOALS -> R.string.wp_filter_goals
                WidgetItem.Kind.CAPTURE -> R.string.wp_widget_capture
                WidgetItem.Kind.CLOCKS -> R.string.wp_world_clock
            })
        }
    }
}
