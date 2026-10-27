package app.olauncher.features.appearance.domain

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reeded (fluted) glass: the backdrop is seen through vertical glass ribs. Each rib shows a magnified,
 * shifted slice of what's behind it, smeared vertically, with a bright leading edge and a shaded trailing
 * edge. Optional Nothing-style monochrome and film grain. Pure function on ARGB pixels; runs once per
 * setting change, off the main thread, and the result is cached.
 */
object ReededGlass {

    data class Params(
        /** Rib width in pixels of the (low-res) buffer. */
        val ribPx: Int,
        val monochrome: Boolean = false,
        /** Darkening for legibility: 0..0.9 darker (dark theme), negative values brighten (light theme). */
        val darken: Float = 0.18f,
        /** 0..1 grain amplitude. */
        val grain: Float = 0.03f,
        val seed: Int = 7,
    )

    fun apply(src: IntArray, w: Int, h: Int, p: Params): IntArray {
        require(src.size == w * h) { "size mismatch" }
        val rib = p.ribPx.coerceAtLeast(4)

        // 1. Soften the source a little so ribs read as glass, not stripes of sharp detail.
        val soft = boxBlur(src, w, h, radiusX = max(1, w / 160), radiusY = max(1, h / 160))

        // 2. Refract: each rib magnifies a slice of what's behind it, shifted sideways AND vertically
        //    across its width. The vertical term draws the rounded "scallops" along colour edges.
        val refracted = IntArray(w * h)
        val lift = rib * 1.15f // about one reed width: rounded scallops, not spikes
        for (x in 0 until w) {
            val ribStart = (x / rib) * rib
            val t = (x - ribStart + 0.5f) / rib
            val sx = (ribStart + rib * 0.5f + (t - 0.5f) * rib * 0.45f + (t - 0.5f) * rib * 0.9f)
                .roundToInt().coerceIn(0, w - 1)
            val dy = ((t - 0.5f) * (t - 0.5f) * 4f - 0.33f) * lift // parabolic: rib centre sits lower
            for (y in 0 until h) {
                val sy = (y + dy).roundToInt().coerceIn(0, h - 1)
                refracted[y * w + x] = soft[sy * w + sx]
            }
        }

        // 3. Light vertical smear inside the ribs.
        val smeared = boxBlur(refracted, w, h, radiusX = 0, radiusY = max(1, h / 140))

        // 4. Per-rib light: a crisp bright edge, soft highlight, gentle shadow toward the trailing edge.
        val shade = FloatArray(rib) { k ->
            val t = (k + 0.5f) / rib
            val edge = when (k) { 0 -> 0.22f; 1 -> 0.08f; else -> 0f }
            val highlight = 0.14f * (1f - t) * (1f - t)
            val shadow = 0.20f * t * t * t
            1f + edge + highlight - shadow
        }

        var rng = p.seed * 1103515245 + 12345
        val out = IntArray(w * h)
        val keep = 1f - p.darken.coerceIn(-0.6f, 0.9f) // negative darken brightens (light theme)
        for (y in 0 until h) {
            var i = y * w
            for (x in 0 until w) {
                val c = smeared[i]
                val f = shade[x % rib] * keep
                var r = ((c shr 16) and 0xFF) * f
                var g = ((c shr 8) and 0xFF) * f
                var b = (c and 0xFF) * f
                if (p.monochrome) {
                    val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    r = l; g = l; b = l
                }
                if (p.grain > 0f) {
                    rng = rng * 1103515245 + 12345
                    val n = (((rng ushr 16) and 0xFF) - 127.5f) / 127.5f * p.grain * 255f
                    r += n; g += n; b += n
                }
                out[i] = (0xFF shl 24) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)
                i++
            }
        }
        return out
    }

    private fun clamp(v: Float) = min(255, max(0, v.roundToInt()))

    /** Separable box blur with running sums (O(n) per pass). radius 0 skips that axis. */
    fun boxBlur(src: IntArray, w: Int, h: Int, radiusX: Int, radiusY: Int): IntArray {
        var buf = src
        if (radiusX > 0) buf = pass(buf, w, h, radiusX, horizontal = true)
        if (radiusY > 0) buf = pass(buf, w, h, radiusY, horizontal = false)
        return buf
    }

    private fun pass(src: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean): IntArray {
        val out = IntArray(src.size)
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val step = if (horizontal) 1 else w
        val window = 2 * r + 1
        for (line in 0 until lines) {
            val base = if (horizontal) line * w else line
            var sr = 0; var sg = 0; var sb = 0
            for (k in -r..r) {
                val c = src[base + k.coerceIn(0, len - 1) * step]
                sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
            }
            for (k in 0 until len) {
                out[base + k * step] = (0xFF shl 24) or ((sr / window) shl 16) or ((sg / window) shl 8) or (sb / window)
                val outC = src[base + (k - r).coerceIn(0, len - 1) * step]
                val inC = src[base + (k + r + 1).coerceIn(0, len - 1) * step]
                sr += ((inC shr 16) and 0xFF) - ((outC shr 16) and 0xFF)
                sg += ((inC shr 8) and 0xFF) - ((outC shr 8) and 0xFF)
                sb += (inC and 0xFF) - (outC and 0xFF)
            }
        }
        return out
    }
}
