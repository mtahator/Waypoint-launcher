package app.olauncher.features.quickanswers.domain

import app.olauncher.features.math.domain.Builtins
import app.olauncher.features.math.domain.MathParser
import app.olauncher.features.math.domain.MathProgram
import app.olauncher.features.worldclock.domain.City
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Spotlight-style answers for the drawer search, computed as you type: `12*3+4`, `sin(30°)`,
 * `15% of 80`, `5 m3 to l`, `100 km/h to mph`, `1$ to £`, `time in tokyo`, and `sin(x)` (a graph).
 * Start with `=` to force calculator mode for anything ([forced]).
 */
object QuickAnswers {

    /**
     * [value] is shown big; [detail] is the caption; [copy] goes to the clipboard on tap/Enter.
     * [graph] is an expression to plot; [currency] means rates are needed and [value] is a placeholder.
     */
    data class Answer(
        val value: String,
        val detail: String,
        val copy: String,
        val graph: String? = null,
        val currency: CurrencyQuery.Query? = null,
    )

    private val PERCENT_OF = Regex("""^(.+?)\s*%\s*(?:of|از)\s+(.+)$""")
    private val TIME_IN = Regex("""^(?:time\s+in|ساعت|زمان)\s+(.+)$""")
    private val TIME_SUFFIX = Regex("""^(.+?)\s+time$""")
    private val GRAPH_WORDS = setOf("graph", "graphs", "plot", "desmos", "grapher", "نمودار")

    fun answer(
        query: String,
        forced: Boolean = false,
        cityLookup: ((String) -> City?)? = null,
        rates: ExchangeRates? = null,
        now: Instant = Instant.now(),
        is24h: Boolean = true,
    ): Answer? {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty() || (!forced && q.length < 2)) return null

        if (q in GRAPH_WORDS) return Answer("Graphing calculator", "Graphs · sliders · equations", "", graph = "")

        CurrencyQuery.parse(q)?.let { c -> return currency(c, rates) }

        PERCENT_OF.matchEntire(q)?.let { m ->
            val pct = Calculator.evaluate(m.groupValues[1], forced = true) ?: return@let
            val base = Calculator.evaluate(m.groupValues[2], forced = true) ?: return@let
            val v = pct / 100.0 * base
            return Answer(format(v), "${format(pct)}% of ${format(base)}", plain(v))
        }
        UnitConverter.convert(q)?.let { r ->
            return Answer("${format(r.value, 6)} ${r.to}", "${r.from} → ${r.to}", plain(r.value, 6))
        }
        if (cityLookup != null) {
            val name = TIME_IN.matchEntire(q)?.groupValues?.get(1) ?: TIME_SUFFIX.matchEntire(q)?.groupValues?.get(1)
            val city = name?.let(cityLookup)
            if (city != null) {
                val t = ZonedDateTime.ofInstant(now, ZoneId.of(city.zoneId))
                val time = t.format(DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm a", Locale.ROOT))
                val day = t.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
                return Answer(time, "${city.name.uppercase(Locale.ROOT)} · $day", time)
            }
        }
        Calculator.evaluate(q, forced)?.let { v ->
            return Answer(format(v), "= ${Calculator.display(q)}", plain(v))
        }
        return graph(query.trim(), forced)
    }

    /** `sin(x)`, `y = x^2`, `x^2 + y^2 = 9`: shown as a small graph that opens the full grapher. */
    private fun graph(text: String, forced: Boolean): Answer? {
        val src = Calculator.prepare(text)
        val stmt = runCatching { MathParser().parseStatement(src) }.getOrNull() ?: return null
        val vars = (stmt.lhs.variables() + (stmt.rhs?.variables() ?: emptySet())) - Builtins.CONSTANTS.keys
        if (vars.isEmpty() || !vars.all { it == "x" || it == "y" }) return null
        if (!forced && src.none { it in "+-*/^()=|√!" }) return null // "x", "xy" alone aren't questions
        val line = MathProgram(listOf(src)).lines.first()
        if (!line.plottable || line.point != null) return null
        val shown = if (stmt.rhs == null) "y = ${Calculator.display(src)}" else Calculator.display(src).replace("=", " = ")
        return Answer(shown, "Graph · tap to open", src, graph = src)
    }

    private fun currency(c: CurrencyQuery.Query, rates: ExchangeRates?): Answer {
        val from = c.from; val to = c.to
        val label = { code: String -> if (code == CurrencyQuery.TOMAN) "TOMAN" else code }
        if (rates == null) return Answer("…", "${format(c.amount)} ${label(from)} → ${label(to)}", "", currency = c)
        val v = rates.convert(c.amount, from, to)
            ?: return Answer("—", "No rate for ${label(if (rates.has(from)) to else from)}", "", currency = c)
        val digits = if (abs(v) >= 1000) 0 else if (abs(v) >= 1) 2 else 4
        val shown = BigDecimal(v).setScale(digits, RoundingMode.HALF_UP).toDouble()
        val sym = CurrencyQuery.symbol(to)
        val value = if (sym != null) "$sym${format(shown)}" else "${format(shown)} ${label(to)}"
        val unit = rates.convert(1.0, from, to) ?: 0.0
        return Answer(value, "1 ${label(from)} = ${format(unit, 4)} ${label(to)}", plain(shown), currency = c)
    }

    /** Up to [significant] digits, grouped: 1,234,567.89; very large/small in scientific notation. */
    fun format(v: Double, significant: Int = 10): String {
        if (v == 0.0) return "0"
        if (v.isNaN()) return "undefined"
        if (v.isInfinite()) return if (v > 0) "∞" else "-∞"
        val a = abs(v)
        if (a >= 1e15 || a < 1e-9) return String.format(Locale.ROOT, "%.6e", v).replace(Regex("0+e"), "e").replace(".e", "e")
        val bd = BigDecimal(v).round(MathContext(significant, RoundingMode.HALF_UP)).stripTrailingZeros()
        val int = bd.toBigInteger().abs().toString().reversed().chunked(3).joinToString(",").reversed()
        val frac = bd.abs().toPlainString().substringAfter('.', "")
        val sign = if (bd.signum() < 0) "-" else ""
        return if (frac.isEmpty()) "$sign$int" else "$sign$int.$frac"
    }

    /** Same rounding without grouping, for pasting into other apps. */
    fun plain(v: Double, significant: Int = 10): String = format(v, significant).replace(",", "")
}
