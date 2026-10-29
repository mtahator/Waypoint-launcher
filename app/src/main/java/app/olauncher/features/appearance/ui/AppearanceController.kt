package app.olauncher.features.appearance.ui

import android.app.Activity
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.olauncher.core.WaypointPrefs
import app.olauncher.features.appearance.domain.AppearanceState
import app.olauncher.features.appearance.platform.BackdropDrawable
import app.olauncher.features.appearance.platform.GlassBackdrop
import app.olauncher.helper.isDarkThemeOn
import kotlinx.coroutines.launch

/**
 * Window look: system wallpaper, Glass (reeded-glass backdrop or blurred wallpaper), or for monochrome /
 * OLED black an opaque neutral background. A toggle is one window flag + background swap.
 */
class AppearanceController(private val prefs: WaypointPrefs, private val backdrop: GlassBackdrop) {

    fun apply(activity: Activity) {
        val dark = activity.isDarkThemeOn()
        val state = AppearanceState.resolve(prefs.launcherMonochrome, prefs.oledBlack, dark)
        val window = activity.window
        when {
            !state.showWallpaper -> {
                GlassStyler.clearWindow(window)
                window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                window.setBackgroundDrawable(ColorDrawable(state.backgroundArgb))
            }
            prefs.glassTheme && prefs.glassBackground != WaypointPrefs.BG_WALLPAPER -> {
                GlassStyler.clearWindow(window)
                window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                val bitmap = backdrop.cachedBitmap()
                if (bitmap != null) window.setBackgroundDrawable(BackdropDrawable(bitmap))
                else {
                    window.setBackgroundDrawable(ColorDrawable(if (dark) 0xFF0E0E0E.toInt() else 0xFFEDEDED.toInt()))
                    (activity as? LifecycleOwner)?.lifecycleScope?.launch {
                        if (backdrop.load() != null && !activity.isDestroyed) apply(activity)
                    }
                }
            }
            prefs.glassTheme -> {
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                window.setFormat(PixelFormat.TRANSLUCENT)
                GlassStyler.applyWindow(window)
            }
            else -> {
                GlassStyler.clearWindow(window)
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                // An opaque backdrop marks the surface opaque, and recreate() keeps that window: without this the
                // wallpaper stays hidden and old frames show through the transparent screens.
                window.setFormat(PixelFormat.TRANSLUCENT)
                window.setBackgroundDrawable(ColorDrawable(AppearanceState.TRANSPARENT))
            }
        }
    }

    /** @return the new monochrome value. */
    fun setMonochrome(on: Boolean): Boolean {
        prefs.launcherMonochrome = on
        return on
    }

    fun toggleMonochrome(): Boolean = setMonochrome(!prefs.launcherMonochrome)
}
