package app.olauncher.features.assistant.domain

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Cards shown under an answer, built from what the tools returned: the weather, a day's calendar, and a
 * proposal for a new tool the assistant wants to make. (Places and routes have their own map card.) Pure.
 */
sealed class Panel {
    data class Weather(val place: String, val temp: Double, val feels: Double, val code: Int, val wind: Double, val days: List<Day>) : Panel() {
        data class Day(val date: String, val min: Double, val max: Double, val code: Int, val rain: Int)
    }
    data class Agenda(val title: String, val events: List<Event>) : Panel() {
        data class Event(val title: String, val begin: Long, val end: Long, val allDay: Boolean, val color: Int, val location: String)
    }
    data class NewTool(val toolId: Long, val name: String, val description: String, val url: String) : Panel()
}

object Panels {
    fun encode(list: List<Panel>): String = JSONArray().apply { list.forEach { put(encode(it)) } }.toString()

    private fun encode(p: Panel): JSONObject = when (p) {
        is Panel.Weather -> JSONObject().put("type", "weather").put("place", p.place).put("temp", p.temp).put("feels", p.feels)
            .put("code", p.code).put("wind", p.wind).put("days", JSONArray().apply {
                p.days.forEach { put(JSONObject().put("date", it.date).put("min", it.min).put("max", it.max).put("code", it.code).put("rain", it.rain)) }
            })
        is Panel.Agenda -> JSONObject().put("type", "agenda").put("title", p.title).put("events", JSONArray().apply {
            p.events.forEach {
                put(JSONObject().put("title", it.title).put("begin", it.begin).put("end", it.end).put("allDay", it.allDay)
                    .put("color", it.color).put("location", it.location))
            }
        })
        is Panel.NewTool -> JSONObject().put("type", "tool").put("id", p.toolId).put("name", p.name).put("description", p.description).put("url", p.url)
    }

    fun decode(json: String?): List<Panel> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { decode(it) } }
        }.getOrDefault(emptyList())
    }

    private fun decode(o: JSONObject): Panel? = runCatching {
        when (o.getString("type")) {
            "weather" -> Panel.Weather(o.optString("place"), o.getDouble("temp"), o.optDouble("feels"), o.optInt("code"), o.optDouble("wind"),
                o.optJSONArray("days").objects().map { Panel.Weather.Day(it.optString("date"), it.optDouble("min"), it.optDouble("max"), it.optInt("code"), it.optInt("rain")) })
            "agenda" -> Panel.Agenda(o.optString("title"), o.optJSONArray("events").objects().map {
                Panel.Agenda.Event(it.optString("title"), it.optLong("begin"), it.optLong("end"), it.optBoolean("allDay"), it.optInt("color"), it.optString("location"))
            })
            "tool" -> Panel.NewTool(o.getLong("id"), o.optString("name"), o.optString("description"), o.optString("url"))
            else -> null
        }
    }.getOrNull()

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    /** The card for a tool's result (our own JSON), or null. */
    fun fromTool(tool: String, result: String): Panel? = runCatching {
        val o = JSONObject(result)
        if (o.has("error")) return null
        when (tool) {
            Tools.WEATHER -> o.optJSONObject("panel")?.let { decode(it.put("type", "weather")) }
            Tools.CALENDAR -> Panel.Agenda(o.optString("title"), o.optJSONArray("events").objects().map {
                Panel.Agenda.Event(it.optString("title"), it.optLong("begin_ms"), it.optLong("end_ms"), it.optBoolean("all_day"), it.optInt("color"), it.optString("location"))
            })
            Tools.CREATE_TOOL -> if (o.has("tool_id")) Panel.NewTool(o.getLong("tool_id"), o.optString("name"), o.optString("description"), o.optString("url")) else null
            else -> null
        }
    }.getOrNull()
}

/**
 * A tool the assistant made for itself: one HTTPS GET with {placeholders} filled from its arguments. It is
 * only offered to the model after the user allows it (from the card in the chat, or in Setup). Pure.
 */
data class CustomTool(
    val id: Long, val name: String, val description: String, val url: String,
    val params: List<Param>, val enabled: Boolean,
) {
    data class Param(val name: String, val description: String)

    /** Offered to the model as "my_<name>". */
    val toolName get() = PREFIX + name

    fun spec(): ToolSpec = ToolSpec(toolName, "$description (a tool made for this user; calls ${host()})", JSONObject()
        .put("type", "object")
        .put("properties", JSONObject().apply { params.forEach { put(it.name, JSONObject().put("type", "string").put("description", it.description)) } })
        .put("required", JSONArray(params.map { it.name })))

    fun host(): String = runCatching { java.net.URI(url.replace(Regex("\\{[^}]*\\}"), "x")).host }.getOrNull().orEmpty()

    /** The URL with each {param} replaced by its URL-encoded argument. */
    fun expand(args: JSONObject): String = params.fold(url) { u, p -> u.replace("{${p.name}}", URLEncoder.encode(args.optString(p.name), "UTF-8")) }

    companion object {
        const val PREFIX = "my_"

        fun normalizeName(s: String) = s.trim().lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_').take(30)

        /** Null when it's acceptable, else why not. */
        fun problem(name: String, url: String, params: List<Param>): String? = when {
            name.length < 2 -> "The tool needs a short name"
            !url.startsWith("https://") -> "Only https:// URLs are allowed"
            Regex("\\{([^}]*)\\}").findAll(url).map { it.groupValues[1] }.any { ph -> params.none { it.name == ph } } ->
                "Every {placeholder} in the URL must be one of the parameters"
            runCatching { java.net.URI(url.replace(Regex("\\{[^}]*\\}"), "x")).host }.getOrNull().isNullOrBlank() -> "The URL has no host"
            else -> null
        }

        fun encodeParams(p: List<Param>) = JSONArray().apply { p.forEach { put(JSONObject().put("name", it.name).put("description", it.description)) } }.toString()
        fun decodeParams(s: String): List<Param> = runCatching {
            val a = JSONArray(s); (0 until a.length()).map { a.getJSONObject(it).let { o -> Param(o.getString("name"), o.optString("description")) } }
        }.getOrDefault(emptyList())
    }
}
