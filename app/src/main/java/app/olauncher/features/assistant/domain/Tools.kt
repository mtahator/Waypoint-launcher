package app.olauncher.features.assistant.domain

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The assistant's everyday tools: where you are, places nearby, distance and travel time, weather, reminders
 * and your Space notes. Maps data is OpenStreetMap (Nominatim search, OSRM routing), weather is Open-Meteo:
 * no keys, works with any chat provider that supports tool calls. Definitions and parsing are pure.
 */
object Tools {

    const val LOCATION = "get_my_location"
    const val PLACES = "find_places"
    const val ROUTE = "route"
    const val WEATHER = "weather"
    const val REMINDER = "add_reminder"
    const val SPACE = "search_my_notes"
    const val REMEMBER = "remember"

    private fun schema(vararg props: Triple<String, String, String>, required: List<String> = emptyList()) = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject().apply { props.forEach { (name, type, desc) -> put(name, JSONObject().put("type", type).put("description", desc)) } })
        .put("required", JSONArray(required))

    val ALL = listOf(
        ToolSpec(LOCATION, "The user's current location (from the phone): coordinates and address.", schema()),
        ToolSpec(PLACES, "Find places (cafés, pharmacies, supermarkets, gyms, a named shop or address…) near the user or near a " +
            "given place, sorted by distance, with walking time and a maps link.",
            schema(
                Triple("query", "string", "What to look for, e.g. \"pharmacy\", \"climbing gym\", \"Tesco\""),
                Triple("near", "string", "Place or address to search around; omit to use the user's current location"),
                Triple("limit", "integer", "How many results (default 5)"),
                required = listOf("query"),
            )),
        ToolSpec(ROUTE, "Distance and travel time between two places (walking, cycling or driving), with a directions link.",
            schema(
                Triple("to", "string", "Destination place or address"),
                Triple("from", "string", "Start; omit to use the user's current location"),
                Triple("mode", "string", "walk, cycle or drive (default walk)"),
                required = listOf("to"),
            )),
        ToolSpec(WEATHER, "Current weather and the next days' forecast for a place.",
            schema(Triple("place", "string", "City or place; omit for the user's current location"))),
        ToolSpec(REMINDER, "Add a to-do to the user's Space, optionally with a reminder notification at a time.",
            schema(
                Triple("title", "string", "What to do"),
                Triple("due", "string", "Local date-time YYYY-MM-DDTHH:MM for a reminder, or omit"),
                required = listOf("title"),
            )),
        ToolSpec(SPACE, "Search the user's own notes, voice-note transcripts, screenshots and to-dos in Space.",
            schema(Triple("query", "string", "Words to look for"), required = listOf("query"))),
        ToolSpec(REMEMBER, "Save a durable fact about the user to their long-term memory (e.g. their home address, " +
            "a preference, a routine). Use it when they ask you to remember something, or tell you something lasting about themselves.",
            schema(
                Triple("text", "string", "The fact, short and self-contained, e.g. \"Home: NG1 2AP, Nottingham\""),
                Triple("tag", "string", "One of: identity, location, education, work, skills, research, goals, interests, lifestyle, health, people, preferences, style, other"),
                required = listOf("text"),
            )),
    )

    /** How to use the tools, sent once with the system prompt when they're offered. */
    const val GUIDE = "You can use tools. For places, distances and weather use them instead of guessing; \"near me\", " +
        "\"nearest\" and \"here\" mean the user's current location (just call the tool; if it fails, use a home address from " +
        "memory or ask). The app shows found places and routes on a map card under your answer, with tap-to-open, so don't " +
        "paste map or directions links. You have a long-term memory about the user: when they ask you to remember something, " +
        "call remember and confirm briefly."

    /** What each call is shown as while it runs. */
    fun status(name: String, args: JSONObject): String = when (name) {
        LOCATION -> "Finding your location…"
        PLACES -> "Looking for ${args.optString("query").ifBlank { "places" }}" + args.optString("near").takeIf { it.isNotBlank() }?.let { " near $it" }.orEmpty() + "…"
        ROUTE -> "Working out the route to ${args.optString("to")}…"
        WEATHER -> "Checking the weather…"
        REMINDER -> "Adding to your to-dos…"
        SPACE -> "Searching your notes…"
        REMEMBER -> "Saving to your memory…"
        else -> "Using $name…"
    }

    /** Questions the tools answer better than a web search (so the search step is skipped). */
    private val TOOL_INTENT = Regex(
        """\b(near(est|by| me)?|closest|around here|how far|distance|directions|route|walk(ing)? to|drive to|get to|weather|forecast|rain|temperature|remind me|my notes|in my space)\b""" +
            """|نزدیک|فاصله|مسیر|چقدر راه|هوا|باران|بارون|دما|یادم بنداز|یادداشت‌هام|یادداشتهام""",
        RegexOption.IGNORE_CASE,
    )

    fun wantsTools(text: String) = TOOL_INTENT.containsMatchIn(text)

    /** About where the user is right now ("nearest…", "how far from here", "weather here"). */
    private val HERE = Regex(
        """\b(near(est|by| me| here)?|closest|around (me|here)|from here|how far|weather( here| today| now)?|my location|where am i)\b""" +
            """|نزدیک|اطرافم|اطراف من|از اینجا|کجام|هوا""",
        RegexOption.IGNORE_CASE,
    )

    fun needsLocation(text: String) = HERE.containsMatchIn(text)

    // ---- maths and links

    /** Great-circle distance in metres. */
    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(a))
    }

    /** About 4.8 km/h. */
    fun walkMinutes(metres: Double) = (metres / 80.0).roundToInt().coerceAtLeast(1)

    fun mapsLink(lat: Double, lon: Double, name: String = "") =
        "https://www.google.com/maps/search/?api=1&query=" + URLEncoder.encode(if (name.isNotBlank()) "$name $lat,$lon" else "$lat,$lon", "UTF-8")

    fun directionsLink(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, mode: String) =
        "https://www.google.com/maps/dir/?api=1&origin=$fromLat,$fromLon&destination=$toLat,$toLon&travelmode=" +
            when (mode) { "drive" -> "driving"; "cycle" -> "bicycling"; else -> "walking" }

    fun normalizeMode(mode: String?): String = when (mode?.lowercase()?.trim()) {
        "drive", "driving", "car" -> "drive"
        "cycle", "cycling", "bike", "bicycle" -> "cycle"
        else -> "walk"
    }

    // ---- map cards

    /** A place on the map card: numbered pins, plus where the user is (origin). */
    data class Pin(val name: String, val lat: Double, val lon: Double, val detail: String = "")
    data class MapData(val pins: List<Pin>, val origin: Pin? = null) {
        val all get() = pins + listOfNotNull(origin)
    }

    fun encodeMap(m: MapData): String = JSONObject().apply {
        put("pins", JSONArray().apply { m.pins.forEach { put(pin(it)) } })
        m.origin?.let { put("origin", pin(it)) }
    }.toString()

    private fun pin(p: Pin) = JSONObject().put("name", p.name).put("lat", p.lat).put("lon", p.lon).put("detail", p.detail)

    fun decodeMap(json: String): MapData? = runCatching {
        if (json.isBlank()) return null
        val o = JSONObject(json)
        fun read(p: JSONObject) = Pin(p.optString("name"), p.getDouble("lat"), p.getDouble("lon"), p.optString("detail"))
        val pins = o.optJSONArray("pins")?.let { a -> (0 until a.length()).map { read(a.getJSONObject(it)) } }.orEmpty()
        MapData(pins, o.optJSONObject("origin")?.let { read(it) }).takeIf { it.pins.isNotEmpty() }
    }.getOrNull()

    /** The map for a [find_places] or [route] result (our own JSON), or null. */
    fun mapFrom(tool: String, result: String): MapData? = runCatching {
        val o = JSONObject(result)
        if (o.has("error")) return null
        fun origin(prefix: String, label: String) = if (o.has("${prefix}_lat")) Pin(label, o.getDouble("${prefix}_lat"), o.getDouble("${prefix}_lon")) else null
        when (tool) {
            PLACES -> {
                val a = o.optJSONArray("results") ?: return null
                val pins = (0 until a.length()).mapNotNull { i ->
                    val p = a.optJSONObject(i) ?: return@mapNotNull null
                    if (!p.has("lat")) return@mapNotNull null
                    Pin(p.optString("name"), p.getDouble("lat"), p.getDouble("lon"),
                        listOf(p.optString("distance"), p.optString("address").split(",").drop(1).take(2).joinToString(",").trim())
                            .filter { it.isNotBlank() }.joinToString(" · "))
                }
                MapData(pins, origin("from", o.optString("from"))).takeIf { pins.isNotEmpty() }
            }
            ROUTE -> {
                val to = origin("to", o.optString("to").split(",").first()) ?: return null
                MapData(listOf(to.copy(detail = listOf(o.optString("distance"), o.optString("time")).filter { it.isNotBlank() }.joinToString(" · "))),
                    origin("from", o.optString("from").split(",").first()))
            }
            else -> null
        }
    }.getOrNull()

    /** Opens a pin in the phone's maps app (geo: URI with a label). */
    fun geoUri(p: Pin) = "geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}(" + URLEncoder.encode(p.name.ifBlank { "Place" }, "UTF-8").replace("+", "%20") + ")"

    /** Web Mercator: world pixel coordinates at [zoom] (256-px tiles). */
    fun worldX(lon: Double, zoom: Int) = (lon + 180.0) / 360.0 * 256.0 * (1 shl zoom)
    fun worldY(lat: Double, zoom: Int): Double {
        val s = sin(Math.toRadians(lat.coerceIn(-85.0, 85.0)))
        return (0.5 - kotlin.math.ln((1 + s) / (1 - s)) / (4 * Math.PI)) * 256.0 * (1 shl zoom)
    }

    /** The closest zoom that fits all [points] in a [w]×[h] (tile-pixel) frame with a margin. */
    fun fitZoom(points: List<Pin>, w: Double, h: Double, maxZoom: Int = 17): Int {
        if (points.size < 2) return 16
        for (z in maxZoom downTo 2) {
            val xs = points.map { worldX(it.lon, z) }; val ys = points.map { worldY(it.lat, z) }
            if (xs.max() - xs.min() <= w * 0.75 && ys.max() - ys.min() <= h * 0.7) return z
        }
        return 2
    }

    // ---- OpenStreetMap / Open-Meteo responses

    data class Place(val name: String, val address: String, val lat: Double, val lon: Double, val kind: String = "")

    /** Nominatim `format=jsonv2` search results. */
    fun parseNominatim(json: String): List<Place> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val display = o.optString("display_name")
            val name = o.optString("name").ifBlank { display.substringBefore(",") }
            Place(name, display, o.optString("lat").toDoubleOrNull() ?: return@mapNotNull null,
                o.optString("lon").toDoubleOrNull() ?: return@mapNotNull null, o.optString("type"))
        }
    }.getOrDefault(emptyList())

    /** Nominatim reverse geocoding: the address line. */
    fun parseReverse(json: String): String? = runCatching { JSONObject(json).optString("display_name").takeIf { it.isNotBlank() } }.getOrNull()

    data class Route(val metres: Double, val seconds: Double)

    /** OSRM `/route/v1` response. */
    fun parseOsrm(json: String): Route? = runCatching {
        val r = JSONObject(json).optJSONArray("routes")?.optJSONObject(0) ?: return null
        Route(r.optDouble("distance"), r.optDouble("duration"))
    }.getOrNull()

    /** A small readable summary of an Open-Meteo forecast (current + up to 4 days). */
    fun summarizeWeather(json: String, place: String): String? = runCatching {
        val o = JSONObject(json)
        val cur = o.optJSONObject("current")
        val daily = o.optJSONObject("daily")
        buildString {
            append("Weather for $place")
            cur?.let { c ->
                append(": now ${c.optDouble("temperature_2m")}°C (feels ${c.optDouble("apparent_temperature")}°C), ")
                append(weatherCode(c.optInt("weather_code"))).append(", wind ${c.optDouble("wind_speed_10m")} km/h")
                if (c.has("precipitation")) append(", precipitation ${c.optDouble("precipitation")} mm")
            }
            daily?.let { d ->
                val days = d.optJSONArray("time") ?: JSONArray()
                for (i in 0 until minOf(4, days.length())) {
                    append("\n").append(days.optString(i)).append(": ")
                    append(d.optJSONArray("temperature_2m_min")?.optDouble(i)).append("–").append(d.optJSONArray("temperature_2m_max")?.optDouble(i)).append("°C, ")
                    append(weatherCode(d.optJSONArray("weather_code")?.optInt(i) ?: 0))
                    d.optJSONArray("precipitation_probability_max")?.optInt(i)?.let { append(", rain chance $it%") }
                }
            }
        }
    }.getOrNull()

    /** WMO weather codes in words. */
    fun weatherCode(code: Int): String = when (code) {
        0 -> "clear"
        1, 2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "fog"
        in 51..57 -> "drizzle"
        in 61..67 -> "rain"
        in 71..77 -> "snow"
        in 80..82 -> "rain showers"
        85, 86 -> "snow showers"
        in 95..99 -> "thunderstorm"
        else -> "code $code"
    }

    /** Round distances the way people say them. */
    fun formatDistance(m: Double) = if (m < 1000) "${(m / 10).roundToInt() * 10} m" else "%.1f km".format(m / 1000)

    fun formatDuration(seconds: Double): String {
        val min = (seconds / 60).roundToInt().coerceAtLeast(1)
        return if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
    }
}
