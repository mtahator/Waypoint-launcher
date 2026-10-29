package app.olauncher.features.space.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

enum class GoalPeriod { DAY, WEEK, MONTH, TOTAL }

/** Progress for "3× a week", "every day", "read 12 books" (TOTAL). Pure. */
object Goals {
    data class Progress(val done: Int, val target: Int, val streak: Int) {
        val fraction get() = if (target <= 0) 0f else (done.toFloat() / target).coerceIn(0f, 1f)
        val complete get() = done >= target
    }

    fun periodStart(period: GoalPeriod, day: LocalDate): LocalDate = when (period) {
        GoalPeriod.DAY -> day
        GoalPeriod.WEEK -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        GoalPeriod.MONTH -> day.withDayOfMonth(1)
        GoalPeriod.TOTAL -> LocalDate.MIN
    }

    private fun next(period: GoalPeriod, start: LocalDate) = when (period) {
        GoalPeriod.DAY -> start.plusDays(1); GoalPeriod.WEEK -> start.plusWeeks(1); GoalPeriod.MONTH -> start.plusMonths(1); GoalPeriod.TOTAL -> start
    }

    /** [checkins] are epoch millis. Streak = consecutive completed periods ending with the current or previous one. */
    fun progress(period: GoalPeriod, target: Int, checkins: List<Long>, now: Long, zone: ZoneId = ZoneId.systemDefault()): Progress {
        val days = checkins.map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        if (period == GoalPeriod.TOTAL) return Progress(days.size, target, 0)
        val start = periodStart(period, today)
        val done = days.count { !it.isBefore(start) && it.isBefore(next(period, start)) }
        var streak = 0
        var p = if (done >= target) start else when (period) {
            GoalPeriod.DAY -> start.minusDays(1); GoalPeriod.WEEK -> start.minusWeeks(1); else -> start.minusMonths(1)
        }
        while (true) {
            val n = days.count { !it.isBefore(p) && it.isBefore(next(period, p)) }
            if (n < target) break
            streak++
            p = when (period) { GoalPeriod.DAY -> p.minusDays(1); GoalPeriod.WEEK -> p.minusWeeks(1); else -> p.minusMonths(1) }
            if (streak > 1000) break
        }
        return Progress(done, target, streak)
    }
}
