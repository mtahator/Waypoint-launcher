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
import app.olauncher.R
import app.olauncher.core.ui.PageBuilder
import app.olauncher.core.ui.WpPageFragment
import app.olauncher.core.ui.chooseOne
import app.olauncher.features.widgets.domain.WidgetItem
import app.olauncher.features.widgets.domain.WidgetStack
import app.olauncher.features.widgets.platform.CalendarSource
import app.olauncher.features.widgets.platform.MediaListenerService
import app.olauncher.features.widgets.platform.NowPlaying
import app.olauncher.helper.showToast

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

    private val stack get() = WidgetStack.decode(container.prefs.widgetStackRaw)
    private fun save(items: List<WidgetItem>) { container.prefs.widgetStackRaw = WidgetStack.encode(items); rebuild() }

    override fun onResume() { super.onResume(); rebuild() }

    override fun build(page: PageBuilder) {
        val prefs = container.prefs
        val ctx = requireContext()
        page.card {
            header(getString(R.string.wp_widgets))
            toggle(getString(R.string.wp_widgets_on_home), prefs.widgetsOnHome, getString(R.string.wp_widgets_on_home_hint)) { prefs.widgetsOnHome = it }
        }
        val items = stack
        page.card {
            header(getString(R.string.wp_widgets_in_stack))
            if (items.isEmpty()) note(getString(R.string.wp_widgets_empty))
            items.forEachIndexed { i, item ->
                row(HomeWidgets.label(ctx, item), "${i + 1}", if (item is WidgetItem.App) getString(R.string.wp_widgets_app_widget) else null) { arrange(i) }
            }
            note(getString(R.string.wp_widgets_stack_note))
        }
        page.card {
            header(getString(R.string.wp_widgets_add))
            listOf(WidgetItem.Kind.CALENDAR, WidgetItem.Kind.SPACE, WidgetItem.Kind.WEATHER).forEach { k ->
                val item = WidgetItem.Own(k)
                if (item !in items) row("＋ " + HomeWidgets.label(ctx, item)) { save(items + item) }
            }
            row(getString(R.string.wp_widgets_add_app), null, getString(R.string.wp_widgets_add_app_hint)) { pickAppWidget() }
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

    private fun arrange(i: Int) {
        val items = stack
        requireContext().chooseOne(R.string.wp_widgets_in_stack, listOf(getString(R.string.wp_widgets_move_up), getString(R.string.wp_widgets_move_down), getString(R.string.wp_delete))) { k ->
            when (k) {
                0 -> save(WidgetStack.move(items, i, i - 1))
                1 -> save(WidgetStack.move(items, i, i + 1))
                else -> {
                    (items[i] as? WidgetItem.App)?.let { container.widgetHost.delete(it.appWidgetId) }
                    save(items.filterIndexed { j, _ -> j != i })
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
        save(stack + WidgetItem.App(pendingId, info.provider.flattenToString(), label))
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
