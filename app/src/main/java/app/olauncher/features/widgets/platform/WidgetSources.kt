package app.olauncher.features.widgets.platform

import android.Manifest
import android.annotation.SuppressLint
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.LocationManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.CalendarContract
import android.service.notification.NotificationListenerService
import androidx.core.content.ContextCompat
import app.olauncher.features.widgets.domain.AgendaEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Today's and tomorrow's events from the phone's calendars (Google Calendar syncs into these). */
object CalendarSource {
    fun hasPermission(c: Context) = ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    data class Cal(val id: Long, val name: String, val account: String, val color: Int, val synced: Boolean)

    /** Every calendar on the phone, so the user can pick; one that isn't syncing has no events here. */
    suspend fun calendars(c: Context): List<Cal> = withContext(Dispatchers.IO) {
        if (!hasPermission(c)) return@withContext emptyList()
        val cols = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR, CalendarContract.Calendars.SYNC_EVENTS)
        runCatching {
            c.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, cols, null, null, null)?.use { cur ->
                buildList { while (cur.moveToNext()) add(Cal(cur.getLong(0), cur.getString(1).orEmpty(), cur.getString(2).orEmpty(), cur.getInt(3), cur.getInt(4) != 0)) }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    /** Events from every calendar except those in [hidden] (ids), whatever their "visible" flag says. */
    suspend fun events(c: Context, from: Long, to: Long, hidden: Set<Long> = emptySet()): List<AgendaEvent> = withContext(Dispatchers.IO) {
        if (!hasPermission(c)) return@withContext emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, from); android.content.ContentUris.appendId(it, to)
        }.build()
        val cols = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.DISPLAY_COLOR, CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_ID,
        )
        runCatching {
            c.contentResolver.query(uri, cols, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { cur ->
                buildList {
                    while (cur.moveToNext()) {
                        if (cur.getLong(6) in hidden) continue
                        val allDay = cur.getInt(3) != 0
                        // all-day events are stored in UTC midnights: shift them into the local day
                        val tz = java.util.TimeZone.getDefault()
                        val b = cur.getLong(1).let { if (allDay) it - tz.getOffset(it) else it }
                        val e = cur.getLong(2).let { if (allDay) it - tz.getOffset(it) else it }
                        add(AgendaEvent(cur.getString(0).orEmpty().ifBlank { "(No title)" }, b, e, allDay, cur.getInt(4), cur.getString(5).orEmpty()))
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }
}

/**
 * What's playing, from any app's media session. Reading sessions needs notification access (Android's rule);
 * Waypoint's listener only uses it for this and never reads notifications.
 */
object NowPlaying {
    data class Track(
        val title: String, val artist: String, val art: Bitmap?, val playing: Boolean,
        val position: Long, val duration: Long, val updatedAt: Long, val pkg: String,
    )

    private val _track = MutableStateFlow<Track?>(null)
    val track: StateFlow<Track?> = _track
    private var controller: MediaController? = null
    private var manager: MediaSessionManager? = null
    private var listener: MediaSessionManager.OnActiveSessionsChangedListener? = null

    fun hasAccess(c: Context): Boolean =
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() { controller = null; publish() }
    }

    /** Starts watching sessions (when access is granted); safe to call repeatedly. */
    fun start(c: Context) {
        if (listener != null || !hasAccess(c)) return
        val m = c.getSystemService(MediaSessionManager::class.java) ?: return
        val component = ComponentName(c, MediaListenerService::class.java)
        val l = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list.orEmpty()) }
        runCatching {
            m.addOnActiveSessionsChangedListener(l, component)
            manager = m; listener = l
            pick(m.getActiveSessions(component))
        }
    }

    fun stop() {
        listener?.let { runCatching { manager?.removeOnActiveSessionsChangedListener(it) } }
        listener = null
        controller?.unregisterCallback(callback); controller = null
    }

    /** The playing session, else the most recent one. */
    private fun pick(list: List<MediaController>) {
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback, android.os.Handler(android.os.Looper.getMainLooper()))
        }
        publish()
    }

    private fun publish() {
        val c = controller
        val md = c?.metadata
        val st = c?.playbackState
        if (c == null || md == null) { _track.value = null; return }
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        _track.value = Track(
            md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty(),
            md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
            art, st?.state == PlaybackState.STATE_PLAYING || st?.state == PlaybackState.STATE_BUFFERING,
            st?.position ?: 0, md.getLong(MediaMetadata.METADATA_KEY_DURATION), st?.lastPositionUpdateTime ?: 0, c.packageName,
        )
    }

    fun playPause() { val c = controller ?: return; if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play() }
    fun next() { controller?.transportControls?.skipToNext() }
    fun previous() { controller?.transportControls?.skipToPrevious() }
    fun seekTo(ms: Long) { controller?.transportControls?.seekTo(ms) }
    fun openApp(c: Context) {
        val pkg = controller?.packageName ?: return
        c.packageManager.getLaunchIntentForPackage(pkg)?.let { c.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** Android needs a notification listener for media sessions; this one does nothing with notifications. */
class MediaListenerService : NotificationListenerService() {
    override fun onListenerConnected() { NowPlaying.start(applicationContext) }
    override fun onListenerDisconnected() { NowPlaying.stop() }
}

/** Weather where you are, for the widget: Open-Meteo, at most every 30 minutes. */
object WeatherSource {
    data class Hour(val hour: Int, val temp: Double, val code: Int)
    data class Now(val temp: Double, val code: Int, val min: Double, val max: Double, val rainChance: Int, val hourly: List<Hour>, val at: Long, val feels: Double = temp)

    private var cache: Now? = null

    @SuppressLint("MissingPermission")
    suspend fun now(c: Context): Now? = withContext(Dispatchers.IO) {
        cache?.takeIf { System.currentTimeMillis() - it.at < 30 * 60_000 }?.let { return@withContext it }
        val ok = ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!ok) return@withContext cache
        val lm = c.getSystemService(LocationManager::class.java)
        val loc = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time } ?: return@withContext cache
        runCatching {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=${loc.latitude}&longitude=${loc.longitude}&timezone=auto&forecast_days=2" +
                "&current=temperature_2m,apparent_temperature,weather_code&hourly=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8_000; readTimeout = 8_000 }
            val o = try { JSONObject(conn.inputStream.bufferedReader().use { it.readText() }) } finally { conn.disconnect() }
            val cur = o.getJSONObject("current"); val d = o.getJSONObject("daily"); val h = o.getJSONObject("hourly")
            val hourNow = java.time.LocalDateTime.now().hour
            val times = h.getJSONArray("time"); val temps = h.getJSONArray("temperature_2m"); val codes = h.optJSONArray("weather_code")
            val hourly = (0 until times.length()).map { i -> Hour(times.getString(i).substring(11, 13).toInt(), temps.getDouble(i), codes?.optInt(i) ?: 0) }
                .drop(hourNow + 1).filterIndexed { i, _ -> i % 2 == 0 }.take(5)
            Now(cur.getDouble("temperature_2m"), cur.getInt("weather_code"), d.getJSONArray("temperature_2m_min").getDouble(0),
                d.getJSONArray("temperature_2m_max").getDouble(0), d.optJSONArray("precipitation_probability_max")?.optInt(0) ?: 0, hourly,
                System.currentTimeMillis(), cur.optDouble("apparent_temperature", cur.getDouble("temperature_2m"))).also { cache = it }
        }.getOrNull() ?: cache
    }
}

/** Waypoint's AppWidgetHost: other apps' widgets live in the Home stack. */
class WidgetHost(context: Context) {
    val manager: AppWidgetManager = AppWidgetManager.getInstance(context)
    val host = AppWidgetHost(context.applicationContext, HOST_ID)

    fun start() = runCatching { host.startListening() }
    fun stop() = runCatching { host.stopListening() }
    fun allocate(): Int = host.allocateAppWidgetId()
    fun delete(id: Int) = runCatching { host.deleteAppWidgetId(id) }

    companion object { const val HOST_ID = 4_711 }
}
