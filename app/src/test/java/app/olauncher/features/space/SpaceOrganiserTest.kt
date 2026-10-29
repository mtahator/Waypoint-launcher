package app.olauncher.features.space

import app.olauncher.features.space.domain.Categories
import app.olauncher.features.space.domain.GoalPeriod
import app.olauncher.features.space.domain.Goals
import app.olauncher.features.space.domain.ReminderParser
import app.olauncher.features.space.domain.SpaceAi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class SpaceOrganiserTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 10, 29, 10, 0).toInstant(ZoneOffset.UTC).toEpochMilli() // Thursday 10:00
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = LocalDateTime.of(y, mo, d, h, mi).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test fun categories() {
        assertEquals("Shopping", Categories.categorize("Buy milk, bread and eggs at the supermarket"))
        assertEquals("Work", Categories.categorize("Meeting with the client about the project deadline"))
        assertEquals("Health", Categories.categorize("Dentist appointment and gym after"))
        assertEquals("Finance", Categories.categorize("پرداخت قبض برق و اجاره"))
        assertEquals(Categories.OTHER, Categories.categorize("hmm"))
        assertEquals("Travel", Categories.normalize("travel"))
    }

    @Test fun reminders() {
        val r = ReminderParser.parse("Okay. Remind me to call mom tomorrow at 5pm. Also nice weather.", now, zone)
        assertEquals(1, r.size)
        assertEquals("Call mom", r[0].title)
        assertEquals(at(2026, 10, 30, 17), r[0].due)

        assertEquals(at(2026, 10, 29, 12), ReminderParser.parse("remind me to stretch in 2 hours", now, zone)[0].due)
        assertEquals(at(2026, 10, 30, 9), ReminderParser.parse("remember to pay rent on Friday", now, zone)[0].due)
        assertEquals("Pay rent", ReminderParser.parse("remember to pay rent on Friday", now, zone)[0].title)
        assertEquals(at(2026, 10, 29, 20), ReminderParser.parse("don't forget to take the bins out tonight", now, zone)[0].due)
        assertNull(ReminderParser.parse("I need to finish the report", now, zone)[0].due)
        assertEquals(at(2026, 10, 29, 17), ReminderParser.parse("remind me at 5 to leave", now, zone)[0].due)
        assertEquals(at(2026, 10, 30, 9, 30), ReminderParser.parse("remind me to check email at 9:30 am", now, zone)[0].due)

        val fa = ReminderParser.parse("یادم بنداز فردا ساعت ۵ عصر به مامان زنگ بزنم", now, zone)
        assertEquals(1, fa.size)
        assertEquals(at(2026, 10, 30, 17), fa[0].due)
        assertTrue(fa[0].title.contains("مامان"))
        assertTrue(ReminderParser.parse("The meeting went well.", now, zone).isEmpty())
    }

    @Test fun goals() {
        val day = 86_400_000L
        val weekly = Goals.progress(GoalPeriod.WEEK, 3, listOf(now, now - day, now - 2 * day), now, zone) // Tue, Wed, Thu
        assertEquals(3, weekly.done); assertTrue(weekly.complete); assertEquals(1, weekly.streak)
        val daily = Goals.progress(GoalPeriod.DAY, 1, listOf(now - day, now - 2 * day, now - 3 * day), now, zone)
        assertEquals(0, daily.done); assertEquals(3, daily.streak) // today not done yet, streak continues from yesterday
        assertEquals(2, Goals.progress(GoalPeriod.TOTAL, 12, listOf(1, 2), now, zone).done)
    }

    @Test fun aiParse() {
        val a = SpaceAi.parse("""{"title":"Call mom","summary":"Remember to call.","category":"personal","tasks":[{"title":"Call mom","due":"2026-10-30T17:00"},{"title":"Buy cake","due":null}]}""", zone)!!
        assertEquals("Personal", a.category)
        assertEquals(at(2026, 10, 30, 17), a.tasks[0].due)
        assertNull(a.tasks[1].due)
        assertNull(SpaceAi.parse("no json"))
    }
}
