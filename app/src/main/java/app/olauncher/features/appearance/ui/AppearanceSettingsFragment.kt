package app.olauncher.features.appearance.ui

import android.view.Gravity
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.core.ui.ExternalFlow
import app.olauncher.core.ui.PageBuilder
import app.olauncher.core.ui.WpPageFragment
import app.olauncher.helper.copyToClipboard
import app.olauncher.helper.isDarkThemeOn
import app.olauncher.helper.showToast

class AppearanceSettingsFragment : WpPageFragment() {
    override val pageTitle = R.string.wp_appearance

    private fun alignmentLabel(g: Int) = getString(
        when (g) {
            Gravity.START -> R.string.wp_align_left
            Gravity.CENTER -> R.string.wp_align_center
            else -> R.string.wp_align_right
        }
    )

    private fun positionLabel(v: Int): String = when (v) {
        0 -> getString(R.string.wp_position_top)
        50 -> getString(R.string.wp_position_center)
        100 -> getString(R.string.wp_position_bottom)
        else -> "$v%"
    }

    override fun build(page: PageBuilder) {
        val prefs = container.prefs
        val upstream = Prefs(requireContext())
        page.card {
            header(getString(R.string.wp_home_layout))
            row(getString(R.string.wp_alignment), alignmentLabel(upstream.homeAlignment)) { r ->
                val next = when (upstream.homeAlignment) {
                    Gravity.END -> Gravity.START
                    Gravity.START -> Gravity.CENTER
                    else -> Gravity.END
                }
                upstream.homeAlignment = next
                r.value = alignmentLabel(next)
            }
            slider(getString(R.string.wp_apps_position), prefs.homeAppsPosition, max = 100, step = 5, label = { positionLabel(it) }) {
                upstream.homeBottomAlignment = false // the slider replaces upstream's top/bottom choice
                prefs.homeAppsPosition = it
            }
            note(getString(R.string.wp_apps_position_hint))
        }
        page.card {
            header(getString(R.string.wp_glass_theme))
            toggle(getString(R.string.wp_glass_theme), prefs.glassTheme, getString(R.string.wp_glass_theme_hint)) {
                prefs.glassTheme = it
                // Surfaces are styled at view creation; recreate once so every screen picks it up.
                requireActivity().recreate()
            }
        }
        page.card {
            header(getString(R.string.wp_launcher_look))
            toggle(getString(R.string.wp_launcher_monochrome), prefs.launcherMonochrome, getString(R.string.wp_launcher_monochrome_hint)) {
                container.appearance.setMonochrome(it)
                container.appearance.apply(requireActivity())
            }
            val oled = toggle(getString(R.string.wp_oled_black), prefs.oledBlack, getString(R.string.wp_oled_black_hint)) {
                prefs.oledBlack = it
                container.appearance.apply(requireActivity())
            }
            if (!requireContext().isDarkThemeOn()) oled.subtitle = getString(R.string.wp_oled_needs_dark)
        }
        page.card {
            header(getString(R.string.wp_system_grayscale))
            note(getString(R.string.wp_system_grayscale_explainer))
            val grayscale = container.grayscale
            grayscale.wellbeingIntent()?.let { intent ->
                row(getString(R.string.wp_open_bedtime)) { ExternalFlow.start(this@AppearanceSettingsFragment, intent) }
            }
            row(getString(R.string.wp_open_accessibility), subtitle = getString(R.string.wp_open_accessibility_hint)) {
                ExternalFlow.start(this@AppearanceSettingsFragment, grayscale.accessibilityIntent())
            }
        }
        page.card {
            header(getString(R.string.wp_advanced_grayscale))
            val grayscale = container.grayscale
            if (grayscale.isAvailable()) {
                toggle(getString(R.string.wp_system_grayscale), grayscale.isOn(), getString(R.string.wp_advanced_grayscale_on_hint)) {
                    if (!grayscale.set(it)) requireContext().showToast(R.string.wp_grayscale_failed)
                }
            } else {
                note(getString(R.string.wp_advanced_grayscale_explainer))
                row(grayscale.adbCommand, subtitle = getString(R.string.wp_tap_to_copy)) {
                    requireContext().copyToClipboard(grayscale.adbCommand)
                }
            }
        }
    }
}
