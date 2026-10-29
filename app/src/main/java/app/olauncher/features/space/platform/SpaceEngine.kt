package app.olauncher.features.space.platform

import android.content.Context
import app.olauncher.core.WaypointPrefs
import app.olauncher.features.assistant.domain.Turn
import app.olauncher.features.assistant.platform.ApiKeyStore
import app.olauncher.features.assistant.platform.ChatClient
import app.olauncher.features.assistant.platform.ChatEngine
import app.olauncher.features.space.data.Note
import app.olauncher.features.space.data.NotesRepository
import app.olauncher.features.space.domain.Categories
import app.olauncher.features.space.domain.GoalPeriod
import app.olauncher.features.space.domain.ReminderParser
import app.olauncher.features.space.domain.SpaceAi
import app.olauncher.features.space.domain.Transcription
import app.olauncher.features.space.domain.TranscriptionApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Space: voice notes transcribed on the phone (Android's on-device recognizer) by default, or with the
 * user's API if they choose cloud transcription. Audio and text stay in app storage; the text is sent to
 * the chat API only for the optional title/summary.
 */
class SpaceEngine(
    private val context: Context,
    private val prefs: WaypointPrefs,
    private val keys: ApiKeyStore,
    private val chat: ChatEngine,
    val notes: NotesRepository,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val _changes = MutableSharedFlow<Long>(extraBufferCapacity = 64)
    val changes: SharedFlow<Long> = _changes

    val dir: File get() = File(context.filesDir, "space").apply { mkdirs() }
    fun audioFile(note: Note) = File(dir, note.audio)

    val isLocal get() = prefs.transcribeApi == TranscriptionApi.LOCAL

    /** On-device: Android 13+ with an on-device recognizer. Cloud: a provider and model are set. */
    fun isTranscribeConfigured() =
        if (isLocal) LocalTranscriber.isSupported(context) else prefs.apiBaseUrl.isNotBlank() && prefs.transcribeModel.isNotBlank()

    suspend fun saveRecording(file: File, durationMs: Long) {
        if (durationMs < 600) { withContext(io) { file.delete() }; return } // accidental tap
        val id = notes.create(file.name, durationMs)
        _changes.tryEmit(id)
        process(id)
    }

    /** Transcribe (if configured), then title + summary (if a chat model is configured). */
    fun process(id: Long) = scope.launch {
        val note = notes.get(id) ?: return@launch
        if (!isTranscribeConfigured()) {
            notes.update(id) { put("status", Note.Status.RECORDED.name); put("error", "") }
            _changes.tryEmit(id); return@launch
        }
        try {
            notes.update(id) { put("status", Note.Status.TRANSCRIBING.name); put("error", "") }
            _changes.tryEmit(id)
            val text = if (isLocal) LocalTranscriber.transcribe(context, audioFile(note), prefs.transcribeLanguage)
            else withContext(io) {
                TranscriptionClient.transcribe(
                    TranscribeConfig(prefs.transcribeApi, prefs.apiBaseUrl, prefs.transcribeModel, keys.get()),
                    audioFile(note),
                )
            }
            notes.update(id) { put("transcript", text); put("title", Transcription.fallbackTitle(text)) }
            analyze(id)
            notes.update(id) { put("status", Note.Status.DONE.name) }
        } catch (e: Throwable) {
            notes.update(id) { put("status", Note.Status.ERROR.name); put("error", e.message ?: "Transcription failed") }
        }
        _changes.tryEmit(id)
    }

    // ---- organising: category, title/summary, tasks and reminders

    /**
     * Categorises an item and pulls out to-dos/reminders. On the phone first (keywords, reminder parser);
     * if "AI title & summary" is on and a chat API is set, the model's JSON analysis refines it.
     */
    suspend fun analyze(id: Long, image: File? = null) {
        val note = notes.get(id) ?: return
        val text = note.text
        val now = System.currentTimeMillis()
        var category = Categories.categorize(text)
        var tasks = ReminderParser.parse(note.body.ifBlank { note.transcript }, now)
        var title: String? = null
        var summary: String? = null
        if (prefs.spaceSummarize && chat.isConfigured() && (text.isNotBlank() || image != null)) {
            notes.update(id) { put("status", Note.Status.SUMMARIZING.name) }
            _changes.tryEmit(id)
            val kind = when (note.kind) { Note.Kind.VOICE -> "voice note transcript"; Note.Kind.TEXT -> "written note"; Note.Kind.SCREENSHOT -> "screenshot" }
            val prompt = SpaceAi.prompt(kind, text.ifBlank { "(see image)" }, java.time.LocalDateTime.now())
            val reply = runCatching {
                withContext(io) { if (image != null) Vision.ask(chat.config(), prompt, image) else ask(prompt) }
            }.getOrNull().orEmpty()
            SpaceAi.parse(reply)?.let { a ->
                a.category?.let { category = it }
                if (a.title.isNotBlank()) title = a.title
                if (a.summary.isNotBlank()) summary = a.summary
                if (a.tasks.isNotEmpty() || tasks.isEmpty()) tasks = a.tasks
            } ?: run {
                // not JSON: treat as the older "title, blank line, summary" format
                if (reply.isNotBlank() && note.kind == Note.Kind.VOICE) {
                    val (t, sm) = Transcription.parseSummary(reply); title = t; summary = sm
                }
            }
        }
        notes.update(id) {
            put("category", category)
            if (title != null && (note.kind != Note.Kind.TEXT || note.title.isBlank())) put("title", title)
            if (summary != null) put("summary", summary)
            put("status", Note.Status.DONE.name)
        }
        val existing = notes.tasks().filter { it.noteId == id }.map { it.title.lowercase() }.toSet()
        for (t in tasks) if (t.title.lowercase() !in existing) addTask(t.title, t.due ?: 0, t.due != null, id, category)
        _changes.tryEmit(id)
    }

    private suspend fun ask(prompt: String): String {
        val sb = StringBuilder()
        ChatClient.stream(chat.config(), listOf(Turn(Turn.Role.USER, prompt))) { sb.append(it) }
        return sb.toString()
    }

    // ---- written notes and images

    suspend fun saveText(id: Long, title: String, body: String, images: List<String>): Long {
        val nid = if (id == 0L) notes.createItem(Note.Kind.TEXT, title.trim(), body, images)
        else { notes.update(id) { put("title", title.trim()); put("body", body); put("images", images.joinToString("\n")) }; id }
        _changes.tryEmit(nid)
        scope.launch { analyze(nid) }
        return nid
    }

    /** Copies an image (photo picker or a new screenshot) into Space. */
    suspend fun copyImage(uri: android.net.Uri): String? = withContext(io) {
        runCatching {
            val name = "img-${System.currentTimeMillis()}-${(Math.random() * 1000).toInt()}.jpg"
            val out = File(dir, name)
            context.contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
            name
        }.getOrNull()
    }

    /** A screenshot or image as its own Space item: copy, read text on the phone, analyse. */
    suspend fun importImage(uri: android.net.Uri, screenshot: Boolean): Long? {
        val name = copyImage(uri) ?: return null
        val id = notes.createItem(Note.Kind.SCREENSHOT, "", "", listOf(name))
        notes.update(id) { put("status", Note.Status.TRANSCRIBING.name) }
        _changes.tryEmit(id)
        scope.launch {
            val file = File(dir, name)
            val text = Ocr.read(context, file)
            notes.update(id) { put("transcript", text); if (text.isNotBlank()) put("title", Transcription.fallbackTitle(text)) }
            analyze(id, image = file.takeIf { prefs.spaceSummarize })
        }
        return id
    }

    fun imageFile(name: String) = File(dir, name)

    // ---- tasks and reminders

    suspend fun addTask(title: String, due: Long, remind: Boolean, noteId: Long = 0, category: String = ""): Long {
        val id = notes.addTask(title.trim(), due, remind, noteId, category.ifBlank { Categories.categorize(title) })
        notes.task(id)?.let { ReminderScheduler.schedule(context, it) }
        _changes.tryEmit(0)
        return id
    }

    suspend fun setTaskDone(id: Long, done: Boolean) {
        notes.updateTask(id) { put("done", if (done) 1 else 0) }
        notes.task(id)?.let { if (done) ReminderScheduler.cancel(context, id) else ReminderScheduler.schedule(context, it) }
        _changes.tryEmit(0)
    }

    suspend fun setTaskDue(id: Long, due: Long, remind: Boolean) {
        notes.updateTask(id) { put("due", due); put("remind", if (remind) 1 else 0) }
        notes.task(id)?.let { ReminderScheduler.schedule(context, it) }
        _changes.tryEmit(0)
    }

    suspend fun updateTask(id: Long, title: String, due: Long, remind: Boolean) {
        notes.updateTask(id) { put("title", title.trim()); put("due", due); put("remind", if (remind) 1 else 0) }
        notes.task(id)?.let { ReminderScheduler.schedule(context, it) }
        _changes.tryEmit(0)
    }

    suspend fun deleteTask(id: Long) { ReminderScheduler.cancel(context, id); notes.deleteTask(id); _changes.tryEmit(0) }

    // ---- goals

    suspend fun addGoal(title: String, target: Int, period: GoalPeriod) { notes.addGoal(title.trim(), target.coerceAtLeast(1), period); _changes.tryEmit(0) }
    suspend fun checkIn(goalId: Long) { notes.checkIn(goalId); _changes.tryEmit(0) }
    suspend fun undoCheckIn(goalId: Long) { notes.undoCheckIn(goalId); _changes.tryEmit(0) }
    suspend fun deleteGoal(goalId: Long) { notes.deleteGoal(goalId); _changes.tryEmit(0) }

    suspend fun setCategory(id: Long, category: String) { notes.update(id) { put("category", category) }; _changes.tryEmit(id) }

    suspend fun rename(id: Long, title: String) { notes.update(id) { put("title", title.trim()) }; _changes.tryEmit(id) }

    suspend fun delete(id: Long) {
        val n = notes.get(id) ?: return
        notes.delete(id)
        notes.tasks().filter { it.noteId == id && !it.done }.forEach { ReminderScheduler.cancel(context, it.id); notes.deleteTask(it.id) }
        withContext(io) {
            if (n.audio.isNotBlank()) audioFile(n).delete()
            n.images.forEach { File(dir, it).delete() }
        }
        _changes.tryEmit(id)
    }

    suspend fun deleteAll() {
        notes.tasks().forEach { ReminderScheduler.cancel(context, it.id) }
        notes.deleteAll()
        withContext(io) { dir.listFiles()?.forEach { it.delete() } }
        _changes.tryEmit(0)
    }
}
