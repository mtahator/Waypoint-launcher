package app.olauncher.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import app.olauncher.features.gestures.domain.Corner
import app.olauncher.features.gestures.domain.GestureAction
import app.olauncher.features.worldclock.domain.WorldClockCodec
import app.olauncher.features.worldclock.domain.WorldClockEntry

/**
 * Waypoint settings. Same SharedPreferences file as upstream [app.olauncher.data.Prefs] (already
 * loaded at start), keys prefixed `WP_`.
 */
class WaypointPrefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("app.olauncher", 0)

    // ---- World clock
    var worldClocksOnHome: Boolean
        get() = prefs.getBoolean(WORLD_CLOCKS_ON_HOME, true)
        set(value) = prefs.edit { putBoolean(WORLD_CLOCKS_ON_HOME, value) }

    var worldClocks: List<WorldClockEntry>
        get() = WorldClockCodec.decode(prefs.getString(WORLD_CLOCKS, null))
        set(value) = prefs.edit { putString(WORLD_CLOCKS, WorldClockCodec.encode(value)) }

    val worldClocksRaw: String? get() = prefs.getString(WORLD_CLOCKS, null)

    // ---- Home layout
    /** Vertical position of the home app list: 0 = top, 50 = centre (upstream), 100 = bottom. */
    var homeAppsPosition: Int
        get() = prefs.getInt(HOME_APPS_POSITION, 50).coerceIn(0, 100)
        set(value) = prefs.edit { putInt(HOME_APPS_POSITION, value.coerceIn(0, 100)) }

    // ---- Appearance
    var launcherMonochrome: Boolean
        get() = prefs.getBoolean(MONOCHROME, false)
        set(value) = prefs.edit { putBoolean(MONOCHROME, value) }

    var oledBlack: Boolean
        get() = prefs.getBoolean(OLED_BLACK, false)
        set(value) = prefs.edit { putBoolean(OLED_BLACK, value) }

    var glassTheme: Boolean
        get() = prefs.getBoolean(GLASS_THEME, false)
        set(value) = prefs.edit { putBoolean(GLASS_THEME, value) }

    // ---- Gestures (corner swipe is opt-in)
    var cornerSwipeEnabled: Boolean
        get() = prefs.getBoolean(CORNER_ENABLED_V2, false)
        set(value) = prefs.edit { putBoolean(CORNER_ENABLED_V2, value) }

    var cornerSwipeCorner: Corner
        get() = if (prefs.getString(CORNER_SIDE, "end") == "start") Corner.TOP_START else Corner.TOP_END
        set(value) = prefs.edit { putString(CORNER_SIDE, if (value == Corner.TOP_START) "start" else "end") }

    var cornerSwipeAction: GestureAction
        get() = GestureAction.decode(prefs.getString(CORNER_ACTION, null))
        set(value) = prefs.edit { putString(CORNER_ACTION, GestureAction.encode(value)) }

    /** 0 = sensitive (48dp), 1 = normal (64dp), 2 = firm (88dp). */
    var cornerSensitivity: Int
        get() = prefs.getInt(CORNER_SENSITIVITY, 1).coerceIn(0, 2)
        set(value) = prefs.edit { putInt(CORNER_SENSITIVITY, value.coerceIn(0, 2)) }

    // ---- Notification capture / ledger
    var ledgerCaptureEnabled: Boolean
        get() = prefs.getBoolean(LEDGER_CAPTURE, false)
        set(value) = prefs.edit { putBoolean(LEDGER_CAPTURE, value) }

    var capturePackages: Set<String>
        get() = prefs.getStringSet(CAPTURE_PACKAGES, emptySet()).orEmpty().toSet()
        set(value) = prefs.edit { putStringSet(CAPTURE_PACKAGES, value) }

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.registerOnSharedPreferenceChangeListener(l)
    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.unregisterOnSharedPreferenceChangeListener(l)

    /**
     * One-time fixes for earlier builds: the corner zone used to reach the very top edge when the status bar
     * was hidden, so pulling down notifications could silently turn on monochrome (which hides the wallpaper).
     * Also drops keys of the removed automation feature.
     */
    fun migrate() {
        if (prefs.getInt(MIGRATION, 0) >= 2) return
        prefs.edit {
            putBoolean(MONOCHROME, false)
            OBSOLETE_KEYS.forEach { remove(it) }
            putInt(MIGRATION, 2)
        }
    }

    companion object {
        const val WORLD_CLOCKS_ON_HOME = "WP_WORLD_CLOCKS_ON_HOME"
        const val WORLD_CLOCKS = "WP_WORLD_CLOCKS"
        const val HOME_APPS_POSITION = "WP_HOME_APPS_POSITION"
        const val MONOCHROME = "WP_MONOCHROME"
        const val OLED_BLACK = "WP_OLED_BLACK"
        const val GLASS_THEME = "WP_GLASS_THEME"
        const val CORNER_ENABLED_V2 = "WP_CORNER_ENABLED_V2"
        const val CORNER_SIDE = "WP_CORNER_SIDE"
        const val CORNER_ACTION = "WP_CORNER_ACTION"
        const val CORNER_SENSITIVITY = "WP_CORNER_SENSITIVITY"
        const val LEDGER_CAPTURE = "WP_LEDGER_CAPTURE"
        const val CAPTURE_PACKAGES = "WP_CAPTURE_PACKAGES"
        private const val MIGRATION = "WP_MIGRATION"
        private const val CORNER_ENABLED_V1 = "WP_CORNER_ENABLED"
        private val OBSOLETE_KEYS = listOf(
            CORNER_ENABLED_V1, "WP_ENABLED_RULES", "WP_ENABLED_RULE_TYPES", "WP_ALARM_AT", "WP_ALARM_RULES", "WP_RULE_PACKAGES",
        )
    }
}
