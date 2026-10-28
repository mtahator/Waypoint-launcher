package app.olauncher.features.quickanswers.domain

/** "5 km to mi", "100 f in c", "2.5 kg = lb". Offline, fixed factors. */
object UnitConverter {

    data class Result(val value: Double, val from: String, val to: String)

    private enum class Kind { LENGTH, MASS, VOLUME, SPEED, TEMP, DATA }

    /** factor = how many base units one unit is (metre, kilogram, litre, km/h, byte). */
    private class Unit(val symbol: String, val kind: Kind, val factor: Double)

    private val UNITS: Map<String, Unit> = buildMap {
        fun add(symbol: String, kind: Kind, factor: Double, vararg aliases: String) {
            val u = Unit(symbol, kind, factor)
            put(symbol, u); aliases.forEach { put(it, u) }
        }
        add("mm", Kind.LENGTH, 0.001, "millimeter", "millimeters", "millimetre")
        add("cm", Kind.LENGTH, 0.01, "centimeter", "centimeters", "centimetre")
        add("m", Kind.LENGTH, 1.0, "meter", "meters", "metre", "metres")
        add("km", Kind.LENGTH, 1000.0, "kilometer", "kilometers", "kilometre")
        add("in", Kind.LENGTH, 0.0254, "inch", "inches")
        add("ft", Kind.LENGTH, 0.3048, "foot", "feet")
        add("yd", Kind.LENGTH, 0.9144, "yard", "yards")
        add("mi", Kind.LENGTH, 1609.344, "mile", "miles")
        add("g", Kind.MASS, 0.001, "gram", "grams")
        add("kg", Kind.MASS, 1.0, "kilo", "kilos", "kilogram", "kilograms")
        add("lb", Kind.MASS, 0.45359237, "lbs", "pound", "pounds")
        add("oz", Kind.MASS, 0.028349523125, "ounce", "ounces")
        add("ml", Kind.VOLUME, 0.001, "milliliter", "milliliters")
        add("l", Kind.VOLUME, 1.0, "liter", "liters", "litre", "litres")
        add("gal", Kind.VOLUME, 3.785411784, "gallon", "gallons")
        add("cup", Kind.VOLUME, 0.2365882365, "cups")
        add("km/h", Kind.SPEED, 1.0, "kmh", "kph")
        add("mph", Kind.SPEED, 1.609344)
        add("m/s", Kind.SPEED, 3.6, "mps")
        add("°C", Kind.TEMP, 0.0, "c", "°c", "celsius")
        add("°F", Kind.TEMP, 0.0, "f", "°f", "fahrenheit")
        add("K", Kind.TEMP, 0.0, "k", "kelvin")
        add("KB", Kind.DATA, 1e3, "kb")
        add("MB", Kind.DATA, 1e6, "mb")
        add("GB", Kind.DATA, 1e9, "gb")
        add("TB", Kind.DATA, 1e12, "tb")
    }

    private val PATTERN = Regex("""^(-?[\d.]+)\s*([a-z°/]+)\s+(?:to|in|=|->|into)\s+([a-z°/]+)$""")

    fun convert(input: String): Result? {
        val m = PATTERN.matchEntire(Calculator.normalizeDigits(input.trim().lowercase())) ?: return null
        val amount = m.groupValues[1].toDoubleOrNull() ?: return null
        val from = UNITS[m.groupValues[2]] ?: return null
        val to = UNITS[m.groupValues[3]] ?: return null
        if (from.kind != to.kind || from === to) return null
        val value = if (from.kind == Kind.TEMP) temperature(amount, from.symbol, to.symbol)
        else amount * from.factor / to.factor
        return Result(value, from.symbol, to.symbol)
    }

    private fun temperature(v: Double, from: String, to: String): Double {
        val c = when (from) { "°F" -> (v - 32) * 5 / 9; "K" -> v - 273.15; else -> v }
        return when (to) { "°F" -> c * 9 / 5 + 32; "K" -> c + 273.15; else -> c }
    }
}
