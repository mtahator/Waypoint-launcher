package app.olauncher.features.assistant.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.olauncher.features.assistant.domain.ToolCall
import app.olauncher.features.assistant.domain.Tools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume

/** What the tools need from the rest of the app (Space), without the assistant depending on it. */
interface ToolHost {
    /** Adds a to-do (reminder when [due] > 0); returns a confirmation line. */
    suspend fun addReminder(title: String, due: Long): String
    /** The best matching Space items as short text. */
    suspend fun searchNotes(query: String): String
}

/**
 * Runs the assistant's tool calls on the phone. Results are short JSON the model reads; errors come back as
 * `{"error": …}` so the model can explain or try another way instead of the chat failing.
 */
class ToolRunner(private val context: Context, private val host: ToolHost) {

    suspend fun run(call: ToolCall): String = runCatching {
        val args = runCatching { JSONObject(call.arguments) }.getOrDefault(JSONObject())
        when (call.name) {
            Tools.LOCATION -> location()
            Tools.PLACES -> places(args.optString("query"), args.optString("near").takeIf { it.isNotBlank() }, args.optInt("limit", 5).coerceIn(1, 10))
            Tools.ROUTE -> route(args.optString("from").takeIf { it.isNotBlank() }, args.optString("to"), Tools.normalizeMode(args.optString("mode")))
            Tools.WEATHER -> weather(args.optString("place").takeIf { it.isNotBlank() })
            Tools.REMINDER -> JSONObject().put("result", host.addReminder(args.optString("title"), parseDue(args.optString("due")))).toString()
            Tools.SPACE -> JSONObject().put("results", host.searchNotes(args.optString("query"))).toString()
            else -> error("Unknown tool ${call.name}")
        }
    }.getOrElse { e -> JSONObject().put("error", e.message ?: e.javaClass.simpleName).toString() }

    private fun parseDue(s: String): Long = runCatching {
        java.time.LocalDateTime.parse(s.trim().take(16)).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    // ---- location

    fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private data class Point(val lat: Double, val lon: Double, val label: String)

    @SuppressLint("MissingPermission")
    private suspend fun here(): Point {
        if (!hasLocationPermission()) error("No location permission. Ask the user to allow location in Wayfinder Setup → Tools, or to name a place.")
        val lm = context.getSystemService(LocationManager::class.java)
        val providers = listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) error("Location is turned off on the phone")
        // a recent fix is enough for "near me"; otherwise ask for a fresh one (Android 11+)
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        val fix = last?.takeIf { System.currentTimeMillis() - it.time < 10 * 60_000 }
            ?: (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) withTimeoutOrNull(12_000) { current(lm, providers.first()) } else null)
            ?: last ?: error("Couldn't get a location fix")
        return Point(fix.latitude, fix.longitude, "your location")
    }

    @SuppressLint("MissingPermission")
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private suspend fun current(lm: LocationManager, provider: String): Location? = suspendCancellableCoroutine { cont ->
        val cancel = android.os.CancellationSignal()
        lm.getCurrentLocation(provider, cancel, context.mainExecutor) { cont.resume(it) }
        cont.invokeOnCancellation { cancel.cancel() }
    }

    private suspend fun location(): String {
        val p = here()
        val address = runCatching { reverse(p.lat, p.lon) }.getOrNull()
        return JSONObject().put("lat", p.lat).put("lon", p.lon).put("address", address ?: "").put("map", Tools.mapsLink(p.lat, p.lon)).toString()
    }

    // ---- places and routes (OpenStreetMap)

    private suspend fun geocode(place: String): Point {
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=" + enc(place)
        val p = Tools.parseNominatim(get(url)).firstOrNull() ?: error("Couldn't find \"$place\" on the map")
        return Point(p.lat, p.lon, p.address)
    }

    private suspend fun reverse(lat: Double, lon: Double): String? =
        Tools.parseReverse(get("https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&lat=$lat&lon=$lon"))

    private suspend fun places(query: String, near: String?, limit: Int): String {
        require(query.isNotBlank()) { "Say what to look for" }
        val center = if (near != null) geocode(near) else here()
        // search a box around the point, widening if nothing is found
        var found = emptyList<Tools.Place>()
        for (km in listOf(1.5, 4.0, 12.0)) {
            val dLat = km / 111.0
            val dLon = km / (111.0 * kotlin.math.cos(Math.toRadians(center.lat)).coerceAtLeast(0.2))
            val box = "${center.lon - dLon},${center.lat + dLat},${center.lon + dLon},${center.lat - dLat}"
            val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&bounded=1&limit=20&viewbox=$box&q=" + enc(query)
            found = Tools.parseNominatim(get(url))
            if (found.size >= minOf(limit, 3)) break
        }
        if (found.isEmpty()) return JSONObject().put("results", JSONArray()).put("note", "Nothing found for \"$query\" within 12 km").toString()
        val results = JSONArray()
        found.map { it to Tools.haversine(center.lat, center.lon, it.lat, it.lon) }.sortedBy { it.second }.take(limit).forEach { (p, m) ->
            results.put(JSONObject().put("name", p.name).put("address", p.address).put("type", p.kind)
                .put("distance", Tools.formatDistance(m)).put("walk", "${Tools.walkMinutes(m)} min (straight-line estimate)")
                .put("map", Tools.mapsLink(p.lat, p.lon, p.name)))
        }
        return JSONObject().put("from", center.label).put("results", results).toString()
    }

    private suspend fun route(from: String?, to: String, mode: String): String {
        require(to.isNotBlank()) { "Say where to" }
        val a = if (from != null) geocode(from) else here()
        val b = geocode(to)
        val profile = when (mode) { "drive" -> "routed-car"; "cycle" -> "routed-bike"; else -> "routed-foot" }
        val url = "https://routing.openstreetmap.de/$profile/route/v1/driving/${a.lon},${a.lat};${b.lon},${b.lat}?overview=false"
        val r = runCatching { Tools.parseOsrm(get(url)) }.getOrNull()
        val straight = Tools.haversine(a.lat, a.lon, b.lat, b.lon)
        return JSONObject().put("from", a.label).put("to", b.label).put("mode", mode)
            .put("distance", r?.let { Tools.formatDistance(it.metres) } ?: Tools.formatDistance(straight))
            .put("time", r?.let { Tools.formatDuration(it.seconds) } ?: "unknown (routing unavailable)")
            .put("straight_line", Tools.formatDistance(straight))
            .put("directions", Tools.directionsLink(a.lat, a.lon, b.lat, b.lon, mode)).toString()
    }

    // ---- weather (Open-Meteo)

    private suspend fun weather(place: String?): String {
        val p = if (place != null) geocode(place) else here()
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${p.lat}&longitude=${p.lon}&timezone=auto&forecast_days=4" +
            "&current=temperature_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
        val label = place ?: runCatching { reverse(p.lat, p.lon)?.split(",")?.take(3)?.joinToString(",") }.getOrNull() ?: "your location"
        return JSONObject().put("weather", Tools.summarizeWeather(get(url), label) ?: error("No forecast")).toString()
    }

    // ---- http

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000; readTimeout = 10_000
            // OpenStreetMap's usage policy asks apps to identify themselves
            setRequestProperty("User-Agent", "Waypoint-Launcher/1.1 (Android; personal assistant)")
            setRequestProperty("Accept-Language", java.util.Locale.getDefault().toLanguageTag() + ",en;q=0.8")
        }
        try {
            if (conn.responseCode !in 200..299) error("Map service answered HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }
}
