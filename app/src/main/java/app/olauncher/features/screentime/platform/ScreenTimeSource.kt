package app.olauncher.features.screentime.platform

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import app.olauncher.core.WaypointPrefs
import app.olauncher.features.screentime.domain.ScreenScore
import app.olauncher.helper.usageStats.EventLogWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Today's use from Android's usage events (needs usage access, which the screen-time line already asks for),
 * summarised by [ScreenScore]. Read at most every two minutes, off the main thread.
 */
class ScreenTimeSource(private val context: Context, private val prefs: WaypointPrefs) {
    @Volatile private var cache: Pair<Long, ScreenScore.Summary>? = null

    fun goals() = ScreenScore.Goals(prefs.screenBudgetMin, prefs.screenBreakMin, prefs.screenPurposePct)

    fun cached(): ScreenScore.Summary? = cache?.second

    fun invalidate() { cache = null }

    suspend fun today(force: Boolean = false): ScreenScore.Summary? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cache?.takeIf { !force && now - it.first < 2 * 60_000 && it.second.goals == goals() }?.let { return@withContext it.second }
        runCatching {
            val start = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            val stats = EventLogWrapper(context).getForegroundStatsByTimestamps(start, now)
            val uses = stats.map { ScreenScore.Use(it.beginTime, it.endTime, it.packageName) }
            val kinds = HashMap<String, ScreenScore.Kind>()
            ScreenScore.summarize(uses, { p -> kinds.getOrPut(p) { kindOf(p) } }, screenOns(start, now), start, now, goals())
        }.getOrNull()?.also { cache = now to it }
    }

    /** The user's choice first, then the app's own category, then a few well-known feeds. */
    fun kindOf(pkg: String): ScreenScore.Kind {
        prefs.appKinds[pkg]?.let { return it }
        val info = runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.getOrNull()
        if (info != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) when (info.category) {
            ApplicationInfo.CATEGORY_SOCIAL, ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_GAME, ApplicationInfo.CATEGORY_NEWS -> return ScreenScore.Kind.PULL
            ApplicationInfo.CATEGORY_PRODUCTIVITY, ApplicationInfo.CATEGORY_MAPS -> return ScreenScore.Kind.PURPOSE
        }
        @Suppress("DEPRECATION")
        if (info != null && info.flags and ApplicationInfo.FLAG_IS_GAME != 0) return ScreenScore.Kind.PULL
        return when {
            PULL.any { pkg.startsWith(it) } -> ScreenScore.Kind.PULL
            PURPOSE.any { pkg.startsWith(it) } -> ScreenScore.Kind.PURPOSE
            else -> ScreenScore.Kind.NEUTRAL
        }
    }

    /** Times the screen came on today (Android 9+). */
    private fun screenOns(start: Long, end: Long): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return 0
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return 0
        val events = usm.queryEvents(start, end)
        val e = UsageEvents.Event()
        var n = 0
        while (events.hasNextEvent()) { events.getNextEvent(e); if (e.eventType == UsageEvents.Event.SCREEN_INTERACTIVE) n++ }
        return n
    }

    fun label(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    companion object {
        private val PULL = listOf(
            "com.instagram.", "com.zhiliaoapp.musically", "com.ss.android.ugc", "com.google.android.youtube", "com.twitter.", "com.reddit.",
            "com.snapchat.", "com.facebook.katana", "com.facebook.lite", "com.netflix.", "tv.twitch.", "com.pinterest", "com.tumblr",
        )
        private val PURPOSE = listOf(
            "com.google.android.apps.docs", "com.google.android.calendar", "com.google.android.keep", "com.google.android.apps.maps",
            "com.microsoft.office", "com.microsoft.teams", "com.notion.", "com.todoist", "com.duolingo", "org.coursera", "com.anthropic.",
        )
    }
}
