package app.olauncher.features.assistant

import app.olauncher.features.assistant.domain.AskCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AskCommandTest {
    @Test fun latinPrefix() = assertEquals("what is reeded glass", AskCommand.question("?what is reeded glass"))
    @Test fun persianPrefix() = assertEquals("هوا چطوره", AskCommand.question("؟ هوا چطوره "))
    @Test fun leadingSpaces() = assertEquals("hi", AskCommand.question("  ? hi"))
    @Test fun notACommand() {
        assertFalse(AskCommand.isCommand("chrome"))
        assertNull(AskCommand.question("chrome?"))
        assertNull(AskCommand.question(null))
    }
    @Test fun prefixOnly() {
        assertTrue(AskCommand.isCommand("?"))
        assertNull(AskCommand.question("?   "))
    }
}
