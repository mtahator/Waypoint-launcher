package app.olauncher.features.assistant.platform

import app.olauncher.core.WaypointPrefs
import app.olauncher.features.assistant.data.ChatRepository
import app.olauncher.features.assistant.domain.ChatProtocol
import app.olauncher.features.assistant.domain.ImageProtocol
import app.olauncher.features.websearch.domain.SearchEngine
import app.olauncher.features.websearch.domain.Source
import app.olauncher.features.websearch.domain.WebSearch
import app.olauncher.features.websearch.domain.WebSearchMode
import app.olauncher.features.websearch.platform.WebSearchClient
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
    private val braveKeys: ApiKeyStore,
    private val repo: ChatRepository,
    private val images: ImageClient,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val sockets = ConcurrentHashMap<Long, HttpURLConnection>()
    private val _changes = MutableSharedFlow<Long>(extraBufferCapacity = 64)
    val changes: SharedFlow<Long> = _changes

    /** What a conversation's pending reply is doing ("Searching: …", "Reading 4 sources…"). */
    private val _status = MutableStateFlow<Map<Long, String>>(emptyMap())
    val status: StateFlow<Map<Long, String>> = _status

    private fun setStatus(id: Long, text: String?) {
        _status.value = if (text == null) _status.value - id else _status.value + (id to text)
    }

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
    suspend fun startConversation(text: String, image: Boolean = false, web: Boolean = false): Long {
        val id = repo.create(ChatProtocol.title(ImageProtocol.command(text) ?: text))
        send(id, text, image, web)
        return id
    }

    /**
     * Adds the user's message and replies: streamed text, or an image for `/image …` (or [image] mode,
     * which stores the message as `/image …` so retries and history know what it was).
     */
    fun send(conversationId: Long, text: String, image: Boolean = false, web: Boolean = false) {
        if (jobs[conversationId]?.isActive == true) return
        val stored = if (image && ImageProtocol.command(text) == null) "/image ${text.trim()}" else text.trim()
        jobs[conversationId] = scope.launch {
            repo.add(conversationId, "user", stored)
            val prompt = ImageProtocol.command(stored)
            if (prompt != null) imageReply(conversationId, prompt) else reply(conversationId, forceSearch = web)
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

    private suspend fun reply(conversationId: Long, forceSearch: Boolean = false) {
        _busy.value = _busy.value + conversationId
        // image turns (the /image request and its picture) aren't text context for the chat model
        val history = repo.messages(conversationId).filter {
            !it.error && it.images.isEmpty() && !(it.isUser && ImageProtocol.command(it.content) != null)
        }
        val msgId = repo.add(conversationId, "assistant", "")
        _changes.tryEmit(conversationId)
        val aboutMe = prefs.aboutMe.takeIf { prefs.useAboutMe && it.isNotBlank() }
        val web = try { research(conversationId, history, aboutMe, forceSearch) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
            Research(null, emptyList(), false, aboutMe != null, note = e.message)
        }
        val turns = buildList {
            prefs.systemPrompt.takeIf { it.isNotBlank() }?.let { add(Turn(Turn.Role.SYSTEM, it)) }
            if (aboutMe != null && web.useAboutMe) add(Turn(Turn.Role.SYSTEM, ABOUT_PREFIX + aboutMe))
            web.prompt?.let { add(Turn(Turn.Role.SYSTEM, it)) }
            web.note?.let { add(Turn(Turn.Role.SYSTEM, "Web search was attempted but failed ($it). Answer from general knowledge and say that live results were unavailable.")) }
            history.forEach { add(Turn(if (it.isUser) Turn.Role.USER else Turn.Role.ASSISTANT, it.content)) }
        }
        if (web.sources.isNotEmpty()) repo.setSources(msgId, web.sources)
        val pluginSources = ArrayList<Source>()
        setStatus(conversationId, null)
        val text = StringBuilder()
        var lastSave = 0L
        try {
            withContext(io) {
                ChatClient.stream(config(), turns, onConnection = { sockets[conversationId] = it }, webPlugin = web.plugin,
                    onCitations = { list -> list.forEach { (t, u) -> if (pluginSources.none { it.url == u }) pluginSources += Source(t.ifBlank { u }, u) } },
                ) { piece ->
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
            if (pluginSources.isNotEmpty()) repo.setSources(msgId, pluginSources)
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
            setStatus(conversationId, null)
            _busy.value = _busy.value - conversationId
            _changes.tryEmit(conversationId)
        }
    }

    /** Result of the planning/search step: an extra system prompt with numbered sources, and the sources. */
    private data class Research(
        val prompt: String?, val sources: List<Source>, val plugin: Boolean, val useAboutMe: Boolean, val note: String? = null,
    )

    /**
     * Perplexity-style: the model plans whether to search, what to search for and whether "About you" matters;
     * then Waypoint searches, reads the top pages and hands them over as numbered sources to cite.
     */
    private suspend fun research(conversationId: Long, history: List<app.olauncher.features.assistant.data.Message>, aboutMe: String?, force: Boolean): Research {
        val mode = prefs.webSearchMode
        if (mode == WebSearchMode.OFF && !force) return Research(null, emptyList(), false, true)
        val question = history.lastOrNull { it.isUser }?.content ?: return Research(null, emptyList(), false, true)
        val today = java.time.LocalDate.now().toString()
        val forced = force || mode == WebSearchMode.ALWAYS
        setStatus(conversationId, "Thinking…")
        val recent = history.dropLast(1).takeLast(6).joinToString("\n") { (if (it.isUser) "User: " else "Assistant: ") + it.content.take(300) }
        val planText = StringBuilder()
        withContext(io) {
            runCatching {
                ChatClient.stream(config(), listOf(Turn(Turn.Role.USER, WebSearch.plannerPrompt(question, recent, aboutMe, today, forced)))) { planText.append(it) }
            }
        }
        // the model plans; if its reply isn't a readable plan, decide on the phone (works with any provider)
        val plan = (if (WebSearch.hasPlan(planText.toString())) WebSearch.parsePlan(planText.toString(), forced)
        else WebSearch.heuristicPlan(question, forced)).let { p ->
            if (p.search && p.queries.isEmpty()) p.copy(queries = listOf(question.take(120))) else p
        }
        if (!plan.search) return Research(null, emptyList(), false, plan.useAboutMe)
        if (prefs.searchEngine == SearchEngine.OPENROUTER) {
            setStatus(conversationId, "Searching the web…")
            return Research(null, emptyList(), true, plan.useAboutMe)
        }
        setStatus(conversationId, "Searching: " + plan.queries.joinToString(" · "))
        val brave = if (prefs.searchEngine == SearchEngine.BRAVE) braveKeys.get() else null
        val results = withContext(io) {
            val perQuery = plan.queries.map { q -> runCatching { WebSearchClient.search(prefs.searchEngine, q, brave) }.getOrElse { e ->
                if (plan.queries.size == 1) throw e else emptyList()
            } }
            val merged = WebSearch.merge(perQuery, 6)
            if (merged.isEmpty()) throw ApiException("no results")
            setStatus(conversationId, "Reading ${minOf(4, merged.size)} sources…")
            WebSearchClient.read(merged)
        }
        val sources = results.map { Source(it.title.ifBlank { it.url }, it.url) }
        setStatus(conversationId, "Writing the answer…")
        return Research(WebSearch.sourcesPrompt(results, today), sources, false, plan.useAboutMe)
    }

    private companion object {
        /** Personal context, used only when it helps (the model is told not to bring it up otherwise). */
        const val ABOUT_PREFIX = "Background about the user, for personalising answers. Use it only when it is relevant " +
            "to the question; don't mention it otherwise.\n"
    }
}
