package app.olauncher.features.transactions.domain

import java.security.MessageDigest

object DedupKey {
    /** Exact-match key. Only identical keys within [WINDOW_MS] are treated as duplicates. */
    fun of(
        sourcePackage: String,
        notificationKey: String,
        amountMinor: Long?,
        currency: String?,
        merchant: String?,
        postTimeMillis: Long,
    ): String {
        val material = listOf(
            sourcePackage, notificationKey, amountMinor?.toString() ?: "", currency ?: "", merchant ?: "",
            (postTimeMillis / 1000).toString(),
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    const val WINDOW_MS = 2 * 60 * 1000L
}

/** Picks the first parser able to handle the notification; the generic parser is always last. */
class ParserRegistry(private val parsers: List<TransactionParser>) {
    fun parse(n: CapturedNotification): ParseResult = parsers.first { it.canParse(n) }.parse(n)

    companion object {
        fun default() = ParserRegistry(listOf(GenericAmountParser()))
    }
}
