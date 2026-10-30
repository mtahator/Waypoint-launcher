package app.olauncher.features.space.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * How a to-do repeats: every day, on weekdays, on chosen days of the week, monthly, or every N days.
 * Ticking off a repeating to-do moves it to its next date (same time) instead of finishing it. Pure.
 */
data class RepeatRule(val kind: Kind, val days: Set<DayOfWeek> = emptySet(), val every: Int = 1) {
    enum class Kind { NONE, DAILY, WEEKDAYS, WEEKLY, MONTHLY, EVERY_N_DAYS }

    val repeats get() = kind != Kind.NONE

    /** "weekly:MON,THU", "every:3", "daily"… ("" = doesn't repeat). */
    fun encode(): String = when (kind) {
        Kind.NONE -> ""
        Kind.DAILY -> "daily"
        Kind.WEEKDAYS -> "weekdays"
        Kind.WEEKLY -> "weekly:" + days.sorted().joinToString(",") { it.name.take(3) }
        Kind.MONTHLY -> "monthly"
        Kind.EVERY_N_DAYS -> "every:$every"
    }

    /** "Every day", "Weekdays", "Mon, Thu", "Monthly", "Every 3 days" ("" when it doesn't repeat). */
    fun describe(): String = when (kind) {
        Kind.NONE -> ""
        Kind.DAILY -> "Every day"
        Kind.WEEKDAYS -> "Weekdays"
        Kind.WEEKLY -> if (days.isEmpty()) "Weekly" else "Every " + days.sorted().joinToString(", ") {
            it.name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() }
        }
        Kind.MONTHLY -> "Monthly"
        Kind.EVERY_N_DAYS -> if (every == 1) "Every day" else "Every $every days"
    }

    /** The first occurrence strictly after [from]'s date, at [from]'s time. */
    fun next(from: LocalDateTime): LocalDateTime {
        val d = from.toLocalDate()
        val date = when (kind) {
            Kind.NONE -> d
            Kind.DAILY -> d.plusDays(1)
            Kind.EVERY_N_DAYS -> d.plusDays(every.coerceAtLeast(1).toLong())
            Kind.MONTHLY -> d.plusMonths(1)
            Kind.WEEKDAYS -> generateSequence(d.plusDays(1)) { it.plusDays(1) }.first { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }
            Kind.WEEKLY -> {
                val on = days.ifEmpty { setOf(d.dayOfWeek) }
                generateSequence(d.plusDays(1)) { it.plusDays(1) }.first { it.dayOfWeek in on }
            }
        }
        return LocalDateTime.of(date, from.toLocalTime())
    }

    companion object {
        val NONE = RepeatRule(Kind.NONE)

        fun decode(s: String?): RepeatRule {
            val t = s?.trim()?.lowercase().orEmpty()
            return when {
                t.isEmpty() -> NONE
                t == "daily" -> RepeatRule(Kind.DAILY)
                t == "weekdays" -> RepeatRule(Kind.WEEKDAYS)
                t == "monthly" -> RepeatRule(Kind.MONTHLY)
                t.startsWith("every:") -> RepeatRule(Kind.EVERY_N_DAYS, every = t.removePrefix("every:").toIntOrNull()?.coerceIn(1, 365) ?: 1)
                t.startsWith("weekly") -> RepeatRule(Kind.WEEKLY, t.substringAfter(':', "").split(',').mapNotNull { code ->
                    DayOfWeek.values().firstOrNull { it.name.take(3).equals(code.trim(), true) }
                }.toSet())
                else -> NONE
            }
        }

        /**
         * Where a repeating to-do goes when it's ticked off: the next occurrence after its current date that
         * is also in the future (so ticking an overdue one skips the missed days). A to-do without a date
         * gets one, starting from [now] at 09:00.
         */
        fun advance(due: Long, rule: RepeatRule, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
            if (!rule.repeats) return due
            val nowDt = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
            var t = if (due > 0) LocalDateTime.ofInstant(Instant.ofEpochMilli(due), zone) else LocalDateTime.of(nowDt.toLocalDate(), LocalTime.of(9, 0))
            var guard = 0
            do { t = rule.next(t); guard++ } while (!t.isAfter(nowDt) && guard < 2_000)
            return t.atZone(zone).toInstant().toEpochMilli()
        }
    }
}

/**
 * A project goal: finish something by a deadline, tracked as % done (e.g. "coursework in 2 weeks").
 * Compares progress with an even pace from start to deadline. Pure.
 */
object Projects {
    data class Status(
        val percent: Int, val daysLeft: Int, val daysTotal: Int,
        /** Where an even pace would be by now. */
        val expected: Int,
        /** % per day still needed to finish on time. */
        val perDay: Double,
    ) {
        val done get() = percent >= 100
        val overdue get() = !done && daysLeft < 0
        /** Ahead (positive) or behind (negative) an even pace, in percentage points. */
        val delta get() = percent - expected
    }

    fun status(start: Long, deadline: Long, percent: Int, now: Long, zone: ZoneId = ZoneId.systemDefault()): Status {
        fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val s = day(start); val e = day(deadline); val today = day(now)
        val total = (e.toEpochDay() - s.toEpochDay()).toInt().coerceAtLeast(1)
        val left = (e.toEpochDay() - today.toEpochDay()).toInt()
        val elapsed = (today.toEpochDay() - s.toEpochDay()).toInt().coerceIn(0, total)
        val p = percent.coerceIn(0, 100)
        val expected = (elapsed * 100 / total).coerceIn(0, 100)
        val perDay = if (p >= 100) 0.0 else (100 - p).toDouble() / (left + 1).coerceAtLeast(1)
        return Status(p, left, total, expected, perDay)
    }

