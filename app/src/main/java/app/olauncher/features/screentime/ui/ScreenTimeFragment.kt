package app.olauncher.features.screentime.ui

import android.content.Intent
import android.provider.Settings
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import app.olauncher.R
import app.olauncher.core.ui.PageBuilder
import app.olauncher.core.ui.WpPageFragment
import app.olauncher.core.ui.chooseOne
import app.olauncher.features.appearance.ui.DotMatrixView
import app.olauncher.features.screentime.domain.ScreenScore
import app.olauncher.features.widgets.ui.DotBars
import app.olauncher.helper.appUsagePermissionGranted
import app.olauncher.helper.getColorFromAttr
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Today's screen time as three rings to close (Budget, Focus, Mindful) and a score, the day's rhythm hour
 * by hour, the apps it went to (tap one to say what it is for you) and the goals behind the rings.
 */
class ScreenTimeFragment : WpPageFragment() {
    override val pageTitle = R.string.wp_st_title
    override val pageSubtitle = R.string.wp_st_subtitle

    private var summary: ScreenScore.Summary? = null
    private var heroRings: ScreenRings? = null
    private var heroScore: DotMatrixView? = null
    private var goalJob: Job? = null

    override fun onResume() {
        super.onResume()
        summary = container.screenTime.cached()
        rebuild()
        load(force = true)
    }

    private fun load(force: Boolean) = viewLifecycleOwner.lifecycleScope.launch {
        summary = container.screenTime.today(force) ?: return@launch
        rebuild()
    }

    private fun d(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dur(ms: Long) = ScreenScore.duration(ms)
    private fun min(m: Int) = ScreenScore.duration(m * 60_000L)

    override fun build(page: PageBuilder) {
        val ctx = requireContext()
        if (!ctx.appUsagePermissionGranted()) {
            page.card {
                note(getString(R.string.wp_st_need_access))
                row(getString(R.string.wp_st_allow)) { runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } }
            }
            return
        }
        val s = summary ?: run { page.card { note(getString(R.string.wp_st_loading)) }; return }
        val ink = ctx.getColorFromAttr(R.attr.primaryColor)
        page.card { add(hero(s, ink)) }
        page.card {
            header(getString(R.string.wp_st_today))
            row(getString(R.string.wp_st_budget), "${dur(s.totalMs)} / ${min(s.goals.budgetMin)}",
                if (s.overBudget) getString(R.string.wp_st_over, dur(s.totalMs - s.goals.budgetMin * 60_000L)) else getString(R.string.wp_st_left, dur(s.leftMs)))
            row(getString(R.string.wp_st_focus), dur(s.longestBreakMs), getString(R.string.wp_st_focus_hint, min(s.goals.breakMin)))
            row(getString(R.string.wp_st_mindful), "${Math.round(s.purposeShare * 100)}%", getString(R.string.wp_st_mindful_hint, s.goals.purposePct))
        }
        page.card {
            header(getString(R.string.wp_st_rhythm))
            add(rhythm(s, ink))
            row(getString(R.string.wp_st_pickups), s.pickups.toString(), getString(R.string.wp_st_pickups_hint))
            row(getString(R.string.wp_st_longest), dur(s.longestSessionMs))
            row(getString(R.string.wp_st_late), dur(s.lateNightMs), getString(R.string.wp_st_late_hint))
        }
        if (s.apps.isNotEmpty()) page.card {
            header(getString(R.string.wp_st_apps))
            s.apps.take(12).forEach { a ->
                row(container.screenTime.label(a.pkg), dur(a.ms), kindLabel(a.kind)) { chooseKind(a.pkg) }
            }
            note(getString(R.string.wp_st_apps_note))
        }
        page.card {
            header(getString(R.string.wp_st_goals))
            val prefs = container.prefs
            slider(getString(R.string.wp_st_budget), prefs.screenBudgetMin, 480, 30, { min(it.coerceAtLeast(30)) }) { prefs.screenBudgetMin = it.coerceAtLeast(30); goalsChanged() }
            slider(getString(R.string.wp_st_focus), prefs.screenBreakMin, 180, 15, { min(it.coerceAtLeast(15)) }) { prefs.screenBreakMin = it.coerceAtLeast(15); goalsChanged() }
            slider(getString(R.string.wp_st_mindful), prefs.screenPurposePct, 100, 5, { "${it.coerceIn(10, 100)}%" }) { prefs.screenPurposePct = it.coerceIn(10, 100); goalsChanged() }
            row(getString(R.string.wp_st_wellbeing)) { openWellbeing() }
        }
    }

