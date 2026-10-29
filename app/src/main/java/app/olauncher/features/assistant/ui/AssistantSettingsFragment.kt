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
import app.olauncher.features.assistant.platform.ChatClient
import app.olauncher.helper.showToast
import kotlinx.coroutines.withContext

/** Where "?" questions go: the Gemini app, or any chat API (OpenAI-compatible or Anthropic). */
class AssistantSettingsFragment : WpPageFragment() {
    override val pageTitle = R.string.wp_assistant
    override val pageSubtitle = R.string.wp_assistant_subtitle

    private var testing = false

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
                header(getString(R.string.wp_api_behaviour))
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
            header(getString(R.string.wp_assistant_privacy))
            note(getString(R.string.wp_assistant_privacy_note))
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
