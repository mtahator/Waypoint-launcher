package app.olauncher.features.transactions.domain

import java.util.Locale

/**
 * Locale-aware, conservative amount extraction for payment notifications from any source.
 *
 * HIGH confidence only when there is exactly one amount, its currency is unambiguous (ISO code or a
 * symbol that maps to a single currency), the sign/direction is clear, and the content isn't redacted.
 * Everything else becomes needs_review. A currency is never guessed (`$` alone is ambiguous).
 */
class GenericAmountParser : TransactionParser {
    override val id = "generic"
    override val version = 1

    override fun canParse(n: CapturedNotification) = true

    override fun parse(n: CapturedNotification): ParseResult {
        if (n.redacted) return low()
        val text = (n.title + "\n" + n.text).take(2_000)
        val amounts = findAmounts(text)
        val distinct = amounts.distinctBy { it.minorUnits to it.currency }
        if (distinct.isEmpty()) return low()

        val first = distinct.first()
        val direction = first.sign ?: directionFromWords(text)
        val single = distinct.size == 1
        val confidence = if (single && first.currency != null && direction != null) Confidence.HIGH else Confidence.LOW
        return ParseResult(
            amountMinor = if (single) first.minorUnits else null,
            currency = if (single) first.currency else null,
            direction = direction,
            merchant = merchant(text),
            confidence = confidence,
            parserId = id,
            parserVersion = version,
        )
    }

    private fun low() = ParseResult(null, null, null, null, Confidence.LOW, id, version)

    data class FoundAmount(val minorUnits: Long, val currency: String?, val sign: TxDirection?)

    fun findAmounts(text: String): List<FoundAmount> {
        val out = mutableListOf<FoundAmount>()
        val consumed = BooleanArray(text.length)
        for (match in AMOUNT_PATTERN.findAll(text)) {
            val range = match.range
            if (range.any { consumed[it] }) continue
            // Numbered groups: named groups need API 26 on Android.
            val g = match.groupValues
            val signText = g[1].ifEmpty { g[4] }
            val curToken = g[2].ifEmpty { g[6] }
            val number = g[3].ifEmpty { g[5] }
            if (curToken.isEmpty() || number.isEmpty()) continue
            val isIsoToken = curToken.length == 3 && curToken.all { it in 'A'..'Z' }
            val currency = currencyFor(curToken)
            if (isIsoToken && currency == null) continue // "2 NEW messages" is not money
            val minor = parseNumber(number, Money.fractionDigits(currency)) ?: continue
            val sign = when (signText) {
                "-", "−" -> TxDirection.DEBIT
                "+" -> TxDirection.CREDIT
                else -> null
            }
            range.forEach { consumed[it] = true }
            out += FoundAmount(minor, currency, sign)
        }
        return out
    }

    companion object {
        private const val SYMBOLS = "£€¥₹₩₺₽₪₦฿₫₴₱\$"
        private const val NUM = """\d{1,3}(?:[.,  ' ]\d{3})+(?:[.,]\d{1,3})?|\d+(?:[.,]\d{1,3})?"""
        private const val ISO = """[A-Z]{3}"""

        private val AMOUNT_PATTERN = Regex(
            """([-−+])?\s?([$SYMBOLS]|(?<![A-Za-z])$ISO(?=\s?\d))\s?($NUM)""" +
                """|([-−+])?($NUM)\s?([$SYMBOLS]|$ISO(?![A-Za-z]))"""
        )

        private val SYMBOL_TO_ISO = mapOf(
            "£" to "GBP", "€" to "EUR", "₹" to "INR", "₩" to "KRW", "₺" to "TRY", "₽" to "RUB",
            "₪" to "ILS", "₦" to "NGN", "฿" to "THB", "₫" to "VND", "₴" to "UAH", "₱" to "PHP",
            // "$" and "¥" are shared by several currencies: deliberately unmapped (never guess).
        )

        fun currencyFor(token: String): String? = when {
            token.length == 3 && token.all { it in 'A'..'Z' } -> token.takeIf { Money.isKnownCurrency(it) }
            else -> SYMBOL_TO_ISO[token]
        }

        /**
         * "1,234.56" / "1.234,56" / "1 234,56" / "4,50" / "1234". With a single separator followed by
         * exactly three digits, it is a thousands separator unless the currency uses three decimals.
         */
        fun parseNumber(raw: String, fractionDigits: Int): Long? {
            val s = raw.replace(' ', ' ').replace(' ', ' ').replace('\'', ' ')
            val lastDot = s.lastIndexOf('.')
            val lastComma = s.lastIndexOf(',')
            val decimalIndex: Int = when {
                lastDot >= 0 && lastComma >= 0 -> maxOf(lastDot, lastComma)
                lastDot >= 0 || lastComma >= 0 -> {
                    val idx = maxOf(lastDot, lastComma)
                    val sep = s[idx]
                    val digitsAfter = s.length - idx - 1
                    val occurrences = s.count { it == sep }
                    if (occurrences > 1 || (digitsAfter == 3 && fractionDigits != 3)) -1 else idx
                }
                else -> -1
            }
            val intPart = (if (decimalIndex >= 0) s.substring(0, decimalIndex) else s).filter { it.isDigit() }
            val fracPart = if (decimalIndex >= 0) s.substring(decimalIndex + 1).filter { it.isDigit() } else ""
            if (intPart.isEmpty() && fracPart.isEmpty()) return null
            if (fracPart.length > fractionDigits) return null
            val text = (intPart.ifEmpty { "0" }) + fracPart.padEnd(fractionDigits, '0')
            return text.toLongOrNull()
        }

        private val DEBIT_WORDS = listOf(
            "paid", "spent", "payment", "purchase", "sent", "withdraw", "charged", "debited", "debit", "card transaction",
        )
        private val CREDIT_WORDS = listOf(
            "received", "refund", "refunded", "credited", "credit", "deposit", "added", "topped up", "top-up", "incoming",
        )

        fun directionFromWords(text: String): TxDirection? {
            val t = text.lowercase(Locale.ROOT)
            val debit = DEBIT_WORDS.any { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(t) }
            val credit = CREDIT_WORDS.any { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(t) }
            return when {
                debit && !credit -> TxDirection.DEBIT
                credit && !debit -> TxDirection.CREDIT
                else -> null
            }
        }

        private val MERCHANT = Regex("""(?i)\b(?:at|to|from)\s+([\p{L}\p{N}][\p{L}\p{N} &'.\-]{0,39}?)(?=\s*(?:[.,!:;\n]|$| on | for | with | via ))""")

        fun merchant(text: String): String? =
            MERCHANT.find(text)?.groupValues?.get(1)?.trim()?.trimEnd('.')?.takeIf { it.length >= 2 }
    }
}
