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
    )

    /** What each call is shown as while it runs. */
    fun status(name: String, args: JSONObject): String = when (name) {
        LOCATION -> "Finding your location…"
        PLACES -> "Looking for ${args.optString("query").ifBlank { "places" }}" + args.optString("near").takeIf { it.isNotBlank() }?.let { " near $it" }.orEmpty() + "…"
        ROUTE -> "Working out the route to ${args.optString("to")}…"
        WEATHER -> "Checking the weather…"
        REMINDER -> "Adding to your to-dos…"
        SPACE -> "Searching your notes…"
        else -> "Using $name…"
    }

    /** Questions the tools answer better than a web search (so the search step is skipped). */
    private val TOOL_INTENT = Regex(
        """\b(near(est|by| me)?|closest|around here|how far|distance|directions|route|walk(ing)? to|drive to|get to|weather|forecast|rain|temperature|remind me|my notes|in my space)\b""" +
            """|نزدیک|فاصله|مسیر|چقدر راه|هوا|باران|بارون|دما|یادم بنداز|یادداشت‌هام|یادداشتهام""",
        RegexOption.IGNORE_CASE,
    )

    fun wantsTools(text: String) = TOOL_INTENT.containsMatchIn(text)

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
