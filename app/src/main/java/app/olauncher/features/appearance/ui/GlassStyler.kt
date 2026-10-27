package app.olauncher.features.appearance.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.annotation.RequiresApi
import app.olauncher.helper.isDarkThemeOn
import app.olauncher.helper.isEinkDisplay

/**
 * "Glass" look: frosted translucent surfaces with a hairline edge and a soft top highlight,
 * plus real system blur of the wallpaper behind the drawer and settings on Android 12+.
 *
 * Cost model: the surfaces are plain GradientDrawables (no offscreen layers). The only GPU blur is
 * the system's cross-window blur, active only while drawer/settings are visible; Android itself
 * disables it in battery saver and on devices that can't afford it.
 */
object GlassStyler {

    enum class Surface { PILL, CARD, FIELD }

    private data class Tone(val fill: Int, val stroke: Int, val highlightTop: Int)

    private fun tone(dark: Boolean, surface: Surface): Tone = if (dark) when (surface) {
        Surface.PILL -> Tone(0x33FFFFFF, 0x40FFFFFF, 0x1FFFFFFF)
        Surface.CARD -> Tone(0x1FFFFFFF, 0x33FFFFFF, 0x14FFFFFF)
        Surface.FIELD -> Tone(0x29FFFFFF, 0x4DFFFFFF, 0x1AFFFFFF)
    } else when (surface) {
        Surface.PILL -> Tone(0x8CFFFFFF.toInt(), 0xB3FFFFFF.toInt(), 0x66FFFFFF)
        Surface.CARD -> Tone(0x73FFFFFF, 0x99FFFFFF.toInt(), 0x4DFFFFFF)
        Surface.FIELD -> Tone(0x99FFFFFF.toInt(), 0xCCFFFFFF.toInt(), 0x59FFFFFF)
    }

    fun drawable(context: Context, surface: Surface, radiusDp: Float): Drawable {
        val t = tone(context.isDarkThemeOn(), surface)
        val radius = dp(context, radiusDp)
        val base = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(t.fill)
            setStroke(dp(context, 1f).toInt().coerceAtLeast(1), t.stroke)
        }
        // Light catching the top edge: fades out by mid-height, like real glass.
        val highlight = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(t.highlightTop, 0x00FFFFFF, 0x00FFFFFF)).apply {
            cornerRadius = radius
        }
        return LayerDrawable(arrayOf(base, highlight))
    }

    /** Frosted pill behind a home text (app label, clock). Removes the text shadow, which smears on glass. */
    fun styleHomeText(view: TextView, radiusDp: Float = 20f) {
        val c = view.context
        view.background = drawable(c, Surface.PILL, radiusDp)
        view.setShadowLayer(0f, 0f, 0f, 0)
        val h = dp(c, 18f).toInt()
        view.setPaddingRelative(h, view.paddingTop, h, view.paddingBottom)
        (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            val m = dp(c, 4f).toInt()
            it.topMargin = m
            it.bottomMargin = m
            view.layoutParams = it
        }
    }

    fun styleBlock(view: View, radiusDp: Float = 28f) {
        val c = view.context
        view.background = drawable(c, Surface.PILL, radiusDp)
        val p = dp(c, 16f).toInt()
        view.setPaddingRelative(p, p, p, p)
    }

    fun styleField(view: View) {
        val c = view.context
        view.background = drawable(c, Surface.FIELD, 26f)
        val h = dp(c, 14f).toInt()
        val v = dp(c, 4f).toInt()
        view.setPaddingRelative(h, v, h, v)
    }

    /** Replaces every card-like background (direct children that have one) inside [container]. */
    fun styleCards(container: ViewGroup, radiusDp: Float = 28f) {
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is ViewGroup && child.background != null) child.background = drawable(container.context, Surface.CARD, radiusDp)
        }
    }

    // ---- Real blur of the wallpaper behind the launcher window (Android 12+)

    private var blurAnimator: ValueAnimator? = null
    private var currentRadius = 0

    fun setWindowBlur(window: Window, enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || window.context.isEinkDisplay()) return
        val target = if (enabled) dp(window.context, BLUR_RADIUS_DP).toInt() else 0
        animateBlur(window, target)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun animateBlur(window: Window, target: Int) {
        if (target == currentRadius) return
        blurAnimator?.cancel()
        if (target > 0 && !window.windowManager.isCrossWindowBlurEnabled) return
        blurAnimator = ValueAnimator.ofInt(currentRadius, target).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                currentRadius = it.animatedValue as Int
                window.setBackgroundBlurRadius(currentRadius)
            }
            start()
        }
    }

    private const val BLUR_RADIUS_DP = 36f

    private fun dp(context: Context, v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics)
}
