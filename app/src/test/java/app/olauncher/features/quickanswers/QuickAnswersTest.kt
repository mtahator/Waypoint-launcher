package app.olauncher.features.quickanswers

import app.olauncher.features.quickanswers.domain.Calculator
import app.olauncher.features.quickanswers.domain.QuickAnswers
import app.olauncher.features.quickanswers.domain.UnitConverter
import app.olauncher.features.worldclock.domain.City
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class QuickAnswersTest {
    private fun calc(s: String) = Calculator.evaluate(s)

    @Test fun arithmetic() {
        assertEquals(40.0, calc("12*3+4")!!, 1e-9)
        assertEquals(14.0, calc("2+3*4")!!, 1e-9)
        assertEquals(20.0, calc("(2+3)*4")!!, 1e-9)
        assertEquals(512.0, calc("2^3^2")!!, 1e-9)
        assertEquals(-6.0, calc("-2*3")!!, 1e-9)
        assertEquals(2.5, calc("5 ÷ 2")!!, 1e-9)
        assertEquals(12.0, calc("3×4")!!, 1e-9)
        assertEquals(3.0, calc("√9")!!, 1e-9)
        assertEquals(1234.0 * 2, calc("1,234*2")!!, 1e-9)
    }

    @Test fun persianDigits() = assertEquals(7.0, calc("۳+۴")!!, 1e-9)

    @Test fun notExpressions() {
        assertNull(calc("chrome"))
        assertNull(calc("c++"))
        assertNull(calc("42"))
        assertNull(calc("-5"))
        assertNull(calc("2026-10-28"))
        assertNull(calc("2+"))
        assertNull(calc("1/0"))
        assertNull(calc("e"))
    }

    @Test fun percentOf() {
        val a = QuickAnswers.answer("15% of 80")!!
        assertEquals("12", a.value)
        assertEquals("12", a.copy)
    }

    @Test fun units() {
        assertEquals(3.10686, UnitConverter.convert("5 km to mi")!!.value, 1e-4)
        assertEquals(37.7778, UnitConverter.convert("100 f to c")!!.value, 1e-3)
        assertEquals(5.51156, UnitConverter.convert("2.5 kg in lb")!!.value, 1e-4)
        assertNull(UnitConverter.convert("5 km to kg"))
        assertEquals("3.10686 mi", QuickAnswers.answer("5 km to mi")!!.value)
        assertEquals("= 12×3+4", QuickAnswers.answer("12x3+4")!!.detail)
    }

    @Test fun timeInCity() {
        val tokyo = City("Tokyo", "Japan", "Asia/Tokyo")
        val now = Instant.parse("2026-10-28T03:00:00Z")
        val a = QuickAnswers.answer("time in tokyo", { if (it == "tokyo") tokyo else null }, now)!!
        assertEquals("12:00", a.value)
        assertEquals("TOKYO · Wed 28 Oct", a.detail)
        assertNull(QuickAnswers.answer("time in atlantis", { null }, now))
    }

    @Test fun formatting() {
        assertEquals("1,234,567.89", QuickAnswers.format(1234567.89))
        assertEquals("0.3333333333", QuickAnswers.format(1.0 / 3))
        assertEquals("-12", QuickAnswers.format(-12.0))
        assertEquals("0.3", QuickAnswers.format(0.1 + 0.2))
    }
}
