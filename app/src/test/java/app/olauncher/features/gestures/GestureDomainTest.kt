package app.olauncher.features.gestures

import app.olauncher.features.gestures.domain.Corner
import app.olauncher.features.gestures.domain.CornerGestureRecognizer
import app.olauncher.features.gestures.domain.GestureAction
import app.olauncher.features.gestures.domain.SafeInsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureDomainTest {

    private fun recognizer(density: Float) = CornerGestureRecognizer(
        zoneSizePx = 72 * density, minDistancePx = 64 * density, touchSlopPx = 8 * density,
    )

    @Test
    fun zoneSitsInsideInsetsAtTopEnd() {
        val r = recognizer(2f)
        val zone = r.zone(1080, Corner.TOP_END, isRtl = false, insets = SafeInsets(left = 40, top = 60, right = 40))
        assertEquals(1040f, zone.right)
        assertEquals(1040f - 144f, zone.left)
        assertEquals(60f, zone.top)
        assertTrue(r.ownsDown(zone, 1000f, 100f))
        assertFalse(r.ownsDown(zone, 1060f, 100f)) // inside the system back-gesture inset
        assertFalse(r.ownsDown(zone, 1000f, 30f)) // status bar
        assertFalse(r.ownsDown(zone, 500f, 100f)) // upstream swipe-down area
    }

    @Test
    fun topEndMirrorsInRtl() {
        val r = recognizer(1f)
        val zone = r.zone(400, Corner.TOP_END, isRtl = true, insets = SafeInsets(left = 10))
        assertEquals(10f, zone.left)
        assertEquals(1, r.inwardSign(Corner.TOP_END, isRtl = true))
        assertEquals(-1, r.inwardSign(Corner.TOP_END, isRtl = false))
        assertEquals(1, r.inwardSign(Corner.TOP_START, isRtl = false))
    }

    @Test
    fun classifiesDirectionsAtSeveralDensities() {
        for (d in listOf(1f, 2.625f, 3.5f)) {
            val r = recognizer(d)
            val inward = -1 // top-end LTR: inward is leftwards
            assertTrue("diagonal @$d", r.isRecognized(dx = -60 * d, dy = 60 * d, durationMs = 200, inwardSign = inward))
            assertTrue("vertical @$d", r.isRecognized(dx = 0f, dy = 80 * d, durationMs = 200, inwardSign = inward))
            assertFalse("horizontal @$d", r.isRecognized(dx = -100 * d, dy = 10 * d, durationMs = 200, inwardSign = inward))
            assertFalse("outward @$d", r.isRecognized(dx = 40 * d, dy = 80 * d, durationMs = 200, inwardSign = inward))
            assertFalse("upward @$d", r.isRecognized(dx = -40 * d, dy = -80 * d, durationMs = 200, inwardSign = inward))
            assertFalse("too short @$d", r.isRecognized(dx = -20 * d, dy = 30 * d, durationMs = 200, inwardSign = inward))
            assertFalse("too slow @$d", r.isRecognized(dx = -60 * d, dy = 60 * d, durationMs = 900, inwardSign = inward))
        }
    }

    @Test
    fun longPressWithoutMovementIsNotASwipe() {
        assertFalse(recognizer(2f).isRecognized(dx = 2f, dy = 3f, durationMs = 550, inwardSign = -1))
    }

    @Test
    fun actionCodecRoundTripAndFallback() {
        val all = listOf(
            GestureAction.OpenApp("com.example.app", 10L),
            GestureAction.ToggleLauncherMonochrome,
            GestureAction.ToggleSystemGrayscale,
            GestureAction.OpenWorldClockPanel,
            GestureAction.None,
        )
        all.forEach { assertEquals(it, GestureAction.decode(GestureAction.encode(it))) }
        assertEquals(GestureAction.None, GestureAction.decode("open_app:broken", GestureAction.None))
        assertEquals(GestureAction.ToggleLauncherMonochrome, GestureAction.decode(null))
        assertEquals(GestureAction.ToggleLauncherMonochrome, GestureAction.decode("what"))
        // Removed automation action from older builds decodes to the fallback
        assertEquals(GestureAction.None, GestureAction.decode("fire_event:corner_swipe", GestureAction.None))
    }
}
