package app.olauncher.core.ui

import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserManager
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.olauncher.R
import app.olauncher.core.ui.styleInput
import app.olauncher.core.waypoint
import app.olauncher.helper.createDialog
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator

data class PickedApp(val label: String, val packageName: String, val userSerial: Long)

/** Launchable apps across all profiles, sorted by label. Must be called off the main thread. */
fun loadLaunchableApps(context: Context): List<PickedApp> {
    val launcherApps = context.getSystemService(LauncherApps::class.java)
    val userManager = context.getSystemService(UserManager::class.java)
    val collator = Collator.getInstance()
    return userManager.userProfiles.flatMap { user ->
        val serial = userManager.getSerialNumberForUser(user)
        runCatching { launcherApps.getActivityList(null, user) }.getOrDefault(emptyList()).map {
            PickedApp(it.label.toString(), it.applicationInfo.packageName, serial)
        }
    }.filter { it.packageName != context.packageName }
        .distinctBy { it.packageName to it.userSerial }
        .sortedWith { a, b -> collator.compare(a.label, b.label) }
}

/** Single-choice app picker. */
fun Context.pickApp(fragment: Fragment, onPicked: (PickedApp) -> Unit) =
    showAppPicker(fragment, multi = false, selected = emptySet()) { picked -> picked.firstOrNull()?.let(onPicked) }

/** Multi-choice picker returning package names (used for notification sources). */
fun Context.pickApps(fragment: Fragment, selected: Set<String>, onDone: (Set<String>) -> Unit) =
    showAppPicker(fragment, multi = true, selected = selected) { picked -> onDone(picked.mapTo(LinkedHashSet()) { it.packageName }) }

private fun Context.showAppPicker(fragment: Fragment, multi: Boolean, selected: Set<String>, onDone: (List<PickedApp>) -> Unit) {
    val ctx = this
    val chosen = LinkedHashSet(selected)
    var apps: List<PickedApp> = emptyList()
    lateinit var list: LinearLayout
    lateinit var field: EditText
    val dialog = createDialog(
        R.string.wp_choose_app,
        if (multi) R.string.wp_done else R.string.close,
        onAction = { if (multi) onDone(apps.filter { it.packageName in chosen }.distinctBy { it.packageName }) },
        content = { parent ->
            LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = (12 * resources.displayMetrics.density).toInt() }
                field = EditText(context).apply {
                    setHint(R.string.wp_search_apps_hint)
                    setSingleLine()
                    styleInput()
                }
                addView(field)
                list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                addView(ScrollView(context).apply { addView(list) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (320 * resources.displayMetrics.density).toInt()))
            }
        },
    )
    fun render() {
        val q = field.text.toString().trim()
        val page = PageBuilder(list)
        page.clear()
        apps.asSequence().filter { q.isEmpty() || it.label.contains(q, ignoreCase = true) }.take(200).forEach { app ->
            val value = if (multi) page.onOff(app.packageName in chosen) else null
            page.row(app.label, value = value) { row ->
                if (!multi) {
                    dialog.dismiss()
                    onDone(listOf(app))
                } else {
                    if (!chosen.add(app.packageName)) chosen.remove(app.packageName)
                    row.value = page.onOff(app.packageName in chosen)
                }
            }
        }
    }
    field.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = render()
    })
    dialog.showRespectingStatusBar()
    fragment.viewLifecycleOwner.lifecycleScope.launch {
        apps = withContext(ctx.waypoint.dispatchers.io) { loadLaunchableApps(ctx) }
        render()
    }
}
