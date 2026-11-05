package app.olauncher.features.appearance.ui

import android.appwidget.AppWidgetHostView
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import app.olauncher.R
import app.olauncher.features.appearance.domain.Ink
import app.olauncher.features.appearance.platform.BackdropSampler
import app.olauncher.features.widgets.ui.FrostedDrawable

/** A custom-drawn view that takes the ink colour (rings, bars, dot charts, page dots). */
interface Inkable { fun setInk(ink: Int) }

/**
 * Gives every text, icon and dot element on a screen its own colour from what is directly behind it
 * (docs/widget-design.md §3): white or near-black, whichever reads better there. Inside a frosted card,
 * "behind" is the backdrop at that spot seen through the card's glass, and the card itself turns to light
 * or dark glass from what is under it.
 *
 * Neutral colours follow; coloured ones (the red accent, event colours) keep theirs; the alpha of each
 * colour is kept, so primary / secondary / tertiary stay distinct. Views tagged [R.id.wp_ink_skip] and
 * app widgets are left alone. Views inside a pager page tagged [R.id.wp_ink_page] are measured at the
 * page's resting place, so a card sliding in already has the right colours.
 *
 * Runs after layout or data changes only (the caller debounces); a walk over Home is well under a millisecond.
 */
class AdaptiveInk(private val sampler: BackdropSampler, private val mode: () -> String) {
    private val loc = IntArray(2)

    fun apply(root: View) {
        val win = root.rootView
        if (win.width == 0) return
        walk(root, null, 0, 0, win.width, win.height)
    }

    private fun bg(v: View, dx: Int, dy: Int, w: Int, h: Int, left: Int, width: Int): Float {
        v.getLocationInWindow(loc)
        val x = loc[0] + dx + left
        return sampler.luminance(x, loc[1] + dy, x + width, loc[1] + dy + v.height, w, h) ?: FALLBACK
    }

    private fun walk(v: View, glass: FrostedDrawable?, dx: Int, dy: Int, w: Int, h: Int) {
        if (v.visibility != View.VISIBLE || v is AppWidgetHostView || v.getTag(R.id.wp_ink_skip) == true) return
        var card = glass
        var ox = dx; var oy = dy
        if (v.getTag(R.id.wp_ink_page) == true) {
            // measure a pager page where it rests: at its pager's left edge
            val pager = v.parent as? View
            if (pager != null) {
                v.getLocationInWindow(loc); val px = loc[0]; val py = loc[1]
                pager.getLocationInWindow(loc)
                ox = dx + loc[0] - px + pager.paddingLeft; oy = dy + loc[1] - py + pager.paddingTop
            }
        }
        val bgDrawable = v.background
        if (bgDrawable is FrostedDrawable) {
            val l = bg(v, ox, oy, w, h, 0, v.width)
            bgDrawable.light = when (mode()) { "light" -> false; "dark" -> true; else -> Ink.lightGlassOver(l) }
            bgDrawable.invalidateSelf() // the backdrop may have just arrived
            card = bgDrawable
        }
        when (v) {
            is TextView -> {
                val orig = original(v) { v.currentTextColor }
                if (Ink.isNeutral(orig)) {
                    val (left, width) = textSpan(v)
                    val ink = ink(bg(v, ox, oy, w, h, left, width), card)
                    val c = Ink.withAlphaOf(ink, orig)
                    if (v.currentTextColor != c) v.setTextColor(c)
                    shadow(v, ink == Ink.LIGHT)
                }
            }
            is DotMatrixView -> {
                val orig = original(v) { v.color }
                if (Ink.isNeutral(orig)) {
                    val c = Ink.withAlphaOf(ink(bg(v, ox, oy, w, h, 0, v.width), card), orig)
                    if (v.color != c) v.color = c
                }
            }
            is ImageView -> {
                val tint = v.imageTintList
                if (tint != null) {
                    val orig = original(v) { tint.defaultColor }
                    if (Ink.isNeutral(orig)) {
                        val c = Ink.withAlphaOf(ink(bg(v, ox, oy, w, h, 0, v.width), card), orig)
                        if (tint.defaultColor != c) v.imageTintList = ColorStateList.valueOf(c)
                    }
                }
            }
            is Inkable -> v.setInk(ink(bg(v as View, ox, oy, w, h, 0, v.width), card))
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), card, ox, oy, w, h)
    }

    private fun ink(bg: Float, card: FrostedDrawable?): Int = when (mode()) {
        "light" -> Ink.LIGHT
        "dark" -> Ink.DARK
        else -> if (Ink.lightInkOn(if (card != null) Ink.throughGlass(bg, card.light) else bg)) Ink.LIGHT else Ink.DARK
    }

    /** The colour the view was built with, remembered the first time. */
    private inline fun original(v: View, current: () -> Int): Int =
        (v.getTag(R.id.wp_ink_original) as? Int) ?: current().also { v.setTag(R.id.wp_ink_original, it) }

    /** Where the text actually is, so a short label isn't judged by the empty space beside it. */
    private fun textSpan(v: TextView): Pair<Int, Int> {
        val layout = v.layout ?: return 0 to v.width
        var maxW = 0f
        for (i in 0 until layout.lineCount) maxW = maxOf(maxW, layout.getLineWidth(i))
        val w = (maxW + v.totalPaddingLeft + v.totalPaddingRight).toInt().coerceIn(1, v.width.coerceAtLeast(1))
        val g = v.gravity and android.view.Gravity.HORIZONTAL_GRAVITY_MASK
        val rtl = v.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val left = when {
            g == android.view.Gravity.CENTER_HORIZONTAL -> (v.width - w) / 2
            g == android.view.Gravity.RIGHT || (g == android.view.Gravity.END && !rtl) || (g == android.view.Gravity.START && rtl) -> v.width - w
            else -> 0
        }
        return left to w
    }

    /** Light ink keeps a faint dark halo (reads over fine detail); dark ink stays crisp. */
    private fun shadow(v: TextView, lightInk: Boolean) {
        val d = v.resources.displayMetrics.density
        if (lightInk) { if (v.shadowRadius == 0f) v.setShadowLayer(6 * d, 0f, 0.5f * d, 0x4D000000) }
        else if (v.shadowRadius != 0f) v.setShadowLayer(0f, 0f, 0f, 0)
    }

    companion object { private const val FALLBACK = 0.1f }
}
