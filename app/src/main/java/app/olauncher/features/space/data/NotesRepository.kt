package app.olauncher.features.space.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * A Space item: a voice note ([audio] in files/space), a written note ([body] + [images]) or a screenshot
 * ([images] = the picture, [transcript] = text read from it).
 */
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
    val kind: Kind = Kind.VOICE,
    val body: String = "",
    val images: List<String> = emptyList(),
    val category: String = "",
    /** The recognizer's own words, kept when the AI clean-up rewrote [transcript]. */
    val raw: String = "",
) {
    enum class Status { RECORDED, TRANSCRIBING, CLEANING, SUMMARIZING, DONE, ERROR }
    enum class Kind { VOICE, TEXT, SCREENSHOT }
    val displayTitle get() = title.ifBlank {
        when (kind) { Kind.VOICE -> "Voice note"; Kind.TEXT -> body.lineSequence().firstOrNull()?.take(48)?.ifBlank { null } ?: "Note"; Kind.SCREENSHOT -> "Screenshot" }
    }
    /** All text worth searching/classifying. */
    val text get() = listOf(title, body, transcript, summary).filter { it.isNotBlank() }.joinToString("\n")
}

/** A to-do; [due] epoch millis (0 = none); [remind] schedules a notification at [due]. */
data class Task(
    val id: Long, val title: String, val done: Boolean, val due: Long, val remind: Boolean, val created: Long, val noteId: Long, val category: String,
    /** [RepeatRule] encoding; "" = once. */
    val repeat: String = "",
) {
    val rule get() = app.olauncher.features.space.domain.RepeatRule.decode(repeat)
}

/**
 * A habit ([target] check-ins per [period]) or a project (finish by [deadline], [percent] done so far).
 */
data class Goal(
    val id: Long, val title: String, val target: Int, val period: app.olauncher.features.space.domain.GoalPeriod, val created: Long, val checkins: List<Long>,
    val kind: Kind = Kind.HABIT, val deadline: Long = 0, val percent: Int = 0,
) {
    enum class Kind { HABIT, PROJECT }
}

private class SpaceDatabase(context: Context) : SQLiteOpenHelper(context, "space.db", null, 4) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT, created INTEGER NOT NULL, duration INTEGER NOT NULL, " +
                "audio TEXT NOT NULL, title TEXT NOT NULL DEFAULT '', transcript TEXT NOT NULL DEFAULT '', " +
                "summary TEXT NOT NULL DEFAULT '', status TEXT NOT NULL, error TEXT NOT NULL DEFAULT '')",
        )
        onUpgrade(db, 1, 4)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE notes ADD COLUMN kind TEXT NOT NULL DEFAULT 'VOICE'")
            db.execSQL("ALTER TABLE notes ADD COLUMN body TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE notes ADD COLUMN images TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE notes ADD COLUMN category TEXT NOT NULL DEFAULT ''")
            db.execSQL(
                "CREATE TABLE tasks (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, done INTEGER NOT NULL DEFAULT 0, " +
                    "due INTEGER NOT NULL DEFAULT 0, remind INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL, " +
                    "note_id INTEGER NOT NULL DEFAULT 0, category TEXT NOT NULL DEFAULT '')",
            )
            db.execSQL("CREATE TABLE goals (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, target INTEGER NOT NULL, period TEXT NOT NULL, created INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE checkins (goal_id INTEGER NOT NULL, at INTEGER NOT NULL)")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE notes ADD COLUMN raw TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 4) {
            // repeating to-dos; project goals (deadline + % done)
            db.execSQL("ALTER TABLE tasks ADD COLUMN repeat TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE goals ADD COLUMN kind TEXT NOT NULL DEFAULT 'HABIT'")
            db.execSQL("ALTER TABLE goals ADD COLUMN deadline INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE goals ADD COLUMN percent INTEGER NOT NULL DEFAULT 0")
        }
    }
}

/** Space notes stay on the phone (space.db + files/space, excluded from backup). */
class NotesRepository(context: Context, private val io: CoroutineDispatcher) {
    private val helper by lazy { SpaceDatabase(context.applicationContext) }
    private val db get() = helper.writableDatabase

