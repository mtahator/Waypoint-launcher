package app.olauncher.features.gestures.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.olauncher.core.WaypointPrefs
import app.olauncher.features.gestures.domain.CornerGestureRecognizer
import app.olauncher.features.gestures.domain.SafeInsets
import kotlin.math.abs

/**
 * Wraps the home screen and owns the corner swipe. It watches every touch without consuming it
 * (`onInterceptTouchEvent`), so upstream gestures and taps on the clock, date and apps work as before.
 * Only when a touch that STARTED in the corner zone moves downward/inward past touch slop does it take
 * over; Android then sends ACTION_CANCEL to whichever child had the touch (no click, no notification
 * shade). The zone sits below the status bar even when that is hidden, and inside system-gesture insets.
 */
class CornerSwipeLayout(context: Context, private val prefs: WaypointPrefs, private val onCornerSwipe: () -> Unit) :
    FrameLayout(context) {

    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var sensitivity = prefs.cornerSensitivity
    private var recognizer = recognizerFor(context, sensitivity)

    private var inZone = false
    private var claimed = false
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var inwardSign = -1

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                claimed = false
                inZone = prefs.cornerSwipeEnabled && isInCornerZone(ev)
                downX = ev.x
                downY = ev.y
                downTime = ev.eventTime
            }
            MotionEvent.ACTION_MOVE -> if (inZone && !claimed) {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (dy > slop && dx * inwardSign >= -slop && dy >= abs(dx) * 0.35f) {
                    claimed = true
                    return true
                }
                if (abs(dx) > slop || dy < -slop) inZone = false // another gesture: leave it alone
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> inZone = false
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!claimed) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP -> {
                claimed = false
                inZone = false
                if (recognizer.isRecognized(ev.x - downX, ev.y - downY, ev.eventTime - downTime, inwardSign)) {
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    onCornerSwipe()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                claimed = false
                inZone = false
            }
        }
        return true
    }

    private fun isInCornerZone(ev: MotionEvent): Boolean {
        if (sensitivity != prefs.cornerSensitivity) {
            sensitivity = prefs.cornerSensitivity
            recognizer = recognizerFor(context, sensitivity)
        }
        val insets = ViewCompat.getRootWindowInsets(this)
        val gestures = insets?.getInsets(WindowInsetsCompat.Type.systemGestures())
        // Status bar height even while hidden (upstream hides it by default): a pull from the very top edge
        // is always the notification shade, never a corner swipe.
        val status = insets?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars())
        val minTop = (MIN_TOP_DP * resources.displayMetrics.density).toInt()
        val safe = SafeInsets(
            left = gestures?.left ?: 0,
            top = maxOf(gestures?.top ?: 0, status?.top ?: 0) + minTop,
            right = gestures?.right ?: 0,
        )
        val isRtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val corner = prefs.cornerSwipeCorner
        inwardSign = recognizer.inwardSign(corner, isRtl)
        return recognizer.ownsDown(recognizer.zone(width, corner, isRtl, safe), ev.x, ev.y)
    }

    companion object {
        private val MIN_DISTANCE_DP = floatArrayOf(48f, 64f, 88f)

        /** Extra gap below the status bar before the corner zone starts. */
        private const val MIN_TOP_DP = 24f

        fun recognizerFor(context: Context, sensitivity: Int): CornerGestureRecognizer {
            val d = context.resources.displayMetrics.density
            return CornerGestureRecognizer(
                zoneSizePx = 72f * d,
                minDistancePx = MIN_DISTANCE_DP[sensitivity.coerceIn(0, 2)] * d,
                touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
            )
        }
    }
}
