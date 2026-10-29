package app.olauncher.features.assistant.platform

import app.olauncher.core.WaypointPrefs
import app.olauncher.features.assistant.data.ChatRepository
import app.olauncher.features.assistant.domain.ChatProtocol
import app.olauncher.features.assistant.domain.ImageProtocol
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
    private val images: ImageClient,
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

    fun isImageConfigured(): Boolean = prefs.apiBaseUrl.isNotBlank() && prefs.imageModel.isNotBlank()

    fun imageConfig(): ImageConfig = ImageConfig(prefs.imageApi, prefs.apiBaseUrl, prefs.imageModel, keys.get(), prefs.imageSize)

    /** Absolute file for a stored image name. */
    fun imageFile(name: String) = images.file(name)

    /** Removes image files of deleted messages. */
    suspend fun pruneImages() = withContext(io) { images.prune(repo.allImages()) }

    /** Starts a new conversation with [text] and replies to it. Returns its id. */
    suspend fun startConversation(text: String, image: Boolean = false): Long {
        val id = repo.create(ChatProtocol.title(ImageProtocol.command(text) ?: text))
        send(id, text, image)
        return id
    }

    /**
     * Adds the user's message and replies: streamed text, or an image for `/image …` (or [image] mode,
     * which stores the message as `/image …` so retries and history know what it was).
     */
    fun send(conversationId: Long, text: String, image: Boolean = false) {
        if (jobs[conversationId]?.isActive == true) return
        val stored = if (image && ImageProtocol.command(text) == null) "/image ${text.trim()}" else text.trim()
        jobs[conversationId] = scope.launch {
            repo.add(conversationId, "user", stored)
            val prompt = ImageProtocol.command(stored)
            if (prompt != null) imageReply(conversationId, prompt) else reply(conversationId)
        }
    }

    /** Replaces a failed or unwanted last reply with a new one. */
    fun retry(conversationId: Long) {
        if (jobs[conversationId]?.isActive == true) return
        jobs[conversationId] = scope.launch {
            val all = repo.messages(conversationId)
            val last = all.lastOrNull()
            if (last != null && !last.isUser) repo.deleteMessage(last.id)
            val prompt = all.lastOrNull { it.isUser }?.let { ImageProtocol.command(it.content) }
            if (prompt != null) imageReply(conversationId, prompt) else reply(conversationId)
        }
    }

    fun stop(conversationId: Long) {
        sockets.remove(conversationId)?.let { s -> scope.launch(io) { runCatching { s.disconnect() } } }
        jobs.remove(conversationId)?.cancel()
    }

    private suspend fun imageReply(conversationId: Long, prompt: String) {
        _busy.value = _busy.value + conversationId
        val msgId = repo.add(conversationId, "assistant", "")
        _changes.tryEmit(conversationId)
        try {
            if (!isImageConfigured()) throw ApiException("Set an image model in Assistant settings")
            val (names, text) = withContext(io) {
                images.generate(imageConfig(), prompt, "m$msgId", onConnection = { sockets[conversationId] = it })
            }
            repo.update(msgId, text.orEmpty())
            repo.setImages(msgId, names)
        } catch (e: Throwable) {
            val stopped = e is kotlinx.coroutines.CancellationException || jobs[conversationId] == null
            withContext(kotlinx.coroutines.NonCancellable) {
                if (stopped) repo.deleteMessage(msgId) else repo.update(msgId, e.message ?: "Image generation failed", error = true)
            }
            if (e is kotlinx.coroutines.CancellationException) throw e
        } finally {
            sockets.remove(conversationId)
            jobs.remove(conversationId)
            _busy.value = _busy.value - conversationId
            _changes.tryEmit(conversationId)
        }
    }

    private suspend fun reply(conversationId: Long) {
        _busy.value = _busy.value + conversationId
        // image turns (the /image request and its picture) aren't text context for the chat model
        val history = repo.messages(conversationId).filter {
            !it.error && it.images.isEmpty() && !(it.isUser && ImageProtocol.command(it.content) != null)
        }
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
