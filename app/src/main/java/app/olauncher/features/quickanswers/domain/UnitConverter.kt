package app.olauncher.features.quickanswers.domain

import kotlin.math.PI
import kotlin.math.pow

/**
 * Offline unit conversion with dimensional analysis, so compound units just work:
 * `5 km to mi`, `2 m3 to l`, `3 m^2 to sq ft`, `100 km/h to m/s`, `1 kWh to MJ`, `9.81 m/s^2 to ft/s2`,
 * `1 acre to m²`, `100 f to c`, `2 GB to MB`, `90 deg to rad`.
 */
object UnitConverter {

    data class Result(val value: Double, val from: String, val to: String)

    /** Exponents of length, mass, time, temperature, data, angle, amount-of-currency-free "count". */
    private class Dim(val l: Int = 0, val m: Int = 0, val t: Int = 0, val k: Int = 0, val d: Int = 0, val a: Int = 0) {
        operator fun times(o: Dim) = Dim(l + o.l, m + o.m, t + o.t, k + o.k, d + o.d, a + o.a)
        fun pow(n: Int) = Dim(l * n, m * n, t * n, k * n, d * n, a * n)
        override fun equals(other: Any?) = other is Dim && l == other.l && m == other.m && t == other.t &&
            k == other.k && d == other.d && a == other.a
        override fun hashCode() = listOf(l, m, t, k, d, a).hashCode()
    }

    private class U(val factor: Double, val dim: Dim, val offset: Double = 0.0)

    private val L = Dim(l = 1); private val M = Dim(m = 1); private val T = Dim(t = 1)
    private val K = Dim(k = 1); private val D = Dim(d = 1); private val A = Dim(a = 1)
    private val AREA = L.pow(2); private val VOL = L.pow(3)
    private val ENERGY = M * L.pow(2) * T.pow(-2)
    private val POWER = ENERGY * T.pow(-1)
    private val PRESSURE = M * L.pow(-1) * T.pow(-2)
    private val FORCE = M * L * T.pow(-2)
    private val FREQ = T.pow(-1)
    private val SPEED = L * T.pow(-1)

    /** Exact (case-sensitive) names first, so Mb (megabit) and MB (megabyte) differ. */
    private val EXACT: Map<String, U> = buildMap {
        fun u(f: Double, dim: Dim, vararg names: String) = names.forEach { put(it, U(f, dim)) }
        u(1.0, D, "B", "byte", "bytes"); u(0.125, D, "b", "bit", "bits")
        u(1e3, D, "kB", "KB"); u(1e6, D, "MB"); u(1e9, D, "GB"); u(1e12, D, "TB"); u(1e15, D, "PB")
        u(1024.0, D, "KiB"); u(1024.0.pow(2), D, "MiB"); u(1024.0.pow(3), D, "GiB"); u(1024.0.pow(4), D, "TiB")
        u(125.0, D, "Kb", "kbit"); u(1.25e5, D, "Mb", "Mbit"); u(1.25e8, D, "Gb", "Gbit")
        u(1e-6, L, "um", "µm", "micron"); u(1e-9, L, "nm")
        u(1e-3, M, "mg"); u(1e6, PRESSURE, "MPa"); u(1e-3, PRESSURE * Dim(), "mPa")
        u(1e3, FORCE, "kN"); u(1e6, ENERGY, "MJ"); u(1e6, POWER, "MW"); u(1e6, FREQ, "MHz")
    }

