package app.olauncher.features.space.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** A to-do pulled from speech or text; [due] in epoch millis, null = no time given. */
data class ParsedTask(val title: String, val due: Long?)

/**
 * On-phone reminder extraction: "remind me to call mom tomorrow at 5pm", "remember to pay rent on Friday",
 * "don't forget the milk", "I need to email Sam in 2 hours", Persian "یادم بنداز فردا ساعت ۵ به مامان زنگ بزنم".
 * Times without a date mean the next occurrence; dates without a time default to 9:00.
 */
object ReminderParser {

    private val TRIGGERS = listOf(
        Regex("""\b(?:please\s+)?remind me (?:to |that |about )?(.+)""", RegexOption.IGNORE_CASE),
        Regex("""\bremember to (.+)""", RegexOption.IGNORE_CASE),
        Regex("""\bdon'?t forget (?:to |the |about )?(.+)""", RegexOption.IGNORE_CASE),
        Regex("""\bi (?:need|have|must|should) to (.+)""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:todo|to-do|to do):?\s+(.+)""", RegexOption.IGNORE_CASE),
        Regex("""(?:یادم (?:بنداز|باشه)|یادآوری کن|فراموش نکنم|باید)\s+(.+)"""),
    )

    fun parse(text: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<ParsedTask> {
        val sentences = normalizeDigits(text).split(Regex("""(?<=[.!?؟\n])\s+|\n"""))
        val out = ArrayList<ParsedTask>()
        for (s in sentences) {
            val body = TRIGGERS.firstNotNullOfOrNull { it.find(s)?.groupValues?.get(1) } ?: continue
            val (when_, rest) = extractTime(body, now, zone)
            val title = rest.trim().trimEnd('.', '!', '?', ',', '؟').trim().replace(Regex("\\s+"), " ")
                .replaceFirstChar { it.uppercase() }
            if (title.length >= 2) out += ParsedTask(title, when_)
        }
        return out.distinctBy { it.title.lowercase() }
    }

    private val WEEKDAYS = mapOf(
        "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY, "sunday" to DayOfWeek.SUNDAY,
        "دوشنبه" to DayOfWeek.MONDAY, "سه‌شنبه" to DayOfWeek.TUESDAY, "سه شنبه" to DayOfWeek.TUESDAY, "چهارشنبه" to DayOfWeek.WEDNESDAY,
        "پنجشنبه" to DayOfWeek.THURSDAY, "پنج‌شنبه" to DayOfWeek.THURSDAY, "جمعه" to DayOfWeek.FRIDAY, "شنبه" to DayOfWeek.SATURDAY,
        "یکشنبه" to DayOfWeek.SUNDAY, "یک‌شنبه" to DayOfWeek.SUNDAY,
    )

    private val IN_REL = Regex("""\bin (\d+|an?|one|two|three|half an?) ?(minute|min|hour|hr|day|week)s?\b""", RegexOption.IGNORE_CASE)
    private val FA_REL = Regex("""(\d+)\s*(دقیقه|ساعت|روز|هفته)\s*(?:دیگه|دیگر|بعد)""")
    private val AT_TIME = Regex("""\b(?:at|by|@)\s*(\d{1,2})(?::(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?""", RegexOption.IGNORE_CASE)
    private val BARE_TIME = Regex("""\b(\d{1,2})(?::(\d{2}))?\s*(am|pm)\b""", RegexOption.IGNORE_CASE)
    private val FA_TIME = Regex("""ساعت\s*(\d{1,2})(?::(\d{2}))?\s*(صبح|ظهر|عصر|بعدازظهر|بعد از ظهر|شب)?""")
    private val ON_DAY = Regex("""\b(?:on |this |next )?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""", RegexOption.IGNORE_CASE)

    /** Finds a time expression, removes it from the text, and returns (epoch millis or null, remaining text). */
    fun extractTime(input: String, now: Long, zone: ZoneId): Pair<Long?, String> {
        var text = " $input "
        val nowDt = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        var date: LocalDate? = null
        var time: LocalTime? = null
        var relative: LocalDateTime? = null

        IN_REL.find(text)?.let { m ->
            val n = when (val a = m.groupValues[1].lowercase()) { "a", "an", "one" -> 1L; "two" -> 2L; "three" -> 3L; else -> if (a.startsWith("half")) 0L else a.toLong() }
            val unit = m.groupValues[2].lowercase()
            relative = when {
                unit.startsWith("min") -> nowDt.plusMinutes(n)
                unit.startsWith("h") -> if (n == 0L) nowDt.plusMinutes(30) else nowDt.plusHours(n)
                unit.startsWith("d") -> nowDt.plusDays(n)
                else -> nowDt.plusWeeks(n)
            }
            text = text.replaceRange(m.range, " ")
        }
        FA_REL.find(text)?.let { m ->
            val n = m.groupValues[1].toLong()
            relative = when (m.groupValues[2]) { "دقیقه" -> nowDt.plusMinutes(n); "ساعت" -> nowDt.plusHours(n); "روز" -> nowDt.plusDays(n); else -> nowDt.plusWeeks(n) }
            text = text.replaceRange(m.range, " ")
        }

        fun word(w: String) = Regex("\\b$w\\b", RegexOption.IGNORE_CASE)
        when {
            word("tomorrow").containsMatchIn(text) -> { date = nowDt.toLocalDate().plusDays(1); text = word("tomorrow").replace(text, " ") }
            word("tonight").containsMatchIn(text) -> { date = nowDt.toLocalDate(); time = LocalTime.of(20, 0); text = word("tonight").replace(text, " ") }
            word("this evening").containsMatchIn(text) -> { date = nowDt.toLocalDate(); time = LocalTime.of(18, 0); text = word("this evening").replace(text, " ") }
            word("this morning").containsMatchIn(text) -> { date = nowDt.toLocalDate(); time = LocalTime.of(9, 0); text = word("this morning").replace(text, " ") }
            word("today").containsMatchIn(text) -> { date = nowDt.toLocalDate(); text = word("today").replace(text, " ") }
            text.contains("پس‌فردا") || text.contains("پس فردا") -> { date = nowDt.toLocalDate().plusDays(2); text = text.replace("پس‌فردا", " ").replace("پس فردا", " ") }
            text.contains("فردا") -> { date = nowDt.toLocalDate().plusDays(1); text = text.replace("فردا", " ") }
            text.contains("امشب") -> { date = nowDt.toLocalDate(); time = LocalTime.of(20, 0); text = text.replace("امشب", " ") }
            text.contains("امروز") -> { date = nowDt.toLocalDate(); text = text.replace("امروز", " ") }
        }
        ON_DAY.find(text)?.let { m ->
            val day = WEEKDAYS.getValue(m.groupValues[1].lowercase())
            date = nowDt.toLocalDate().with(TemporalAdjusters.next(day))
            text = text.replaceRange(m.range, " ")
        }
        if (date == null) WEEKDAYS.entries.sortedByDescending { it.key.length }.firstOrNull { it.key.any { c -> c.code > 0x600 } && text.contains(it.key) }?.let { (k, d) ->
            date = nowDt.toLocalDate().with(TemporalAdjusters.next(d)); text = text.replace(k, " ")
        }
        val tm = AT_TIME.find(text) ?: BARE_TIME.find(text)
        if (tm != null) {
            var h = tm.groupValues[1].toInt(); val min = tm.groupValues[2].ifEmpty { "0" }.toInt()
            val ap = tm.groupValues[3].lowercase().replace(".", "")
            if (ap == "pm" && h < 12) h += 12
            if (ap == "am" && h == 12) h = 0
            if (ap.isEmpty() && h in 1..7) h += 12 // "at 5" usually means 5 pm
            if (h in 0..23 && min in 0..59) { time = LocalTime.of(h, min); text = text.replaceRange(tm.range, " ") }
        } else FA_TIME.find(text)?.let { m ->
            var h = m.groupValues[1].toInt(); val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            val part = m.groupValues[3]
            if ((part == "عصر" || part == "شب" || part.startsWith("بعد")) && h < 12) h += 12
            if (part.isEmpty() && h in 1..7) h += 12
            if (h in 0..23 && min in 0..59) { time = LocalTime.of(h, min); text = text.replaceRange(m.range, " ") }
        }

        val due: LocalDateTime? = relative ?: when {
            date != null -> LocalDateTime.of(date, time ?: LocalTime.of(9, 0))
            time != null -> LocalDateTime.of(nowDt.toLocalDate(), time).let { if (it.isAfter(nowDt)) it else it.plusDays(1) }
            else -> null
        }
        text = text.replace(Regex("""\b(at|on|by)\s*$""", RegexOption.IGNORE_CASE), " ")
        return due?.atZone(zone)?.toInstant()?.toEpochMilli() to text.trim()
    }

    fun normalizeDigits(s: String) = buildString {
        for (c in s) append(when (c) { in '۰'..'۹' -> '0' + (c - '۰'); in '٠'..'٩' -> '0' + (c - '٠'); else -> c })
    }
}
