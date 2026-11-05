package app.olauncher.features.widgets.domain

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the Home widget stack holds: pages of Waypoint's own widgets and widgets from installed apps, one or
 * two per page, swiped through like a smart stack; Music joins at the front by itself while something plays.
 * Pure.
 */
sealed class WidgetItem {
    /** Waypoint's widgets. */
    data class Own(val kind: Kind) : WidgetItem()

    /** An app's widget, bound to this launcher's AppWidgetHost. */
    data class App(val appWidgetId: Int, val provider: String, val label: String) : WidgetItem()

    enum class Kind { CALENDAR, SPACE, WEATHER, MUSIC, CLOCKS, GOALS, CAPTURE }

    val key get() = when (this) { is Own -> "own:${kind.name}"; is App -> "app:$appWidgetId" }
}

/** A page of the Home stack: one widget (full width) or two side by side (squares). */
data class WidgetPage(val items: List<WidgetItem>) {
    val wide get() = items.size == 1
}

object WidgetStack {
    /** To start with: calendar, to-dos, weather + goals side by side, capture. */
    val DEFAULT_PAGES = listOf(
        WidgetPage(listOf(WidgetItem.Own(WidgetItem.Kind.CALENDAR))),
        WidgetPage(listOf(WidgetItem.Own(WidgetItem.Kind.SPACE))),
        WidgetPage(listOf(WidgetItem.Own(WidgetItem.Kind.WEATHER), WidgetItem.Own(WidgetItem.Kind.GOALS))),
        WidgetPage(listOf(WidgetItem.Own(WidgetItem.Kind.CAPTURE))),
    )

    private fun item(o: JSONObject): WidgetItem? = when {
        o.has("own") -> runCatching { WidgetItem.Own(WidgetItem.Kind.valueOf(o.getString("own"))) }.getOrNull()
        o.has("app") -> WidgetItem.App(o.getInt("app"), o.optString("provider"), o.optString("label"))
        else -> null
    }

    private fun json(i: WidgetItem) = when (i) {
        is WidgetItem.Own -> JSONObject().put("own", i.kind.name)
        is WidgetItem.App -> JSONObject().put("app", i.appWidgetId).put("provider", i.provider).put("label", i.label)
    }

    private fun items(a: JSONArray?): List<WidgetItem> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { n -> a.optJSONObject(n)?.let { item(it) } }

    fun encodePages(pages: List<WidgetPage>): String = JSONObject().put("pages", JSONArray().apply {
        pages.forEach { p -> put(JSONArray().apply { p.items.take(2).forEach { put(json(it)) } }) }
    }).toString()

    /**
     * The pages from settings. Older layouts still load: a single list becomes one widget per page, and the
     * stacks of the square/full-width layout become one page per widget, consecutive squares paired.
     * A widget appears once; empty pages are dropped.
     */
    fun decodePages(raw: String?): List<WidgetPage> {
        if (raw.isNullOrBlank()) return DEFAULT_PAGES
        val pages = runCatching {
            val t = raw.trimStart()
            when {
                t.startsWith("[") -> items(JSONArray(raw)).map { WidgetPage(listOf(it)) }
                JSONObject(raw).has("pages") -> {
                    val a = JSONObject(raw).getJSONArray("pages")
                    (0 until a.length()).map { WidgetPage(items(a.optJSONArray(it)).take(2)) }
                }
                else -> {
                    val a = JSONObject(raw).getJSONArray("stacks")
                    val out = ArrayList<WidgetPage>()
                    var square: WidgetItem? = null
                    for (k in 0 until a.length()) {
                        val o = a.getJSONObject(k)
                        val list = items(o.optJSONArray("items"))
                        if (o.optBoolean("wide", true)) { square?.let { out += WidgetPage(listOf(it)) }; square = null; list.forEach { out += WidgetPage(listOf(it)) } }
                        else for (i in list) { val s = square; if (s == null) square = i else { out += WidgetPage(listOf(s, i)); square = null } }
                    }
                    square?.let { out += WidgetPage(listOf(it)) }
                    out
                }
            }
        }.getOrNull() ?: return DEFAULT_PAGES
        val seen = HashSet<String>()
        return pages.map { p -> WidgetPage(p.items.filter { seen.add(it.key) }) }.filter { it.items.isNotEmpty() }
    }

    /** While music has a session (playing or paused) it is the first page, unless the user placed it. */
    fun withMusic(pages: List<WidgetPage>, active: Boolean): List<WidgetPage> {
        val music = WidgetItem.Own(WidgetItem.Kind.MUSIC)
        if (!active) return pages.map { p -> WidgetPage(p.items.filter { it != music }) }.filter { it.items.isNotEmpty() }
        if (pages.any { music in it.items }) return pages
        return listOf(WidgetPage(listOf(music))) + pages
    }

    fun all(pages: List<WidgetPage>) = pages.flatMap { it.items }

    fun <T> move(items: List<T>, from: Int, to: Int): List<T> {
        if (from !in items.indices || to !in items.indices) return items
        val m = items.toMutableList()
        m.add(to, m.removeAt(from))
        return m
    }
}

/** An event in the Calendar widget (from the phone's calendars, which include synced Google calendars). */
data class AgendaEvent(val title: String, val begin: Long, val end: Long, val allDay: Boolean, val color: Int, val location: String = "")

object Agenda {
    /** The events still to come today (and all-day ones), then tomorrow's; at most [limit]. */
    fun upcoming(events: List<AgendaEvent>, now: Long, endOfToday: Long, limit: Int = 4): List<AgendaEvent> =
        events.filter { it.end > now }.sortedWith(compareBy({ !it.allDay || it.begin >= endOfToday }, { it.begin })).take(limit)

    /** "Free until 14:00", "In 25 min", "Now" for the next timed event. */
    fun nextLabel(e: AgendaEvent, now: Long): String {
        if (e.allDay) return "All day"
        if (e.begin <= now) return "Now"
        val min = ((e.begin - now) / 60_000).toInt()
        return when {
            min < 60 -> "In $min min"
            min < 24 * 60 -> "In ${min / 60} h ${min % 60} min"
            else -> ""
        }
    }
}
