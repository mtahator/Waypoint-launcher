package app.olauncher.features.automation

import app.olauncher.features.automation.domain.AutomationEvent
import app.olauncher.features.automation.domain.AutomationRule
import app.olauncher.features.automation.domain.ActionSpec
import app.olauncher.features.automation.domain.ConditionEvaluator
import app.olauncher.features.automation.domain.ConditionSpec
import app.olauncher.features.automation.domain.MatchMode
import app.olauncher.features.automation.domain.NextTimeCalculator
import app.olauncher.features.automation.domain.ReasonCode
import app.olauncher.features.automation.domain.RuleStatus
import app.olauncher.features.automation.domain.SafeRegex
import app.olauncher.features.automation.domain.ToggleMode
import app.olauncher.features.automation.domain.TriggerSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ConditionAndTimeTest {
    private val london = ZoneId.of("Europe/London")
    private val evaluator = ConditionEvaluator { _, text -> text.contains("£") }

    @Test
    fun timeRangeIncludingWrapPastMidnight() {
        val t = { h: Int, m: Int -> LocalTime.of(h, m) }
        assertTrue(ConditionEvaluator.isTimeBetween(t(9, 0), t(9, 0), t(17, 0)))
        assertFalse(ConditionEvaluator.isTimeBetween(t(17, 0), t(9, 0), t(17, 0)))
        assertTrue(ConditionEvaluator.isTimeBetween(t(23, 30), t(22, 0), t(6, 0)))
        assertTrue(ConditionEvaluator.isTimeBetween(t(5, 59), t(22, 0), t(6, 0)))
        assertFalse(ConditionEvaluator.isTimeBetween(t(12, 0), t(22, 0), t(6, 0)))
        assertTrue(ConditionEvaluator.isTimeBetween(t(3, 0), t(8, 0), t(8, 0)))
    }

    @Test
    fun dayOfWeekUsesTheEventZone() {
        // Friday 23:30 in London is already Saturday in Tokyo
        val instant = Instant.parse("2026-10-02T22:30:00Z")
        val event = AutomationEvent.Gesture("k", instant.toEpochMilli())
        val weekdays = listOf(ConditionSpec.DayOfWeekIn(setOf(DayOfWeek.FRIDAY)))
        assertNull(evaluator.firstFailure(weekdays, event, instant.atZone(london)))
        assertEquals(ReasonCode.CONDITION_DAY, evaluator.firstFailure(weekdays, event, instant.atZone(ZoneId.of("Asia/Tokyo"))))
    }

    @Test
    fun notificationConditions() {
        val n = AutomationEvent.NotificationReceived("com.bank", "k", "Revolut", "Paid £4.50 at Coffee", 0, false, 0)
        val now = ZonedDateTime.now(london)
        assertNull(evaluator.firstFailure(listOf(ConditionSpec.SourcePackageIn(setOf("com.bank")), ConditionSpec.AmountExtractable), n, now))
        assertEquals(ReasonCode.CONDITION_SOURCE, evaluator.firstFailure(listOf(ConditionSpec.SourcePackageIn(setOf("x"))), n, now))
        assertNull(evaluator.firstFailure(listOf(ConditionSpec.TextMatches(MatchMode.CONTAINS, "paid")), n, now))
        assertNull(evaluator.firstFailure(listOf(ConditionSpec.TextMatches(MatchMode.STARTS_WITH, "revolut")), n, now))
        assertNull(evaluator.firstFailure(listOf(ConditionSpec.TextMatches(MatchMode.SAFE_REGEX, "£\\d+\\.\\d{2}")), n, now))
        assertEquals(ReasonCode.CONDITION_TEXT,
            evaluator.firstFailure(listOf(ConditionSpec.TextMatches(MatchMode.CONTAINS, "paid", ignoreCase = false)), n, now))
        val redacted = n.copy(redacted = true)
        assertEquals(ReasonCode.CONDITION_AMOUNT, evaluator.firstFailure(listOf(ConditionSpec.AmountExtractable), redacted, now))
        // Notification-only conditions never pass for other events
        assertEquals(ReasonCode.CONDITION_SOURCE,
            evaluator.firstFailure(listOf(ConditionSpec.SourcePackageIn(setOf("com.bank"))), AutomationEvent.Gesture("k", 0), now))
    }

    @Test
    fun safeRegexAbortsCatastrophicInputs() {
        assertEquals(SafeRegex.Check.Dangerous, SafeRegex.check("(a+)+b"))
        // Even a pattern that slips past the static check is bounded by the access budget.
        val start = System.nanoTime()
        assertFalse(SafeRegex.find("(a|aa)*c", "a".repeat(2_000), ignoreCase = false))
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2_000)
        assertTrue(SafeRegex.find("paid", "You PAID", ignoreCase = true))
    }

    private fun timeRule(id: String, h: Int, m: Int, days: Set<DayOfWeek> = DayOfWeek.entries.toSet(), zone: String? = null, created: Long = 0) =
        AutomationRule(id, id, RuleStatus.ENABLED, TriggerSpec.Time(LocalTime.of(h, m), days, zone), emptyList(),
            listOf(ActionSpec.SetLauncherMonochrome(ToggleMode.ON)), createdAt = created, updatedAt = 0)

    @Test
    fun nextFireIsTheSingleEarliestAcrossRules() {
        val now = ZonedDateTime.of(2026, 10, 27, 7, 0, 0, 0, london).toInstant()
        val next = NextTimeCalculator.nextFire(listOf(timeRule("a", 9, 0), timeRule("b", 8, 0), timeRule("c", 8, 0)), now, london)
        assertEquals(ZonedDateTime.of(2026, 10, 27, 8, 0, 0, 0, london).toInstant(), next?.at)
        assertEquals(setOf("b", "c"), next?.ruleIds)
        assertNull(NextTimeCalculator.nextFire(emptyList(), now, london))
        val disabled = timeRule("d", 8, 0).copy(status = RuleStatus.DISABLED)
        assertNull(NextTimeCalculator.nextFire(listOf(disabled), now, london))
    }

    @Test
    fun nextFireSkipsToSelectedDayAndIsStrictlyAfterNow() {
        val tuesday8 = ZonedDateTime.of(2026, 10, 27, 8, 0, 0, 0, london).toInstant()
        val r = timeRule("a", 8, 0, setOf(DayOfWeek.TUESDAY))
        assertEquals(ZonedDateTime.of(2026, 11, 3, 8, 0, 0, 0, london).toInstant(), NextTimeCalculator.nextFire(listOf(r), tuesday8, london)?.at)
    }

    @Test
    fun dstGapAndOverlap() {
        // 29 Mar 2026: London clocks jump 01:00 -> 02:00. A 01:30 rule fires at the shifted instant (02:30 BST).
        val before = Instant.parse("2026-03-28T12:00:00Z")
        val gap = NextTimeCalculator.nextFire(listOf(timeRule("g", 1, 30, setOf(DayOfWeek.SUNDAY))), before, london)
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), gap?.at)
        // 25 Oct 2026: 01:30 happens twice; fire once, at the earlier offset (BST, 00:30Z).
        val overlap = NextTimeCalculator.nextFire(listOf(timeRule("o", 1, 30, setOf(DayOfWeek.SUNDAY))), Instant.parse("2026-10-24T12:00:00Z"), london)
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), overlap?.at)
    }

    @Test
    fun ruleZoneOverridesDeviceZone() {
        val now = Instant.parse("2026-10-27T00:00:00Z")
        val r = timeRule("t", 9, 0, zone = "Asia/Tokyo")
        // 09:00 Tokyo == 00:00Z: exactly "now", so the next one is tomorrow
        assertEquals(Instant.parse("2026-10-28T00:00:00Z"), NextTimeCalculator.nextFire(listOf(r), now, london)?.at)
    }
}
