package app.olauncher.features.assistant.ui

import app.olauncher.R
import app.olauncher.core.ui.PageBuilder
import app.olauncher.core.ui.WpPageFragment
import app.olauncher.core.ui.chooseOne
import app.olauncher.core.ui.promptText
import app.olauncher.features.assistant.domain.Memories
import app.olauncher.features.assistant.domain.MemoryItem
import app.olauncher.helper.createDialog
import app.olauncher.helper.showToast
import kotlinx.coroutines.launch

/**
 * The memory about you: tagged facts, imported from a long text (organised by the AI) or learned from chats.
 * Each message only gets the core facts and the ones relevant to it.
 */
class MemoryFragment : WpPageFragment() {
    override val pageTitle = R.string.wp_memory
    override val pageSubtitle = R.string.wp_memory_subtitle

    private var items: List<MemoryItem>? = null
    private var importing = false
    private val engine get() = container.chatEngine

    override fun onViewCreated(view: android.view.View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (activity is ChatActivity && !container.prefs.glassTheme) binding.pageRoot.setBackgroundColor(ChatStyle(requireContext()).scrim)
        reload()
        launchUi { engine.memoryChanges.collect { reload() } }
    }

    private fun reload() = launchUi { items = engine.memories(); rebuild() }

    override fun build(page: PageBuilder) {
        val prefs = container.prefs
        val list = items
        page.card {
            header(getString(R.string.wp_memory))
            toggle(getString(R.string.wp_use_about_you), prefs.useAboutMe, getString(R.string.wp_memory_use_hint)) { prefs.useAboutMe = it }
            toggle(getString(R.string.wp_memory_learn), prefs.learnFromChats, getString(R.string.wp_memory_learn_hint)) { prefs.learnFromChats = it }
            row(getString(R.string.wp_memory_import), if (importing) "…" else null, getString(R.string.wp_memory_import_hint)) { if (!importing) import() }
            row(getString(R.string.wp_memory_add)) { add() }
            note(getString(if (engine.isConfigured()) R.string.wp_memory_note else R.string.wp_memory_note_offline))
        }
        if (list == null) return
        if (list.isEmpty()) {
            page.card { note(getString(R.string.wp_memory_empty)) }
            return
        }
        // core facts first, then one card per tag
        list.groupBy { if (it.core) CORE else it.tag }.toSortedMap(compareBy<String>({ it != CORE }, { it })).forEach { (tag, facts) ->
            page.card {
                header((if (tag == CORE) getString(R.string.wp_memory_core) else tag.replaceFirstChar { it.uppercase() }) + "  ·  ${facts.size}")
                facts.forEach { m ->
                    row(m.text, if (m.source == MemoryItem.SOURCE_CHAT) getString(R.string.wp_memory_learned) else null,
                        if (tag == CORE) m.tag else null) { edit(m) }
                }
            }
        }
        page.card {
            row(getString(R.string.wp_memory_clear)) {
                requireContext().createDialog(R.string.wp_memory_clear, R.string.wp_delete, message = R.string.wp_memory_clear_message, onAction = {
                    launchUi { container.chats.clearMemories(); engine.memoryEdited() }
                }).showRespectingStatusBar()
            }
        }
    }

