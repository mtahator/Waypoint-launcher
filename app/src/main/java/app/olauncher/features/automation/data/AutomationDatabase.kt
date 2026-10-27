package app.olauncher.features.automation.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Plain SQLite (see docs/dependencies.md for why not Room). Opened lazily from a background
 * thread only: never on the home path. Schema is versioned; migrations live in [onUpgrade].
 */
class AutomationDatabase(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE rules(
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                status TEXT NOT NULL,
                trigger_type TEXT NOT NULL,
                definition_json TEXT NOT NULL,
                schema_version INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX idx_rules_status_trigger ON rules(status, trigger_type)")
        db.execSQL(
            """CREATE TABLE executions(
                id TEXT PRIMARY KEY NOT NULL,
                rule_id TEXT NOT NULL REFERENCES rules(id) ON DELETE CASCADE,
                started_at INTEGER NOT NULL,
                finished_at INTEGER NOT NULL,
                outcome TEXT NOT NULL,
                reason_code TEXT NOT NULL,
                detail TEXT NOT NULL)"""
        )
        db.execSQL("CREATE INDEX idx_exec_rule_started ON executions(rule_id, started_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 is the first schema. Future: `if (oldVersion < 2) { ... }` steps, each covered by a test.
    }

    companion object {
        const val NAME = "automation.db"
        const val VERSION = 1
    }
}
