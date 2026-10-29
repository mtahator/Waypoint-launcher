package app.olauncher.features.assistant.platform

import app.olauncher.core.WaypointPrefs
import app.olauncher.features.assistant.data.ChatRepository
import app.olauncher.features.assistant.domain.ChatProtocol
import app.olauncher.features.assistant.domain.Commands
import app.olauncher.features.assistant.domain.Memories
import app.olauncher.features.assistant.domain.MemoryItem
import app.olauncher.features.assistant.domain.Tools
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
    /** Requests done on the phone instead of by the model (reminders, to-dos); returns the reply, or null. */
    private val localAction: suspend (String) -> String? = { null },
    /** Runs the model's tool calls (location, places, routes, weather, reminders, notes); null = no tools. */
    private val tools: ToolRunner? = null,
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
            when {
                prompt != null -> imageReply(conversationId, prompt)
                !web && localReply(conversationId, stored) -> Unit
                else -> reply(conversationId, forceSearch = web)
            }
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

    /** "Set a reminder…", "add a to-do…": done on the phone, confirmed in the chat, no model or search. */
    private suspend fun localReply(conversationId: Long, text: String): Boolean {
        val answer = runCatching { localAction(text) }.getOrNull() ?: return false
        repo.add(conversationId, "assistant", answer)
        jobs.remove(conversationId)
        _changes.tryEmit(conversationId)
        return true
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
        val lastUser = history.lastOrNull { it.isUser }
        val question = lastUser?.content.orEmpty()
        // "/name …": the user's own command adds its instruction; the model sees only the rest as the message
        val command = Commands.match(question, repo.commands())
        val asked = command?.second?.ifBlank { null } ?: question
        val recentText = history.dropLast(1).takeLast(4).joinToString(" ") { it.content.take(300) }
        val memory = if (prefs.useAboutMe) Memories.promptBlock(Memories.select(memories(), asked, recentText)) else null
        val toolsOn = toolsAvailable()
        // places, routes and weather come from the tools, not from a web search
        val web = if (toolsOn && !forceSearch && Tools.wantsTools(asked)) Research(null, emptyList(), false, true)
        else try { research(conversationId, history, memory, forceSearch) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
            Research(null, emptyList(), false, memory != null, note = e.message)
        }
        fun turns(research: Research) = buildList {
            prefs.systemPrompt.takeIf { it.isNotBlank() }?.let { add(Turn(Turn.Role.SYSTEM, it)) }
            add(Turn(Turn.Role.SYSTEM, nowLine()))
            if (memory != null && research.useAboutMe) add(Turn(Turn.Role.SYSTEM, memory))
            command?.let { (cmd, input) -> add(Turn(Turn.Role.SYSTEM, Commands.instruction(cmd, input))) }
            research.prompt?.let { add(Turn(Turn.Role.SYSTEM, it)) }
            research.note?.let { add(Turn(Turn.Role.SYSTEM, "Web search was attempted but failed ($it). Answer from general knowledge and say that live results were unavailable.")) }
            history.forEach {
                val content = if (it.id == lastUser?.id && command != null) asked else it.content
                add(Turn(if (it.isUser) Turn.Role.USER else Turn.Role.ASSISTANT, content))
            }
        }
        if (web.sources.isNotEmpty()) repo.setSources(msgId, web.sources)
        val pluginSources = ArrayList<Source>()
        setStatus(conversationId, null)
        val text = StringBuilder()
        var lastSave = 0L
        try {
            suspend fun answer(research: Research) {
                val base = turns(research)
                val extra = ArrayList<Turn>()
                var offer = if (toolsOn && !research.plugin) Tools.ALL else emptyList()
                var round = 0
                while (true) {
                    val roundStart = text.length
                    val calls = try {
                        withContext(io) {
                            ChatClient.stream(config(), base + extra, onConnection = { sockets[conversationId] = it }, webPlugin = research.plugin,
                                onCitations = { list -> list.forEach { (t, u) -> if (pluginSources.none { it.url == u }) pluginSources += Source(t.ifBlank { u }, u) } },
                                tools = offer,
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
                    } catch (e: ApiException) {
                        // a provider/model without tool calls: remember that and answer without them
                        if (offer.isEmpty() || round > 0 || text.length > roundStart || e.code !in 400..422) throw e
                        prefs.toolsUnsupported = toolsKey()
                        offer = emptyList()
                        continue
                    }
                    if (calls.isEmpty() || tools == null) break
                    round++
                    extra += Turn(Turn.Role.ASSISTANT, text.substring(roundStart), toolCalls = calls)
                    for (call in calls) {
                        setStatus(conversationId, Tools.status(call.name, runCatching { org.json.JSONObject(call.arguments) }.getOrDefault(org.json.JSONObject())))
                        _changes.tryEmit(conversationId)
                        extra += Turn(Turn.Role.TOOL, tools.run(call).take(6_000), toolCallId = call.id, toolName = call.name)
                    }
                    setStatus(conversationId, "Writing the answer…")
                    if (text.isNotEmpty() && !text.endsWith("\n")) text.append("\n\n")
                    // enough rounds: the last one must answer in text
                    if (round >= 4) offer = emptyList()
                }
            }
            try {
                answer(web)
            } catch (e: java.io.IOException) {
                // Some providers/firewalls drop large requests: if the web results were part of it, answer without them.
                if (text.isNotEmpty() || (web.prompt == null && !web.plugin) || jobs[conversationId] == null) throw e
                repo.setSources(msgId, emptyList())
                answer(Research(null, emptyList(), false, web.useAboutMe, note = "the provider dropped the request with the results"))
            }
            repo.update(msgId, text.toString().trim())
            if (pluginSources.isNotEmpty()) repo.setSources(msgId, pluginSources)
            if (prefs.useAboutMe && prefs.learnFromChats && lastUser != null && Memories.mentionsSelf(question)) learnLater(conversationId)
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

    // ---- memory, commands, tools

    private var migrated = false

    /** The memory, after moving an old single "About you" text into it once. */
    suspend fun memories(): List<MemoryItem> {
        if (!migrated) {
            migrated = true
            val old = prefs.aboutMe
            if (old.isNotBlank() && repo.memories().isEmpty()) {
                Memories.fallbackOrganize(old).forEach { repo.addMemory(it.tag, it.text, it.core) }
                prefs.aboutMe = ""
            }
        }
        return repo.memories()
    }

    private val _memoryChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    /** The memory changed (learned from a chat, or edited). */
    val memoryChanges: SharedFlow<Unit> = _memoryChanges
    fun memoryEdited() { _memoryChanges.tryEmit(Unit) }

    /**
     * Organises a long text about the user into tagged facts (with the chat model when one is set, else on
     * the phone) and adds them; [replace] clears the memory first. Returns how many facts were saved.
     */
    suspend fun importMemory(raw: String, replace: Boolean): Int {
        val drafts = if (isConfigured()) {
            // long profiles go in parts at blank lines so nothing is cut off
            val parts = ArrayList<String>()
            val cur = StringBuilder()
            for (para in raw.split(Regex("\n\\s*\n"))) {
                if (cur.length + para.length > 12_000 && cur.isNotEmpty()) { parts += cur.toString(); cur.clear() }
                cur.append(para).append("\n\n")
            }
            if (cur.isNotBlank()) parts += cur.toString()
            parts.flatMap { part ->
                val reply = StringBuilder()
                runCatching { withContext(io) { ChatClient.stream(config(), listOf(Turn(Turn.Role.USER, Memories.organizePrompt(part)))) { reply.append(it) } } }
                Memories.parseOrganized(reply.toString()).ifEmpty { Memories.fallbackOrganize(part) }
            }
        } else Memories.fallbackOrganize(raw)
        if (drafts.isEmpty()) return 0
        if (replace) repo.clearMemories()
        val known = repo.memories().map { it.text.lowercase() }.toSet()
        var n = 0
        for (d in drafts) if (d.text.lowercase() !in known) { repo.addMemory(d.tag, d.text, d.core); n++ }
        memoryEdited()
        return n
    }

    private val learnedUpTo = ConcurrentHashMap<Long, Long>()

    /** In the background: new facts from the user's recent messages in this conversation. */
    private fun learnLater(conversationId: Long) = scope.launch {
        runCatching {
            val since = learnedUpTo[conversationId] ?: 0L
            val users = repo.messages(conversationId).filter { it.isUser && it.id > since && ImageProtocol.command(it.content) == null }.takeLast(3)
            if (users.isEmpty()) return@launch
            learnedUpTo[conversationId] = users.last().id
            val existing = memories()
            val reply = StringBuilder()
            withContext(io) { ChatClient.stream(config(), listOf(Turn(Turn.Role.USER, Memories.learnPrompt(existing, users.map { it.content })))) { reply.append(it) } }
            val changes = Memories.parseLearn(reply.toString(), existing)
            if (changes.isEmpty) return@launch
            changes.add.forEach { repo.addMemory(it.tag, it.text, source = MemoryItem.SOURCE_CHAT) }
            changes.update.forEach { (id, text) -> repo.updateMemory(id, text = text) }
            changes.delete.forEach { repo.deleteMemory(it) }
            memoryEdited()
        }
    }

    private fun toolsKey() = prefs.apiBaseUrl.trim() + "|" + prefs.apiModel.trim()

    private fun toolsAvailable() = tools != null && prefs.toolsEnabled && prefs.toolsUnsupported != toolsKey()

    /** Lets a provider that once rejected tools try again (after the user changes model or turns tools on). */
    fun resetToolSupport() { prefs.toolsUnsupported = "" }

    /** Date, time and zone, so "tonight", "tomorrow at 5" and weekdays mean the right thing. */
    private fun nowLine(): String {
        val now = java.time.ZonedDateTime.now()
        return "Now: " + now.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", java.util.Locale.ENGLISH)) +
            " (${now.zone.id})."
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
}
