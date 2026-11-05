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
    suspend fun addReminder(title: String, due: Long, steps: List<String> = emptyList()): String
    /** The best matching Space items as short text. */
    suspend fun searchNotes(query: String): String
    /** Saves a fact to the memory about the user; returns a confirmation line. */
    suspend fun remember(tag: String, text: String): String
    /** Saves a tool the assistant proposed (not usable until the user allows it); returns its id. */
    suspend fun proposeTool(tool: app.olauncher.features.assistant.domain.CustomTool): Long
    /** The tools the user allowed. */
    suspend fun customTools(): List<app.olauncher.features.assistant.domain.CustomTool>
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
            Tools.REMINDER -> JSONObject().put("result", host.addReminder(args.optString("title"), parseDue(args.optString("due")),
                args.optJSONArray("steps")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty())).toString()
            Tools.SPACE -> JSONObject().put("results", host.searchNotes(args.optString("query"))).toString()
            Tools.REMEMBER -> JSONObject().put("result", host.remember(args.optString("tag"), args.optString("text"))).toString()
            Tools.CALENDAR -> calendar(args.optString("date").takeIf { it.isNotBlank() }, args.optInt("days", 1).coerceIn(1, 14))
            Tools.READ_PAGE -> readPage(args.optString("url"))
            Tools.CREATE_TOOL -> createTool(args)
            else -> if (call.name.startsWith(app.olauncher.features.assistant.domain.CustomTool.PREFIX)) custom(call.name, args) else error("Unknown tool ${call.name}")
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

    /**
     * Where the user is: a fix from the last 15 minutes if the phone has one, else a fresh one (network first,
     * it's fast indoors; then GPS), else the newest fix of any age.
     */
    @SuppressLint("MissingPermission")
    private suspend fun here(): Point {
        if (!hasLocationPermission()) error("No location permission. Ask the user to allow location for Wayfinder, or to name a place.")
        val lm = context.getSystemService(LocationManager::class.java)
        val providers = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.none { it != LocationManager.PASSIVE_PROVIDER }) error("Location is turned off on the phone. Ask the user to turn it on, or to name a place.")
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 15 * 60_000) return Point(last.latitude, last.longitude, "your location")
        for (p in providers.filter { it != LocationManager.PASSIVE_PROVIDER }) {
            val fix = withTimeoutOrNull(if (p == LocationManager.GPS_PROVIDER) 15_000L else 8_000L) { fresh(lm, p) }
            if (fix != null) return Point(fix.latitude, fix.longitude, "your location")
        }
        last?.let { return Point(it.latitude, it.longitude, "your last known location") }
        error("Couldn't get a location fix. Ask the user to name a place.")
    }

    @SuppressLint("MissingPermission")
    private suspend fun fresh(lm: LocationManager, provider: String): Location? = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cancel = android.os.CancellationSignal()
            runCatching { lm.getCurrentLocation(provider, cancel, context.mainExecutor) { if (cont.isActive) cont.resume(it) } }
                .onFailure { if (cont.isActive) cont.resume(null) }
            cont.invokeOnCancellation { cancel.cancel() }
        } else {
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: Location) { if (cont.isActive) cont.resume(location) }
                @Deprecated("old API") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) { if (cont.isActive) cont.resume(null) }
            }
            @Suppress("DEPRECATION")
            runCatching { lm.requestSingleUpdate(provider, listener, android.os.Looper.getMainLooper()) }.onFailure { if (cont.isActive) cont.resume(null) }
            cont.invokeOnCancellation { lm.removeUpdates(listener) }
        }
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
            results.put(JSONObject().put("name", p.name).put("address", p.address).put("type", p.kind).put("lat", p.lat).put("lon", p.lon)
                .put("distance", Tools.formatDistance(m)).put("walk", "${Tools.walkMinutes(m)} min (straight-line estimate)")
                .put("map", Tools.mapsLink(p.lat, p.lon, p.name)))
        }
        return JSONObject().put("from", center.label).put("from_lat", center.lat).put("from_lon", center.lon).put("results", results).toString()
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
            .put("from_lat", a.lat).put("from_lon", a.lon).put("to_lat", b.lat).put("to_lon", b.lon)
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
        val label = place ?: runCatching { reverse(p.lat, p.lon)?.split(",")?.take(2)?.joinToString(",") }.getOrNull() ?: "your location"
        val body = get(url)
        val out = JSONObject().put("weather", Tools.summarizeWeather(body, label) ?: error("No forecast"))
        // the same numbers for the weather card under the answer
        runCatching {
            val o = JSONObject(body); val c = o.getJSONObject("current"); val d = o.getJSONObject("daily")
            out.put("panel", JSONObject().put("place", label.split(",").first().trim()).put("temp", c.getDouble("temperature_2m"))
                .put("feels", c.optDouble("apparent_temperature")).put("code", c.optInt("weather_code")).put("wind", c.optDouble("wind_speed_10m"))
                .put("days", JSONArray().apply {
                    val t = d.getJSONArray("time")
                    for (i in 0 until t.length()) put(JSONObject().put("date", t.getString(i)).put("min", d.getJSONArray("temperature_2m_min").getDouble(i))
                        .put("max", d.getJSONArray("temperature_2m_max").getDouble(i)).put("code", d.getJSONArray("weather_code").getInt(i))
                        .put("rain", d.optJSONArray("precipitation_probability_max")?.optInt(i) ?: 0))
                }))
        }
        return out.toString()
    }

    // ---- calendar, web pages, tools the assistant makes

    private suspend fun calendar(date: String?, days: Int): String {
        if (!app.olauncher.features.widgets.platform.CalendarSource.hasPermission(context))
            error("No calendar access. Ask the user to allow it in Settings → Widgets → Calendar.")
        val zone = java.time.ZoneId.systemDefault()
        val start = (date?.let { runCatching { java.time.LocalDate.parse(it.take(10)) }.getOrNull() } ?: java.time.LocalDate.now()).atStartOfDay(zone)
        val events = app.olauncher.features.widgets.platform.CalendarSource.events(context, start.toInstant().toEpochMilli(), start.plusDays(days.toLong()).toInstant().toEpochMilli())
        val fmt = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM HH:mm")
        val title = if (days == 1) start.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM")) else
            start.format(java.time.format.DateTimeFormatter.ofPattern("d MMM")) + " – " + start.plusDays(days - 1L).format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))
        return JSONObject().put("title", title).put("events", JSONArray().apply {
            events.forEach { e ->
                put(JSONObject().put("title", e.title).put("all_day", e.allDay).put("color", e.color).put("location", e.location)
                    .put("begin_ms", e.begin).put("end_ms", e.end)
                    .put("start", java.time.Instant.ofEpochMilli(e.begin).atZone(zone).format(fmt))
                    .put("end", java.time.Instant.ofEpochMilli(e.end).atZone(zone).format(fmt)))
            }
        }).put("note", if (events.isEmpty()) "No events" else "The app shows these as a card").toString()
    }

    private suspend fun readPage(url: String): String {
        require(url.startsWith("https://") || url.startsWith("http://")) { "Give a full web address" }
        val text = withContext(Dispatchers.IO) { app.olauncher.features.websearch.platform.WebSearchClient.readPage(url, 8_000) }
        if (text.isBlank()) error("Couldn't read that page")
        return JSONObject().put("url", url).put("text", text).toString()
    }

    private suspend fun createTool(args: JSONObject): String {
        val name = app.olauncher.features.assistant.domain.CustomTool.normalizeName(args.optString("name"))
        val url = args.optString("url").trim()
        val params = args.optJSONArray("params")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { app.olauncher.features.assistant.domain.CustomTool.Param(it.optString("name").trim(), it.optString("description")) }
        }?.filter { it.name.isNotBlank() }
            ?: Regex("\\{([a-zA-Z0-9_]+)\\}").findAll(url).map { app.olauncher.features.assistant.domain.CustomTool.Param(it.groupValues[1], it.groupValues[1]) }.toList()
        app.olauncher.features.assistant.domain.CustomTool.problem(name, url, params)?.let { error(it) }
        val tool = app.olauncher.features.assistant.domain.CustomTool(0, name, args.optString("description").trim(), url, params, enabled = false)
        val id = host.proposeTool(tool)
        return JSONObject().put("tool_id", id).put("name", name).put("description", tool.description).put("url", url)
            .put("result", "Proposed. The user must tap Allow on the card before it can be used as ${tool.toolName}; tell them what it does.").toString()
    }

    private suspend fun custom(name: String, args: JSONObject): String {
        val tool = host.customTools().firstOrNull { it.toolName == name && it.enabled } ?: error("That tool isn't allowed")
        val body = get(tool.expand(args))
        val text = if (body.trimStart().startsWith("<")) app.olauncher.features.websearch.domain.WebSearch.htmlToText(body, 6_000) else body.take(6_000)
        return JSONObject().put("result", text).toString()
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
