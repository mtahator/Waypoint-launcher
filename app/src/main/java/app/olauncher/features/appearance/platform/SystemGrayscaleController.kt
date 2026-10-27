package app.olauncher.features.appearance.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import app.olauncher.BuildConfig

/**
 * Best-effort, opt-in system-wide grayscale (spec 7.3.3). Works only after the user grants
 * WRITE_SECURE_SETTINGS themselves over ADB; writes the documented-by-string daltonizer keys.
 * Never uses an accessibility service. Device support varies: see docs/grayscale-compat.md.
 */
class SystemGrayscaleController(private val context: Context) {

    val adbCommand get() = "adb shell pm grant ${context.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}"

    fun isAvailable(): Boolean = BuildConfig.ENABLE_SYSTEM_GRAYSCALE_ADB &&
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun isOn(): Boolean = try {
        Settings.Secure.getInt(context.contentResolver, KEY_ENABLED, 0) == 1 &&
            Settings.Secure.getInt(context.contentResolver, KEY_MODE, -1) == MODE_MONOCHROMACY
    } catch (_: SecurityException) {
        false
    }

    /** @return false when unavailable or the platform refused the write. */
    fun set(on: Boolean): Boolean {
        if (!isAvailable()) return false
        return try {
            val resolver = context.contentResolver
            if (on) Settings.Secure.putInt(resolver, KEY_MODE, MODE_MONOCHROMACY)
            Settings.Secure.putInt(resolver, KEY_ENABLED, if (on) 1 else 0)
        } catch (_: SecurityException) {
            false
        }
    }

    fun toggle(): Boolean = set(!isOn())

    /** Official path (7.3.2): Bedtime / Digital Wellbeing, when resolvable on this device. */
    fun wellbeingIntent(): Intent? = WELLBEING_COMPONENTS.asSequence()
        .map { (pkg, cls) -> Intent().setClassName(pkg, cls) }
        .firstOrNull { it.resolveActivity(context.packageManager) != null }

    fun accessibilityIntent() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    companion object {
        private const val KEY_ENABLED = "accessibility_display_daltonizer_enabled"
        private const val KEY_MODE = "accessibility_display_daltonizer"
        private const val MODE_MONOCHROMACY = 0
        private val WELLBEING_COMPONENTS = listOf(
            "com.google.android.apps.wellbeing" to "com.google.android.apps.wellbeing.settings.TopLevelSettingsActivity",
            "com.samsung.android.forest" to "com.samsung.android.forest.launcher.LauncherActivity",
        )
    }
}
