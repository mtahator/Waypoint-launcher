package app.olauncher.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import app.olauncher.features.assistant.domain.ApiFormat
import app.olauncher.features.assistant.domain.AssistantMode
import app.olauncher.features.assistant.domain.ImageApi
import app.olauncher.features.gestures.domain.Corner
import app.olauncher.features.space.domain.TranscriptionApi
import app.olauncher.features.websearch.domain.SearchEngine
import app.olauncher.features.websearch.domain.WebSearchMode
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

    /** Glass background: "aurora" (built-in), "photo" (user-picked), or "wallpaper" (system wallpaper, blurred). */
    var glassBackground: String
        get() = prefs.getString(GLASS_BACKGROUND, BG_AURORA) ?: BG_AURORA
        set(value) = prefs.edit { putString(GLASS_BACKGROUND, value) }

    /** Nothing-style grey glass. */
    var glassMonochrome: Boolean
        get() = prefs.getBoolean(GLASS_MONO, false)
        set(value) = prefs.edit { putBoolean(GLASS_MONO, value) }

    /** Reed (rib) width: 0 narrow, 1 normal, 2 wide. */
    var glassRibWidth: Int
        get() = prefs.getInt(GLASS_RIB, 1).coerceIn(0, 2)
        set(value) = prefs.edit { putInt(GLASS_RIB, value.coerceIn(0, 2)) }

    /** Changes whenever the user picks a new photo (part of the render cache key). */
    var glassPhotoStamp: Long
        get() = prefs.getLong(GLASS_PHOTO_STAMP, 0L)
        set(value) = prefs.edit { putLong(GLASS_PHOTO_STAMP, value) }

    // ---- Quick answers & graphing
    /** Exchange rates may be fetched (only when a currency conversion is typed). */
    var onlineRates: Boolean
        get() = prefs.getBoolean(ONLINE_RATES, true)
        set(value) = prefs.edit { putBoolean(ONLINE_RATES, value) }

    /** Grapher lines, one expression per entry. */
    var graphLines: List<String>
        get() = prefs.getString(GRAPH_LINES, null)?.split(GRAPH_SEP) ?: listOf("")
        set(value) = prefs.edit { putString(GRAPH_LINES, value.joinToString(GRAPH_SEP)) }

    // ---- Assistant ("?" in the drawer search)
    var assistantMode: AssistantMode
        get() = runCatching { AssistantMode.valueOf(prefs.getString(ASSIST_MODE, null) ?: "") }.getOrDefault(AssistantMode.GEMINI_APP)
        set(value) = prefs.edit { putString(ASSIST_MODE, value.name) }

    var apiFormat: ApiFormat
        get() = runCatching { ApiFormat.valueOf(prefs.getString(API_FORMAT, null) ?: "") }.getOrDefault(ApiFormat.OPENAI)
        set(value) = prefs.edit { putString(API_FORMAT, value.name) }

    var apiPreset: String
        get() = prefs.getString(API_PRESET, null) ?: ""
        set(value) = prefs.edit { putString(API_PRESET, value) }

    var apiBaseUrl: String
        get() = prefs.getString(API_BASE_URL, null) ?: ""
        set(value) = prefs.edit { putString(API_BASE_URL, value.trim()) }

    var apiModel: String
        get() = prefs.getString(API_MODEL, null) ?: ""
        set(value) = prefs.edit { putString(API_MODEL, value.trim()) }

    /** Image model for `/image` in Chat (same provider and key); blank = image generation off. */
    var imageModel: String
        get() = prefs.getString(IMAGE_MODEL, null) ?: ""
        set(value) = prefs.edit { putString(IMAGE_MODEL, value.trim()) }

    var imageApi: ImageApi
        get() = runCatching { ImageApi.valueOf(prefs.getString(IMAGE_API, null) ?: "") }.getOrDefault(ImageApi.AUTO)
        set(value) = prefs.edit { putString(IMAGE_API, value.name) }

    var imageSize: String
        get() = prefs.getString(IMAGE_SIZE, null) ?: "1024x1024"
        set(value) = prefs.edit { putString(IMAGE_SIZE, value) }

    /** Things the assistant may use when relevant (name, work, preferences…). */
    var aboutMe: String
        get() = prefs.getString(ABOUT_ME, null) ?: ""
        set(value) = prefs.edit { putString(ABOUT_ME, value.trim()) }

    var useAboutMe: Boolean
        get() = prefs.getBoolean(USE_ABOUT_ME, true)
        set(value) = prefs.edit { putBoolean(USE_ABOUT_ME, value) }

    /** After a chat, the model may add or correct facts in the memory about you. */
    var learnFromChats: Boolean
        get() = prefs.getBoolean(LEARN_FROM_CHATS, true)
        set(value) = prefs.edit { putBoolean(LEARN_FROM_CHATS, value) }

    /** The assistant's tools: location, places, routes, weather, reminders, Space search. */
    var toolsEnabled: Boolean
        get() = prefs.getBoolean(TOOLS_ENABLED, true)
        set(value) = prefs.edit { putBoolean(TOOLS_ENABLED, value) }

    /** "url|model" of a provider that rejected tool calls; tools are skipped for it until the setting changes. */
    var toolsUnsupported: String
        get() = prefs.getString(TOOLS_UNSUPPORTED, null) ?: ""
        set(value) = prefs.edit { putString(TOOLS_UNSUPPORTED, value) }

    /** Cloud speech-to-text model for Space (only used when transcription isn't on-device). */
    var transcribeModel: String
        get() = prefs.getString(TRANSCRIBE_MODEL, null) ?: ""
        set(value) = prefs.edit { putString(TRANSCRIBE_MODEL, value.trim()) }

    var transcribeApi: TranscriptionApi
        get() = runCatching { TranscriptionApi.valueOf(prefs.getString(TRANSCRIBE_API, null) ?: "") }.getOrDefault(TranscriptionApi.LOCAL)
        set(value) = prefs.edit { putString(TRANSCRIBE_API, value.name) }

    /** BCP-47 language for on-device transcription (default: the phone's language). */
    var transcribeLanguage: String
        get() = prefs.getString(TRANSCRIBE_LANGUAGE, null) ?: java.util.Locale.getDefault().toLanguageTag()
        set(value) = prefs.edit { putString(TRANSCRIBE_LANGUAGE, value) }

    /** After transcribing, let the chat model write a title and summary (sends the text to the API). */
    var spaceSummarize: Boolean
        get() = prefs.getBoolean(SPACE_SUMMARIZE, true)
        set(value) = prefs.edit { putBoolean(SPACE_SUMMARIZE, value) }

    /** After transcribing, let the chat model fix words, punctuation and paragraphs (keeps the original too). */
    var spaceCleanup: Boolean
        get() = prefs.getBoolean(SPACE_CLEANUP, true)
        set(value) = prefs.edit { putBoolean(SPACE_CLEANUP, value) }

    /** Add new screenshots to Space automatically (needs photo access). */
    var autoScreenshots: Boolean
        get() = prefs.getBoolean(AUTO_SCREENSHOTS, false)
        set(value) = prefs.edit { putBoolean(AUTO_SCREENSHOTS, value) }

    /** MediaStore DATE_ADDED (seconds) of the newest screenshot already imported. */
    var lastScreenshotSeen: Long
        get() = prefs.getLong(LAST_SCREENSHOT, 0L)
        set(value) = prefs.edit { putLong(LAST_SCREENSHOT, value) }

    /** Wayfinder button on Home: "left", "right" (bottom corners) or "off". */
    var wayfinderShortcut: String
        get() = prefs.getString(COMPASS_SHORTCUT, null) ?: "left"
        set(value) = prefs.edit { putString(COMPASS_SHORTCUT, value) }

    var systemPrompt: String
        get() = prefs.getString(SYSTEM_PROMPT, null) ?: ""
        set(value) = prefs.edit { putString(SYSTEM_PROMPT, value) }

    /** Web search for Wayfinder: off, auto (the model decides) or always. */
    var webSearchMode: WebSearchMode
        get() = runCatching { WebSearchMode.valueOf(prefs.getString(WEB_SEARCH, null) ?: "") }.getOrDefault(WebSearchMode.AUTO)
        set(value) = prefs.edit { putString(WEB_SEARCH, value.name) }

    var searchEngine: SearchEngine
        get() = runCatching { SearchEngine.valueOf(prefs.getString(SEARCH_ENGINE, null) ?: "") }.getOrDefault(SearchEngine.DUCKDUCKGO)
        set(value) = prefs.edit { putString(SEARCH_ENGINE, value.name) }

    internal var braveKeySealed: String?
        get() = prefs.getString(BRAVE_KEY_SEALED, null)
        set(value) = prefs.edit { if (value == null) remove(BRAVE_KEY_SEALED) else putString(BRAVE_KEY_SEALED, value) }

    /** Encrypted with a non-exportable Android Keystore key (see ApiKeyStore). */
    internal var apiKeySealed: String?
        get() = prefs.getString(API_KEY_SEALED, null)
        set(value) = prefs.edit { if (value == null) remove(API_KEY_SEALED) else putString(API_KEY_SEALED, value) }

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
        const val GLASS_BACKGROUND = "WP_GLASS_BACKGROUND"
        const val GLASS_MONO = "WP_GLASS_MONO"
        const val GLASS_RIB = "WP_GLASS_RIB"
        const val GLASS_PHOTO_STAMP = "WP_GLASS_PHOTO_STAMP"
        const val ONLINE_RATES = "WP_ONLINE_RATES"
        const val ASSIST_MODE = "WP_ASSIST_MODE"
        const val API_FORMAT = "WP_API_FORMAT"
        const val API_PRESET = "WP_API_PRESET"
        const val API_BASE_URL = "WP_API_BASE_URL"
        const val API_MODEL = "WP_API_MODEL"
        const val SYSTEM_PROMPT = "WP_SYSTEM_PROMPT"
        const val ABOUT_ME = "WP_ABOUT_ME"
        const val USE_ABOUT_ME = "WP_USE_ABOUT_ME"
        const val TRANSCRIBE_MODEL = "WP_TRANSCRIBE_MODEL"
        const val TRANSCRIBE_API = "WP_TRANSCRIBE_API"
        const val TRANSCRIBE_LANGUAGE = "WP_TRANSCRIBE_LANGUAGE"
        const val SPACE_SUMMARIZE = "WP_SPACE_SUMMARIZE"
        const val SPACE_CLEANUP = "WP_SPACE_CLEANUP"
        const val LEARN_FROM_CHATS = "WP_LEARN_FROM_CHATS"
        const val TOOLS_ENABLED = "WP_TOOLS_ENABLED"
        const val TOOLS_UNSUPPORTED = "WP_TOOLS_UNSUPPORTED"
        const val COMPASS_SHORTCUT = "WP_COMPASS_SHORTCUT"
        const val AUTO_SCREENSHOTS = "WP_AUTO_SCREENSHOTS"
        const val LAST_SCREENSHOT = "WP_LAST_SCREENSHOT"
        const val IMAGE_MODEL = "WP_IMAGE_MODEL"
        const val IMAGE_API = "WP_IMAGE_API"
        const val IMAGE_SIZE = "WP_IMAGE_SIZE"
        const val API_KEY_SEALED = "WP_API_KEY_SEALED"
        const val WEB_SEARCH = "WP_WEB_SEARCH"
        const val SEARCH_ENGINE = "WP_SEARCH_ENGINE"
        const val BRAVE_KEY_SEALED = "WP_BRAVE_KEY_SEALED"
        const val GRAPH_LINES = "WP_GRAPH_LINES"
        private const val GRAPH_SEP = "\u001F"
        const val BG_AURORA = "aurora"
        const val BG_PHOTO = "photo"
        const val BG_WALLPAPER = "wallpaper"
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
