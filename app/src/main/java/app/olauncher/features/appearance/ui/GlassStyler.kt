package app.olauncher.features.appearance.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import app.olauncher.R
import app.olauncher.helper.isDarkThemeOn
import app.olauncher.helper.isEinkDisplay
import java.util.function.Consumer

/**
 * Glass: a monochrome, dot-matrix look in the spirit of Nothing OS.
 *
 * - The whole wallpaper is blurred behind the launcher window (Android 12+, cross-window blur) and
 *   covered by a soft scrim. Where the system can't blur (older Android, battery saver, some GPUs)
 *   the scrim simply gets denser, so text always stays readable.
 * - Type: a dot-matrix face for clocks, monospaced caps for captions, a single red accent dot.
 * - Surfaces: hairline-bordered translucent cards and capsules. No shadows, no pills behind labels.
 *
 * Cost: plain drawables and one system blur pass handled by the compositor; no offscreen layers.
 */
object GlassStyler {

    const val ACCENT_RED = 0xFFD71921.toInt()

    private const val BLUR_RADIUS_DP = 56f
    private var dotTypeface: Typeface? = null
    private var blurListener: Consumer<Boolean>? = null

    // ---------------------------------------------------------------- window

    /** Full-screen blur + scrim for a window that shows the wallpaper. */
    fun applyWindow(window: Window) {
        val context = window.context
        val dark = context.isDarkThemeOn()
        val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !context.isEinkDisplay() &&
            window.windowManager.isCrossWindowBlurEnabled
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            window.setBackgroundBlurRadius(if (canBlur) dp(context, BLUR_RADIUS_DP).toInt() else 0)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(scrim(dark, canBlur)))
    }

    /** Removes blur when glass is off or the wallpaper is hidden. */
    fun clearWindow(window: Window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setBackgroundBlurRadius(0)
    }

    /** Re-applies when the system turns cross-window blur on/off (e.g. battery saver). */
    fun watchBlurAvailability(window: Window, onChange: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || blurListener != null) return
        val l = Consumer<Boolean> { onChange() }
        blurListener = l
        window.windowManager.addCrossWindowBlurEnabledListener(l)
    }

    fun unwatchBlurAvailability(window: Window) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        blurListener?.let { window.windowManager.removeCrossWindowBlurEnabledListener(it) }
        blurListener = null
    }

    private fun scrim(dark: Boolean, blurred: Boolean): Int = when {
        dark && blurred -> 0x66000000
        dark -> 0xA6000000.toInt()
        blurred -> 0x59FFFFFF
        else -> 0x99FFFFFF.toInt()
    }

    // ---------------------------------------------------------------- type

    fun dotFont(context: Context): Typeface =
        dotTypeface ?: (ResourcesCompat.getFont(context, R.font.doto_dot) ?: Typeface.MONOSPACE).also { dotTypeface = it }

    /** Big dot-matrix clock. */
    fun styleClock(view: TextView) {
        view.typeface = dotFont(view.context)
        view.setShadowLayer(0f, 0f, 0f, 0)
        view.letterSpacing = 0.02f
        view.includeFontPadding = false
    }

    /** Monospaced, letter-spaced caption ("TUE 27 OCT"). */
    fun styleCaption(view: TextView, accentDot: Boolean = false) {
        view.typeface = Typeface.MONOSPACE
        view.isAllCaps = true
        view.letterSpacing = 0.14f
        view.setShadowLayer(0f, 0f, 0f, 0)
        if (accentDot) {
            view.setCompoundDrawablesRelativeWithIntrinsicBounds(accentDot(view.context), null, null, null)
            view.compoundDrawablePadding = dp(view.context, 10f).toInt()
        }
    }

    /** App labels: crisp, no shadow, a touch of tracking. */
    fun styleLabel(view: TextView) {
        view.setShadowLayer(0f, 0f, 0f, 0)
        view.letterSpacing = 0.01f
    }

    fun accentDot(context: Context, sizeDp: Float = 7f): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(ACCENT_RED)
        val s = dp(context, sizeDp).toInt()
        setSize(s, s)
    }

    // ---------------------------------------------------------------- surfaces

    /** Hairline glass card. */
    fun card(context: Context, radiusDp: Float = 24f): Drawable {
        val dark = context.isDarkThemeOn()
        return GradientDrawable().apply {
            cornerRadius = dp(context, radiusDp)
            setColor(if (dark) 0x40000000 else 0x80FFFFFF.toInt()) // tinted so text reads over bright glass
            setStroke(hairline(context), if (dark) 0x29FFFFFF else 0x1F000000)
        }
    }

    /** Hairline capsule for inputs (drawer search). */
    fun capsule(context: Context): Drawable {
        val dark = context.isDarkThemeOn()
        return GradientDrawable().apply {
            cornerRadius = dp(context, 100f)
            setColor(if (dark) 0x1AFFFFFF else 0x80FFFFFF.toInt())
            setStroke(hairline(context), if (dark) 0x40FFFFFF else 0x29000000)
        }
    }

    fun styleField(view: View) {
        val c = view.context
        view.background = capsule(c)
        val h = dp(c, 18f).toInt()
        val v = dp(c, 6f).toInt()
        view.setPaddingRelative(h, v, h, v)
    }

    /** Replaces every card-like background (direct children that have one) inside [container]. */
    fun styleCards(container: ViewGroup) {
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is ViewGroup && child.background != null) child.background = card(container.context)
        }
    }

    /** Lets the blurred window show through a full-screen page (drawer, settings). */
    fun clearPageBackground(view: View) {
        view.background = null
    }

    private fun hairline(context: Context) = dp(context, 1f).toInt().coerceAtLeast(1)

    fun dp(context: Context, v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics)
}
