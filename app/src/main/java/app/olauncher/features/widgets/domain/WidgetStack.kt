package app.olauncher.features.widgets.domain

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the Home widget stack holds, in order: Waypoint's own widgets and widgets from installed apps.
 * One stack, swiped through like a smart stack; Music joins at the front by itself while something plays.
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

/** A stack on Home: square (two fit side by side) or full width; swipe through its widgets. */
data class StackSlot(val id: Int, val wide: Boolean, val items: List<WidgetItem>)

object WidgetStack {
    /** To start with: a full-width stack (calendar, to-dos, capture) and two squares (goals, weather). */
    val DEFAULT_LAYOUT = listOf(
        StackSlot(1, true, listOf(WidgetItem.Own(WidgetItem.Kind.CALENDAR), WidgetItem.Own(WidgetItem.Kind.SPACE), WidgetItem.Own(WidgetItem.Kind.CAPTURE))),
        StackSlot(2, false, listOf(WidgetItem.Own(WidgetItem.Kind.WEATHER))),
        StackSlot(3, false, listOf(WidgetItem.Own(WidgetItem.Kind.GOALS))),
    )
    val DEFAULT = DEFAULT_LAYOUT.flatMap { it.items }

    private fun item(o: JSONObject): WidgetItem? = when {
        o.has("own") -> runCatching { WidgetItem.Own(WidgetItem.Kind.valueOf(o.getString("own"))) }.getOrNull()
        o.has("app") -> WidgetItem.App(o.getInt("app"), o.optString("provider"), o.optString("label"))
        else -> null
    }

    private fun json(i: WidgetItem) = when (i) {
        is WidgetItem.Own -> JSONObject().put("own", i.kind.name)
        is WidgetItem.App -> JSONObject().put("app", i.appWidgetId).put("provider", i.provider).put("label", i.label)
    }

    fun encodeLayout(slots: List<StackSlot>): String = JSONObject().put("stacks", JSONArray().apply {
        slots.forEach { s -> put(JSONObject().put("id", s.id).put("wide", s.wide).put("items", JSONArray().apply { s.items.forEach { put(json(it)) } })) }
    }).toString()

    /** Stacks from settings; an older single list becomes one full-width stack. */
    fun decodeLayout(raw: String?): List<StackSlot> {
        if (raw.isNullOrBlank()) return DEFAULT_LAYOUT
        return runCatching {
            if (raw.trimStart().startsWith("[")) listOf(StackSlot(1, true, decode(raw)))
            else {
                val a = JSONObject(raw).getJSONArray("stacks")
                (0 until a.length()).map { k ->
                    val o = a.getJSONObject(k); val items = o.optJSONArray("items")
                    StackSlot(o.getInt("id"), o.optBoolean("wide", true),
                        if (items == null) emptyList() else (0 until items.length()).mapNotNull { n -> items.optJSONObject(n)?.let { item(it) } }.distinctBy { it.key })
                }
            }
        }.getOrDefault(DEFAULT_LAYOUT)
    }

    fun nextId(slots: List<StackSlot>) = (slots.maxOfOrNull { it.id } ?: 0) + 1

    /** Rows on Home: a full-width stack alone; square ones in pairs (a lone square sits at the side). */
    fun rows(slots: List<StackSlot>): List<List<StackSlot>> {
        val out = ArrayList<List<StackSlot>>()
        var pending: StackSlot? = null
        for (s in slots.filter { it.items.isNotEmpty() || it.id == MUSIC_SLOT }) {
            if (s.wide) { pending?.let { out += listOf(it) }; pending = null; out += listOf(s) }
            else if (pending == null) pending = s
            else { out += listOf(pending, s); pending = null }
        }
        pending?.let { out += listOf(it) }
        return out
    }

    /**
     * While music has a session (playing or paused), Music sits at the front of the first stack, unless
     * the user put it in a stack themselves.
     */
    fun withMusic(slots: List<StackSlot>, active: Boolean): List<StackSlot> {
        val music = WidgetItem.Own(WidgetItem.Kind.MUSIC)
        if (!active) return slots.map { s -> s.copy(items = s.items.filter { it != music }) }
        if (slots.any { music in it.items }) return slots
        if (slots.isEmpty()) return listOf(StackSlot(MUSIC_SLOT, true, listOf(music)))
        return slots.mapIndexed { i, s -> if (i == 0) s.copy(items = listOf(music) + s.items) else s }
    }

    const val MUSIC_SLOT = -1

    fun decode(json: String?): List<WidgetItem> {
        if (json.isNullOrBlank()) return DEFAULT
        return runCatching {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { k -> a.optJSONObject(k)?.let { item(it) } }.distinctBy { it.key }
        }.getOrDefault(DEFAULT)
    }

    fun encode(items: List<WidgetItem>): String = JSONArray().apply { items.forEach { put(json(it)) } }.toString()

    fun visible(items: List<WidgetItem>, playing: Boolean): List<WidgetItem> {
        val music = WidgetItem.Own(WidgetItem.Kind.MUSIC)
        val rest = items.filter { it != music }
        return if (playing) listOf(music) + rest else rest
    }

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
