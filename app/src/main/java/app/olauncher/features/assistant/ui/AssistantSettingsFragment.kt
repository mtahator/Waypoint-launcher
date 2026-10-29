package app.olauncher.features.assistant.ui

import android.content.Intent
import android.text.InputType
import app.olauncher.R
import app.olauncher.core.ui.PageBuilder
import app.olauncher.core.ui.WpPageFragment
import app.olauncher.core.ui.chooseOne
import app.olauncher.core.ui.promptText
import app.olauncher.features.assistant.domain.ApiFormat
import app.olauncher.features.assistant.domain.AssistantMode
import app.olauncher.features.assistant.domain.ImageApi
import app.olauncher.features.assistant.domain.ImageProtocol
import app.olauncher.features.assistant.domain.Presets
import app.olauncher.features.assistant.domain.Turn
import app.olauncher.features.space.domain.TranscriptionApi
import app.olauncher.features.websearch.domain.SearchEngine
import app.olauncher.features.websearch.domain.WebSearchMode
import app.olauncher.features.space.platform.LocalTranscriber
import app.olauncher.features.space.platform.ReminderScheduler
import app.olauncher.features.space.platform.ScreenshotWatcher
import app.olauncher.features.assistant.platform.ChatClient
import app.olauncher.helper.showToast
import kotlinx.coroutines.withContext

/** Where "?" questions go: the Gemini app, or any chat API (OpenAI-compatible or Anthropic). */
class AssistantSettingsFragment : WpPageFragment() {
    override val pageTitle = R.string.wp_assistant
    override val pageSubtitle = R.string.wp_assistant_subtitle

    private var testing = false

