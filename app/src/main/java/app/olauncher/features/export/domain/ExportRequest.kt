package app.olauncher.features.export.domain

import app.olauncher.features.transactions.domain.TxStatus

enum class CsvColumn(val header: String, val sensitive: Boolean) {
    TRANSACTION_DATE("transaction_date", false),
    RECEIVED_AT("received_at", false),
    SOURCE("source", false),
    MERCHANT("merchant", true),
    AMOUNT("amount", true),
    CURRENCY("currency", false),
    DIRECTION("direction", false),
    STATUS("status", false),
    NOTE("note", true),
}

data class ExportRequest(
    val fromMillis: Long,
    val toMillisExclusive: Long,
    val statuses: Set<TxStatus>,
    val columns: List<CsvColumn> = CsvColumn.entries,
    val excelBom: Boolean = false,
)
