package app.olauncher.features.space.platform

import android.content.Context
import app.olauncher.core.WaypointPrefs
import app.olauncher.features.assistant.domain.Turn
import app.olauncher.features.assistant.platform.ApiKeyStore
import app.olauncher.features.assistant.platform.ChatClient
import app.olauncher.features.assistant.platform.ChatEngine
import app.olauncher.features.space.data.Note
import app.olauncher.features.space.data.NotesRepository
import app.olauncher.features.space.domain.Transcription
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Space: voice notes that are transcribed and summarised with the user's own API. Audio and text stay
 * in app storage; nothing is uploaded unless a transcription model is set.
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

    fun isTranscribeConfigured() = prefs.apiBaseUrl.isNotBlank() && prefs.transcribeModel.isNotBlank()

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
            val text = withContext(io) {
                TranscriptionClient.transcribe(
                    TranscribeConfig(prefs.transcribeApi, prefs.apiBaseUrl, prefs.transcribeModel, keys.get()),
                    audioFile(note),
                )
            }
            notes.update(id) { put("transcript", text); put("title", Transcription.fallbackTitle(text)) }
            if (text.isNotBlank() && chat.isConfigured()) {
                notes.update(id) { put("status", Note.Status.SUMMARIZING.name) }
                _changes.tryEmit(id)
                val reply = StringBuilder()
                runCatching {
                    withContext(io) {
                        ChatClient.stream(chat.config(), listOf(Turn(Turn.Role.USER, Transcription.summaryPrompt(text)))) { reply.append(it) }
                    }
                }
                val (title, summary) = Transcription.parseSummary(reply.toString())
                notes.update(id) {
                    if (title.isNotBlank()) put("title", title)
                    put("summary", summary)
                }
            }
            notes.update(id) { put("status", Note.Status.DONE.name) }
        } catch (e: Throwable) {
            notes.update(id) { put("status", Note.Status.ERROR.name); put("error", e.message ?: "Transcription failed") }
        }
        _changes.tryEmit(id)
    }

    suspend fun rename(id: Long, title: String) { notes.update(id) { put("title", title.trim()) }; _changes.tryEmit(id) }

    suspend fun delete(id: Long) {
        val n = notes.get(id) ?: return
        notes.delete(id)
        withContext(io) { audioFile(n).delete() }
        _changes.tryEmit(id)
    }

    suspend fun deleteAll() {
        notes.deleteAll()
        withContext(io) { dir.listFiles()?.forEach { it.delete() } }
        _changes.tryEmit(0)
    }
}
