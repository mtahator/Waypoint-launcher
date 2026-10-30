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
import app.olauncher.features.space.domain.ReminderRequest
import app.olauncher.features.space.domain.RepeatRule
import app.olauncher.features.space.domain.SpaceAi
import app.olauncher.features.space.domain.Transcription
import app.olauncher.features.space.domain.TranscriptCleanup
import app.olauncher.features.space.domain.TranscriptionApi
import app.olauncher.features.assistant.platform.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    /** Per note: how far a long transcription or clean-up has got ("40%", "2/5"). */
    private val _progress = MutableStateFlow<Map<Long, String>>(emptyMap())
    val progress: StateFlow<Map<Long, String>> = _progress
    private fun setProgress(id: Long, text: String?) {
        _progress.value = if (text == null) _progress.value - id else _progress.value + (id to text)
        _changes.tryEmit(id)
    }

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
            val log = StringBuilder()
            val raw = if (isLocal) LocalTranscriber.transcribe(context, audioFile(note), prefs.transcribeLanguage, log) { done, total, sofar ->
                // the text so far is saved as it comes, so a long meeting shows up while it's still being transcribed
                notes.update(id) { put("transcript", sofar); put("raw", "") }
                setProgress(id, if (total > 1) "${done * 100 / total}%" else null)
            }
            else withContext(io) {
                TranscriptionClient.transcribe(
                    TranscribeConfig(prefs.transcribeApi, prefs.apiBaseUrl, prefs.transcribeModel, keys.get()),
                    audioFile(note),
                )
            }
            setProgress(id, null)
            // with status DONE the error column holds the per-piece log ("Transcription details")
            notes.update(id) { put("transcript", raw); put("raw", ""); put("title", Transcription.fallbackTitle(raw)); put("error", log.toString()) }
            if (raw.isBlank()) throw ApiException("No speech was recognised")
            val clean = cleanUp(id, raw)
            if (clean != raw) notes.update(id) { put("transcript", clean); put("raw", raw); put("title", Transcription.fallbackTitle(clean)) }
            analyze(id)
            notes.update(id) { put("status", Note.Status.DONE.name) }
        } catch (e: Throwable) {
            setProgress(id, null)
            notes.update(id) { put("status", Note.Status.ERROR.name); put("error", e.message ?: "Transcription failed") }
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
        _changes.tryEmit(id)
    }

    /**
     * The chat model polishes the raw words (punctuation, paragraphs, misheard words), part by part for
     * long recordings. Any part it fails or rewrites unfaithfully stays as recognised.
     */
    private suspend fun cleanUp(id: Long, raw: String): String {
        if (!prefs.spaceCleanup || !chat.isConfigured()) return raw
        val parts = TranscriptCleanup.parts(raw)
        notes.update(id) { put("status", Note.Status.CLEANING.name) }
        val out = ArrayList<String>()
        for ((i, part) in parts.withIndex()) {
            if (parts.size > 1) setProgress(id, "${i + 1}/${parts.size}")
            val reply = runCatching { withContext(io) { ask(TranscriptCleanup.prompt(part, out.lastOrNull().orEmpty(), i, parts.size)) } }
                .getOrNull().orEmpty()
            out += TranscriptCleanup.accept(part, reply) ?: part.replace('\n', ' ')
        }
        setProgress(id, null)
        return out.joinToString("\n\n").trim()
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
            val prompt = SpaceAi.prompt(kind, text.ifBlank { "(see image)" }, java.time.LocalDateTime.now(), long = note.durationMs > 3 * 60_000)
            val reply = runCatching {
                withContext(io) { if (image != null) Vision.ask(chat.config(), prompt, image) else ask(prompt) }
            }.getOrNull().orEmpty()
            SpaceAi.parse(reply)?.let { a ->
                a.category?.let { category = it }
                if (a.title.isNotBlank()) title = a.title
                if (a.summary.isNotBlank()) summary = a.summary
                // the model read the whole thing in context: its list (possibly empty) replaces the phone's guesses
                tasks = a.tasks
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

    /** For the assistant's add_reminder tool. */
    suspend fun toolReminder(title: String, due: Long): String {
        if (title.isBlank()) return "Nothing to add"
        addTask(title, due, due > 0)
        return if (due > 0) "Reminder set: $title at " + android.text.format.DateUtils.formatDateTime(context, due,
            android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY or android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME)
        else "Added to to-dos: $title (no reminder time)"
    }

    /** For the assistant's search_my_notes tool: the best matching notes and to-dos as short text. */
    suspend fun searchText(query: String): String {
        val words = app.olauncher.features.assistant.domain.Memories.words(query).toSet()
        if (words.isEmpty()) return "Nothing to search for"
        fun score(text: String) = app.olauncher.features.assistant.domain.Memories.words(text).count { it in words }
        val hits = notes.all().map { it to score(it.text) }.filter { it.second > 0 }.sortedByDescending { it.second }.take(5)
        val todos = notes.tasks().map { it to score(it.title) }.filter { it.second > 0 }.sortedByDescending { it.second }.take(5)
        if (hits.isEmpty() && todos.isEmpty()) return "No matching notes or to-dos"
        val df = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
        return buildString {
            hits.forEach { (n, _) ->
                append("- ").append(n.kind.name.lowercase()).append(" note \"").append(n.displayTitle).append("\" (").append(df.format(java.util.Date(n.created))).append("): ")
                append((n.summary.ifBlank { n.body.ifBlank { n.transcript } }).replace('\n', ' ').take(500)).append('\n')
            }
            todos.forEach { (t, _) -> append("- to-do: ").append(t.title).append(if (t.done) " (done)" else "").append('\n') }
        }.trim()
    }

    /** A chat message like "remind me tonight to do the dishes" becomes a to-do/reminder; returns the confirmation. */
    suspend fun chatRequest(text: String): String? {
        val r = ReminderRequest.parse(text, System.currentTimeMillis()) ?: return null
        addTask(r.title, r.due ?: 0, r.remind, repeat = r.repeat.encode())
        val fa = text.any { it in '\u0600'..'\u06FF' }
        val time = r.due?.let {
            android.text.format.DateUtils.formatDateTime(context, it, android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY or
                android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME or android.text.format.DateUtils.FORMAT_ABBREV_ALL)
        }?.let { t -> if (r.repeat.repeats) "$t (${r.repeat.describe().lowercase()})" else t }
        return when {
            fa && r.remind -> "یادآوری تنظیم شد: **${r.title}** — $time\nدر Space → کارها می‌توانید آن را تغییر دهید."
            fa -> "به کارها اضافه شد: **${r.title}**${time?.let { " — $it" }.orEmpty()}"
            r.remind -> "Reminder set: **${r.title}** — $time.\nYou'll get a notification then; change it in Space → To-do."
            else -> "Added to your to-dos: **${r.title}**${time?.let { " — $it" }.orEmpty()}.\nAdd a time (e.g. \"at 8pm\") if you want a reminder."
        }
    }

    suspend fun addTask(title: String, due: Long, remind: Boolean, noteId: Long = 0, category: String = "", repeat: String = ""): Long {
        val id = notes.addTask(title.trim(), due, remind, noteId, category.ifBlank { Categories.categorize(title) }, repeat)
        notes.task(id)?.let { ReminderScheduler.schedule(context, it) }
        _changes.tryEmit(0)
        return id
    }

    /**
     * Ticks a to-do off (or back on). A repeating one isn't finished: it moves to its next date, with its
     * reminder; the new due time is returned so the UI can say when it comes back.
     */
    suspend fun setTaskDone(id: Long, done: Boolean): Long? {
        val task = notes.task(id) ?: return null
        if (done && task.rule.repeats) {
            val next = RepeatRule.advance(task.due, task.rule, System.currentTimeMillis())
            notes.updateTask(id) { put("due", next); put("done", 0) }
            notes.task(id)?.let { ReminderScheduler.schedule(context, it) }
            _changes.tryEmit(0)
            return next
        }
        notes.updateTask(id) { put("done", if (done) 1 else 0) }
        notes.task(id)?.let { if (done) ReminderScheduler.cancel(context, id) else ReminderScheduler.schedule(context, it) }
        _changes.tryEmit(0)
        return null
    }

    suspend fun setTaskDue(id: Long, due: Long, remind: Boolean) {
        notes.updateTask(id) { put("due", due); put("remind", if (remind) 1 else 0) }
        notes.task(id)?.let { ReminderScheduler.schedule(context, it) }
        _changes.tryEmit(0)
    }

    suspend fun updateTask(id: Long, title: String, due: Long, remind: Boolean, repeat: String = "") {
        notes.updateTask(id) { put("title", title.trim()); put("due", due); put("remind", if (remind) 1 else 0); put("repeat", repeat) }
        notes.task(id)?.let { ReminderScheduler.schedule(context, it) }
        _changes.tryEmit(0)
    }

    suspend fun deleteTask(id: Long) { ReminderScheduler.cancel(context, id); notes.deleteTask(id); _changes.tryEmit(0) }

    // ---- goals

    suspend fun addGoal(title: String, target: Int, period: GoalPeriod) { notes.addGoal(title.trim(), target.coerceAtLeast(1), period); _changes.tryEmit(0) }
    suspend fun checkIn(goalId: Long) { notes.checkIn(goalId); _changes.tryEmit(0) }
    suspend fun undoCheckIn(goalId: Long) { notes.undoCheckIn(goalId); _changes.tryEmit(0) }
    suspend fun deleteGoal(goalId: Long) { notes.deleteGoal(goalId); _changes.tryEmit(0) }
    suspend fun addProject(title: String, deadline: Long, percent: Int) { notes.addProject(title.trim(), deadline, percent); _changes.tryEmit(0) }
    suspend fun setGoalProgress(goalId: Long, percent: Int) { notes.updateGoal(goalId) { put("percent", percent.coerceIn(0, 100)) }; _changes.tryEmit(0) }
    suspend fun updateGoal(goalId: Long, title: String, target: Int, period: GoalPeriod, deadline: Long) {
        notes.updateGoal(goalId) { put("title", title.trim()); put("target", target.coerceAtLeast(1)); put("period", period.name); put("deadline", deadline) }
        _changes.tryEmit(0)
    }

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
