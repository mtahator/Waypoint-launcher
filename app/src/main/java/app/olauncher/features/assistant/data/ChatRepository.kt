package app.olauncher.features.assistant.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

data class Conversation(val id: Long, val title: String, val updated: Long, val preview: String, val count: Int)

data class Message(val id: Long, val conversationId: Long, val role: String, val content: String, val created: Long, val error: Boolean) {
    val isUser get() = role == "user"
}

/** Conversations stay on the phone (chats.db, excluded from backup). */
private class ChatDatabase(context: Context) : SQLiteOpenHelper(context, "chats.db", null, 1) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE conversations (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, conversation_id INTEGER NOT NULL " +
                "REFERENCES conversations(id) ON DELETE CASCADE, role TEXT NOT NULL, content TEXT NOT NULL, " +
                "created INTEGER NOT NULL, error INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL("CREATE INDEX messages_conv ON messages(conversation_id, id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}

class ChatRepository(context: Context, private val io: CoroutineDispatcher) {
    private val helper by lazy { ChatDatabase(context.applicationContext) }
    private val db get() = helper.writableDatabase

    suspend fun conversations(): List<Conversation> = withContext(io) {
        db.rawQuery(
            "SELECT c.id, c.title, c.updated, " +
                "(SELECT content FROM messages m WHERE m.conversation_id = c.id ORDER BY m.id DESC LIMIT 1), " +
                "(SELECT COUNT(*) FROM messages m WHERE m.conversation_id = c.id) " +
                "FROM conversations c ORDER BY c.updated DESC",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(Conversation(c.getLong(0), c.getString(1), c.getLong(2), c.getString(3) ?: "", c.getInt(4)))
            }
        }
    }

    suspend fun conversation(id: Long): Conversation? = withContext(io) {
        db.rawQuery("SELECT id, title, updated FROM conversations WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) Conversation(c.getLong(0), c.getString(1), c.getLong(2), "", 0) else null
        }
    }

    suspend fun messages(conversationId: Long): List<Message> = withContext(io) {
        db.rawQuery(
            "SELECT id, conversation_id, role, content, created, error FROM messages WHERE conversation_id = ? ORDER BY id",
            arrayOf(conversationId.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(Message(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getLong(4), c.getInt(5) != 0))
            }
        }
    }

    suspend fun create(title: String, now: Long = System.currentTimeMillis()): Long = withContext(io) {
        db.insert("conversations", null, ContentValues().apply {
            put("title", title); put("created", now); put("updated", now)
        })
    }

    suspend fun add(conversationId: Long, role: String, content: String, error: Boolean = false, now: Long = System.currentTimeMillis()): Long =
        withContext(io) {
            val id = db.insert("messages", null, ContentValues().apply {
                put("conversation_id", conversationId); put("role", role); put("content", content)
                put("created", now); put("error", if (error) 1 else 0)
            })
            touch(conversationId, now)
            id
        }

    suspend fun update(messageId: Long, content: String, error: Boolean = false) = withContext(io) {
        db.update("messages", ContentValues().apply { put("content", content); put("error", if (error) 1 else 0) },
            "id = ?", arrayOf(messageId.toString()))
    }

    suspend fun deleteMessage(messageId: Long) = withContext(io) { db.delete("messages", "id = ?", arrayOf(messageId.toString())) }

    suspend fun rename(conversationId: Long, title: String) = withContext(io) {
        db.update("conversations", ContentValues().apply { put("title", title) }, "id = ?", arrayOf(conversationId.toString()))
    }

    suspend fun delete(conversationId: Long) = withContext(io) {
        db.delete("conversations", "id = ?", arrayOf(conversationId.toString()))
    }

    suspend fun deleteAll() = withContext(io) { db.delete("conversations", null, null) }

    suspend fun count(): Int = withContext(io) {
        db.rawQuery("SELECT COUNT(*) FROM conversations", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    private fun touch(conversationId: Long, now: Long) {
        db.update("conversations", ContentValues().apply { put("updated", now) }, "id = ?", arrayOf(conversationId.toString()))
    }
}
