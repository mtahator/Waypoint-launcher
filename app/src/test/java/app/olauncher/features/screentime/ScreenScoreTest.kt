package app.olauncher.features.screentime

import app.olauncher.features.screentime.domain.ScreenScore
import app.olauncher.features.screentime.domain.ScreenScore.Kind
import app.olauncher.features.screentime.domain.ScreenScore.Use
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenScoreTest {
    private val min = 60_000L
    private val hour = 60 * min
    private val day = 1_000_000_000_000L
    private fun at(h: Int, m: Int = 0) = day + h * hour + m * min
    private val kinds = mapOf("feed" to Kind.PULL, "docs" to Kind.PURPOSE)
    private fun kind(p: String) = kinds[p] ?: Kind.NEUTRAL

    @Test fun overlapsCountOnce() {
        val uses = listOf(Use(at(9), at(9, 30), "docs"), Use(at(9, 10), at(9, 40), "feed"))
        val s = ScreenScore.summarize(uses, ::kind, 0, day, at(12))
        assertEquals(40 * min, s.totalMs)
        assertEquals(1, s.sessions)
        assertEquals(1, s.pickups) // no screen-on count: sessions stand in
    }

    @Test fun ringsAndScore() {
        // 1h of feed, 1h of docs, 3h budget; breaks: 7:00→9:00 (2h) is the longest
        val uses = listOf(Use(at(9), at(10), "feed"), Use(at(10, 30), at(11, 30), "docs"))
        val s = ScreenScore.summarize(uses, ::kind, 14, day, at(12), ScreenScore.Goals(budgetMin = 180, breakMin = 90, purposePct = 60))
        assertEquals(2 * hour, s.longestBreakMs)
        assertEquals(1f, s.focus, 0.001f)
        assertEquals(1f / 3, s.budget, 0.001f)
        assertEquals(0.5f, s.purposeShare, 0.001f)
        assertEquals(0.5f / 0.6f, s.mindful, 0.001f)
        assertEquals(Math.round(100 * (0.4f / 3 + 0.3f + 0.3f * 0.5f / 0.6f)), s.score)
        assertEquals(14, s.pickups)
        assertEquals(60, s.hourly[9]); assertEquals(30, s.hourly[10]); assertEquals(0, s.hourly[8])
        assertEquals(listOf("feed", "docs").toSet(), s.apps.map { it.pkg }.toSet())
    }

    @Test fun overBudgetAndLateNight() {
        val uses = listOf(Use(at(0), at(2), "feed"), Use(at(13), at(15), "feed"))
        val s = ScreenScore.summarize(uses, ::kind, 0, day, at(16), ScreenScore.Goals(budgetMin = 180))
        assertTrue(s.overBudget)
        assertEquals(0f, s.budget, 0f)
        assertEquals(2 * hour, s.lateNightMs)
        assertEquals(0f, s.mindful, 0f)
        assertEquals("4h", ScreenScore.duration(s.totalMs))
        assertEquals("1h 2m", ScreenScore.duration(62 * min))
        assertEquals("12m", ScreenScore.duration(12 * min))
    }

    @Test fun beforeTheDayStartsThereIsNoBreakYet() {
        val s = ScreenScore.summarize(emptyList(), ::kind, 0, day, at(6))
        assertEquals(0L, s.longestBreakMs)
        assertEquals(1f, s.budget, 0f)
        assertEquals(1f, s.mindful, 0f)
    }
}