    private fun read(c: android.database.Cursor) = Note(
        c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6),
        runCatching { Note.Status.valueOf(c.getString(7)) }.getOrDefault(Note.Status.ERROR), c.getString(8),
        runCatching { Note.Kind.valueOf(c.getString(9)) }.getOrDefault(Note.Kind.VOICE), c.getString(10),
        c.getString(11).split('\n').filter { it.isNotBlank() }, c.getString(12), c.getString(13),
    )

    private val cols = "id, created, duration, audio, title, transcript, summary, status, error, kind, body, images, category, raw"

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

    /** A written note or a screenshot. */
    suspend fun createItem(kind: Note.Kind, title: String, body: String, images: List<String>, created: Long = System.currentTimeMillis()): Long = withContext(io) {
        db.insert("notes", null, ContentValues().apply {
            put("created", created); put("duration", 0); put("audio", ""); put("status", Note.Status.DONE.name)
            put("kind", kind.name); put("title", title); put("body", body); put("images", images.joinToString("\n"))
        })
    }

    suspend fun findByImage(name: String): Note? = all().firstOrNull { name in it.images }

    // ---- tasks

    private fun readTask(c: android.database.Cursor) = Task(c.getLong(0), c.getString(1), c.getInt(2) != 0, c.getLong(3), c.getInt(4) != 0, c.getLong(5), c.getLong(6), c.getString(7), c.getString(8))
    private val taskCols = "id, title, done, due, remind, created, note_id, category, repeat"

    suspend fun tasks(): List<Task> = withContext(io) {
        db.rawQuery("SELECT $taskCols FROM tasks ORDER BY done, CASE WHEN due = 0 THEN 1 ELSE 0 END, due, created DESC", null)
            .use { c -> buildList { while (c.moveToNext()) add(readTask(c)) } }
    }

    suspend fun task(id: Long): Task? = withContext(io) {
        db.rawQuery("SELECT $taskCols FROM tasks WHERE id = ?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) readTask(c) else null }
    }

    suspend fun addTask(title: String, due: Long, remind: Boolean, noteId: Long = 0, category: String = "", repeat: String = ""): Long = withContext(io) {
        db.insert("tasks", null, ContentValues().apply {
            put("repeat", repeat)
            put("title", title); put("due", due); put("remind", if (remind) 1 else 0); put("created", System.currentTimeMillis())
            put("note_id", noteId); put("category", category)
        })
    }

    suspend fun updateTask(id: Long, block: ContentValues.() -> Unit) = withContext(io) {
        db.update("tasks", ContentValues().apply(block), "id = ?", arrayOf(id.toString()))
    }

    suspend fun deleteTask(id: Long) = withContext(io) { db.delete("tasks", "id = ?", arrayOf(id.toString())) }

    // ---- goals

    suspend fun goals(): List<Goal> = withContext(io) {
        val checks = db.rawQuery("SELECT goal_id, at FROM checkins", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0) to c.getLong(1)) }
        }.groupBy({ it.first }, { it.second })
        db.rawQuery("SELECT id, title, target, period, created, kind, deadline, percent FROM goals ORDER BY created DESC", null).use { c ->
            buildList {
                while (c.moveToNext()) add(Goal(c.getLong(0), c.getString(1), c.getInt(2),
                    runCatching { app.olauncher.features.space.domain.GoalPeriod.valueOf(c.getString(3)) }.getOrDefault(app.olauncher.features.space.domain.GoalPeriod.WEEK),
                    c.getLong(4), checks[c.getLong(0)].orEmpty(),
                    runCatching { Goal.Kind.valueOf(c.getString(5)) }.getOrDefault(Goal.Kind.HABIT), c.getLong(6), c.getInt(7)))
            }
        }
    }

    suspend fun addGoal(title: String, target: Int, period: app.olauncher.features.space.domain.GoalPeriod): Long = withContext(io) {
        db.insert("goals", null, ContentValues().apply { put("title", title); put("target", target); put("period", period.name); put("created", System.currentTimeMillis()) })
    }

    suspend fun addProject(title: String, deadline: Long, percent: Int): Long = withContext(io) {
        db.insert("goals", null, ContentValues().apply {
            put("title", title); put("target", 100); put("period", "TOTAL"); put("created", System.currentTimeMillis())
            put("kind", Goal.Kind.PROJECT.name); put("deadline", deadline); put("percent", percent.coerceIn(0, 100))
        })
    }

    suspend fun updateGoal(id: Long, block: ContentValues.() -> Unit) = withContext(io) {
        db.update("goals", ContentValues().apply(block), "id = ?", arrayOf(id.toString()))
    }

    suspend fun checkIn(goalId: Long, at: Long = System.currentTimeMillis()) = withContext(io) {
        db.insert("checkins", null, ContentValues().apply { put("goal_id", goalId); put("at", at) })
    }

    /** Removes the most recent check-in (undo). */
    suspend fun undoCheckIn(goalId: Long) = withContext(io) {
        db.execSQL("DELETE FROM checkins WHERE rowid = (SELECT rowid FROM checkins WHERE goal_id = ? ORDER BY at DESC LIMIT 1)", arrayOf(goalId))
    }

    suspend fun deleteGoal(id: Long) = withContext(io) {
        db.delete("checkins", "goal_id = ?", arrayOf(id.toString())); db.delete("goals", "id = ?", arrayOf(id.toString()))
    }

    suspend fun update(id: Long, block: ContentValues.() -> Unit) = withContext(io) {
        db.update("notes", ContentValues().apply(block), "id = ?", arrayOf(id.toString()))
    }

    suspend fun delete(id: Long) = withContext(io) { db.delete("notes", "id = ?", arrayOf(id.toString())) }

    suspend fun deleteAll() = withContext(io) {
        db.delete("notes", null, null); db.delete("tasks", null, null); db.delete("checkins", null, null); db.delete("goals", null, null)
    }

    suspend fun audioFiles(): Set<String> = withContext(io) {
        db.rawQuery("SELECT audio, images FROM notes", null).use { c ->
            buildSet { while (c.moveToNext()) { add(c.getString(0)); c.getString(1).split('\n').filter { it.isNotBlank() }.forEach { add(it) } } }
        }
    }
}
