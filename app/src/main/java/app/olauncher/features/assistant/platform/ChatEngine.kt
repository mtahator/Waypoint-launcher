package app.olauncher.features.assistant.platform

import app.olauncher.core.WaypointPrefs
import app.olauncher.features.assistant.data.ChatRepository
import app.olauncher.features.assistant.domain.ChatProtocol
import app.olauncher.features.assistant.domain.Turn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs replies in the app scope, so leaving the chat screen doesn't cut an answer off. The partial reply
 * is saved as it streams (throttled), and [changes] tells open screens which conversation to reload.
 * Nothing is sent anywhere except the provider the user configured.
 */
class ChatEngine(
    private val prefs: WaypointPrefs,
    private val keys: ApiKeyStore,
    private val repo: ChatRepository,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val sockets = ConcurrentHashMap<Long, HttpURLConnection>()
    private val _changes = MutableSharedFlow<Long>(extraBufferCapacity = 64)
    val changes: SharedFlow<Long> = _changes

    /** Conversations currently waiting for or streaming a reply. */
    private val _busy = MutableStateFlow<Set<Long>>(emptySet())
    val busy: StateFlow<Set<Long>> = _busy

    fun isConfigured(): Boolean = prefs.apiBaseUrl.isNotBlank() && prefs.apiModel.isNotBlank()

    fun config(): ApiConfig = ApiConfig(prefs.apiFormat, prefs.apiBaseUrl, prefs.apiModel, keys.get())

    /** Starts a new conversation with [text] and replies to it. Returns its id. */
    suspend fun startConversation(text: String): Long {
        val id = repo.create(ChatProtocol.title(text))
        send(id, text)
        return id
    }

    /** Adds the user's message and streams the assistant's reply. */
    fun send(conversationId: Long, text: String) {
        if (jobs[conversationId]?.isActive == true) return
        jobs[conversationId] = scope.launch {
            repo.add(conversationId, "user", text.trim())
            reply(conversationId)
        }
    }

    /** Replaces a failed or unwanted last reply with a new one. */
    fun retry(conversationId: Long) {
        if (jobs[conversationId]?.isActive == true) return
        jobs[conversationId] = scope.launch {
            val last = repo.messages(conversationId).lastOrNull()
            if (last != null && !last.isUser) repo.deleteMessage(last.id)
            reply(conversationId)
        }
    }

    fun stop(conversationId: Long) {
        sockets.remove(conversationId)?.let { s -> scope.launch(io) { runCatching { s.disconnect() } } }
        jobs.remove(conversationId)?.cancel()
    }

    private suspend fun reply(conversationId: Long) {
        _busy.value = _busy.value + conversationId
        val history = repo.messages(conversationId).filter { !it.error }
        val turns = buildList {
            prefs.systemPrompt.takeIf { it.isNotBlank() }?.let { add(Turn(Turn.Role.SYSTEM, it)) }
            history.forEach { add(Turn(if (it.isUser) Turn.Role.USER else Turn.Role.ASSISTANT, it.content)) }
        }
        val msgId = repo.add(conversationId, "assistant", "")
        _changes.tryEmit(conversationId)
        val text = StringBuilder()
        var lastSave = 0L
        try {
            withContext(io) {
                ChatClient.stream(config(), turns, onConnection = { sockets[conversationId] = it }) { piece ->
                    text.append(piece)
                    val now = System.currentTimeMillis()
                    if (now - lastSave > 120) {
                        lastSave = now
                        val snapshot = text.toString()
                        scope.launch { repo.update(msgId, snapshot); _changes.tryEmit(conversationId) }
                    }
                }
            }
            repo.update(msgId, text.toString())
        } catch (e: Throwable) {
            val stopped = e is kotlinx.coroutines.CancellationException || jobs[conversationId] == null
            withContext(kotlinx.coroutines.NonCancellable) {
                when {
                    stopped && text.isNotEmpty() -> repo.update(msgId, text.toString())
                    stopped -> repo.deleteMessage(msgId)
                    else -> repo.update(msgId, (if (text.isNotEmpty()) "$text\n\n" else "") + (e.message ?: "Request failed"), error = true)
                }
            }
            if (e is kotlinx.coroutines.CancellationException) throw e
        } finally {
            sockets.remove(conversationId)
            jobs.remove(conversationId)
            _busy.value = _busy.value - conversationId
            _changes.tryEmit(conversationId)
        }
    }
}
