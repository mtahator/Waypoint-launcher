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
 * loaded at start), keys prefixed `WP_`. Values used on the home path are read once and cached.
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

    // ---- Gestures
    var cornerSwipeEnabled: Boolean
        get() = prefs.getBoolean(CORNER_ENABLED, true)
        set(value) = prefs.edit { putBoolean(CORNER_ENABLED, value) }

    var cornerSwipeCorner: Corner
        get() = if (prefs.getString(CORNER_SIDE, "end") == "start") Corner.TOP_START else Corner.TOP_END
        set(value) = prefs.edit { putString(CORNER_SIDE, if (value == Corner.TOP_START) "start" else "end") }

    var cornerSwipeAction: GestureAction
        get() = GestureAction.decode(prefs.getString(CORNER_ACTION, null))
        set(value) = prefs.edit { putString(CORNER_ACTION, GestureAction.encode(value)) }

    /** Distance multiplier: 0 = sensitive (48dp), 1 = normal (64dp), 2 = firm (88dp). */
    var cornerSensitivity: Int
        get() = prefs.getInt(CORNER_SENSITIVITY, 1).coerceIn(0, 2)
        set(value) = prefs.edit { putInt(CORNER_SENSITIVITY, value.coerceIn(0, 2)) }

    // ---- Automation (cached counts keep the engine and DB off the home path when unused)
    var enabledRuleCount: Int
        get() = prefs.getInt(ENABLED_RULES, 0)
        set(value) = prefs.edit { putInt(ENABLED_RULES, value) }

    var enabledRuleTypes: Set<String>
        get() = prefs.getStringSet(ENABLED_RULE_TYPES, emptySet()).orEmpty().toSet()
        set(value) = prefs.edit { putStringSet(ENABLED_RULE_TYPES, value) }

    var scheduledAlarmAt: Long
        get() = prefs.getLong(ALARM_AT, 0L)
        set(value) = prefs.edit { putLong(ALARM_AT, value) }

    var scheduledAlarmRules: Set<String>
        get() = prefs.getStringSet(ALARM_RULES, emptySet()).orEmpty().toSet()
        set(value) = prefs.edit { putStringSet(ALARM_RULES, value) }

    // ---- Notification capture / ledger
    var ledgerCaptureEnabled: Boolean
        get() = prefs.getBoolean(LEDGER_CAPTURE, false)
        set(value) = prefs.edit { putBoolean(LEDGER_CAPTURE, value) }

    var capturePackages: Set<String>
        get() = prefs.getStringSet(CAPTURE_PACKAGES, emptySet()).orEmpty().toSet()
        set(value) = prefs.edit { putStringSet(CAPTURE_PACKAGES, value) }

    /** Packages watched by enabled notification rules (kept in sync on rule CRUD). */
    var rulePackages: Set<String>
        get() = prefs.getStringSet(RULE_PACKAGES, emptySet()).orEmpty().toSet()
        set(value) = prefs.edit { putStringSet(RULE_PACKAGES, value) }

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.registerOnSharedPreferenceChangeListener(l)
    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.unregisterOnSharedPreferenceChangeListener(l)

    companion object {
        const val WORLD_CLOCKS_ON_HOME = "WP_WORLD_CLOCKS_ON_HOME"
        const val WORLD_CLOCKS = "WP_WORLD_CLOCKS"
        const val MONOCHROME = "WP_MONOCHROME"
        const val OLED_BLACK = "WP_OLED_BLACK"
        const val GLASS_THEME = "WP_GLASS_THEME"
        const val CORNER_ENABLED = "WP_CORNER_ENABLED"
        const val CORNER_SIDE = "WP_CORNER_SIDE"
        const val CORNER_ACTION = "WP_CORNER_ACTION"
        const val CORNER_SENSITIVITY = "WP_CORNER_SENSITIVITY"
        const val ENABLED_RULES = "WP_ENABLED_RULES"
        const val ENABLED_RULE_TYPES = "WP_ENABLED_RULE_TYPES"
        const val ALARM_AT = "WP_ALARM_AT"
        const val ALARM_RULES = "WP_ALARM_RULES"
        const val LEDGER_CAPTURE = "WP_LEDGER_CAPTURE"
        const val CAPTURE_PACKAGES = "WP_CAPTURE_PACKAGES"
        const val RULE_PACKAGES = "WP_RULE_PACKAGES"
    }
}
