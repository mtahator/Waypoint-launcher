package app.olauncher.features.quickanswers

import app.olauncher.features.quickanswers.domain.Calculator
import app.olauncher.features.quickanswers.domain.CurrencyQuery
import app.olauncher.features.quickanswers.domain.ExchangeRates
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
        val a = QuickAnswers.answer("time in tokyo", cityLookup = { if (it == "tokyo") tokyo else null }, now = now)!!
        assertEquals("12:00", a.value)
        assertEquals("TOKYO · Wed 28 Oct", a.detail)
        assertNull(QuickAnswers.answer("time in atlantis", cityLookup = { null }, now = now))
    }

    @Test fun formatting() {
        assertEquals("1,234,567.89", QuickAnswers.format(1234567.89))
        assertEquals("0.3333333333", QuickAnswers.format(1.0 / 3))
        assertEquals("-12", QuickAnswers.format(-12.0))
        assertEquals("0.3", QuickAnswers.format(0.1 + 0.2))
    }
}

class QuickAnswersMoreTest {
    @Test fun trig() {
        assertEquals("1", QuickAnswers.answer("sin(pi/2)")!!.value)
        assertEquals("0.5", QuickAnswers.answer("sin 30°")!!.value)
        assertEquals("120", QuickAnswers.answer("5!")!!.value)
    }

    @Test fun forcedMode() {
        assertNull(QuickAnswers.answer("pi"))
        assertEquals("3.141592654", QuickAnswers.answer("pi", forced = true)!!.value)
        assertEquals("42", QuickAnswers.answer("42", forced = true)!!.value)
    }

    @Test fun graphs() {
        val a = QuickAnswers.answer("sin(x)")!!
        assertEquals(listOf("sin(x)"), a.graph)
        assertEquals("y = sin(x)", a.value)
        assertEquals(listOf("x^2+y^2=9"), QuickAnswers.answer("x^2+y^2=9")!!.graph)
        assertEquals(listOf("x^2+y^2=r", "r = 1"), QuickAnswers.answer("x^2+y^2=r")!!.graph)
        assertEquals(listOf("y=a*x+b", "a = 1", "b = 1"), QuickAnswers.answer("y=a*x+b")!!.graph)
        assertEquals("y > x^2", QuickAnswers.answer("y>x^2")!!.value)
        assertNull(QuickAnswers.answer("xbox"))
        assertNull(QuickAnswers.answer("x"))
        assertEquals(emptyList<String>(), QuickAnswers.answer("graph")!!.graph)
    }

    @Test fun unitPowers() {
        assertEquals(1000.0, UnitConverter.convert("1 m3 to l")!!.value, 1e-9)
        assertEquals(1000.0, UnitConverter.convert("1 m^3 to l")!!.value, 1e-9)
        assertEquals(1000.0, UnitConverter.convert("1 m³ to l")!!.value, 1e-9)
        assertEquals(10.7639, UnitConverter.convert("1 m2 to sq ft")!!.value, 1e-4)
        assertEquals(27.7778, UnitConverter.convert("100 km/h to m/s")!!.value, 1e-4)
        assertEquals(62.1371, UnitConverter.convert("100 kmh to mph")!!.value, 1e-4)
        assertEquals(3.6, UnitConverter.convert("1 kwh to MJ")!!.value, 1e-9)
        assertEquals(32.174, UnitConverter.convert("9.80665 m/s^2 to ft/s2")!!.value, 1e-3)
        assertEquals(4046.86, UnitConverter.convert("1 acre to m2")!!.value, 1e-2)
        assertEquals(1000.0, UnitConverter.convert("1 GB to MB")!!.value, 1e-9)
        assertEquals(8.0, UnitConverter.convert("1 B to bit")!!.value, 1e-9)
        assertEquals(Math.PI / 2, UnitConverter.convert("90 deg to rad")!!.value, 1e-12)
        assertEquals(2.54, UnitConverter.convert("1 in to cm")!!.value, 1e-12)
        assertEquals(0.393701, UnitConverter.convert("cm to in")!!.value, 1e-6)
        assertEquals("ft²", UnitConverter.convert("1 m2 to sqft")!!.to)
        assertNull(UnitConverter.convert("1 m3 to kg"))
    }

    @Test fun currencyParsing() {
        assertEquals(CurrencyQuery.Query(1.0, "USD", "GBP"), CurrencyQuery.parse("1$ to £"))
        assertEquals(CurrencyQuery.Query(20.0, "EUR", "USD"), CurrencyQuery.parse("€20 in $"))
        assertEquals(CurrencyQuery.Query(100.0, "USD", "EUR"), CurrencyQuery.parse("100 usd to eur"))
        assertEquals(CurrencyQuery.Query(50.0, "EUR", CurrencyQuery.TOMAN), CurrencyQuery.parse("50 euros in toman"))
        assertNull(CurrencyQuery.parse("10 pounds to kg"))
        assertNull(CurrencyQuery.parse("5 km to mi"))
    }

    @Test fun currencyAnswer() {
        val rates = ExchangeRates("USD", mapOf("USD" to 1.0, "GBP" to 0.8, "IRR" to 420000.0), 0, 0)
        val pending = QuickAnswers.answer("1$ to £")!!
        assertEquals("…", pending.value)
        val a = QuickAnswers.answer("10$ to £", rates = rates)!!
        assertEquals("£8", a.value)
        assertEquals("420,000", QuickAnswers.answer("10 usd to toman", rates = rates)!!.value.substringBefore(" "))
    }

    @Test fun mathFunctionCommas() = assertEquals("2", QuickAnswers.answer("max(1,2)")!!.value)
}
