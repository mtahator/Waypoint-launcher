package app.olauncher.features.math.domain

import kotlin.math.*

/** Built-in functions and constants. Angles are in radians; write `30°` for degrees. */
object Builtins {

    val CONSTANTS: Map<String, Double> = mapOf("pi" to PI, "tau" to 2 * PI, "e" to E, "inf" to Double.POSITIVE_INFINITY)

    private val F1: Map<String, (Double) -> Double> = mapOf(
        "sin" to { x: Double -> sin(x) }, "cos" to { x: Double -> cos(x) }, "tan" to { x: Double -> tan(x) },
        "sec" to { x: Double -> 1 / cos(x) }, "csc" to { x: Double -> 1 / sin(x) }, "cot" to { x: Double -> 1 / tan(x) },
        "asin" to { x: Double -> asin(x) }, "acos" to { x: Double -> acos(x) }, "atan" to { x: Double -> atan(x) },
        "arcsin" to { x: Double -> asin(x) }, "arccos" to { x: Double -> acos(x) }, "arctan" to { x: Double -> atan(x) },
        "sinh" to { x: Double -> sinh(x) }, "cosh" to { x: Double -> cosh(x) }, "tanh" to { x: Double -> tanh(x) },
        "asinh" to { x: Double -> asinh(x) }, "acosh" to { x: Double -> acosh(x) }, "atanh" to { x: Double -> atanh(x) },
        "sqrt" to { x: Double -> sqrt(x) }, "cbrt" to { x: Double -> cbrt(x) }, "abs" to { x: Double -> abs(x) }, "exp" to { x: Double -> exp(x) },
        "ln" to { x: Double -> ln(x) }, "lg" to { x: Double -> log10(x) }, "floor" to { x: Double -> floor(x) }, "ceil" to { x: Double -> ceil(x) },
        "round" to { x: Double -> x.roundToLong().toDouble() }, "sign" to { x: Double -> sign(x) }, "sgn" to { x: Double -> sign(x) },
        "fact" to { x: Double -> factorial(x) }, "gamma" to { x: Double -> gamma(x) },
    )

    private val FN: Map<String, (List<Double>) -> Double> = mapOf(
        "log" to { a: List<Double> -> if (a.size == 1) log10(a[0]) else ln(a[1]) / ln(a[0]) }, // log(x) or log(base, x)
        "min" to { a: List<Double> -> a.minOrNull() ?: Double.NaN },
        "max" to { a: List<Double> -> a.maxOrNull() ?: Double.NaN },
        "mod" to { a: List<Double> -> if (a.size != 2) Double.NaN else a[0] - a[1] * floor(a[0] / a[1]) },
        "atan2" to { a: List<Double> -> if (a.size != 2) Double.NaN else atan2(a[0], a[1]) },
        "hypot" to { a: List<Double> -> sqrt(a.sumOf { it * it }) },
        "root" to { a: List<Double> -> if (a.size != 2) Double.NaN else nthRoot(a[1], a[0]) }, // root(n, x)
        "ncr" to { a: List<Double> -> if (a.size != 2) Double.NaN else choose(a[0], a[1]) },
        "npr" to { a: List<Double> -> if (a.size != 2) Double.NaN else factorial(a[0]) / factorial(a[0] - a[1]) },
        "gcd" to { a: List<Double> -> if (a.size != 2) Double.NaN else gcd(a[0], a[1]) },
        "lcm" to { a: List<Double> -> if (a.size != 2) Double.NaN else abs(a[0] * a[1]) / gcd(a[0], a[1]) },
        "mean" to { a: List<Double> -> a.average() },
        "total" to { a: List<Double> -> a.sum() },
    )

    val FUNCTIONS: Set<String> = F1.keys + FN.keys

    /** Names the parser recognises inside a run of letters, longest first. */
    val WORDS: List<String> = (FUNCTIONS + CONSTANTS.keys).filter { it.length > 1 }.sortedByDescending { it.length }

    fun call(name: String, args: List<Double>): Double {
        F1[name]?.let { f -> return if (args.size == 1) f(args[0]) else Double.NaN }
        FN[name]?.let { f -> return f(args) }
        return Double.NaN
    }

    fun unary(name: String): ((Double) -> Double)? = F1[name]

    fun factorial(x: Double): Double {
        if (x < 0 && x == floor(x)) return Double.NaN
        if (x == floor(x) && x <= 170) {
            var r = 1.0
            for (i in 2..x.toInt()) r *= i
            return r
        }
        return gamma(x + 1)
    }

    /** Lanczos approximation (g = 7), with reflection for x < 0.5. */
    fun gamma(x: Double): Double {
        if (x < 0.5) return PI / (sin(PI * x) * gamma(1 - x))
        val g = 7.0
        val c = doubleArrayOf(
            0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313,
            -176.61502916214059, 12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6,
            1.5056327351493116e-7,
        )
        val y = x - 1
        var a = c[0]
        val t = y + g + 0.5
        for (i in 1 until 9) a += c[i] / (y + i)
        return sqrt(2 * PI) * t.pow(y + 0.5) * exp(-t) * a
    }

    private fun choose(n: Double, k: Double): Double {
        if (k < 0 || k > n) return 0.0
        var r = 1.0
        val kk = min(k, n - k).toInt()
        for (i in 1..kk) r = r * (n - kk + i) / i
        return r.roundToLong().toDouble()
    }

    private fun gcd(a: Double, b: Double): Double {
        var x = abs(a.roundToLong()); var y = abs(b.roundToLong())
        while (y != 0L) { val t = x % y; x = y; y = t }
        return x.toDouble()
    }

    private fun nthRoot(x: Double, n: Double): Double =
        if (x < 0 && n.roundToLong() % 2 == 1L) -(-x).pow(1 / n) else x.pow(1 / n)

    /** x^y that keeps odd roots of negatives real: (-8)^(1/3) = -2, like a graphing calculator. */
    fun pow(x: Double, y: Double): Double {
        if (x >= 0 || y == floor(y)) return x.pow(y)
        val inv = 1 / y
        val n = inv.roundToLong()
        return if (abs(inv - n) < 1e-9 && n % 2 == 1L) -(-x).pow(y) else Double.NaN
    }
}