    private val NAMES: Map<String, U> = buildMap {
        fun u(f: Double, dim: Dim, vararg names: String) = names.forEach { put(it, U(f, dim)) }
        // length
        u(1e-3, L, "mm", "millimeter", "millimeters", "millimetre", "millimetres")
        u(1e-2, L, "cm", "centimeter", "centimeters", "centimetre", "centimetres")
        u(0.1, L, "dm"); u(1.0, L, "m", "meter", "meters", "metre", "metres")
        u(1e3, L, "km", "kilometer", "kilometers", "kilometre", "kilometres")
        u(0.0254, L, "in", "inch", "inches", "\"")
        u(0.3048, L, "ft", "foot", "feet", "'")
        u(0.9144, L, "yd", "yard", "yards"); u(1609.344, L, "mi", "mile", "miles")
        u(1852.0, L, "nmi"); u(1.495978707e11, L, "au"); u(9.4607304725808e15, L, "ly", "lightyear", "lightyears")
        // mass
        u(1e-3, M, "g", "gram", "grams", "gr"); u(1.0, M, "kg", "kilo", "kilos", "kilogram", "kilograms")
        u(1e3, M, "t", "ton", "tons", "tonne", "tonnes"); u(0.45359237, M, "lb", "lbs", "pound", "pounds")
        u(0.028349523125, M, "oz", "ounce", "ounces"); u(6.35029318, M, "st", "stone")
        u(2e-4, M, "ct", "carat", "carats")
        // area / volume with their own names
        u(1e4, AREA, "ha", "hectare", "hectares"); u(4046.8564224, AREA, "acre", "acres")
        u(1e-3, VOL, "l", "liter", "liters", "litre", "litres"); u(1e-6, VOL, "ml", "milliliter", "milliliters", "cc")
        u(1e-5, VOL, "cl"); u(1e-4, VOL, "dl")
        u(3.785411784e-3, VOL, "gal", "gallon", "gallons"); u(2.365882365e-4, VOL, "cup", "cups")
        u(1.478676478125e-5, VOL, "tbsp"); u(4.92892159375e-6, VOL, "tsp")
        u(2.95735295625e-5, VOL, "floz"); u(4.73176473e-4, VOL, "pint", "pints", "pt")
        u(9.46352946e-4, VOL, "quart", "quarts", "qt"); u(0.158987294928, VOL, "bbl", "barrel", "barrels")
        // time
        u(1e-3, T, "ms"); u(1.0, T, "s", "sec", "secs", "second", "seconds")
        u(60.0, T, "min", "mins", "minute", "minutes"); u(3600.0, T, "h", "hr", "hrs", "hour", "hours")
        u(86400.0, T, "d", "day", "days"); u(604800.0, T, "wk", "week", "weeks")
        u(2629746.0, T, "mo", "month", "months"); u(31556952.0, T, "yr", "yrs", "year", "years")
        // speed shorthands
        u(1 / 3.6, SPEED, "kmh", "kph"); u(0.44704, SPEED, "mph"); u(0.514444444, SPEED, "kn", "kt", "knot", "knots")
        u(1.0, SPEED, "mps"); u(0.3048, SPEED, "fps")
        // energy / power / force / pressure / frequency
        u(1.0, ENERGY, "j", "joule", "joules"); u(1e3, ENERGY, "kj"); u(4.184, ENERGY, "cal")
        u(4184.0, ENERGY, "kcal"); u(3600.0, ENERGY, "wh"); u(3.6e6, ENERGY, "kwh"); u(3.6e9, ENERGY, "mwh")
        u(1.602176634e-19, ENERGY, "ev"); u(1055.05585, ENERGY, "btu")
        u(1.0, POWER, "w", "watt", "watts"); u(1e3, POWER, "kw"); u(745.69987, POWER, "hp")
        u(1.0, FORCE, "n", "newton", "newtons"); u(4.4482216152605, FORCE, "lbf")
        u(1.0, PRESSURE, "pa"); u(100.0, PRESSURE, "hpa"); u(1e3, PRESSURE, "kpa"); u(1e5, PRESSURE, "bar")
        u(100.0, PRESSURE, "mbar"); u(6894.757293168, PRESSURE, "psi"); u(101325.0, PRESSURE, "atm")
        u(133.322387415, PRESSURE, "mmhg", "torr")
        u(1.0, FREQ, "hz"); u(1e3, FREQ, "khz"); u(1e9, FREQ, "ghz"); u(1 / 60.0, FREQ, "rpm")
        // angle
        u(PI / 180, A, "deg", "degree", "degrees", "°"); u(1.0, A, "rad", "radian", "radians")
        u(PI / 200, A, "grad", "gon"); u(2 * PI, A, "turn", "turns", "rev"); u(PI / 10800, A, "arcmin")
        u(PI / 648000, A, "arcsec")
        // data (lazy lowercase)
        u(1e3, D, "kb"); u(1e6, D, "mb"); u(1e9, D, "gb"); u(1e12, D, "tb")
    }

    private val TEMPS = mapOf(
        "c" to "°C", "°c" to "°C", "celsius" to "°C", "f" to "°F", "°f" to "°F", "fahrenheit" to "°F",
        "k" to "K", "kelvin" to "K",
    )