    /** The deadline as an epoch millis at the end of [date] (local). */
    fun endOf(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
        date.atTime(23, 59).atZone(zone).toInstant().toEpochMilli()
}

/**
 * Finds a repeat in a to-do's text and removes it: "every day", "daily", "weekdays", "every Monday and
 * Thursday", "every week", "every 3 days", "monthly"; Persian "هر روز", "هر هفته", "هر دوشنبه". Pure.
 */
object RepeatParser {
    private val DAYS = mapOf(
        "monday" to DayOfWeek.MONDAY, "mon" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "tue" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "wed" to DayOfWeek.WEDNESDAY, "thursday" to DayOfWeek.THURSDAY, "thu" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "fri" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY, "sat" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY, "sun" to DayOfWeek.SUNDAY,
        "دوشنبه" to DayOfWeek.MONDAY, "سه‌شنبه" to DayOfWeek.TUESDAY, "سه شنبه" to DayOfWeek.TUESDAY, "چهارشنبه" to DayOfWeek.WEDNESDAY,
        "پنجشنبه" to DayOfWeek.THURSDAY, "پنج‌شنبه" to DayOfWeek.THURSDAY, "جمعه" to DayOfWeek.FRIDAY, "شنبه" to DayOfWeek.SATURDAY,
        "یکشنبه" to DayOfWeek.SUNDAY, "یک‌شنبه" to DayOfWeek.SUNDAY,
    )
    private const val DAY_WORDS = "monday|mon|tuesday|tue|wednesday|wed|thursday|thu|friday|fri|saturday|sat|sunday|sun"
    private val WEEKLY_ON = Regex("""\b(?:every|each|on)\s+((?:(?:$DAY_WORDS)s?(?:\s*,\s*|\s+and\s+|\s*&\s*|\s+))+)""", RegexOption.IGNORE_CASE)
    private val EVERY_N = Regex("""\bevery\s+(\d{1,3}|two|three|four|five|six|seven)\s+days?\b""", RegexOption.IGNORE_CASE)
    private val SIMPLE = listOf(
        Regex("""\b(?:every\s*day|daily|each day)\b""", RegexOption.IGNORE_CASE) to RepeatRule(RepeatRule.Kind.DAILY),
        Regex("""\b(?:every\s+weekday|on\s+weekdays|weekdays)\b""", RegexOption.IGNORE_CASE) to RepeatRule(RepeatRule.Kind.WEEKDAYS),
        Regex("""\b(?:every\s+week|weekly|each week)\b""", RegexOption.IGNORE_CASE) to RepeatRule(RepeatRule.Kind.WEEKLY),
        Regex("""\b(?:every\s+month|monthly|each month)\b""", RegexOption.IGNORE_CASE) to RepeatRule(RepeatRule.Kind.MONTHLY),
        Regex("""هر\s*روز""") to RepeatRule(RepeatRule.Kind.DAILY),
        Regex("""هر\s*هفته""") to RepeatRule(RepeatRule.Kind.WEEKLY),
        Regex("""هر\s*ماه""") to RepeatRule(RepeatRule.Kind.MONTHLY),
    )

    /** (rule, text without it). */
    fun extract(text: String): Pair<RepeatRule, String> {
        fun clean(s: String) = s.replace(Regex("\\s{2,}"), " ").trim().trimEnd(',', '.').trim()
        EVERY_N.find(text)?.let { m ->
            val n = when (val w = m.groupValues[1].lowercase()) { "two" -> 2; "three" -> 3; "four" -> 4; "five" -> 5; "six" -> 6; "seven" -> 7; else -> w.toInt() }
            return RepeatRule(RepeatRule.Kind.EVERY_N_DAYS, every = n.coerceIn(1, 365)) to clean(text.replaceRange(m.range, " "))
        }
        WEEKLY_ON.find(text)?.takeIf { it.value.trim().lowercase().let { v -> v.startsWith("every") || v.startsWith("each") } }?.let { m ->
            val days = Regex(DAY_WORDS, RegexOption.IGNORE_CASE).findAll(m.groupValues[1]).mapNotNull { DAYS[it.value.lowercase()] }.toSet()
            if (days.isNotEmpty()) return RepeatRule(RepeatRule.Kind.WEEKLY, days) to clean(text.replaceRange(m.range, " "))
        }
        Regex("""هر\s*(یک‌شنبه|یکشنبه|دوشنبه|سه‌شنبه|سه شنبه|چهارشنبه|پنج‌شنبه|پنجشنبه|جمعه|شنبه)""").find(text)?.let { m ->
            DAYS[m.groupValues[1]]?.let { return RepeatRule(RepeatRule.Kind.WEEKLY, setOf(it)) to clean(text.replaceRange(m.range, " ")) }
        }
        for ((re, rule) in SIMPLE) re.find(text)?.let { m -> return rule to clean(text.replaceRange(m.range, " ")) }
        return RepeatRule.NONE to text.trim()
    }
}
