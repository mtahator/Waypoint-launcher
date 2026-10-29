package app.olauncher.features.space

import app.olauncher.features.space.domain.ReminderRequest
import app.olauncher.features.space.domain.TranscriptCleanup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ReminderRequestTest {
    private val zone = ZoneId.of("Europe/London")
    private val now = LocalDateTime.of(2026, 10, 29, 12, 41).atZone(zone).toInstant().toEpochMilli()
    private fun at(h: Int, m: Int = 0, day: Int = 29) = LocalDateTime.of(2026, 10, day, h, m).atZone(zone).toInstant().toEpochMilli()

    @Test fun setReminderTonight() {
        val r = ReminderRequest.parse("Set reminder for tonight that I must do the dishes!", now, zone)!!
        assertEquals("Do the dishes", r.title)
        assertEquals(at(20), r.due)
        assertTrue(r.remind)
    }

    @Test fun remindMeVariants() {
        ReminderRequest.parse("remind me to call mom at 5pm", now, zone)!!.let { assertEquals("Call mom", it.title); assertEquals(at(17), it.due) }
        ReminderRequest.parse("Can you remind me tomorrow at 9:30 am about the dentist", now, zone)!!.let {
            assertEquals("The dentist", it.title); assertEquals(at(9, 30, 30), it.due)
        }
        ReminderRequest.parse("set a reminder in 2 hours to take the pills", now, zone)!!.let { assertEquals("Take the pills", it.title); assertEquals(at(14, 41), it.due) }
    }

    @Test fun todos() {
        ReminderRequest.parse("add buy milk to my to-do list", now, zone)!!.let { assertEquals("Buy milk", it.title); assertNull(it.due); assertEquals(false, it.remind) }
        ReminderRequest.parse("Add a task: finish the report by Friday", now, zone)!!.let { assertEquals("Finish the report", it.title); assertNotNull(it.due) }
    }

    @Test fun persian() {
        val r = ReminderRequest.parse("یادم بنداز فردا ساعت ۵ به مامان زنگ بزنم", now, zone)!!
        assertEquals("به مامان زنگ بزنم", r.title)
        assertEquals(at(17, 0, 30), r.due)
    }

    @Test fun questionsAreNotRequests() {
        assertNull(ReminderRequest.parse("What's the weather today in Nottingham?", now, zone))
        assertNull(ReminderRequest.parse("How do I set a reminder on Android?", now, zone))
        assertNull(ReminderRequest.parse("I have to admit this is great", now, zone))
    }

    @Test fun cleanupPartsAndAcceptance() {
        val raw = (1..400).joinToString("\n") { "this is recognised piece number $it of a long meeting" }
        val parts = TranscriptCleanup.parts(raw, 2_000)
        assertTrue(parts.size > 5)
        assertTrue(parts.all { it.length <= 2_000 })
        assertEquals(raw.replace("\n", " "), parts.joinToString(" ").replace("\n", " "))

        val part = "so um today we talked about the budget and uh the timeline for the launch next month"
        assertEquals("Today we talked about the budget and the timeline for the launch next month.",
            TranscriptCleanup.accept(part, "Here's the cleaned text:\nToday we talked about the budget and the timeline for the launch next month."))
        assertNull(TranscriptCleanup.accept(part, "Budget and timeline."))  // summarised
        assertNull(TranscriptCleanup.accept(part, ""))
    }

    @Test fun talksAreNotTodos() {
        val talk = "Hey, how are you? So, this is a test that I want to do on a long, long talk about PINNs, which are " +
            "the flagship physics-informed neural networks. Basically, what you do is create neural networks. I have to say " +
            "the loss function is the physics equation. With automatic differentiation we calculate derivatives and the residual."
        assertTrue(app.olauncher.features.space.domain.ReminderParser.parse(talk, now, zone).isEmpty())
        // a long note still keeps explicit reminders
        assertEquals("Email the slides", app.olauncher.features.space.domain.ReminderParser.parse("$talk Remind me to email the slides tomorrow.", now, zone).single().title)
    }

    @Test fun incompleteRecognition() {
        val chunks = app.olauncher.features.space.domain.SpeechChunks
        assertTrue(chunks.looksIncomplete(words = 9, speechMs = 14_000))
        assertEquals(false, chunks.looksIncomplete(words = 30, speechMs = 12_000))
        assertEquals(false, chunks.looksIncomplete(words = 1, speechMs = 1_200))
    }
}
