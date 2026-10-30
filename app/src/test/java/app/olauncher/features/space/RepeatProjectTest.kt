package app.olauncher.features.space

import app.olauncher.features.space.domain.Projects
import app.olauncher.features.space.domain.RepeatRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

class RepeatProjectTest {
    private val zone = ZoneId.of("Europe/London")
    private fun ms(y: Int, mo: Int, d: Int, h: Int = 9, mi: Int = 0) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test fun encodeDecode() {
        val rules = listOf(
            RepeatRule.NONE, RepeatRule(RepeatRule.Kind.DAILY), RepeatRule(RepeatRule.Kind.WEEKDAYS), RepeatRule(RepeatRule.Kind.MONTHLY),
            RepeatRule(RepeatRule.Kind.EVERY_N_DAYS, every = 3), RepeatRule(RepeatRule.Kind.WEEKLY, setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)),
        )
        rules.forEach { assertEquals(it, RepeatRule.decode(it.encode())) }
        assertEquals("weekly:MON,THU", rules.last().encode())
        assertEquals(RepeatRule.NONE, RepeatRule.decode("nonsense"))
    }

    @Test fun advancesToTheNextFutureOccurrence() {
        // Thu 29 Oct 2026, 12:00
        val now = ms(2026, 10, 29, 12)
        // daily at 8pm, ticked off early: tomorrow 8pm
        assertEquals(ms(2026, 10, 30, 20), RepeatRule.advance(ms(2026, 10, 29, 20), RepeatRule(RepeatRule.Kind.DAILY), now, zone))
        // overdue for a week: skips the missed days
        assertEquals(ms(2026, 10, 30, 9), RepeatRule.advance(ms(2026, 10, 22, 9), RepeatRule(RepeatRule.Kind.DAILY), now, zone))
        // weekdays from Friday: Monday
        assertEquals(ms(2026, 11, 2, 9), RepeatRule.advance(ms(2026, 10, 30, 9), RepeatRule(RepeatRule.Kind.WEEKDAYS), now, zone))
        // weekly Mon+Thu from Thursday: next Monday
        val monThu = RepeatRule(RepeatRule.Kind.WEEKLY, setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY))
        assertEquals(ms(2026, 11, 2, 18), RepeatRule.advance(ms(2026, 10, 29, 18), monThu, now, zone))
        // every 3 days and monthly
        assertEquals(ms(2026, 11, 1, 9), RepeatRule.advance(ms(2026, 10, 29, 9), RepeatRule(RepeatRule.Kind.EVERY_N_DAYS, every = 3), now, zone))
        assertEquals(ms(2026, 11, 29, 9), RepeatRule.advance(ms(2026, 10, 29, 9), RepeatRule(RepeatRule.Kind.MONTHLY), now, zone))
        // no date yet: tomorrow 9:00 for a daily one
        assertEquals(ms(2026, 10, 30, 9), RepeatRule.advance(0, RepeatRule(RepeatRule.Kind.DAILY), now, zone))
    }

    @Test fun projectPace() {
        // coursework: two weeks from 20 Oct to 3 Nov, today 27 Oct (halfway)
        val s = Projects.status(ms(2026, 10, 20), ms(2026, 11, 3, 23, 59), 30, ms(2026, 10, 27, 15), zone)
        assertEquals(14, s.daysTotal)
        assertEquals(7, s.daysLeft)
        assertEquals(50, s.expected)
        assertEquals(-20, s.delta)
        assertEquals(70.0 / 8, s.perDay, 1e-9)
        assertFalse(s.overdue)
        val late = Projects.status(ms(2026, 10, 20), ms(2026, 11, 3), 90, ms(2026, 11, 5), zone)
        assertTrue(late.overdue)
        assertTrue(Projects.status(ms(2026, 10, 20), ms(2026, 11, 3), 100, ms(2026, 11, 5), zone).done)
    }

    @Test fun parsesRepeats() {
        fun x(t: String) = app.olauncher.features.space.domain.RepeatParser.extract(t)
        assertEquals(RepeatRule(RepeatRule.Kind.DAILY) to "Take vitamins at 9am", x("Take vitamins every day at 9am"))
        assertEquals(RepeatRule(RepeatRule.Kind.WEEKLY, setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)) to "Gym at 7am", x("Gym every Monday and Thursday at 7am"))
        assertEquals(RepeatRule(RepeatRule.Kind.WEEKDAYS) to "Stand-up 10:00", x("Stand-up weekdays 10:00"))
        assertEquals(RepeatRule(RepeatRule.Kind.EVERY_N_DAYS, every = 3) to "Water the plants", x("Water the plants every 3 days"))
        assertEquals(RepeatRule(RepeatRule.Kind.MONTHLY) to "Pay rent", x("Pay rent monthly"))
        assertEquals(RepeatRule(RepeatRule.Kind.WEEKLY, setOf(DayOfWeek.MONDAY)) to "باشگاه", x("باشگاه هر دوشنبه"))
        assertEquals(RepeatRule.NONE to "Call mom on Friday", x("Call mom on Friday"))
    }

    @Test fun parsesSteps() {
        val steps = app.olauncher.features.space.domain.Steps
        assertEquals(listOf("Outline the report", "Write the method section", "Proofread"),
            steps.parse("""Here you go: {"steps": ["Outline the report", "Write the method section.", "Proofread"]}"""))
        assertEquals(listOf("Buy flour", "Preheat oven"), steps.parse("1. Buy flour\n2) Preheat oven\nEnjoy!"))
        assertTrue(steps.parse("no steps here").isEmpty())
    }
}
