package app.olauncher.features.appearance

import app.olauncher.features.appearance.domain.DotMatrixFont
import app.olauncher.features.appearance.domain.ReededGlass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassRenderingTest {
    private fun gradient(w: Int, h: Int) = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        (0xFF shl 24) or ((x * 255 / (w - 1)) shl 16) or ((y * 255 / (h - 1)) shl 8) or 0x80
    }

    @Test
    fun keepsSizeAndIsOpaqueAndDeterministic() {
        val w = 120; val h = 200
        val p = ReededGlass.Params(ribPx = 12)
        val a = ReededGlass.apply(gradient(w, h), w, h, p)
        assertEquals(w * h, a.size)
        assertTrue(a.all { (it ushr 24) == 0xFF })
        assertArrayEquals(a, ReededGlass.apply(gradient(w, h), w, h, p))
    }

    @Test
    fun monochromeIsGray() {
        val w = 60; val h = 80
        val out = ReededGlass.apply(gradient(w, h), w, h, ReededGlass.Params(ribPx = 10, monochrome = true, grain = 0f))
        assertTrue(out.all { ((it shr 16) and 0xFF) == ((it shr 8) and 0xFF) && ((it shr 8) and 0xFF) == (it and 0xFF) })
    }

    @Test
    fun ribsHaveBrightLeadingAndDarkTrailingEdges() {
        val w = 100; val h = 40
        val flat = IntArray(w * h) { 0xFF808080.toInt() }
        val out = ReededGlass.apply(flat, w, h, ReededGlass.Params(ribPx = 10, darken = 0f, grain = 0f))
        val lead = out[20 * w + 10] and 0xFF
        val trail = out[20 * w + 19] and 0xFF
        assertTrue("lead=$lead trail=$trail", lead > 128 && trail < 128)
    }

    @Test
    fun boxBlurPreservesFlatImages() {
        val flat = IntArray(30 * 30) { 0xFF336699.toInt() }
        assertArrayEquals(flat, ReededGlass.boxBlur(flat, 30, 30, 3, 3))
    }

    @Test
    fun dotFontCoversClockAndDates() {
        assertTrue(DotMatrixFont.supports("21:36"))
        assertTrue(DotMatrixFont.supports("SAT, 21 DEC"))
        assertTrue(DotMatrixFont.supports("tue, 27 oct, 100%"))
        assertTrue(DotMatrixFont.supports("۲۱:۳۶")) // Persian digits map to ASCII
        assertFalse(DotMatrixFont.supports("سه‌شنبه"))
        assertNull(DotMatrixFont.glyph('ж'))
        assertEquals(5 + 1 + 5 + 1 + 1 + 1 + 5 + 1 + 5, DotMatrixFont.width("21:36"))
        DotMatrixFont.run { "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".forEach { c -> assertEquals(7, glyph(c)?.size) } }
    }
}
