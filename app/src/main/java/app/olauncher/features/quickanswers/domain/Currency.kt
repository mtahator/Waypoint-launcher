package app.olauncher.features.quickanswers.domain

import java.util.Locale

/** "1$ to £", "$20 in €", "100 usd to eur", "50 euros in toman", "1 bitcoin"… parsed offline. */
object CurrencyQuery {

    data class Query(val amount: Double, val from: String, val to: String)

    private val SYMBOLS = mapOf(
        "$" to "USD", "us$" to "USD", "£" to "GBP", "€" to "EUR", "¥" to "JPY", "₹" to "INR", "₽" to "RUB",
        "₺" to "TRY", "₩" to "KRW", "₪" to "ILS", "₦" to "NGN", "₱" to "PHP", "₫" to "VND", "฿" to "THB",
        "₴" to "UAH", "﷼" to "IRR", "c$" to "CAD", "a$" to "AUD", "r$" to "BRL", "hk$" to "HKD",
    )

    private val WORDS = mapOf(
        "dollar" to "USD", "dollars" to "USD", "buck" to "USD", "bucks" to "USD", "دلار" to "USD",
        "euro" to "EUR", "euros" to "EUR", "یورو" to "EUR",
        "pound" to "GBP", "pounds" to "GBP", "sterling" to "GBP", "پوند" to "GBP",
        "yen" to "JPY", "yuan" to "CNY", "rmb" to "CNY", "rupee" to "INR", "rupees" to "INR",
        "lira" to "TRY", "ruble" to "RUB", "rubles" to "RUB", "won" to "KRW", "franc" to "CHF", "francs" to "CHF",
        "dirham" to "AED", "dirhams" to "AED", "درهم" to "AED", "riyal" to "SAR", "لیر" to "TRY",
        "rial" to "IRR", "rials" to "IRR", "ریال" to "IRR", "toman" to TOMAN, "tomans" to TOMAN, "تومان" to TOMAN,
        "تومن" to TOMAN, "lari" to "GEL", "dram" to "AMD", "manat" to "AZN",
    )

    /** Iran's toman = 10 rials; not an ISO code, derived from IRR. */
    const val TOMAN = "TMN"

    private val ISO: Set<String> by lazy {
        runCatching { java.util.Currency.getAvailableCurrencies().map { it.currencyCode } }.getOrDefault(emptyList()).toSet()
    }

    private val SPLIT = Regex("""^(.+?)\s+(?:to|in|into|as|=|->|→|به)\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val AMOUNT = Regex("""[-+]?(?:\d[\d,]*\.?\d*|\.\d+)(?:[km])?""", RegexOption.IGNORE_CASE)

    fun parse(input: String): Query? {
        val q = Calculator.normalizeDigits(input.trim())
        val m = SPLIT.matchEntire(q) ?: return null
        val (amount, from) = side(m.groupValues[1], requireAmount = false) ?: return null
        val (_, to) = side(m.groupValues[2], requireAmount = false) ?: return null
        if (from == to) return null
        return Query(amount ?: 1.0, from, to)
    }

    /** Reads "1$", "$1", "100 usd", "usd", "£", "100k toman". */
    private fun side(text: String, requireAmount: Boolean): Pair<Double?, String>? {
        val t = text.trim().lowercase(Locale.ROOT)
        val num = AMOUNT.find(t)
        var amount: Double? = null
        var rest = t
        if (num != null) {
            val raw = num.value.replace(",", "")
            val mult = when (raw.lastOrNull()?.lowercaseChar()) { 'k' -> 1e3; 'm' -> 1e6; else -> 1.0 }
            amount = raw.trimEnd('k', 'K', 'm', 'M').toDoubleOrNull()?.times(mult)
            rest = t.removeRange(num.range).trim()
        }
        if (requireAmount && amount == null) return null
        val code = code(rest) ?: return null
        return amount to code
    }

    fun code(token: String): String? {
        val t = token.trim().lowercase(Locale.ROOT)
        if (t.isEmpty()) return null
        SYMBOLS[t]?.let { return it }
        WORDS[t]?.let { return it }
        val up = t.uppercase(Locale.ROOT)
        if (up.length == 3 && up.all { it in 'A'..'Z' } && up in ISO) return up
        return null
    }

    fun symbol(code: String): String? = when (code) {
        "USD" -> "$"; "GBP" -> "£"; "EUR" -> "€"; "JPY" -> "¥"; "INR" -> "₹"; "TRY" -> "₺"; "KRW" -> "₩"
        "RUB" -> "₽"; else -> null
    }
}

/** Rates relative to [base] as published by the provider; [fetchedAt] in epoch millis. */
data class ExchangeRates(val base: String, val rates: Map<String, Double>, val fetchedAt: Long, val publishedAt: Long) {

    fun rate(code: String): Double? =
        if (code == CurrencyQuery.TOMAN) rates["IRR"]?.div(10.0) else rates[code]

    fun convert(amount: Double, from: String, to: String): Double? {
        val f = rate(from) ?: return null
        val t = rate(to) ?: return null
        return amount / f * t
    }

    fun has(code: String) = rate(code) != null
}
