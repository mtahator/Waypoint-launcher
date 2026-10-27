package app.olauncher.features.transactions.domain

enum class TxStatus(val key: String) {
    PARSED("parsed"), NEEDS_REVIEW("needs_review"), IGNORED("ignored");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: NEEDS_REVIEW
    }
}

enum class TxDirection(val key: String) {
    DEBIT("debit"), CREDIT("credit");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key }
    }
}

data class Transaction(
    val id: String,
    val receivedAt: Long,
    val transactionAt: Long?,
    val sourcePackage: String,
    val sourceLabel: String,
    val merchant: String?,
    val amountMinor: Long?,
    val currency: String?,
    val direction: TxDirection?,
    val status: TxStatus,
    val dedupKey: String,
    val notificationKey: String,
    val parserId: String,
    val parserVersion: Int,
    val userNote: String?,
    val editedByUser: Boolean,
    val updatedAt: Long,
) {
    override fun toString() = "Transaction(id=$id, status=$status)" // never print amounts/merchants
}

/** In-memory notification content handed to parsers. Never persisted, never logged. */
data class CapturedNotification(
    val packageName: String,
    val sourceLabel: String,
    val key: String,
    val postTime: Long,
    val title: String,
    val text: String,
    val redacted: Boolean,
) {
    override fun toString() = "CapturedNotification(package=$packageName, redacted=$redacted)"
}

enum class Confidence { HIGH, LOW }

data class ParseResult(
    val amountMinor: Long?,
    val currency: String?,
    val direction: TxDirection?,
    val merchant: String?,
    val confidence: Confidence,
    val parserId: String,
    val parserVersion: Int,
) {
    val status get() = if (confidence == Confidence.HIGH) TxStatus.PARSED else TxStatus.NEEDS_REVIEW
    val hasAmount get() = amountMinor != null

    override fun toString() = "ParseResult(parser=$parserId v$parserVersion, confidence=$confidence)"
}

interface TransactionParser {
    val id: String
    val version: Int
    fun canParse(n: CapturedNotification): Boolean
    fun parse(n: CapturedNotification): ParseResult
}
