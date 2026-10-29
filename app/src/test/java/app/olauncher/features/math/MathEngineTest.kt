package app.olauncher.features.math

import app.olauncher.features.math.domain.Compiler
import app.olauncher.features.math.domain.MathParser
import app.olauncher.features.math.domain.MathProgram
import app.olauncher.features.math.domain.MathProgram.Kind
import app.olauncher.features.math.domain.Plotter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class MathEngineTest {
    private fun ev(s: String, x: Double = 0.0) = Compiler().compile(MathParser().parseExpression(s))(x, 0.0)

    @Test fun precedence() {
        assertEquals(14.0, ev("2+3*4"), 1e-12)
        assertEquals(-4.0, ev("-2^2"), 1e-12)
        assertEquals(512.0, ev("2^3^2"), 1e-12)
        assertEquals(0.5, ev("2^-1"), 1e-12)
        assertEquals(2.5, ev("5 ÷ 2"), 1e-12)
    }

    @Test fun functions() {
        assertEquals(1.0, ev("sin(pi/2)"), 1e-12)
        assertEquals(0.5, ev("sin(30°)"), 1e-12)
        assertEquals(0.5, ev("cos 60°"), 1e-12)
        assertEquals(2.0, ev("log 100"), 1e-12)
        assertEquals(3.0, ev("log(2, 8)"), 1e-12)
        assertEquals(1.0, ev("ln e"), 1e-12)
        assertEquals(120.0, ev("5!"), 1e-9)
        assertEquals(sqrt(PI), ev("gamma(0.5)"), 1e-9)
        assertEquals(10.0, ev("nCr(5,2)"), 1e-12)
        assertEquals(3.0, ev("|-3|"), 1e-12)
        assertEquals(-2.0, ev("(-8)^(1/3)"), 1e-12)
        assertEquals(3.0, ev("√9"), 1e-12)
        assertEquals(1.0, ev("sin^2(1)+cos^2(1)"), 1e-12)
        assertEquals(2.0, ev("max(1, 2)"), 1e-12)
        assertEquals(1.0, ev("mod(7, 3)"), 1e-12)
    }

    @Test fun implicitMultiplication() {
        assertEquals(6.0, ev("2x", 3.0), 1e-12)
        assertEquals(2 * sin(3.0), ev("2sin x", 3.0), 1e-12)
        assertEquals(sin(6.0), ev("sin 2x", 3.0), 1e-12)
        assertEquals(8.0, ev("(x+1)(x-1)", 3.0), 1e-12)
        assertEquals(PI * 9, ev("pi x^2", 3.0), 1e-12)
        assertEquals(sin(3.0) * kotlin.math.cos(3.0), ev("sinx cosx", 3.0), 1e-12)
        assertEquals(6.0, ev("2|x|", -3.0), 1e-12)
        assertEquals(1500.0, ev("1.5e3"), 1e-12)
    }

    @Test fun persian() = assertEquals(7.0, ev("۳+۴"), 1e-12)

    @Test fun program() {
        val p = MathProgram(listOf("a = 2", "f(x) = x^2", "y = a f(x) + b", "b = a + 1", "x^2 + y^2 = 9", "(1, a)", "x = 3", "", "2^10"))
        assertEquals(Kind.VARIABLE, p.lines[0].kind); assertEquals("a", p.lines[0].sliderName)
        assertEquals(Kind.FUNCTION, p.lines[1].kind)
        assertEquals(Kind.GRAPH_Y, p.lines[2].kind); assertEquals(2 * 4.0 + 3, p.lines[2].curve!!(2.0), 1e-12)
        assertEquals(Kind.VARIABLE, p.lines[3].kind); assertEquals(null, p.lines[3].sliderName); assertEquals(3.0, p.lines[3].value!!, 0.0)
        assertEquals(Kind.IMPLICIT, p.lines[4].kind)
        assertEquals(Kind.POINT, p.lines[5].kind); assertEquals(1.0 to 2.0, p.lines[5].point)
        assertEquals(Kind.GRAPH_X, p.lines[6].kind)
        assertEquals(Kind.EMPTY, p.lines[7].kind)
        assertEquals(Kind.VALUE, p.lines[8].kind); assertEquals(1024.0, p.lines[8].value!!, 0.0)
    }

    @Test fun missingBecomesSliderSuggestion() {
        val p = MathProgram(listOf("y = m x + c"))
        assertEquals(Kind.ERROR, p.lines[0].kind)
        assertEquals(listOf("c", "m"), p.lines[0].missing)
    }

    @Test fun errors() {
        val p = MathProgram(listOf("sin(", "y = 2 +"))
        assertTrue(p.lines.all { it.kind == Kind.ERROR && it.error != null })
    }

    @Test fun tanBreaksAtAsymptotes() {
        val lines = Plotter.explicit({ kotlin.math.tan(it) }, -5.0, 5.0, 400, -5.0, 5.0)
        assertTrue("expected several pieces, got ${lines.size}", lines.size >= 3)
    }

    @Test fun circleIsTraced() {
        val f = MathProgram(listOf("x^2 + y^2 = 9")).lines[0].implicit
        assertNotNull(f)
        val seg = Plotter.implicit(f!!, -5.0, 5.0, -5.0, 5.0, 60, 60)
        assertTrue(seg.size > 100)
        for (i in seg.indices step 2) assertEquals(3.0, sqrt(seg[i] * seg[i] + seg[i + 1] * seg[i + 1]), 0.1)
    }

    @Test fun poleIsNotARoot() {
        val f = MathProgram(listOf("y = 1/x")).lines[0]
        val seg = Plotter.explicit(f.curve!!, -2.0, 2.0, 401, -10.0, 10.0)
        assertTrue(seg.size >= 2)
    }

    @Test fun gridStep() {
        assertEquals(1.0, Plotter.gridStep(10.0, 10), 1e-12)
        assertEquals(2.0, Plotter.gridStep(20.0, 8), 1e-12)
        assertEquals(0.5, Plotter.gridStep(4.0, 8), 1e-12)
    }
}
