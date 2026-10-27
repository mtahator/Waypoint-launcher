package app.olauncher.features.gestures.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.olauncher.core.WaypointPrefs
import app.olauncher.features.gestures.domain.CornerGestureRecognizer
import app.olauncher.features.gestures.domain.SafeInsets
import kotlin.math.abs

/**
 * Single owner of home-surface touch interpretation. Every event goes to the upstream listener
 * (swipes, taps, long-press, double-tap) until a gesture that STARTED in the corner zone moves
 * downward/inward past touch slop. Only then does the corner swipe claim it: upstream receives
 * ACTION_CANCEL (so no swipe-down/notification shade fires) and the swipe is evaluated on UP.
 * The zone is computed from system-gesture and status-bar insets, so the back gesture and the
 * notification pull never start a corner swipe.
 */
class HomeGestureCoordinator(
    private val context: Context,
    private val prefs: WaypointPrefs,
    private val upstream: View.OnTouchListener,
    private val onCornerSwipe: () -> Unit,
) : View.OnTouchListener {

    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var recognizer = recognizerFor(context, prefs.cornerSensitivity)
    private var sensitivity = prefs.cornerSensitivity

    private var inZone = false
    private var claimed = false
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var inwardSign = -1

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                claimed = false
                inZone = prefs.cornerSwipeEnabled && isInCornerZone(view, event)
                downX = event.x
                downY = event.y
                downTime = event.eventTime
            }
            MotionEvent.ACTION_MOVE -> if (inZone && !claimed) {
                val dx = event.x - downX
                val dy = event.y - downY
                if (dy > slop && dx * inwardSign >= -slop && dy >= abs(dx) * 0.35f) {
                    claimed = true
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    upstream.onTouch(view, cancel)
                    cancel.recycle()
                } else if (abs(dx) > slop || dy < -slop) {
                    inZone = false // some other gesture: leave it entirely to upstream
                }
            }
            MotionEvent.ACTION_UP -> if (claimed) {
                claimed = false
                inZone = false
                if (recognizer.isRecognized(event.x - downX, event.y - downY, event.eventTime - downTime, inwardSign)) {
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    onCornerSwipe()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                val wasClaimed = claimed
                claimed = false
                inZone = false
                if (wasClaimed) return true
            }
        }
        if (claimed) return true
        return upstream.onTouch(view, event)
    }

    private fun isInCornerZone(view: View, event: MotionEvent): Boolean {
        if (sensitivity != prefs.cornerSensitivity) {
            sensitivity = prefs.cornerSensitivity
            recognizer = recognizerFor(context, sensitivity)
        }
        val insets = ViewCompat.getRootWindowInsets(view)
        val gestures = insets?.getInsets(WindowInsetsCompat.Type.systemGestures())
        // Status bar height even while it is hidden (upstream hides it by default): a pull-down from the very
        // top edge is always the notification shade, never a corner swipe.
        val status = insets?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars())
        val minTop = (MIN_TOP_DP * context.resources.displayMetrics.density).toInt()
        val safe = SafeInsets(
            left = gestures?.left ?: 0,
            top = maxOf(gestures?.top ?: 0, status?.top ?: 0) + minTop,
            right = gestures?.right ?: 0,
        )
        val isRtl = view.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val corner = prefs.cornerSwipeCorner
        inwardSign = recognizer.inwardSign(corner, isRtl)
        return recognizer.ownsDown(recognizer.zone(view.width, corner, isRtl, safe), event.x, event.y)
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
