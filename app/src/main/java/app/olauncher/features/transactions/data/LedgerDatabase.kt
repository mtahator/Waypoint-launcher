package app.olauncher.features.transactions.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Separate file so it can be excluded from backup and wiped in one step. App-private storage only. */
class LedgerDatabase(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE transactions(
                id TEXT PRIMARY KEY NOT NULL,
                received_at INTEGER NOT NULL,
                transaction_at INTEGER,
                source_package TEXT NOT NULL,
                source_label TEXT NOT NULL,
                merchant TEXT,
                amount_minor INTEGER,
                currency TEXT,
                direction TEXT,
                status TEXT NOT NULL,
                dedup_key TEXT NOT NULL,
                notification_key TEXT NOT NULL,
                parser_id TEXT NOT NULL,
                parser_version INTEGER NOT NULL,
                user_note TEXT,
                edited_by_user INTEGER NOT NULL,
                updated_at INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX idx_tx_received ON transactions(received_at)")
        db.execSQL("CREATE INDEX idx_tx_status ON transactions(status)")
        db.execSQL("CREATE INDEX idx_tx_dedup ON transactions(dedup_key)")
        db.execSQL("CREATE INDEX idx_tx_notification ON transactions(source_package, notification_key)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    companion object {
        const val NAME = "ledger.db"
        const val VERSION = 1
    }
}
