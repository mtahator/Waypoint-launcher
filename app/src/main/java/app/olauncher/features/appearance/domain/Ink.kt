package app.olauncher.features.appearance.domain

import kotlin.math.pow

/**
 * Picks text colour from what is behind it: white or near-black, whichever has the higher WCAG contrast.
 * Also the glass tints and a small box blur for the backdrop. Pure.
 */
object Ink {
    const val LIGHT = 0xFFFFFFFF.toInt()
    const val DARK = 0xFF141414.toInt()

    /** Dark glass: black over the backdrop; light glass: white. */
    const val DARK_TINT = 0.26f
    const val LIGHT_TINT = 0.40f

    /** Above this backdrop luminance a card turns to light glass (where dark glass would need dark ink). */
    const val LIGHT_GLASS_FROM = 0.26f

    private fun channel(c: Int): Double { val v = c / 255.0; return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4) }

    /** WCAG relative luminance, 0..1. */
    fun luminance(argb: Int): Float = (0.2126 * channel((argb shr 16) and 0xFF) + 0.7152 * channel((argb shr 8) and 0xFF) + 0.0722 * channel(argb and 0xFF)).toFloat()

    fun contrast(a: Float, b: Float): Float = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)

    private val LIGHT_L = luminance(LIGHT)
    private val DARK_L = luminance(DARK)

    /** True when white ink reads better than dark ink on a background of luminance [bg]. */
    fun lightInkOn(bg: Float): Boolean = contrast(LIGHT_L, bg) >= contrast(DARK_L, bg)

    /** Luminance seen through glass of the given kind over a backdrop of luminance [bg]. */
    fun throughGlass(bg: Float, lightGlass: Boolean): Float =
        if (lightGlass) bg * (1 - LIGHT_TINT) + LIGHT_L * LIGHT_TINT else bg * (1 - DARK_TINT)

    fun lightGlassOver(bg: Float) = bg >= LIGHT_GLASS_FROM

    /** Neutral (grey) colours follow the ink; coloured ones (the red accent, event colours) keep theirs. */
    fun isNeutral(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        val max = maxOf(r, g, b); val min = minOf(r, g, b)
        return max == 0 || (max - min).toFloat() / max < 0.22f
    }

    fun withAlphaOf(ink: Int, original: Int): Int = (ink and 0x00FFFFFF) or (original and 0xFF000000.toInt())

    /** Average luminance of a [w]×[h] pixel array inside the rectangle (clamped). */
    fun average(px: IntArray, w: Int, h: Int, left: Int, top: Int, right: Int, bottom: Int): Float {
        val l = left.coerceIn(0, w - 1); val t = top.coerceIn(0, h - 1)
        val r = right.coerceIn(l + 1, w); val b = bottom.coerceIn(t + 1, h)
        var sum = 0.0; var n = 0
        val step = maxOf(1, ((r - l) * (b - t)) / 400) // at most ~400 samples
        var i = 0
        for (y in t until b) for (x in l until r) { if (i++ % step == 0) { sum += luminance(px[y * w + x]); n++ } }
        return if (n == 0) 0f else (sum / n).toFloat()
    }

    /** Three passes of a box blur (≈ Gaussian), in place. */
    fun blur(px: IntArray, w: Int, h: Int, radius: Int) {
        if (radius < 1 || w < 2 || h < 2) return
        val tmp = IntArray(px.size)
        repeat(3) { pass(px, tmp, w, h, radius, true); pass(tmp, px, w, h, radius, false) }
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val div = 2 * r + 1
        for (line in 0 until lines) {
            fun at(i: Int): Int { val k = i.coerceIn(0, len - 1); return if (horizontal) src[line * w + k] else src[k * w + line] }
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in -r..r) { val c = at(i); a += c ushr 24; rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF }
            for (i in 0 until len) {
                val out = ((a / div) shl 24) or ((rr / div) shl 16) or ((g / div) shl 8) or (b / div)
                if (horizontal) dst[line * w + i] = out else dst[i * w + line] = out
                val add = at(i + r + 1); val sub = at(i - r)
                a += (add ushr 24) - (sub ushr 24); rr += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                g += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF); b += (add and 0xFF) - (sub and 0xFF)
            }
        }
    }
}