    private fun import() {
        requireContext().promptText(R.string.wp_memory_import, "", hint = R.string.wp_memory_import_paste, multiLine = true) { text ->
            if (text.isBlank()) return@promptText
            val hasAny = !items.isNullOrEmpty()
            val run = { replace: Boolean ->
                importing = true; rebuild()
                // in the app scope: organising a long profile can take a while and shouldn't stop if you leave
                val app = container
                app.scope.launch {
                    val n = runCatching { engine.importMemory(text, replace) }.getOrDefault(0)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        importing = false
                        if (isAdded) { requireContext().showToast(getString(R.string.wp_memory_imported, n)); reload() }
                    }
                }
            }
            if (!hasAny) run(false)
            else requireContext().chooseOne(R.string.wp_memory_import, listOf(getString(R.string.wp_memory_merge), getString(R.string.wp_memory_replace))) { i -> run(i == 1) }
        }
    }

    private fun add() {
        requireContext().promptText(R.string.wp_memory_add, "", hint = R.string.wp_memory_add_hint, multiLine = true) { text ->
            val t = text.trim()
            if (t.length < 3) return@promptText
            launchUi { container.chats.addMemory(Memories.guessTag(t) ?: "other", t); engine.memoryEdited() }
        }
    }

    private fun edit(m: MemoryItem) {
        val ctx = requireContext()
        ctx.chooseOne(R.string.wp_memory_fact, listOf(
            getString(R.string.wp_memory_edit), getString(R.string.wp_memory_tag),
            getString(if (m.core) R.string.wp_memory_uncore else R.string.wp_memory_make_core), getString(R.string.wp_delete),
        )) { i ->
            when (i) {
                0 -> ctx.promptText(R.string.wp_memory_edit, m.text, multiLine = true) { t ->
                    if (t.isNotBlank()) launchUi { container.chats.updateMemory(m.id, text = t.trim()); engine.memoryEdited() }
                }
                1 -> {
                    val tags = (Memories.TAGS + (items.orEmpty().map { it.tag })).distinct()
                    ctx.chooseOne(R.string.wp_memory_tag, tags + getString(R.string.wp_memory_new_tag)) { k ->
                        if (k < tags.size) launchUi { container.chats.updateMemory(m.id, tag = tags[k]); engine.memoryEdited() }
                        else ctx.promptText(R.string.wp_memory_new_tag, "") { t ->
                            if (t.isNotBlank()) launchUi { container.chats.updateMemory(m.id, tag = Memories.normalizeTag(t)); engine.memoryEdited() }
                        }
                    }
                }
                2 -> launchUi { container.chats.updateMemory(m.id, core = !m.core); engine.memoryEdited() }
                else -> launchUi { container.chats.deleteMemory(m.id); engine.memoryEdited() }
            }
        }
    }

    private companion object { const val CORE = "\u0000core" }
}

/** Your /commands: a name and an instruction, used by typing `/name …` in a chat. */
class CommandsFragment : WpPageFragment() {
    override val pageTitle = R.string.wp_commands
    override val pageSubtitle = R.string.wp_commands_subtitle

    private var commands: List<app.olauncher.features.assistant.domain.ChatCommand>? = null

    override fun onViewCreated(view: android.view.View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (activity is ChatActivity && !container.prefs.glassTheme) binding.pageRoot.setBackgroundColor(ChatStyle(requireContext()).scrim)
        reload()
    }

    private fun reload() = launchUi { commands = container.chats.commands(); rebuild() }

    override fun build(page: PageBuilder) {
        page.card {
            header(getString(R.string.wp_commands))
            row(getString(R.string.wp_command_new)) { edit(null) }
            note(getString(R.string.wp_commands_note))
        }
        val list = commands ?: return
        if (list.isEmpty()) {
            page.card {
                header(getString(R.string.wp_command_examples))
                EXAMPLES.forEach { (name, instruction) -> row("/$name", getString(R.string.wp_command_add_example), instruction) {
                    launchUi { container.chats.saveCommand(name, instruction); reload() }
                } }
            }
            return
        }
        page.card {
            list.forEach { c -> row("/${c.name}", null, c.instruction.take(140)) { options(c) } }
        }
    }

    private fun options(c: app.olauncher.features.assistant.domain.ChatCommand) {
        requireContext().chooseOne(R.string.wp_commands, listOf(getString(R.string.wp_memory_edit), getString(R.string.wp_delete))) { i ->
            if (i == 0) edit(c) else launchUi { container.chats.deleteCommand(c.id); reload() }
        }
    }

    private fun edit(c: app.olauncher.features.assistant.domain.ChatCommand?) {
        val ctx = requireContext()
        val askInstruction = { name: String ->
            ctx.promptText(R.string.wp_command_instruction, c?.instruction.orEmpty(), hint = R.string.wp_command_instruction_hint, multiLine = true) { ins ->
                if (ins.isNotBlank()) launchUi {
                    if (c != null && c.name != name) container.chats.deleteCommand(c.id)
                    container.chats.saveCommand(name, ins.trim()); reload()
                }
            }
        }
        ctx.promptText(R.string.wp_command_name, c?.name.orEmpty(), hint = R.string.wp_command_name_hint) { raw ->
            val name = app.olauncher.features.assistant.domain.Commands.normalizeName(raw)
            if (name.isNotEmpty() && name != "image") askInstruction(name)
        }
    }

    private companion object {
        val EXAMPLES = listOf(
            "review" to "Review this like a strict Q1-journal reviewer: novelty, research gap, validation, baselines, weaknesses, and what would make it publishable.\n\n{input}",
            "explain" to "Explain this for a smart beginner: the idea first, then a concrete example, then the maths, without oversimplifying.\n\n{input}",
            "fa" to "Translate into natural, fluent Persian (keep technical terms where Persian speakers use the English word):\n\n{input}",
            "email" to "Write a short, polite, professional email from these notes. Keep it under 150 words:\n\n{input}",
        )
    }
}
