package app.olauncher.features.gestures.ui

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.olauncher.R
import app.olauncher.core.ui.PageBuilder
import app.olauncher.core.ui.WpPageFragment
import app.olauncher.core.ui.chooseOne
import app.olauncher.core.ui.pickApp
import app.olauncher.features.appearance.ui.GlassStyler
import app.olauncher.features.gestures.domain.Corner
import app.olauncher.features.gestures.domain.CornerGestureRecognizer
import app.olauncher.features.gestures.domain.GestureAction
import app.olauncher.features.gestures.domain.SafeInsets

class GestureSettingsFragment : WpPageFragment() {
    override val pageTitle = R.string.wp_gestures
    override val pageSubtitle = R.string.wp_gestures_subtitle

    override fun build(page: PageBuilder) {
        val prefs = container.prefs
        page.card {
            header(getString(R.string.wp_corner_swipe))
            toggle(getString(R.string.wp_enabled), prefs.cornerSwipeEnabled) { prefs.cornerSwipeEnabled = it }
            row(getString(R.string.wp_corner), cornerLabel(prefs.cornerSwipeCorner)) { r ->
                val next = if (prefs.cornerSwipeCorner == Corner.TOP_END) Corner.TOP_START else Corner.TOP_END
                prefs.cornerSwipeCorner = next
                r.value = cornerLabel(next)
            }
            row(getString(R.string.wp_action), actionLabel(prefs.cornerSwipeAction)) { chooseAction() }
            row(getString(R.string.wp_sensitivity), sensitivityLabel(prefs.cornerSensitivity)) { r ->
                val next = (prefs.cornerSensitivity + 1) % 3
                prefs.cornerSensitivity = next
                r.value = sensitivityLabel(next)
            }
            note(getString(R.string.wp_corner_swipe_hint))
        }
        page.card {
            header(getString(R.string.wp_try_it))
            add(tryArea())
        }
        page.card {
            header(getString(R.string.wp_side_swipes))
            note(getString(R.string.wp_side_swipes_hint))
        }
    }

    private fun cornerLabel(c: Corner) = getString(if (c == Corner.TOP_END) R.string.wp_top_end else R.string.wp_top_start)

    private fun sensitivityLabel(v: Int) = getString(
        when (v) {
            0 -> R.string.wp_sensitive
            2 -> R.string.wp_firm
            else -> R.string.wp_normal
        }
    )

    private fun actionLabel(a: GestureAction): String = when (a) {
        is GestureAction.OpenApp -> getString(R.string.wp_open_app_named, appLabel(a.packageName))
        GestureAction.ToggleLauncherMonochrome -> getString(R.string.wp_action_toggle_monochrome)
        GestureAction.ToggleSystemGrayscale -> getString(R.string.wp_action_toggle_grayscale)
        GestureAction.OpenWorldClockPanel -> getString(R.string.wp_open_world_clock)
        GestureAction.None -> getString(R.string.wp_action_none)
    }

    private fun appLabel(pkg: String) = runCatching {
        val pm = requireContext().packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun chooseAction() {
        val prefs = container.prefs
        val options = listOf(
            getString(R.string.wp_action_toggle_monochrome) to { prefs.cornerSwipeAction = GestureAction.ToggleLauncherMonochrome; rebuild() },
            getString(R.string.wp_action_toggle_grayscale) to { prefs.cornerSwipeAction = GestureAction.ToggleSystemGrayscale; rebuild() },
            getString(R.string.wp_open_world_clock) to { prefs.cornerSwipeAction = GestureAction.OpenWorldClockPanel; rebuild() },
            getString(R.string.wp_action_open_app) to {
                requireContext().pickApp(this) { app ->
                    prefs.cornerSwipeAction = GestureAction.OpenApp(app.packageName, app.userSerial)
                    rebuild()
                }
            },
            getString(R.string.wp_action_none) to { prefs.cornerSwipeAction = GestureAction.None; rebuild() },
        )
        requireContext().chooseOne(R.string.wp_action, options.map { it.first }) { options[it].second() }
    }

    /** A sandbox using the exact recognizer and thresholds of the home screen. */
    @SuppressLint("ClickableViewAccessibility")
    private fun tryArea(): FrameLayout {
        val ctx = requireContext()
        val prefs = container.prefs
        val density = resources.displayMetrics.density
        val area = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (220 * density).toInt())
            background = GlassStyler.card(ctx)
            contentDescription = getString(R.string.wp_try_it_description)
        }
        val label = TextView(ctx, null, 0, R.style.TextSmall).apply {
            text = getString(R.string.wp_try_it_idle)
            gravity = Gravity.CENTER
            setShadowLayer(0f, 0f, 0f, 0)
        }
        area.addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val recognizer = CornerSwipeLayout.recognizerFor(ctx, prefs.cornerSensitivity)
        var downX = 0f
        var downY = 0f
        var downAt = 0L
        var owned = false
        area.setOnTouchListener { v, e ->
            val isRtl = v.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
            val zone = recognizer.zone(v.width, prefs.cornerSwipeCorner, isRtl, SafeInsets())
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; downAt = SystemClock.uptimeMillis()
                    owned = recognizer.ownsDown(zone, e.x, e.y)
                    label.text = getString(if (owned) R.string.wp_try_it_in_zone else R.string.wp_try_it_outside)
                }
                MotionEvent.ACTION_UP -> if (owned) {
                    val ok = recognizer.isRecognized(
                        e.x - downX, e.y - downY, SystemClock.uptimeMillis() - downAt,
                        recognizer.inwardSign(prefs.cornerSwipeCorner, isRtl),
                    )
                    label.text = getString(if (ok) R.string.wp_try_it_recognized else R.string.wp_try_it_not_recognized)
                    if (ok) v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                }
            }
            true
        }
        return area
    }
}
