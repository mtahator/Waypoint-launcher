package app.olauncher.features.automation.domain

import java.time.LocalTime
import java.time.ZonedDateTime

/** Pure evaluation of a rule's conditions (AND in schema v1). */
class ConditionEvaluator(
    private val amountExtractable: (title: String, text: String) -> Boolean,
) {
    /** Returns null when every condition holds, otherwise the reason of the first failing one. */
    fun firstFailure(conditions: List<ConditionSpec>, event: AutomationEvent, now: ZonedDateTime): ReasonCode? {
        for (c in conditions) {
            val ok = when (c) {
                is ConditionSpec.DayOfWeekIn -> now.dayOfWeek in c.days
                is ConditionSpec.TimeBetween -> isTimeBetween(now.toLocalTime(), c.start, c.end)
                is ConditionSpec.SourcePackageIn ->
                    event is AutomationEvent.NotificationReceived && event.packageName in c.packages
                is ConditionSpec.TextMatches ->
                    event is AutomationEvent.NotificationReceived && !event.redacted &&
                        textMatches(c, event.title + "\n" + event.text)
                ConditionSpec.AmountExtractable ->
                    event is AutomationEvent.NotificationReceived && !event.redacted &&
                        amountExtractable(event.title, event.text)
            }
            if (!ok) return reasonFor(c)
        }
        return null
    }

    private fun reasonFor(c: ConditionSpec) = when (c) {
        is ConditionSpec.DayOfWeekIn -> ReasonCode.CONDITION_DAY
        is ConditionSpec.TimeBetween -> ReasonCode.CONDITION_TIME
        is ConditionSpec.SourcePackageIn -> ReasonCode.CONDITION_SOURCE
        is ConditionSpec.TextMatches -> ReasonCode.CONDITION_TEXT
        ConditionSpec.AmountExtractable -> ReasonCode.CONDITION_AMOUNT
    }

    companion object {
        /** Start inclusive, end exclusive. end <= start wraps past midnight; start == end means all day. */
        fun isTimeBetween(t: LocalTime, start: LocalTime, end: LocalTime): Boolean = when {
            start == end -> true
            start < end -> t >= start && t < end
            else -> t >= start || t < end
        }

        fun textMatches(c: ConditionSpec.TextMatches, input: String): Boolean {
            val haystack = input.take(SafeRegex.MAX_INPUT_LENGTH)
            return when (c.mode) {
                MatchMode.CONTAINS -> haystack.contains(c.pattern, ignoreCase = c.ignoreCase)
                MatchMode.STARTS_WITH -> haystack.trimStart().startsWith(c.pattern, ignoreCase = c.ignoreCase)
                MatchMode.SAFE_REGEX -> SafeRegex.find(c.pattern, haystack, c.ignoreCase)
            }
        }
    }
}