    private val QUERY = Regex("""^([-+]?(?:\d[\d,]*\.?\d*|\.\d+)(?:e[-+]?\d+)?)?\s*(\S+)\s+(?:to|in|as|into|=|->|→|به)\s+(\S+)$""", RegexOption.IGNORE_CASE)

    fun convert(input: String): Result? {
        val q = normalize(input) ?: return null
        val m = QUERY.matchEntire(q) ?: return null
        val amount = m.groupValues[1].takeIf { it.isNotEmpty() }?.replace(",", "")?.toDoubleOrNull() ?: 1.0
        val fromText = m.groupValues[2]; val toText = m.groupValues[3]

        val tf = TEMPS[fromText.lowercase()]; val tt = TEMPS[toText.lowercase()]
        if (tf != null && tt != null) return if (tf == tt) null else Result(temperature(amount, tf, tt), tf, tt)

        val from = unit(fromText) ?: return null
        val to = unit(toText) ?: return null
        if (from.dim != to.dim || from.factor == to.factor && fromText == toText) return null
        return Result(amount * from.factor / to.factor, pretty(fromText), pretty(toText))
    }

    private fun normalize(s: String): String? {
        var q = s.trim()
        if (q.isEmpty()) return null
        q = buildString { for (c in q) append(when (c) { in '۰'..'۹' -> '0' + (c - '۰'); '٫' -> '.'; else -> c }) }
        q = q.replace(Regex("""\b(sq|square)\s+""", RegexOption.IGNORE_CASE), "sq")
            .replace(Regex("""\b(cu|cubic)\s+""", RegexOption.IGNORE_CASE), "cu")
            .replace(Regex("""\s*/\s*"""), "/")
            .replace(Regex("""\s*\^\s*"""), "^")
        return q
    }

    /** "km/h", "m/s^2", "kg*m^2", "m3", "m³", "sqft", "cuin", "N·m" → factor + dimension. */
    private fun unit(text: String): U? {
        val parts = text.split('/')
        if (parts.size > 2 || parts.any { it.isEmpty() }) return null
        var factor = 1.0; var dim = Dim()
        parts.forEachIndexed { idx, part ->
            for (piece in part.split('*', '·', '.')) {
                if (piece.isEmpty()) return null
                val (base, power) = splitPower(piece) ?: return null
                val p = if (idx == 0) power else -power
                factor *= base.factor.pow(p)
                dim *= base.dim.pow(p)
            }
        }
        return U(factor, dim)
    }

    private fun splitPower(piece: String): Pair<U, Int>? {
        var name = piece; var power = 1
        when {
            name.endsWith("²") -> { name = name.dropLast(1); power = 2 }
            name.endsWith("³") -> { name = name.dropLast(1); power = 3 }
            name.contains('^') -> { power = name.substringAfter('^').toIntOrNull() ?: return null; name = name.substringBefore('^') }
            name.length > 1 && name.last().isDigit() && !name[name.length - 2].isDigit() -> {
                power = name.last().digitToInt(); name = name.dropLast(1)
            }
        }
        val lower = name.lowercase()
        when {
            lower.startsWith("sq") && lookup(name.drop(2)) != null && lookup(name) == null -> { name = name.drop(2); power *= 2 }
            lower.startsWith("cu") && lookup(name.drop(2)) != null && lookup(name) == null -> { name = name.drop(2); power *= 3 }
        }
        val u = lookup(name) ?: return null
        if (power == 0 || power > 4) return null
        return u to power
    }

    private fun lookup(name: String): U? = EXACT[name] ?: NAMES[name.lowercase()]

    private fun pretty(text: String): String = text
        .replace("^2", "²").replace("^3", "³")
        .replace(Regex("""(?<=[a-zA-Z])2$"""), "²").replace(Regex("""(?<=[a-zA-Z])3$"""), "³")
        .let { if (it.startsWith("sq", true) && it.length > 2 && lookup(it) == null) it.drop(2) + "²" else it }
        .let { if (it.startsWith("cu", true) && it.length > 2 && lookup(it) == null) it.drop(2) + "³" else it }

    private fun temperature(v: Double, from: String, to: String): Double {
        val c = when (from) { "°F" -> (v - 32) * 5 / 9; "K" -> v - 273.15; else -> v }
        return when (to) { "°F" -> c * 9 / 5 + 32; "K" -> c + 273.15; else -> c }
    }
}
