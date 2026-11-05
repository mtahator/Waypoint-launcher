package app.olauncher.features.widgets.ui

import android.Manifest
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.olauncher.R
import app.olauncher.core.ui.PageBuilder
import app.olauncher.core.ui.WpPageFragment
import app.olauncher.core.ui.chooseOne
import app.olauncher.features.widgets.domain.WidgetItem
import app.olauncher.features.widgets.domain.WidgetPage
import app.olauncher.features.widgets.domain.WidgetStack
import app.olauncher.features.widgets.platform.CalendarSource
import app.olauncher.features.widgets.platform.MediaListenerService
import app.olauncher.features.widgets.platform.NowPlaying
import app.olauncher.helper.showToast
import kotlinx.coroutines.launch

/** Arrange the Home widget stack: its pages (one widget or two), the calendars it reads, and the access it needs. */
class WidgetsSettingsFragment : WpPageFragment() {
    override val pageTitle = R.string.wp_widgets
    override val pageSubtitle = R.string.wp_widgets_subtitle

    private var pendingId = -1
    private var pendingInfo: AppWidgetProviderInfo? = null

    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { rebuild() }

    /** After Android asks the user to allow Waypoint to show the widget. */
    private val bind = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) configureOrAdd() else cancelPending()
    }

    /** After the widget's own setup screen. */
    private val configure = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) addPending() else cancelPending()
    }

    /** Where a picked app widget goes: beside the widget of this page, or (-1) on a new page. */
    private var pendingPage = -1
    private var calendars: List<CalendarSource.Cal>? = null
    private var pendingSync: CalendarSource.Cal? = null

    private val writeCalendar = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val cal = pendingSync; pendingSync = null
        if (ok) cal?.let { sync(listOf(it)) } ?: syncAll() else rebuild()
    }

    private val pages get() = WidgetStack.decodePages(container.prefs.widgetStackRaw)
    private fun save(list: List<WidgetPage>) { container.prefs.widgetStackRaw = WidgetStack.encodePages(list.filter { it.items.isNotEmpty() }); rebuild() }

    override fun onResume() {
        super.onResume(); rebuild()
        loadCalendars()
    }

    private fun loadCalendars() = viewLifecycleOwner.lifecycleScope.launch { calendars = CalendarSource.calendars(requireContext()); rebuild() }

    override fun build(page: PageBuilder) {
        val prefs = container.prefs
        val ctx = requireContext()
        page.card {
            header(getString(R.string.wp_widgets))
            toggle(getString(R.string.wp_widgets_on_home), prefs.widgetsOnHome, getString(R.string.wp_widgets_on_home_hint)) { prefs.widgetsOnHome = it }
            val modes = listOf("auto" to R.string.wp_ink_auto, "light" to R.string.wp_ink_light, "dark" to R.string.wp_ink_dark)
            row(getString(R.string.wp_ink_row), getString(modes.firstOrNull { it.first == prefs.inkMode }?.second ?: R.string.wp_ink_auto), getString(R.string.wp_ink_hint)) {
                ctx.chooseOne(R.string.wp_ink_row, modes.map { getString(it.second) }) { i -> prefs.inkMode = modes[i].first; rebuild() }
            }
        }
        val list = pages
        page.card {
            header(getString(R.string.wp_widgets_pages))
            if (list.isEmpty()) note(getString(R.string.wp_widgets_empty))
            list.forEachIndexed { i, p ->
                row(p.items.joinToString("  +  ") { HomeWidgets.label(ctx, it) }, "${i + 1}",
                    getString(if (p.wide) R.string.wp_widgets_page_one else R.string.wp_widgets_page_two)) { pageOptions(i) }
            }
            row(getString(R.string.wp_widgets_add_page)) { choose(-1) }
            note(getString(R.string.wp_widgets_pages_note))
        }
        val cals = calendars
        if (CalendarSource.hasPermission(ctx) && !cals.isNullOrEmpty()) page.card {
            header(getString(R.string.wp_widgets_calendars))
            val hidden = prefs.hiddenCalendars
            val unsynced = cals.filter { !it.synced }
            if (unsynced.size > 1) row(getString(R.string.wp_widgets_sync_all), null, getString(R.string.wp_widgets_sync_all_hint)) { syncAll() }
            cals.sortedWith(compareBy({ it.synced }, { it.account.lowercase() }, { it.name.lowercase() })).forEach { cal ->
                val name = cal.name.ifBlank { cal.account }
                val account = cal.account.takeIf { it.isNotBlank() && it != cal.name }
                if (!cal.synced) {
                    row(name, getString(R.string.wp_widgets_sync), listOfNotNull(account, getString(R.string.wp_widgets_sync_hint)).joinToString(" · ")) { syncOne(cal) }
                } else {
                    val count = resources.getQuantityString(R.plurals.wp_widgets_events_count, cal.upcoming, cal.upcoming)
                    toggle(name, cal.id !in hidden, listOfNotNull(account, count).joinToString(" · ")) { on ->
                        prefs.hiddenCalendars = if (on) prefs.hiddenCalendars - cal.id else prefs.hiddenCalendars + cal.id
                    }
                }
            }
            note(getString(R.string.wp_widgets_calendars_note))
        }
        page.card {
            header(getString(R.string.wp_widgets_access))
            val cal = CalendarSource.hasPermission(ctx)
            row(getString(R.string.wp_widget_calendar), getString(if (cal) R.string.wp_tools_location_on else R.string.wp_tools_location_off),
                getString(R.string.wp_widgets_calendar_hint)) { if (!cal) permission.launch(arrayOf(Manifest.permission.READ_CALENDAR)) else appSettings() }
            val loc = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            row(getString(R.string.wp_widget_weather), getString(if (loc) R.string.wp_tools_location_on else R.string.wp_tools_location_off),
                getString(R.string.wp_widgets_weather_hint)) {
                if (!loc) permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) else appSettings()
            }
            val music = NowPlaying.hasAccess(ctx)
            row(getString(R.string.wp_widget_music), getString(if (music) R.string.wp_tools_location_on else R.string.wp_tools_location_off),
                getString(R.string.wp_widgets_music_hint)) { musicAccess() }
        }
    }

    // ---- calendars that aren't on the phone

    private fun syncOne(cal: CalendarSource.Cal) {
        if (!CalendarSource.canWrite(requireContext())) { pendingSync = cal; writeCalendar.launch(Manifest.permission.WRITE_CALENDAR); return }
        sync(listOf(cal))
    }

    private fun syncAll() {
        val list = calendars.orEmpty().filter { !it.synced }
        if (list.isEmpty()) return
        if (!CalendarSource.canWrite(requireContext())) { pendingSync = null; writeCalendar.launch(Manifest.permission.WRITE_CALENDAR); return }
        sync(list)
    }

    private fun sync(list: List<CalendarSource.Cal>) = viewLifecycleOwner.lifecycleScope.launch {
        val ok = list.count { CalendarSource.enableSync(requireContext(), it) }
        requireContext().showToast(getString(if (ok > 0) R.string.wp_widgets_sync_started else R.string.wp_widgets_sync_failed))
        loadCalendars()
        // the account syncs in the background; look again in a little while
        kotlinx.coroutines.delay(20_000)
        if (isAdded) loadCalendars()
    }

    // ---- pages

    /** Pick a widget (Waypoint's not yet shown, or one from an app) for a new page, or beside page [beside]. */
    private fun choose(beside: Int) {
        val ctx = requireContext()
        val used = WidgetStack.all(pages).toSet()
        val own = listOf(WidgetItem.Kind.CALENDAR, WidgetItem.Kind.SPACE, WidgetItem.Kind.WEATHER, WidgetItem.Kind.GOALS, WidgetItem.Kind.CAPTURE, WidgetItem.Kind.MUSIC)
            .map { WidgetItem.Own(it) }.filter { it !in used }
        val labels = own.map { HomeWidgets.label(ctx, it) as CharSequence } + getString(R.string.wp_widgets_app_option)
        ctx.chooseOne(if (beside >= 0) R.string.wp_widgets_add_beside else R.string.wp_widgets_add_page, labels) { k ->
            if (k < own.size) put(own[k], beside) else { pendingPage = beside; pickAppWidget() }
        }
    }

    private fun put(item: WidgetItem, beside: Int) {
        val list = pages
        save(if (beside in list.indices) list.mapIndexed { i, p -> if (i == beside) WidgetPage((p.items + item).take(2)) else p } else list + WidgetPage(listOf(item)))
    }

    private fun pageOptions(i: Int) {
        val ctx = requireContext()
        val list = pages
        val p = list[i]
        val opts = ArrayList<Pair<String, () -> Unit>>()
        if (p.items.size == 1) opts += getString(R.string.wp_widgets_add_beside) to { choose(i) }
        else {
            opts += getString(R.string.wp_widgets_swap) to { save(list.mapIndexed { j, q -> if (j == i) WidgetPage(q.items.reversed()) else q }) }
            opts += getString(R.string.wp_widgets_split) to { save(list.take(i) + p.items.map { WidgetPage(listOf(it)) } + list.drop(i + 1)) }
            p.items.forEach { item ->
                opts += getString(R.string.wp_widgets_remove_x, HomeWidgets.label(ctx, item)) to {
                    (item as? WidgetItem.App)?.let { container.widgetHost.delete(it.appWidgetId) }
                    save(list.mapIndexed { j, q -> if (j == i) WidgetPage(q.items - item) else q })
                }
            }
        }
        if (i > 0) opts += getString(R.string.wp_widgets_move_page_up) to { save(WidgetStack.move(list, i, i - 1)) }
        if (i < list.size - 1) opts += getString(R.string.wp_widgets_move_page_down) to { save(WidgetStack.move(list, i, i + 1)) }
        opts += getString(R.string.wp_widgets_remove_page) to {
            p.items.filterIsInstance<WidgetItem.App>().forEach { container.widgetHost.delete(it.appWidgetId) }
            save(list.filterIndexed { j, _ -> j != i })
        }
        ctx.chooseOne(R.string.wp_widgets_pages, opts.map { it.first }) { k -> opts[k].second() }
    }

    // ---- app widgets: pick, allow, set up, add

    private fun pickAppWidget() {
        val ctx = requireContext()
        val pm = ctx.packageManager
        val providers = container.widgetHost.manager.installedProviders
            .filter { it.provider.packageName != ctx.packageName }
            .map { it to (runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it.provider.packageName, 0)).toString() }.getOrDefault(it.provider.packageName) + " · " + it.loadLabel(pm)) }
            .sortedBy { it.second.lowercase() }
        if (providers.isEmpty()) { ctx.showToast(getString(R.string.wp_widgets_none_installed)); return }
        ctx.chooseOne(R.string.wp_widgets_add_app, providers.map { it.second }) { i ->
            val info = providers[i].first
            val id = container.widgetHost.allocate()
            pendingId = id; pendingInfo = info
            if (container.widgetHost.manager.bindAppWidgetIdIfAllowed(id, info.provider)) configureOrAdd()
            else bind.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            })
        }
    }

    private fun configureOrAdd() {
        val info = pendingInfo ?: return
        val conf = info.configure
        if (conf == null) { addPending(); return }
        runCatching {
            configure.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).setComponent(conf).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId))
        }.onFailure { addPending() }
    }

    private fun addPending() {
        val info = pendingInfo ?: return
        val label = runCatching { info.loadLabel(requireContext().packageManager) }.getOrDefault(info.provider.className)
        put(WidgetItem.App(pendingId, info.provider.flattenToString(), label), pendingPage)
        pendingId = -1; pendingInfo = null; pendingPage = -1
    }

    private fun cancelPending() {
        if (pendingId >= 0) container.widgetHost.delete(pendingId)
        pendingId = -1; pendingInfo = null
    }

    private fun musicAccess() {
        val ctx = requireContext()
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(ctx, MediaListenerService::class.java).flattenToString())
        else Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        runCatching { startActivity(intent) }.onFailure { runCatching { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) } }
    }

    private fun appSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", requireContext().packageName, null)))
    }
}
