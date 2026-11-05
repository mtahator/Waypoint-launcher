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
class HomeWidgets(private val fragment: Fragment, private val container: FrameLayout, glass: Boolean, private val openSettings: () -> Unit) : WidgetActions {
    private val ctx = fragment.requireContext()
    private val wp = ctx.waypoint
    private val stack = WidgetStackView(ctx, glass, this)
    private var loop: Job? = null
    private var music: Job? = null
    private var playing = false
    private var signature = ""

    init {
        container.removeAllViews()
        container.addView(stack, FrameLayout.LayoutParams(-1, -2))
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
                val now = t != null && t.playing
                if (now != playing) { playing = now; rebuild(); if (now) stack.showFirst() }
                refresh(onlyMusic = true)
            }
        }
    }

    fun onStop() {
        loop?.cancel(); music?.cancel()
        wp.widgetHost.stop()
    }

    /** Re-reads the arranged stack; app widget views are created once per id. */
    fun rebuild(force: Boolean = false) {
        val items = WidgetStack.visible(WidgetStack.decode(wp.prefs.widgetStackRaw), playing)
        val key = items.joinToString { it.key }
        if (!force && key == signature) return
        signature = key
        val views = HashMap<Int, AppWidgetHostView>()
        val widthDp = (ctx.resources.displayMetrics.widthPixels / ctx.resources.displayMetrics.density).toInt() - 32
        items.filterIsInstance<WidgetItem.App>().forEach { a ->
            val info = runCatching { wp.widgetHost.manager.getAppWidgetInfo(a.appWidgetId) }.getOrNull() ?: return@forEach
            runCatching { wp.widgetHost.host.createView(ctx.applicationContext, a.appWidgetId, info) }.getOrNull()?.let { v ->
                WidgetStackView.sizeFor(v, widthDp, 172)
                views[a.appWidgetId] = v
            }
        }
        stack.setItems(items, views)
    }

    private var last = WidgetData()

    private suspend fun refresh(onlyMusic: Boolean = false) {
        val track = NowPlaying.track.value
        if (onlyMusic) { last = last.copy(track = track); stack.update(last); return }
        val zone = java.time.ZoneId.systemDefault()
        val start = java.time.LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val calendar = CalendarSource.hasPermission(ctx)
        val events = if (calendar) CalendarSource.events(ctx, start, start + 2 * 86_400_000L) else null
        val now = System.currentTimeMillis()
        val endOfToday = start + 86_400_000L
        val tasks = runCatching { wp.space.notes.tasks() }.getOrDefault(emptyList()).filter { it.parent == 0L && !it.done }
        val todos = tasks.filter { it.due in 1 until endOfToday || it.due == 0L }
            .sortedWith(compareBy({ it.due == 0L }, { it.due }))
            .map { WidgetData.Todo(it.id, it.title, it.due, it.rule.repeats) }
        val located = listOf(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION).any {
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        val weather = if (located && WidgetStack.decode(wp.prefs.widgetStackRaw).contains(WidgetItem.Own(WidgetItem.Kind.WEATHER))) WeatherSource.now(ctx) else null
        last = WidgetData(events, calendar, todos, tasks.count { it.due in 1 until now }, weather, located, track)
        stack.update(last)
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
                WidgetItem.Kind.CLOCKS -> R.string.wp_world_clock
            })
        }
    }
}
