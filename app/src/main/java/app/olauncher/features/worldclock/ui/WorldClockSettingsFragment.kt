package app.olauncher.features.worldclock.ui

import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import app.olauncher.R
import app.olauncher.core.ui.styleInput
import app.olauncher.core.ui.PageBuilder
import app.olauncher.core.ui.WpPageFragment
import app.olauncher.core.ui.chooseOne
import app.olauncher.core.ui.openPage
import app.olauncher.core.ui.promptText
import app.olauncher.features.worldclock.data.WorldClockRepository
import app.olauncher.features.worldclock.domain.City
import app.olauncher.features.worldclock.domain.CitySearch
import app.olauncher.features.worldclock.domain.DayOffset
import app.olauncher.features.worldclock.domain.WorldClockCodec
import app.olauncher.helper.createDialog
import app.olauncher.helper.showToast
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class WorldClockSettingsFragment : WpPageFragment() {
    override val pageTitle = R.string.wp_world_clock
    override val pageSubtitle = R.string.wp_world_clock_subtitle

    private val repo by lazy { WorldClockRepository(requireContext().applicationContext, container.prefs) }
    private var search: CitySearch? = null

    override fun build(page: PageBuilder) {
        val prefs = container.prefs
        page.card {
            toggle(getString(R.string.wp_show_on_home), prefs.worldClocksOnHome, getString(R.string.wp_show_on_home_hint)) {
                prefs.worldClocksOnHome = it
            }
            row(getString(R.string.wp_open_world_clock)) { openPanel() }
        }
        page.card {
            header(getString(R.string.wp_cities))
            val entries = repo.entries()
            if (entries.isEmpty()) note(getString(R.string.wp_no_cities))
            val now = Instant.now()
            val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            entries.forEachIndexed { index, e ->
                val zone = DayOffset.parseZone(e.zoneId)
                val subtitle = if (zone == null) getString(R.string.wp_unknown_zone_remove) else {
                    val days = DayOffset.daysBetween(e.zoneId, now, ZoneId.systemDefault()) ?: 0
                    val time = timeFormat.format(now.atZone(zone))
                    listOfNotNull(e.zoneId, time, if (days != 0) WorldClockRowsBinder.formatOffset(requireContext(), days) else null)
                        .joinToString(" · ")
                }
                row(e.label, subtitle = subtitle) { showEntryMenu(index, e.label, entries.size) }
            }
            if (entries.size < WorldClockCodec.MAX_ENTRIES) row(getString(R.string.wp_add_city), value = "+") { showSearch() }
        }
    }

    private fun openPanel() = openPage(R.id.worldClockPanelFragment)

    private fun showEntryMenu(index: Int, label: String, size: Int) {
        val options = buildList<Pair<String, () -> Unit>> {
            add(getString(R.string.rename) to { rename(index, label) })
            if (index > 0) add(getString(R.string.wp_move_up) to { repo.move(index, -1); rebuild() })
            if (index < size - 1) add(getString(R.string.wp_move_down) to { repo.move(index, 1); rebuild() })
            add(getString(R.string.wp_remove) to { repo.remove(index); rebuild() })
        }
        requireContext().chooseOne(R.string.wp_city_options, options.map { it.first }) { options[it].second() }
    }

    private fun rename(index: Int, label: String) {
        requireContext().promptText(R.string.rename, initial = label) {
            repo.rename(index, it)
            rebuild()
        }
    }

    private fun showSearch() {
        val ctx = requireContext()
        lateinit var results: LinearLayout
        lateinit var field: EditText
        val dialog = ctx.createDialog(R.string.wp_add_city, R.string.close, content = { parent ->
            LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = (12 * resources.displayMetrics.density).toInt() }
                field = EditText(context).apply {
                    setHint(R.string.wp_search_city_hint)
                    maxLines = 1
                    setSingleLine()
                    styleInput()
                }
                addView(field)
                val scroll = ScrollView(context)
                results = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                scroll.addView(results)
                addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (280 * resources.displayMetrics.density).toInt()))
            }
        })
        fun render(query: String) {
            val builder = PageBuilder(results)
            builder.clear()
            val s = search ?: return
            s.search(query, 40).forEach { city: City ->
                builder.row(city.name, subtitle = "${city.country} · ${city.zoneId}") {
                    if (repo.add(city)) {
                        dialog.dismiss()
                        rebuild()
                    } else ctx.showToast(R.string.wp_city_already_added)
                }
            }
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = render(s?.toString().orEmpty())
        })
        dialog.showRespectingStatusBar()
        if (search == null) launchUi {
            search = repo.loadSearch(container.dispatchers.io)
            render(field.text.toString())
        }
    }
}
