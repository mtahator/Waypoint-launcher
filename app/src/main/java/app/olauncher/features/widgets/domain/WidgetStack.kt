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

    enum class Kind { CALENDAR, SPACE, WEATHER, MUSIC, CLOCKS }

    val key get() = when (this) { is Own -> "own:${kind.name}"; is App -> "app:$appWidgetId" }
}

object WidgetStack {
    /** A stack to start with: calendar first, then the day's to-dos and the weather. */
    val DEFAULT = listOf(WidgetItem.Own(WidgetItem.Kind.CALENDAR), WidgetItem.Own(WidgetItem.Kind.SPACE), WidgetItem.Own(WidgetItem.Kind.WEATHER))

    fun encode(items: List<WidgetItem>): String = JSONArray().apply {
        items.forEach { i ->
            put(when (i) {
                is WidgetItem.Own -> JSONObject().put("own", i.kind.name)
                is WidgetItem.App -> JSONObject().put("app", i.appWidgetId).put("provider", i.provider).put("label", i.label)
            })
        }
    }.toString()

    fun decode(json: String?): List<WidgetItem> {
        if (json.isNullOrBlank()) return DEFAULT
        return runCatching {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { k ->
                val o = a.optJSONObject(k) ?: return@mapNotNull null
                when {
                    o.has("own") -> runCatching { WidgetItem.Own(WidgetItem.Kind.valueOf(o.getString("own"))) }.getOrNull()
                    o.has("app") -> WidgetItem.App(o.getInt("app"), o.optString("provider"), o.optString("label"))
                    else -> null
                }
            }.distinctBy { it.key }
        }.getOrDefault(DEFAULT)
    }

    /**
     * What's shown, in order: Music first while something is playing (whether or not it's in the stack),
     * and not at all otherwise; the rest as arranged.
     */
    fun visible(items: List<WidgetItem>, playing: Boolean): List<WidgetItem> {
        val music = WidgetItem.Own(WidgetItem.Kind.MUSIC)
        val rest = items.filter { it != music }
        return if (playing) listOf(music) + rest else rest
    }

    fun move(items: List<WidgetItem>, from: Int, to: Int): List<WidgetItem> {
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
