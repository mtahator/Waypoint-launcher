package app.olauncher.features.space.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** A voice note in Space. [audio] is a file name in files/space. */
data class Note(
    val id: Long,
    val created: Long,
    val durationMs: Long,
    val audio: String,
    val title: String,
    val transcript: String,
    val summary: String,
    val status: Status,
    val error: String,
) {
    enum class Status { RECORDED, TRANSCRIBING, SUMMARIZING, DONE, ERROR }
    val displayTitle get() = title.ifBlank { "Voice note" }
}

private class SpaceDatabase(context: Context) : SQLiteOpenHelper(context, "space.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT, created INTEGER NOT NULL, duration INTEGER NOT NULL, " +
                "audio TEXT NOT NULL, title TEXT NOT NULL DEFAULT '', transcript TEXT NOT NULL DEFAULT '', " +
                "summary TEXT NOT NULL DEFAULT '', status TEXT NOT NULL, error TEXT NOT NULL DEFAULT '')",
        )
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}

/** Space notes stay on the phone (space.db + files/space, excluded from backup). */
class NotesRepository(context: Context, private val io: CoroutineDispatcher) {
    private val helper by lazy { SpaceDatabase(context.applicationContext) }
    private val db get() = helper.writableDatabase

    private fun read(c: android.database.Cursor) = Note(
        c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6),
        runCatching { Note.Status.valueOf(c.getString(7)) }.getOrDefault(Note.Status.ERROR), c.getString(8),
    )

    private val cols = "id, created, duration, audio, title, transcript, summary, status, error"

    suspend fun all(): List<Note> = withContext(io) {
        db.rawQuery("SELECT $cols FROM notes ORDER BY created DESC", null).use { c -> buildList { while (c.moveToNext()) add(read(c)) } }
    }

    suspend fun get(id: Long): Note? = withContext(io) {
        db.rawQuery("SELECT $cols FROM notes WHERE id = ?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) read(c) else null }
    }

    suspend fun create(audio: String, durationMs: Long, created: Long = System.currentTimeMillis()): Long = withContext(io) {
        db.insert("notes", null, ContentValues().apply {
            put("created", created); put("duration", durationMs); put("audio", audio); put("status", Note.Status.RECORDED.name)
        })
    }

    suspend fun update(id: Long, block: ContentValues.() -> Unit) = withContext(io) {
        db.update("notes", ContentValues().apply(block), "id = ?", arrayOf(id.toString()))
    }

    suspend fun delete(id: Long) = withContext(io) { db.delete("notes", "id = ?", arrayOf(id.toString())) }

    suspend fun deleteAll() = withContext(io) { db.delete("notes", null, null) }

    suspend fun audioFiles(): Set<String> = withContext(io) {
        db.rawQuery("SELECT audio FROM notes", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
    }
}
