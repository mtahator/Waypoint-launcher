package app.olauncher.features.appearance.ui

import android.net.Uri
import android.view.Gravity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import app.olauncher.core.WaypointPrefs
import app.olauncher.core.ui.chooseOne
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

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        ExternalFlow.active = false
        if (uri == null) return@registerForActivityResult
        launchUi {
            if (container.backdrop.importPhoto(uri)) {
                container.prefs.glassBackground = WaypointPrefs.BG_PHOTO
                refreshBackdrop()
            } else requireContext().showToast(R.string.wp_photo_failed)
        }
    }

    private fun backgroundLabel(v: String) = getString(
        when (v) {
            WaypointPrefs.BG_PHOTO -> R.string.wp_bg_photo
            WaypointPrefs.BG_WALLPAPER -> R.string.wp_bg_wallpaper
            else -> R.string.wp_bg_aurora
        }
    )

    private fun ribLabel(v: Int) = getString(
        when (v) {
            0 -> R.string.wp_ribs_narrow
            2 -> R.string.wp_ribs_wide
            else -> R.string.wp_ribs_normal
        }
    )

    private fun chooseBackground() {
        val prefs = container.prefs
        val options = listOf<Pair<String, () -> Unit>>(
            getString(R.string.wp_bg_aurora) to { prefs.glassBackground = WaypointPrefs.BG_AURORA; refreshBackdrop() },
            getString(R.string.wp_bg_photo_pick) to {
                ExternalFlow.active = true
                pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            getString(R.string.wp_bg_wallpaper) to { prefs.glassBackground = WaypointPrefs.BG_WALLPAPER; refreshBackdrop() },
        )
        requireContext().chooseOne(R.string.wp_glass_background, options.map { it.first }) { options[it].second() }
    }

    /** Re-renders the reeded glass off the main thread, then swaps the window background. */
    private fun refreshBackdrop() = launchUi {
        if (container.prefs.glassBackground != WaypointPrefs.BG_WALLPAPER) container.backdrop.load()
        container.appearance.apply(requireActivity())
        rebuild()
    }

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
            header(getString(R.string.wp_theme))
            toggle(getString(R.string.wp_glass_theme), prefs.glassTheme, getString(R.string.wp_glass_theme_hint)) {
                prefs.glassTheme = it
                // Surfaces are styled at view creation; recreate once so every screen picks it up.
                requireActivity().recreate()
            }
            if (prefs.glassTheme) {
                row(getString(R.string.wp_glass_background), backgroundLabel(prefs.glassBackground)) { chooseBackground() }
                if (prefs.glassBackground != WaypointPrefs.BG_WALLPAPER) {
                    toggle(getString(R.string.wp_glass_mono), prefs.glassMonochrome, getString(R.string.wp_glass_mono_hint)) {
                        prefs.glassMonochrome = it
                        refreshBackdrop()
                    }
                    row(getString(R.string.wp_glass_ribs), ribLabel(prefs.glassRibWidth)) { r ->
                        prefs.glassRibWidth = (prefs.glassRibWidth + 1) % 3
                        r.value = ribLabel(prefs.glassRibWidth)
                        refreshBackdrop()
                    }
                }
                note(getString(if (prefs.glassBackground == WaypointPrefs.BG_WALLPAPER) R.string.wp_glass_wallpaper_note else R.string.wp_glass_backdrop_note))
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