    /** The rings large, the score in dots in the middle, a legend underneath. */
    private fun hero(s: ScreenScore.Summary, ink: Int): LinearLayout {
        val ctx = requireContext()
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, d(8), 0, d(8)) }
        val box = FrameLayout(ctx)
        val rings = ScreenRings(ctx, big = true).apply { setInk(ink); set(s.budget, s.focus, s.mindful) }
        box.addView(rings, FrameLayout.LayoutParams(d(210), d(210)))
        val mid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val score = DotMatrixView(ctx).apply { text = s.score.toString(); cellPx = 6f * resources.displayMetrics.density; fill = 0.84f; color = ink }
        mid.addView(score, LinearLayout.LayoutParams(-2, -2))
        mid.addView(caption(getString(R.string.wp_st_score), ink), LinearLayout.LayoutParams(-2, -2).apply { topMargin = d(10) })
        box.addView(mid, FrameLayout.LayoutParams(d(210), d(210)))
        col.addView(box, LinearLayout.LayoutParams(d(210), d(210)))
        // one column per ring, outer to inner: what it measures, where it stands, a check once it's closed
        val legend = LinearLayout(ctx).apply { setPadding(d(4), d(16), d(4), 0) }
        listOf(
            Triple(R.string.wp_st_budget, if (s.overBudget) getString(R.string.wp_st_over_short) else getString(R.string.wp_st_left_short, dur(s.leftMs)), s.budget),
            Triple(R.string.wp_st_focus, "${dur(s.longestBreakMs)} / ${min(s.goals.breakMin)}", s.focus),
            Triple(R.string.wp_st_mindful, "${Math.round(s.purposeShare * 100)}% / ${s.goals.purposePct}%", s.mindful),
        ).forEachIndexed { i, (label, value, ring) ->
            val c = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            c.addView(caption("${i + 1} · ${getString(label)}", ink).apply { gravity = Gravity.CENTER })
            c.addView(TextView(ctx).apply {
                text = value + if (ring >= 1f) "  ✓" else ""
                textSize = 14f; setTextColor(if (ring >= 1f) app.olauncher.features.widgets.ui.WidgetDesign.ACCENT else ink)
                gravity = Gravity.CENTER; maxLines = 1
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(5) })
            legend.addView(c, LinearLayout.LayoutParams(0, -2, 1f))
        }
        col.addView(legend, LinearLayout.LayoutParams(-1, -2))
        heroRings = rings; heroScore = score
        return col
    }

    /** Minutes in each hour as dot columns, 0–24. */
    private fun rhythm(s: ScreenScore.Summary, ink: Int): LinearLayout {
        val ctx = requireContext()
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(d(4), d(4), d(4), d(10)) }
        val peak = (s.hourly.maxOrNull() ?: 0).coerceAtLeast(10)
        val nowHour = java.time.LocalTime.now().hour
        col.addView(DotBars(ctx, s.hourly.map { it.toFloat() / peak }, rows = 6, emptyAtZero = true, highlight = nowHour).apply { setInk(ink) },
            LinearLayout.LayoutParams(-1, d(64)))
        val labels = LinearLayout(ctx)
        (0 until 24).forEach { h -> labels.addView(caption(if (h % 6 == 0) "%02d".format(h) else "", ink).apply { gravity = Gravity.START }, LinearLayout.LayoutParams(0, -2, 1f)) }
        col.addView(labels, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(4) })
        return col
    }

    private fun caption(text: String, ink: Int) = TextView(requireContext()).apply {
        this.text = text.uppercase(); textSize = 10.5f; typeface = android.graphics.Typeface.MONOSPACE; letterSpacing = 0.12f
        setTextColor((ink and 0x00FFFFFF) or 0xA8000000.toInt()); maxLines = 1
    }

    private fun kindLabel(k: ScreenScore.Kind) = getString(when (k) {
        ScreenScore.Kind.PULL -> R.string.wp_st_kind_pull
        ScreenScore.Kind.PURPOSE -> R.string.wp_st_kind_purpose
        ScreenScore.Kind.NEUTRAL -> R.string.wp_st_kind_neutral
    })

    private fun chooseKind(pkg: String) {
        val kinds = listOf(ScreenScore.Kind.PURPOSE, ScreenScore.Kind.PULL, ScreenScore.Kind.NEUTRAL)
        requireContext().chooseOne(R.string.wp_st_kind_title, kinds.map { kindLabel(it) } + getString(R.string.wp_st_kind_auto)) { i ->
            container.prefs.setAppKind(pkg, kinds.getOrNull(i))
            container.screenTime.invalidate()
            load(force = true)
        }
    }

    /** Goals move the rings live; the full page is rebuilt when you come back. */
    private fun goalsChanged() {
        goalJob?.cancel()
        goalJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(350)
            val s = container.screenTime.today(force = true) ?: return@launch
            summary = s
            heroRings?.set(s.budget, s.focus, s.mindful)
            heroScore?.text = s.score.toString()
        }
    }

    private fun openWellbeing() {
        val intent = Intent().setClassName("com.google.android.apps.wellbeing", "com.google.android.apps.wellbeing.settings.TopLevelSettingsActivity")
        runCatching { startActivity(intent) }.onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    }

    override fun onDestroyView() { heroRings = null; heroScore = null; super.onDestroyView() }
}
