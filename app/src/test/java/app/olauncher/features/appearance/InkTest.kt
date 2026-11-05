package app.olauncher.features.appearance

import app.olauncher.features.appearance.domain.Ink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InkTest {
    @Test fun picksReadableInk() {
        assertTrue(Ink.lightInkOn(Ink.luminance(0xFF000000.toInt())))
        assertFalse(Ink.lightInkOn(Ink.luminance(0xFFFFFFFF.toInt())))
        assertTrue(Ink.lightInkOn(Ink.luminance(0xFF3A3A3A.toInt())))
        assertFalse(Ink.lightInkOn(Ink.luminance(0xFFBDBDBD.toInt())))
    }

    @Test fun halfBlackHalfWhite() {
        // a 4×2 screen, left half black, right half white: each side gets its own ink
        val b = 0xFF000000.toInt(); val w = 0xFFFFFFFF.toInt()
        val px = intArrayOf(b, b, w, w, b, b, w, w)
        val map = Ink.brightnessMap(px, 4, 2)
        assertTrue(Ink.lightInkOn(Ink.average(map, 4, 2, 0, 0, 2, 2)))
        assertFalse(Ink.lightInkOn(Ink.average(map, 4, 2, 2, 0, 4, 2)))
        assertEquals(0.5f, Ink.average(map, 4, 2, 0, 0, 4, 2), 0.001f)
        // out of bounds clamps to the edge
        assertEquals(1f, Ink.average(map, 4, 2, 3, 1, 9, 9), 0.001f)
    }

    @Test fun glassKeepsInkConsistent() {
        // whichever glass a card gets over its backdrop, its ink reads with good contrast
        for (v in 0..255 step 15) {
            val bg = Ink.luminance(0xFF000000.toInt() or (v shl 16) or (v shl 8) or v)
            val light = Ink.lightGlassOver(bg)
            val through = Ink.throughGlass(bg, light)
            val ink = if (Ink.lightInkOn(through)) Ink.luminance(Ink.LIGHT) else Ink.luminance(Ink.DARK)
            assertTrue("grey $v", Ink.contrast(ink, through) >= 4.5f)
        }
    }

    @Test fun neutralAndAlpha() {
        assertTrue(Ink.isNeutral(0xA8FFFFFF.toInt()))
        assertTrue(Ink.isNeutral(0xFF141414.toInt()))
        assertFalse(Ink.isNeutral(0xFFD71921.toInt()))
        assertEquals(0xA8141414.toInt(), Ink.withAlphaOf(Ink.DARK, 0xA8FFFFFF.toInt()))
    }

    @Test fun blurKeepsFlatColour() {
        val px = IntArray(20 * 10) { 0xFF336699.toInt() }
        Ink.blur(px, 20, 10, 3)
        assertTrue(px.all { it == 0xFF336699.toInt() })
    }
}
