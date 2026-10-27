package app.olauncher.features.transactions.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

/** Exact money: integer minor units + ISO 4217 code. BigDecimal only at the parse/format edges. */
object Money {
    /** Fraction digits for an ISO code (JPY 0, GBP 2, KWD 3). Unknown or null code -> 2. */
    fun fractionDigits(currency: String?): Int {
        if (currency == null) return 2
        return try {
            Currency.getInstance(currency).defaultFractionDigits.takeIf { it >= 0 } ?: 2
        } catch (_: IllegalArgumentException) {
            2
        }
    }

    fun isKnownCurrency(code: String): Boolean = try {
        Currency.getInstance(code); true
    } catch (_: IllegalArgumentException) {
        false
    }

    /** Converts a plain decimal (e.g. 4.5) to minor units, or null if it has more precision than the currency allows. */
    fun toMinor(amount: BigDecimal, currency: String?): Long? = try {
        amount.setScale(fractionDigits(currency), RoundingMode.UNNECESSARY).unscaledValue().longValueExact()
    } catch (_: ArithmeticException) {
        null
    }

    /** Locale-independent plain decimal with `.` separator: 450 GBP -> "4.50", 1200 JPY -> "1200". */
    fun formatPlain(amountMinor: Long, currency: String?): String =
        BigDecimal.valueOf(amountMinor, fractionDigits(currency)).toPlainString()

    /** Parses user input such as "4.50" or "4,50" for editing. */
    fun parseUserAmount(input: String, currency: String?): Long? {
        val cleaned = input.trim().replace(" ", "").replace(',', '.')
        if (cleaned.isEmpty() || cleaned.count { it == '.' } > 1) return null
        val value = cleaned.toBigDecimalOrNull() ?: return null
        if (value.signum() < 0) return null
        return toMinor(value, currency)
    }
}
