package app.olauncher.features.transactions.data

import android.content.ContentValues
import android.database.Cursor
import app.olauncher.features.transactions.domain.CapturedNotification
import app.olauncher.features.transactions.domain.DedupKey
import app.olauncher.features.transactions.domain.ParseResult
import app.olauncher.features.transactions.domain.Transaction
import app.olauncher.features.transactions.domain.TxDirection
import app.olauncher.features.transactions.domain.TxStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.UUID

data class LedgerFilter(
    val statuses: Set<TxStatus> = TxStatus.entries.toSet(),
    val sourcePackage: String? = null,
    val fromMillis: Long? = null,
    val toMillisExclusive: Long? = null,
)

enum class SaveOutcome { INSERTED, UPDATED, DUPLICATE }

class TransactionRepository(
    private val dbProvider: () -> LedgerDatabase,
    private val io: CoroutineDispatcher,
) {
    private val db by lazy { dbProvider().writableDatabase }

    /**
     * Conservative dedup: an identical key within 2 minutes is dropped; a repost of the same
     * notification key with different content updates the existing record; anything else is new.
     */
    suspend fun saveCaptured(n: CapturedNotification, parsed: ParseResult, now: Long): SaveOutcome = withContext(io) {
        val key = DedupKey.of(n.packageName, n.key, parsed.amountMinor, parsed.currency, parsed.merchant, n.postTime)
        db.beginTransaction()
        try {
            val dup = db.rawQuery(
                "SELECT COUNT(*) FROM transactions WHERE dedup_key = ? AND received_at >= ?",
                arrayOf(key, (now - DedupKey.WINDOW_MS).toString())
            ).use { it.moveToFirst() && it.getInt(0) > 0 }
            if (dup) {
                db.setTransactionSuccessful()
                return@withContext SaveOutcome.DUPLICATE
            }
            val values = ContentValues().apply {
                put("source_label", n.sourceLabel)
                put("merchant", parsed.merchant)
                put("amount_minor", parsed.amountMinor)
                put("currency", parsed.currency)
                put("direction", parsed.direction?.key)
                put("status", parsed.status.key)
                put("dedup_key", key)
                put("parser_id", parsed.parserId)
                put("parser_version", parsed.parserVersion)
                put("updated_at", now)
            }
            // Updated notification (same key, new content) replaces the record unless the user edited it.
            val updated = db.update(
                "transactions", values,
                "source_package = ? AND notification_key = ? AND edited_by_user = 0 AND received_at >= ?",
                arrayOf(n.packageName, n.key, (now - DedupKey.WINDOW_MS).toString())
            )
            val outcome = if (updated > 0) SaveOutcome.UPDATED else {
                values.put("id", UUID.randomUUID().toString())
                values.put("received_at", now)
                values.putNull("transaction_at")
                values.put("source_package", n.packageName)
                values.put("notification_key", n.key)
                values.putNull("user_note")
                values.put("edited_by_user", 0)
                db.insertOrThrow("transactions", null, values)
                SaveOutcome.INSERTED
            }
            db.setTransactionSuccessful()
            outcome
        } finally {
            db.endTransaction()
        }
    }

    suspend fun list(filter: LedgerFilter, limit: Int, offset: Int): List<Transaction> = withContext(io) {
        val (where, args) = where(filter)
        db.query("transactions", null, where, args, null, null, "received_at DESC", "$offset,$limit")
            .use { c -> buildList { while (c.moveToNext()) add(read(c)) } }
    }

    suspend fun count(filter: LedgerFilter): Int = withContext(io) {
        val (where, args) = where(filter)
        db.rawQuery("SELECT COUNT(*) FROM transactions" + (where?.let { " WHERE $it" } ?: ""), args)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /** Streams matching rows in pages, oldest first, for export. */
    suspend fun forEachForExport(filter: LedgerFilter, block: (Transaction) -> Unit) = withContext(io) {
        val (where, args) = where(filter)
        db.query("transactions", null, where, args, null, null, "received_at ASC").use { c ->
            while (c.moveToNext()) block(read(c))
        }
    }

    suspend fun get(id: String): Transaction? = withContext(io) {
        db.query("transactions", null, "id = ?", arrayOf(id), null, null, null).use { if (it.moveToFirst()) read(it) else null }
    }

    suspend fun update(t: Transaction) = withContext(io) {
        db.update("transactions", ContentValues().apply {
            put("merchant", t.merchant)
            put("amount_minor", t.amountMinor)
            put("currency", t.currency)
            put("direction", t.direction?.key)
            put("status", t.status.key)
            put("transaction_at", t.transactionAt)
            put("user_note", t.userNote)
            put("edited_by_user", 1)
            put("updated_at", t.updatedAt)
        }, "id = ?", arrayOf(t.id))
        Unit
    }

    suspend fun setStatus(id: String, status: TxStatus, now: Long) = withContext(io) {
        db.update("transactions", ContentValues().apply {
            put("status", status.key); put("edited_by_user", 1); put("updated_at", now)
        }, "id = ?", arrayOf(id))
        Unit
    }

    suspend fun delete(id: String) = withContext(io) { db.delete("transactions", "id = ?", arrayOf(id)); Unit }

    suspend fun deleteRange(fromMillis: Long, toMillisExclusive: Long): Int = withContext(io) {
        db.delete("transactions", "received_at >= ? AND received_at < ?", arrayOf(fromMillis.toString(), toMillisExclusive.toString()))
    }

    suspend fun deleteAll(): Int = withContext(io) { db.delete("transactions", null, null) }

    suspend fun sources(): List<Pair<String, String>> = withContext(io) {
        db.rawQuery("SELECT DISTINCT source_package, source_label FROM transactions ORDER BY source_label", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
    }

    private fun where(f: LedgerFilter): Pair<String?, Array<String>?> {
        val parts = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (f.statuses.size < TxStatus.entries.size) {
            if (f.statuses.isEmpty()) parts += "0" else {
                parts += "status IN (" + f.statuses.joinToString(",") { "?" } + ")"
                args += f.statuses.map { it.key }
            }
        }
        f.sourcePackage?.let { parts += "source_package = ?"; args += it }
        f.fromMillis?.let { parts += "received_at >= ?"; args += it.toString() }
        f.toMillisExclusive?.let { parts += "received_at < ?"; args += it.toString() }
        return if (parts.isEmpty()) null to null else parts.joinToString(" AND ") to args.toTypedArray()
    }

    private fun read(c: Cursor): Transaction {
        fun s(n: String) = c.getString(c.getColumnIndexOrThrow(n))
        fun sn(n: String) = c.getColumnIndexOrThrow(n).let { if (c.isNull(it)) null else c.getString(it) }
        fun ln(n: String) = c.getColumnIndexOrThrow(n).let { if (c.isNull(it)) null else c.getLong(it) }
        return Transaction(
            id = s("id"),
            receivedAt = c.getLong(c.getColumnIndexOrThrow("received_at")),
            transactionAt = ln("transaction_at"),
            sourcePackage = s("source_package"),
            sourceLabel = s("source_label"),
            merchant = sn("merchant"),
            amountMinor = ln("amount_minor"),
            currency = sn("currency"),
            direction = TxDirection.fromKey(sn("direction")),
            status = TxStatus.fromKey(s("status")),
            dedupKey = s("dedup_key"),
            notificationKey = s("notification_key"),
            parserId = s("parser_id"),
            parserVersion = c.getInt(c.getColumnIndexOrThrow("parser_version")),
            userNote = sn("user_note"),
            editedByUser = c.getInt(c.getColumnIndexOrThrow("edited_by_user")) != 0,
            updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
        )
    }
}