    private val photoPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) container.prefs.autoScreenshots = false
        ScreenshotWatcher.update(requireContext())
        rebuild()
    }

    override fun build(page: PageBuilder) {
        val prefs = container.prefs
        val api = prefs.assistantMode == AssistantMode.API
        page.card {
            header(getString(R.string.wp_assistant_mode))
            row(getString(R.string.wp_assistant_gemini_app), if (!api) "●" else "", getString(R.string.wp_assistant_gemini_app_hint)) {
                prefs.assistantMode = AssistantMode.GEMINI_APP; rebuild()
            }
            row(getString(R.string.wp_assistant_api), if (api) "●" else "", getString(R.string.wp_assistant_api_hint)) {
                prefs.assistantMode = AssistantMode.API
                if (prefs.apiBaseUrl.isBlank()) applyPreset(0)
                rebuild()
            }
        }
        if (api) {
            page.card {
                header(getString(R.string.wp_api_connection))
                row(getString(R.string.wp_api_provider), prefs.apiPreset.ifBlank { getString(R.string.wp_api_custom) }) { choosePreset() }
                row(getString(R.string.wp_api_format), if (prefs.apiFormat == ApiFormat.ANTHROPIC) "Anthropic" else "OpenAI-compatible") {
                    prefs.apiFormat = if (prefs.apiFormat == ApiFormat.ANTHROPIC) ApiFormat.OPENAI else ApiFormat.ANTHROPIC
                    prefs.apiPreset = ""; rebuild()
                }
                row(getString(R.string.wp_api_url), prefs.apiBaseUrl.ifBlank { "—" }) {
                    requireContext().promptText(R.string.wp_api_url, prefs.apiBaseUrl,
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) {
                        prefs.apiBaseUrl = it; prefs.apiPreset = ""; rebuild()
                    }
                }
                row(getString(R.string.wp_api_model), prefs.apiModel.ifBlank { "—" }) {
                    requireContext().promptText(R.string.wp_api_model, prefs.apiModel,
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) {
                        prefs.apiModel = it; rebuild()
                    }
                }
                row(getString(R.string.wp_api_key), container.apiKeys.masked() ?: getString(R.string.wp_api_key_none)) {
                    requireContext().promptText(R.string.wp_api_key, "", hint = R.string.wp_api_key_paste,
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) {
                        runCatching { container.apiKeys.set(it) }
                            .onFailure { requireContext().showToast(getString(R.string.wp_api_key_failed)) }
                        rebuild()
                    }
                }
                Presets.ALL.firstOrNull { it.name == prefs.apiPreset }?.let { note(it.keyHint) }
                note(getString(R.string.wp_api_key_note))
            }
            page.card {
                header(getString(R.string.wp_image_generation))
                row(getString(R.string.wp_image_model), prefs.imageModel.ifBlank { getString(R.string.wp_image_off) }) {
                    requireContext().promptText(R.string.wp_image_model, prefs.imageModel, hint = R.string.wp_image_model_hint,
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) {
                        prefs.imageModel = it; rebuild()
                    }
                }
                row(getString(R.string.wp_image_api), when (prefs.imageApi) {
                    ImageApi.AUTO -> getString(R.string.wp_image_api_auto)
                    ImageApi.CHAT -> getString(R.string.wp_image_api_chat)
                    ImageApi.OPENAI_IMAGES -> getString(R.string.wp_image_api_images)
                }) {
                    val options = listOf(ImageApi.AUTO, ImageApi.CHAT, ImageApi.OPENAI_IMAGES)
                    requireContext().chooseOne(R.string.wp_image_api, listOf(
                        getString(R.string.wp_image_api_auto), getString(R.string.wp_image_api_chat), getString(R.string.wp_image_api_images),
                    )) { i -> prefs.imageApi = options[i]; rebuild() }
                }
                if (ImageProtocol.resolve(prefs.imageApi, prefs.apiBaseUrl) == ImageApi.OPENAI_IMAGES) {
                    row(getString(R.string.wp_image_size), prefs.imageSize) {
                        val sizes = listOf("1024x1024", "1024x1536", "1536x1024", "1792x1024", "1024x1792", "512x512")
                        requireContext().chooseOne(R.string.wp_image_size, sizes) { i -> prefs.imageSize = sizes[i]; rebuild() }
                    }
                }
                note(getString(R.string.wp_image_note))
            }
            page.card {
                header(getString(R.string.wp_web_search))
                val modes = listOf(WebSearchMode.AUTO to R.string.wp_web_auto, WebSearchMode.ALWAYS to R.string.wp_web_always, WebSearchMode.OFF to R.string.wp_web_off)
                row(getString(R.string.wp_web_mode_row), getString(modes.first { it.first == prefs.webSearchMode }.second)) {
                    requireContext().chooseOne(R.string.wp_web_mode_row, modes.map { getString(it.second) }) { i -> prefs.webSearchMode = modes[i].first; rebuild() }
                }
                val engines = listOf(SearchEngine.DUCKDUCKGO to R.string.wp_engine_ddg, SearchEngine.BRAVE to R.string.wp_engine_brave, SearchEngine.OPENROUTER to R.string.wp_engine_openrouter)
                row(getString(R.string.wp_search_engine), getString(engines.first { it.first == prefs.searchEngine }.second)) {
                    requireContext().chooseOne(R.string.wp_search_engine, engines.map { getString(it.second) }) { i -> prefs.searchEngine = engines[i].first; rebuild() }
                }
                if (prefs.searchEngine == SearchEngine.BRAVE) {
                    row(getString(R.string.wp_brave_key), container.braveKeys.masked() ?: getString(R.string.wp_api_key_none)) {
                        requireContext().promptText(R.string.wp_brave_key, "", hint = R.string.wp_api_key_paste,
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) {
                            runCatching { container.braveKeys.set(it) }; rebuild()
                        }
                    }
                }
                note(getString(R.string.wp_web_note))
            }
            page.card {
                header(getString(R.string.wp_api_behaviour))
                row(getString(R.string.wp_about_you), prefs.aboutMe.ifBlank { getString(R.string.wp_none) }.replace('\n', ' ').take(40),
                    getString(R.string.wp_about_you_hint)) {
                    requireContext().promptText(R.string.wp_about_you, prefs.aboutMe, hint = R.string.wp_about_you_example, multiLine = true) {
                        prefs.aboutMe = it; rebuild()
                    }
                }
                toggle(getString(R.string.wp_use_about_you), prefs.useAboutMe) { prefs.useAboutMe = it }
                row(getString(R.string.wp_system_prompt), prefs.systemPrompt.ifBlank { getString(R.string.wp_none) }.take(40)) {
                    requireContext().promptText(R.string.wp_system_prompt, prefs.systemPrompt, multiLine = true) {
                        prefs.systemPrompt = it; rebuild()
                    }
                }
                row(getString(R.string.wp_api_test), if (testing) "…" else null) { testConnection() }
                row(getString(R.string.wp_open_chats)) { startActivity(Intent(requireContext(), ChatActivity::class.java)) }
            }
        }
        page.card {
            header(getString(R.string.wp_space))
            val local = prefs.transcribeApi == TranscriptionApi.LOCAL
            row(getString(R.string.wp_transcribe_engine), getString(if (local) R.string.wp_transcribe_local else R.string.wp_transcribe_cloud)) {
                requireContext().chooseOne(R.string.wp_transcribe_engine, listOf(getString(R.string.wp_transcribe_local), getString(R.string.wp_transcribe_cloud))) { i ->
                    prefs.transcribeApi = if (i == 0) TranscriptionApi.LOCAL else TranscriptionApi.AUTO
                    rebuild()
                }
            }
            if (local) {
                val ctx = requireContext()
                if (!LocalTranscriber.isSupported(ctx)) {
                    note(getString(R.string.wp_transcribe_local_unsupported))
                } else {
                    val lang = prefs.transcribeLanguage
                    val state = when {
                        languages == null -> "…"
                        languages!!.installed.any { it.equals(lang, true) } -> getString(R.string.wp_transcribe_installed)
                        languages!!.pending.any { it.equals(lang, true) } -> getString(R.string.wp_transcribe_downloading)
                        else -> getString(R.string.wp_transcribe_not_installed)
                    }
                    row(getString(R.string.wp_transcribe_language), "${java.util.Locale.forLanguageTag(lang).displayName} · $state") { chooseLanguage() }
                    if (languages != null && languages!!.installed.none { it.equals(lang, true) }) {
                        row(getString(R.string.wp_transcribe_download)) {
                            LocalTranscriber.download(ctx, lang)
                            launchUi { kotlinx.coroutines.delay(1500); loadLanguages() }
                        }
                    }
                    note(getString(R.string.wp_transcribe_local_note))
                    if (languages == null) launchUi { loadLanguages() }
                }
            } else {
                row(getString(R.string.wp_transcribe_model), prefs.transcribeModel.ifBlank { getString(R.string.wp_image_off) }) {
                    requireContext().promptText(R.string.wp_transcribe_model, prefs.transcribeModel,
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) {
                        prefs.transcribeModel = it; rebuild()
                    }
                }
                row(getString(R.string.wp_transcribe_api), when (prefs.transcribeApi) {
                    TranscriptionApi.WHISPER -> getString(R.string.wp_transcribe_whisper)
                    TranscriptionApi.CHAT_AUDIO -> getString(R.string.wp_transcribe_chat)
                    else -> getString(R.string.wp_image_api_auto)
                }) {
                    val options = listOf(TranscriptionApi.AUTO, TranscriptionApi.WHISPER, TranscriptionApi.CHAT_AUDIO)
                    requireContext().chooseOne(R.string.wp_transcribe_api, listOf(
                        getString(R.string.wp_image_api_auto), getString(R.string.wp_transcribe_whisper), getString(R.string.wp_transcribe_chat),
                    )) { i -> prefs.transcribeApi = options[i]; rebuild() }
                }
                note(getString(R.string.wp_transcribe_note))
            }
            toggle(getString(R.string.wp_space_cleanup), prefs.spaceCleanup, getString(R.string.wp_space_cleanup_hint)) { prefs.spaceCleanup = it }
            toggle(getString(R.string.wp_space_summarize), prefs.spaceSummarize, getString(R.string.wp_space_summarize_hint)) { prefs.spaceSummarize = it }
            toggle(getString(R.string.wp_auto_screenshots), prefs.autoScreenshots, getString(R.string.wp_auto_screenshots_hint)) { on ->
                prefs.autoScreenshots = on
                if (on && !ScreenshotWatcher.hasPermission(requireContext())) photoPermission.launch(ScreenshotWatcher.permission())
                ScreenshotWatcher.update(requireContext())
            }
            if (!ReminderScheduler.canExact(requireContext())) {
                row(getString(R.string.wp_exact_reminders), getString(R.string.wp_exact_reminders_off), getString(R.string.wp_exact_reminders_hint)) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) runCatching {
                        startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            android.net.Uri.parse("package:" + requireContext().packageName)))
                    }
                }
            }
        }
        page.card {
            header(getString(R.string.wp_wayfinder_shortcut))
            val choices = listOf("left" to R.string.wp_shortcut_left, "right" to R.string.wp_shortcut_right, "off" to R.string.wp_shortcut_off)
            row(getString(R.string.wp_wayfinder_shortcut_row), getString(choices.firstOrNull { it.first == prefs.wayfinderShortcut }?.second ?: R.string.wp_shortcut_left),
                getString(R.string.wp_wayfinder_shortcut_hint)) {
                requireContext().chooseOne(R.string.wp_wayfinder_shortcut, choices.map { getString(it.second) }) { i ->
                    prefs.wayfinderShortcut = choices[i].first; rebuild()
                }
            }
        }
        page.card {
            header(getString(R.string.wp_assistant_privacy))
            note(getString(R.string.wp_assistant_privacy_note))
        }
    }

    private var languages: LocalTranscriber.Languages? = null

    private suspend fun loadLanguages() {
        languages = LocalTranscriber.languages(requireContext().applicationContext) ?: LocalTranscriber.Languages(emptyList(), emptyList(), emptyList())
        if (view != null) rebuild()
    }

    /** Installed languages first, then the ones the phone can download. */
    private fun chooseLanguage() {
        val l = languages ?: return
        val tags = (l.installed + l.available.filter { a -> l.installed.none { it.equals(a, true) } }).distinct()
        if (tags.isEmpty()) return
        val labels = tags.map { t ->
            val installed = l.installed.any { it.equals(t, true) }
            "${java.util.Locale.forLanguageTag(t).displayName} ($t)" + if (installed) " ✓" else ""
        }
        requireContext().chooseOne(R.string.wp_transcribe_language, labels) { i ->
            container.prefs.transcribeLanguage = tags[i]; rebuild()
        }
    }

    private fun choosePreset() {
        val names = Presets.ALL.map { it.name } + getString(R.string.wp_api_custom)
        requireContext().chooseOne(R.string.wp_api_provider, names) { i ->
            if (i < Presets.ALL.size) applyPreset(i) else container.prefs.apiPreset = ""
            rebuild()
        }
    }

    private fun applyPreset(i: Int) {
        val p = Presets.ALL[i]
        val prefs = container.prefs
        prefs.apiPreset = p.name
        prefs.apiFormat = p.format
        prefs.apiBaseUrl = p.baseUrl
        prefs.apiModel = p.model
        prefs.imageModel = p.imageModel
        prefs.transcribeModel = p.transcribeModel
        prefs.imageApi = ImageApi.AUTO
    }

    private fun testConnection() {
        if (testing) return
        testing = true; rebuild()
        launchUi {
            val engine = container.chatEngine
            val result = runCatching {
                withContext(container.dispatchers.io) {
                    val sb = StringBuilder()
                    ChatClient.stream(engine.config(), listOf(Turn(Turn.Role.USER, "Reply with the single word: ready"))) { sb.append(it) }
                    sb.toString().trim()
                }
            }
            testing = false
            if (!isAdded || view == null) return@launchUi
            requireContext().showToast(
                result.fold({ getString(R.string.wp_api_test_ok, it.take(40)) }, { it.message ?: getString(R.string.wp_api_test_failed) }),
            )
            rebuild()
        }
    }
}
