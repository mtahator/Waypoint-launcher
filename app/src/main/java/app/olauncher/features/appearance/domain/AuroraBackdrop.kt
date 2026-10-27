package app.olauncher.features.appearance.domain

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Built-in backdrop for the Glass theme: warm orange-to-red sky, a blue arc, a dark orb with a violet rim.
 * Generated procedurally (no bundled image), so it fits any screen and costs no APK size.
 */
object AuroraBackdrop {

    private class Blob(
        val cx: Float, val cy: Float, val rx: Float, val ry: Float,
        val color: Int, val alpha: Float, val soft: Float,
    )

    private val BLOBS = listOf(
        Blob(0.65f, 0.55f, 0.75f, 0.25f, 0x2878EB, 1.00f, 0.06f),
        Blob(0.32f, 0.49f, 0.28f, 0.11f, 0x5AC8FF, 0.75f, 0.35f),
        Blob(0.30f, 0.78f, 0.36f, 0.15f, 0x1A0408, 1.00f, 0.08f),
        Blob(0.28f, 0.64f, 0.22f, 0.05f, 0x7832B4, 0.55f, 0.35f),
    )

    fun render(w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val t = y.toFloat() / h
            val base = if (t < 0.5f) mix(0xF2B05C, 0xD63A30, t * 2f) else mix(0xD63A30, 0xB0181E, (t - 0.5f) * 2f)
            for (x in 0 until w) out[y * w + x] = base
        }
        for (b in BLOBS) {
            val cx = b.cx * w; val cy = b.cy * h; val rx = b.rx * w; val ry = b.ry * h
            val x0 = max(0, (cx - rx * (1 + b.soft)).toInt()); val x1 = min(w - 1, (cx + rx * (1 + b.soft)).toInt())
            val y0 = max(0, (cy - ry * (1 + b.soft)).toInt()); val y1 = min(h - 1, (cy + ry * (1 + b.soft)).toInt())
            for (y in y0..y1) for (x in x0..x1) {
                val dx = (x - cx) / rx; val dy = (y - cy) / ry
                val d = sqrt(dx * dx + dy * dy)
                val a = b.alpha * smooth(1f + b.soft, 1f - b.soft, d)
                if (a > 0f) out[y * w + x] = mix(out[y * w + x], b.color, a)
            }
        }
        for (i in out.indices) out[i] = out[i] or (0xFF shl 24)
        return out
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val r = ((a shr 16) and 0xFF) + (((b shr 16) and 0xFF) - ((a shr 16) and 0xFF)) * t
        val g = ((a shr 8) and 0xFF) + (((b shr 8) and 0xFF) - ((a shr 8) and 0xFF)) * t
        val bl = (a and 0xFF) + ((b and 0xFF) - (a and 0xFF)) * t
        return (r.toInt() shl 16) or (g.toInt() shl 8) or bl.toInt()
    }
}
