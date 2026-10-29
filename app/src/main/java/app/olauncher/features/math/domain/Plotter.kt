package app.olauncher.features.math.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Turns functions into drawable polylines/segments for a viewport (world coordinates). */
object Plotter {

    /**
     * Samples y = f(x) on [x0, x1] with [n] steps. Returns polylines as [x0, y0, x1, y1, …]; a line breaks
     * where f is undefined or jumps across an asymptote (e.g. tan x), so no false vertical strokes.
     */
    fun explicit(f: (Double) -> Double, x0: Double, x1: Double, n: Int, yLo: Double, yHi: Double): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        val span = yHi - yLo
        val clampLo = yLo - span * 4
        val clampHi = yHi + span * 4
        var cur = DoubleArrayBuilder()
        var px = Double.NaN
        var py = Double.NaN
        val step = (x1 - x0) / n
        for (i in 0..n) {
            val x = x0 + step * i
            val y = safe(f, x)
            if (!y.isFinite()) {
                if (cur.size >= 4) out += cur.toArray()
                cur = DoubleArrayBuilder(); px = Double.NaN; continue
            }
            if (px.isFinite() && abs(y - py) > span * 0.5) {
                // big jump: continuous (steep) or an asymptote? Check the midpoint lies between.
                val mid = safe(f, (px + x) / 2)
                val between = mid.isFinite() && mid >= min(py, y) - span * 0.05 && mid <= max(py, y) + span * 0.05
                if (!between) {
                    if (cur.size >= 4) out += cur.toArray()
                    cur = DoubleArrayBuilder()
                }
            }
            cur.add(x); cur.add(y.coerceIn(clampLo, clampHi))
            px = x; py = y
        }
        if (cur.size >= 4) out += cur.toArray()
        return out
    }

    /**
     * Marching squares for F(x, y) = 0 on an nx × ny grid. Returns segments [x1, y1, x2, y2, …].
     * Crossings where F only changes sign by blowing up (poles) are rejected.
     */
    fun implicit(f: Fn2, x0: Double, x1: Double, y0: Double, y1: Double, nx: Int, ny: Int): DoubleArray {
        val dx = (x1 - x0) / nx
        val dy = (y1 - y0) / ny
        val v = Array(ny + 1) { j -> DoubleArray(nx + 1) { i -> safe2(f, x0 + i * dx, y0 + j * dy) } }
        val out = DoubleArrayBuilder()
        val pts = DoubleArray(8)
        for (j in 0 until ny) for (i in 0 until nx) {
            val a = v[j][i]; val b = v[j][i + 1]; val c = v[j + 1][i + 1]; val d = v[j + 1][i]
            if (!(a.isFinite() && b.isFinite() && c.isFinite() && d.isFinite())) continue
            val xa = x0 + i * dx; val ya = y0 + j * dy
            var k = 0
            fun edge(fa: Double, fb: Double, ax: Double, ay: Double, bx: Double, by: Double) {
                if ((fa < 0) == (fb < 0) || fa == fb) return
                val t = fa / (fa - fb)
                val px = ax + (bx - ax) * t; val py = ay + (by - ay) * t
                val fp = safe2(f, px, py)
                if (!fp.isFinite() || abs(fp) > 0.5 * max(abs(fa), abs(fb)) + 1e-12) return // pole, not a root
                if (k < 8) { pts[k++] = px; pts[k++] = py }
            }
            edge(a, b, xa, ya, xa + dx, ya)
            edge(b, c, xa + dx, ya, xa + dx, ya + dy)
            edge(c, d, xa + dx, ya + dy, xa, ya + dy)
            edge(d, a, xa, ya + dy, xa, ya)
            if (k >= 4) { for (m in 0 until 4) out.add(pts[m]) }
            if (k == 8) { for (m in 4 until 8) out.add(pts[m]) }
        }
        return out.toArray()
    }

    /** Value of y = f(x) closest to the touch, for the trace readout. */
    fun safe(f: (Double) -> Double, x: Double): Double = try { f(x) } catch (_: Exception) { Double.NaN }
    private fun safe2(f: Fn2, x: Double, y: Double): Double = try { f(x, y) } catch (_: Exception) { Double.NaN }

    /** Grid step that gives about [targetLines] lines across [span]: 1, 2 or 5 × 10^k. */
    fun gridStep(span: Double, targetLines: Int): Double {
        val raw = span / targetLines
        val mag = Math.pow(10.0, Math.floor(Math.log10(raw)))
        val norm = raw / mag
        return mag * when { norm < 1.5 -> 1.0; norm < 3.5 -> 2.0; norm < 7.5 -> 5.0; else -> 10.0 }
    }

    class DoubleArrayBuilder {
        private var data = DoubleArray(64)
        var size = 0; private set
        fun add(v: Double) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }
        fun toArray(): DoubleArray = data.copyOf(size)
    }
}
