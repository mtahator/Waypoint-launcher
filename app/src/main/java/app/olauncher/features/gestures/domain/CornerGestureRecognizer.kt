package app.olauncher.features.gestures.domain

import kotlin.math.abs
import kotlin.math.hypot

enum class Corner { TOP_START, TOP_END }

data class ZoneRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
}

/** System-gesture / status bar insets in px. The start zone is always placed fully inside them. */
data class SafeInsets(val left: Int = 0, val top: Int = 0, val right: Int = 0)

/**
 * Pure corner-swipe recognizer. All distances are px (callers convert dp with display density).
 *
 * A swipe is recognized when it starts inside a square zone in the chosen top corner, travels at
 * least [minDistancePx] downward/inward (toward the screen centre), and finishes within
 * [maxDurationMs]. Horizontal or upward movement is never claimed, so upstream swipes keep working.
 */
class CornerGestureRecognizer(
    private val zoneSizePx: Float,
    private val minDistancePx: Float,
    private val touchSlopPx: Float,
    private val maxDurationMs: Long = 600L,
) {
    fun zone(viewWidth: Int, corner: Corner, isRtl: Boolean, insets: SafeInsets): ZoneRect {
        val atRightEdge = (corner == Corner.TOP_END) != isRtl
        val top = insets.top.toFloat()
        return if (atRightEdge) {
            val right = (viewWidth - insets.right).toFloat()
            ZoneRect(right - zoneSizePx, top, right, top + zoneSizePx)
        } else {
            val left = insets.left.toFloat()
            ZoneRect(left, top, left + zoneSizePx, top + zoneSizePx)
        }
    }

    fun ownsDown(zone: ZoneRect, x: Float, y: Float): Boolean = zone.contains(x, y)

    /**
     * @param inwardSign +1 when "toward centre" means increasing x (zone on the left edge), -1 otherwise.
     */
    fun isRecognized(dx: Float, dy: Float, durationMs: Long, inwardSign: Int): Boolean {
        if (durationMs > maxDurationMs) return false
        if (dy <= touchSlopPx) return false                       // must move down
        val inward = dx * inwardSign
        if (inward < -touchSlopPx) return false                    // never outward toward the edge
        if (hypot(dx, dy) < minDistancePx) return false
        // Between straight down and ~70° from vertical: excludes near-horizontal swipes.
        return dy >= abs(dx) * 0.35f
    }

    fun inwardSign(corner: Corner, isRtl: Boolean): Int = if ((corner == Corner.TOP_END) != isRtl) -1 else 1
}
