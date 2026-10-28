package app.olauncher.features.quickanswers.domain

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
 * Spotlight-style answers for the drawer search, computed offline as you type:
 * `12*3+4`, `15% of 80`, `5 km to mi`, `100 f to c`, `time in tokyo`.
 */
object QuickAnswers {

    /** [value] is shown big; [detail] is the small caption; [copy] goes to the clipboard on tap/Enter. */
    data class Answer(val value: String, val detail: String, val copy: String)

    private val PERCENT_OF = Regex("""^(.+?)\s*%\s*(?:of|از)\s+(.+)$""")
    private val TIME_IN = Regex("""^(?:time\s+in|ساعت|زمان)\s+(.+)$""")
    private val TIME_SUFFIX = Regex("""^(.+?)\s+time$""")

    fun answer(
        query: String,
        cityLookup: ((String) -> City?)? = null,
        now: Instant = Instant.now(),
        is24h: Boolean = true,
    ): Answer? {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.length < 2) return null

        PERCENT_OF.matchEntire(q)?.let { m ->
            val pct = Calculator.evaluate("(${m.groupValues[1]})+0") ?: return@let
            val base = Calculator.evaluate("(${m.groupValues[2]})+0") ?: return@let
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
        Calculator.evaluate(q)?.let { v ->
            return Answer(format(v), "= " + q.replace(" ", "").replace('*', '×').replace('x', '×').replace('/', '÷'), plain(v))
        }
        return null
    }

    /** Up to 10 significant digits, grouped: 1,234,567.89 ; very large/small in scientific notation. */
    fun format(v: Double, significant: Int = 10): String {
        if (v == 0.0) return "0"
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
