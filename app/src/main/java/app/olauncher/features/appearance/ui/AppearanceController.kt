package app.olauncher.features.appearance.ui

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import app.olauncher.core.WaypointPrefs
import app.olauncher.features.appearance.domain.AppearanceState
import app.olauncher.helper.isDarkThemeOn

/**
 * Launcher monochrome / OLED black. A toggle is a flag write plus one window flag + background
 * swap: no activity recreation, no offscreen layers, no ColorMatrix.
 */
class AppearanceController(private val prefs: WaypointPrefs) {

    fun apply(activity: Activity) {
        val state = AppearanceState.resolve(prefs.launcherMonochrome, prefs.oledBlack, activity.isDarkThemeOn())
        val window = activity.window
        if (state.showWallpaper) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(ColorDrawable(AppearanceState.TRANSPARENT))
        } else {
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
