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
import app.olauncher.features.widgets.domain.StackSlot
import app.olauncher.features.widgets.domain.WidgetItem
import app.olauncher.features.widgets.domain.WidgetStack
import app.olauncher.features.widgets.platform.CalendarSource
import app.olauncher.features.widgets.platform.MediaListenerService
import app.olauncher.features.widgets.platform.NowPlaying
import app.olauncher.helper.showToast
import kotlinx.coroutines.launch

/** Arrange the Home widget stack: Waypoint's widgets, other apps' widgets, and the access they need. */
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

    private var pendingSlot = -1
    private var calendars: List<CalendarSource.Cal>? = null

    private val layout get() = WidgetStack.decodeLayout(container.prefs.widgetStackRaw)
    private fun save(slots: List<StackSlot>) { container.prefs.widgetStackRaw = WidgetStack.encodeLayout(slots); rebuild() }

    override fun onResume() {
        super.onResume(); rebuild()
        viewLifecycleOwner.lifecycleScope.launch { calendars = CalendarSource.calendars(requireContext()); rebuild() }
    }

    override fun build(page: PageBuilder) {
        val prefs = container.prefs
        val ctx = requireContext()
        page.card {
            header(getString(R.string.wp_widgets))
            toggle(getString(R.string.wp_widgets_on_home), prefs.widgetsOnHome, getString(R.string.wp_widgets_on_home_hint)) { prefs.widgetsOnHome = it }
        }
        val slots = layout
        slots.forEachIndexed { si, slot ->
            page.card {
                header(getString(R.string.wp_widgets_stack_n, si + 1) + " · " + getString(if (slot.wide) R.string.wp_widgets_wide else R.string.wp_widgets_square))
                if (slot.items.isEmpty()) note(getString(R.string.wp_widgets_stack_empty))
                slot.items.forEachIndexed { i, item ->
                    row(HomeWidgets.label(ctx, item), "${i + 1}", if (item is WidgetItem.App) getString(R.string.wp_widgets_app_widget) else null) { arrange(si, i) }
                }
                row(getString(R.string.wp_widgets_add_here)) { addTo(slot.id) }
                row(getString(R.string.wp_widgets_size), getString(if (slot.wide) R.string.wp_widgets_wide else R.string.wp_widgets_square),
                    getString(R.string.wp_widgets_size_hint)) { save(slots.map { if (it.id == slot.id) it.copy(wide = !it.wide) else it }) }
                row(getString(R.string.wp_widgets_stack_options)) { stackOptions(si) }
            }
        }
        if (slots.isEmpty()) page.card { note(getString(R.string.wp_widgets_empty)) }
        page.card {
            header(getString(R.string.wp_widgets_new_stack))
            row(getString(R.string.wp_widgets_new_wide)) { newStack(true) }
            row(getString(R.string.wp_widgets_new_square)) { newStack(false) }
            note(getString(R.string.wp_widgets_stack_note))
        }
        val cals = calendars
        if (CalendarSource.hasPermission(ctx) && !cals.isNullOrEmpty()) page.card {
            header(getString(R.string.wp_widgets_calendars))
            val hidden = prefs.hiddenCalendars
            cals.sortedWith(compareBy({ it.account.lowercase() }, { it.name.lowercase() })).forEach { cal ->
                val sub = listOfNotNull(cal.account.takeIf { it.isNotBlank() && it != cal.name },
                    getString(R.string.wp_widgets_not_syncing).takeIf { !cal.synced }).joinToString(" · ").ifBlank { null }
                toggle(cal.name.ifBlank { cal.account }, cal.id !in hidden, sub) { on ->
                    prefs.hiddenCalendars = if (on) prefs.hiddenCalendars - cal.id else prefs.hiddenCalendars + cal.id
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

    private fun newStack(wide: Boolean) {
        val slots = layout
        val id = WidgetStack.nextId(slots)
        save(slots + StackSlot(id, wide, emptyList()))
        addTo(id)
    }

    /** Waypoint's widgets not yet in any stack, then "from an app". */
    private fun addTo(slotId: Int) {
        val ctx = requireContext()
        val used = layout.flatMap { it.items }.toSet()
        val own = listOf(WidgetItem.Kind.CALENDAR, WidgetItem.Kind.SPACE, WidgetItem.Kind.CAPTURE, WidgetItem.Kind.GOALS, WidgetItem.Kind.WEATHER, WidgetItem.Kind.MUSIC)
            .map { WidgetItem.Own(it) }.filter { it !in used }
        val labels = own.map { HomeWidgets.label(ctx, it) as CharSequence } + getString(R.string.wp_widgets_app_option)
        ctx.chooseOne(R.string.wp_widgets_add, labels) { k ->
            if (k < own.size) save(layout.map { if (it.id == slotId) it.copy(items = it.items + own[k]) else it })
            else { pendingSlot = slotId; pickAppWidget() }
        }
    }

    private fun stackOptions(si: Int) {
        val slots = layout
        requireContext().chooseOne(R.string.wp_widgets_stack_options,
            listOf(getString(R.string.wp_widgets_move_stack_up), getString(R.string.wp_widgets_move_stack_down), getString(R.string.wp_widgets_remove_stack))) { k ->
            when (k) {
                0 -> save(WidgetStack.move(slots, si, si - 1))
                1 -> save(WidgetStack.move(slots, si, si + 1))
                else -> {
                    slots[si].items.filterIsInstance<WidgetItem.App>().forEach { container.widgetHost.delete(it.appWidgetId) }
                    save(slots.filterIndexed { j, _ -> j != si })
                }
            }
        }
    }

    private fun arrange(si: Int, i: Int) {
        val slots = layout
        val slot = slots[si]
        val opts = mutableListOf<CharSequence>(getString(R.string.wp_widgets_move_up), getString(R.string.wp_widgets_move_down))
        if (slots.size > 1) opts += getString(R.string.wp_widgets_move_to)
        opts += getString(R.string.wp_delete)
        requireContext().chooseOne(R.string.wp_widgets_in_stack, opts) { k ->
            fun put(items: List<WidgetItem>) = save(slots.mapIndexed { j, s -> if (j == si) s.copy(items = items) else s })
            when (opts[k]) {
                opts[0] -> put(WidgetStack.move(slot.items, i, i - 1))
                opts[1] -> put(WidgetStack.move(slot.items, i, i + 1))
                getString(R.string.wp_widgets_move_to) -> {
                    val others = slots.withIndex().filter { it.index != si }
                    requireContext().chooseOne(R.string.wp_widgets_move_to, others.map {
                        getString(R.string.wp_widgets_stack_n, it.index + 1) + " · " + getString(if (it.value.wide) R.string.wp_widgets_wide else R.string.wp_widgets_square)
                    }) { t ->
                        val target = others[t].index
                        val item = slot.items[i]
                        save(slots.mapIndexed { j, s -> when (j) {
                            si -> s.copy(items = s.items.filterIndexed { n, _ -> n != i })
                            target -> s.copy(items = s.items + item)
                            else -> s
                        } })
                    }
                }
                else -> {
                    (slot.items[i] as? WidgetItem.App)?.let { container.widgetHost.delete(it.appWidgetId) }
                    put(slot.items.filterIndexed { j, _ -> j != i })
                }
            }
        }
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
        val app = WidgetItem.App(pendingId, info.provider.flattenToString(), label)
        val slots = layout
        val target = slots.firstOrNull { it.id == pendingSlot } ?: slots.firstOrNull()
        save(if (target == null) listOf(StackSlot(1, true, listOf(app))) else slots.map { if (it.id == target.id) it.copy(items = it.items + app) else it })
        pendingId = -1; pendingInfo = null
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
