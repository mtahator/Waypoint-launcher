package app.olauncher.features.assistant.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.olauncher.features.assistant.domain.ChatCommand
import app.olauncher.features.assistant.domain.MemoryItem
import app.olauncher.features.websearch.domain.Source
import app.olauncher.features.websearch.domain.WebSearch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

data class Conversation(val id: Long, val title: String, val updated: Long, val preview: String, val count: Int)

data class Message(
    val id: Long, val conversationId: Long, val role: String, val content: String, val created: Long, val error: Boolean,
    /** Generated images: file names in files/chat_images. */
    val images: List<String> = emptyList(),
    /** Web sources this answer cites ([1] = first). */
    val sources: List<Source> = emptyList(),
    /** Places and routes the tools found, shown as a map card ([Tools.encodeMap] JSON, "" = none). */
    val map: String = "",
) {
    val isUser get() = role == "user"
}

/** Conversations stay on the phone (chats.db, excluded from backup). */
private class ChatDatabase(context: Context) : SQLiteOpenHelper(context, "chats.db", null, 5) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE conversations (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, conversation_id INTEGER NOT NULL " +
                "REFERENCES conversations(id) ON DELETE CASCADE, role TEXT NOT NULL, content TEXT NOT NULL, " +
                "created INTEGER NOT NULL, error INTEGER NOT NULL DEFAULT 0, images TEXT NOT NULL DEFAULT '', sources TEXT NOT NULL DEFAULT '')",
        )
        db.execSQL("CREATE INDEX messages_conv ON messages(conversation_id, id)")
        createV4(db)
        db.execSQL("ALTER TABLE messages ADD COLUMN map TEXT NOT NULL DEFAULT ''")
    }

    /** Memory about the user (tagged facts) and the user's own /commands. */
    private fun createV4(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE memories (id INTEGER PRIMARY KEY AUTOINCREMENT, tag TEXT NOT NULL, text TEXT NOT NULL, " +
                "core INTEGER NOT NULL DEFAULT 0, source TEXT NOT NULL DEFAULT 'you', updated INTEGER NOT NULL)",
        )
        db.execSQL("CREATE TABLE commands (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, instruction TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE messages ADD COLUMN images TEXT NOT NULL DEFAULT ''")
        if (oldVersion < 3) db.execSQL("ALTER TABLE messages ADD COLUMN sources TEXT NOT NULL DEFAULT ''")
        if (oldVersion < 4) createV4(db)
        if (oldVersion < 5) db.execSQL("ALTER TABLE messages ADD COLUMN map TEXT NOT NULL DEFAULT ''")
    }
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
            "SELECT id, conversation_id, role, content, created, error, images, sources, map FROM messages WHERE conversation_id = ? ORDER BY id",
            arrayOf(conversationId.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(Message(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getLong(4), c.getInt(5) != 0,
                    c.getString(6).split('\n').filter { it.isNotBlank() },
                    c.getString(7).takeIf { it.isNotBlank() }?.let { WebSearch.decodeSources(it) } ?: emptyList(), c.getString(8)))
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

    suspend fun setSources(messageId: Long, sources: List<Source>) = withContext(io) {
        db.update("messages", ContentValues().apply { put("sources", if (sources.isEmpty()) "" else WebSearch.encodeSources(sources)) },
            "id = ?", arrayOf(messageId.toString()))
    }

    suspend fun setMap(messageId: Long, map: String) = withContext(io) {
        db.update("messages", ContentValues().apply { put("map", map) }, "id = ?", arrayOf(messageId.toString()))
    }

    suspend fun setImages(messageId: Long, images: List<String>) = withContext(io) {
        db.update("messages", ContentValues().apply { put("images", images.joinToString("\n")) }, "id = ?", arrayOf(messageId.toString()))
    }

    /** Image files referenced by any message (to clean up orphans after deletes). */
    suspend fun allImages(): Set<String> = withContext(io) {
        db.rawQuery("SELECT images FROM messages WHERE images != ''", null).use { c ->
            buildSet { while (c.moveToNext()) c.getString(0).split('\n').filter { it.isNotBlank() }.forEach { add(it) } }
        }
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

    // ---- memory

    suspend fun memories(): List<MemoryItem> = withContext(io) {
        db.rawQuery("SELECT id, tag, text, core, source, updated FROM memories ORDER BY core DESC, tag, id", null).use { c ->
            buildList { while (c.moveToNext()) add(MemoryItem(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3) != 0, c.getString(4), c.getLong(5))) }
        }
    }

    suspend fun addMemory(tag: String, text: String, core: Boolean = false, source: String = MemoryItem.SOURCE_USER): Long = withContext(io) {
        db.insert("memories", null, ContentValues().apply {
            put("tag", tag); put("text", text); put("core", if (core) 1 else 0); put("source", source); put("updated", System.currentTimeMillis())
        })
    }

    suspend fun updateMemory(id: Long, tag: String? = null, text: String? = null, core: Boolean? = null) = withContext(io) {
        db.update("memories", ContentValues().apply {
            tag?.let { put("tag", it) }; text?.let { put("text", it) }; core?.let { put("core", if (it) 1 else 0) }
            put("updated", System.currentTimeMillis())
        }, "id = ?", arrayOf(id.toString()))
    }

    suspend fun deleteMemory(id: Long) = withContext(io) { db.delete("memories", "id = ?", arrayOf(id.toString())) }

    suspend fun clearMemories() = withContext(io) { db.delete("memories", null, null) }

    // ---- commands

    suspend fun commands(): List<ChatCommand> = withContext(io) {
        db.rawQuery("SELECT id, name, instruction FROM commands ORDER BY name", null).use { c ->
            buildList { while (c.moveToNext()) add(ChatCommand(c.getLong(0), c.getString(1), c.getString(2))) }
        }
    }

    /** Adds or replaces the command called [name]. */
    suspend fun saveCommand(name: String, instruction: String) = withContext(io) {
        db.insertWithOnConflict("commands", null, ContentValues().apply { put("name", name); put("instruction", instruction) },
            SQLiteDatabase.CONFLICT_REPLACE)
    }

    suspend fun deleteCommand(id: Long) = withContext(io) { db.delete("commands", "id = ?", arrayOf(id.toString())) }

    private fun touch(conversationId: Long, now: Long) {
        db.update("conversations", ContentValues().apply { put("updated", now) }, "id = ?", arrayOf(conversationId.toString()))
    }
}
