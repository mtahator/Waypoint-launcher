package app.olauncher.features.appearance.ui

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import app.olauncher.core.WaypointPrefs
import app.olauncher.features.appearance.domain.AppearanceState
import app.olauncher.helper.isDarkThemeOn

/**
 * Window look: wallpaper (optionally blurred by Glass) or, for monochrome / OLED black, an opaque
 * neutral background. A toggle is one window flag + background swap: no recreation, no GPU filters.
 */
class AppearanceController(private val prefs: WaypointPrefs) {

    fun apply(activity: Activity) {
        val state = AppearanceState.resolve(prefs.launcherMonochrome, prefs.oledBlack, activity.isDarkThemeOn())
        val window = activity.window
        if (state.showWallpaper) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            if (prefs.glassTheme) GlassStyler.applyWindow(window)
            else {
                GlassStyler.clearWindow(window)
                window.setBackgroundDrawable(ColorDrawable(AppearanceState.TRANSPARENT))
            }
        } else {
            GlassStyler.clearWindow(window)
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(ColorDrawable(state.backgroundArgb))
        }
    }

    /** @return the new monochrome value. */
    fun setMonochrome(on: Boolean): Boolean {
        prefs.launcherMonochrome = on
        return on
    }

    fun toggleMonochrome(): Boolean = setMonochrome(!prefs.launcherMonochrome)
}
