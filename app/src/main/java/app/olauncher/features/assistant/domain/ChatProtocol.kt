package app.olauncher.features.assistant.domain

import org.json.JSONArray
import org.json.JSONObject

/** One turn of a conversation, as sent to a model. */
data class Turn(val role: Role, val content: String) {
    enum class Role(val wire: String) { SYSTEM("system"), USER("user"), ASSISTANT("assistant") }
}

/** Where questions typed after "?" go. */
enum class AssistantMode { GEMINI_APP, API }

/** Wire formats. Most providers (OpenAI, Gemini, OpenRouter, Groq, Mistral, Ollama…) speak OpenAI's. */
enum class ApiFormat { OPENAI, ANTHROPIC }

data class ApiPreset(
    val name: String, val format: ApiFormat, val baseUrl: String, val model: String, val keyHint: String,
    /** Default image model ("" = this provider has none in Waypoint). */
    val imageModel: String = "",
    /** Default speech-to-text model for Space ("" = none). */
    val transcribeModel: String = "",
)

object Presets {
    val ALL = listOf(
        ApiPreset("OpenAI", ApiFormat.OPENAI, "https://api.openai.com/v1", "gpt-4o-mini", "platform.openai.com → API keys", imageModel = "gpt-image-1", transcribeModel = "gpt-4o-mini-transcribe"),
        ApiPreset("Gemini API", ApiFormat.OPENAI, "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash", "aistudio.google.com → Get API key", imageModel = "imagen-3.0-generate-002", transcribeModel = "gemini-2.5-flash"),
        ApiPreset("Anthropic (Claude)", ApiFormat.ANTHROPIC, "https://api.anthropic.com/v1", "claude-sonnet-5-5", "console.anthropic.com → API keys"),
        ApiPreset("OpenRouter", ApiFormat.OPENAI, "https://openrouter.ai/api/v1", "openai/gpt-4o-mini", "openrouter.ai → Keys", imageModel = "google/gemini-2.5-flash-image-preview", transcribeModel = "google/gemini-2.5-flash"),
        ApiPreset("Groq", ApiFormat.OPENAI, "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "console.groq.com → API keys", transcribeModel = "whisper-large-v3-turbo"),
        ApiPreset("GapGPT", ApiFormat.OPENAI, "https://api.gapgpt.app/v1", "gpt-4o-mini", "gapgpt.app → API keys"),
        ApiPreset("Ollama (local)", ApiFormat.OPENAI, "http://127.0.0.1:11434/v1", "llama3.2", "No key needed"),
    )
}

/** Request = URL + headers + JSON body. Built without any I/O so it can be tested. */
data class ChatRequest(val url: String, val headers: Map<String, String>, val body: String)

object ChatProtocol {

    const val MAX_TURNS = 40

    /** [webPlugin]: OpenRouter's server-side web search (`plugins: [{id: web}]`), OpenAI format only. */
    fun request(format: ApiFormat, baseUrl: String, apiKey: String?, model: String, turns: List<Turn>, stream: Boolean = true, webPlugin: Boolean = false): ChatRequest {
        val base = baseUrl.trim().trimEnd('/')
        val history = turns.filter { it.role != Turn.Role.SYSTEM && it.content.isNotBlank() }.takeLast(MAX_TURNS)
        val system = turns.filter { it.role == Turn.Role.SYSTEM }.joinToString("\n\n") { it.content }.trim()
        return when (format) {
            ApiFormat.OPENAI -> {
                val messages = JSONArray()
                if (system.isNotEmpty()) messages.put(JSONObject().put("role", "system").put("content", system))
                history.forEach { messages.put(JSONObject().put("role", it.role.wire).put("content", it.content)) }
                val body = JSONObject().put("model", model).put("messages", messages).put("stream", stream)
                if (webPlugin) body.put("plugins", JSONArray().put(JSONObject().put("id", "web").put("max_results", 5)))
                val headers = buildMap {
                    put("Content-Type", "application/json")
                    put("Accept", if (stream) "text/event-stream" else "application/json")
                    if (!apiKey.isNullOrBlank()) put("Authorization", "Bearer ${apiKey.trim()}")
                }
                ChatRequest("$base/chat/completions", headers, body.toString())
            }
            ApiFormat.ANTHROPIC -> {
                val messages = JSONArray()
                history.forEach { messages.put(JSONObject().put("role", it.role.wire).put("content", it.content)) }
                val body = JSONObject().put("model", model).put("max_tokens", 4096).put("messages", messages).put("stream", stream)
                if (system.isNotEmpty()) body.put("system", system)
                val headers = buildMap {
                    put("Content-Type", "application/json")
                    put("anthropic-version", "2023-06-01")
                    if (!apiKey.isNullOrBlank()) put("x-api-key", apiKey.trim())
                }
                ChatRequest("$base/messages", headers, body.toString())
            }
        }
    }

    /**
     * Text added by one server-sent event `data:` payload, or null if it carries none.
     * OpenAI: choices[0].delta.content. Anthropic: content_block_delta → delta.text.
     */
    fun delta(format: ApiFormat, data: String): String? {
        if (data == "[DONE]") return null
        val o = runCatching { JSONObject(data) }.getOrNull() ?: return null
        return when (format) {
            ApiFormat.OPENAI -> o.optJSONArray("choices")?.optJSONObject(0)?.let { c ->
                c.optJSONObject("delta")?.optString("content", "")?.takeIf { it.isNotEmpty() && c.optJSONObject("delta")?.isNull("content") == false }
                    ?: c.optJSONObject("message")?.optString("content")?.takeIf { it.isNotEmpty() }
            }
            ApiFormat.ANTHROPIC -> if (o.optString("type") == "content_block_delta")
                o.optJSONObject("delta")?.optString("text")?.takeIf { it.isNotEmpty() } else null
        }
    }

    /** URL citations a provider attached (OpenRouter web plugin): `annotations[].url_citation`. */
    fun citations(data: String): List<Pair<String, String>> {
        val o = runCatching { JSONObject(data) }.getOrNull() ?: return emptyList()
        val c = o.optJSONArray("choices")?.optJSONObject(0) ?: return emptyList()
        val arr = c.optJSONObject("delta")?.optJSONArray("annotations") ?: c.optJSONObject("message")?.optJSONArray("annotations")
            ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.optJSONObject("url_citation")?.let { u -> u.optString("title") to u.optString("url") }
        }.filter { it.second.startsWith("http") }
    }

    /** Full text of a non-streaming response (fallback when a server ignores `stream`). */
    fun fullText(format: ApiFormat, body: String): String? {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return when (format) {
            ApiFormat.OPENAI -> o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
            ApiFormat.ANTHROPIC -> o.optJSONArray("content")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("text") }.joinToString("")
            }
        }?.takeIf { it.isNotEmpty() }
    }

    /** A readable message from an error body: `{"error":{"message":…}}` in both formats. */
    fun errorMessage(code: Int, body: String?): String {
        val msg = body?.let { b ->
            runCatching { JSONObject(b) }.getOrNull()?.let { o ->
                o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() }
                    ?: o.optString("message").takeIf { it.isNotEmpty() }
            } ?: b.take(200).takeIf { it.isNotBlank() }
        }
        val hint = when (code) {
            401, 403 -> "Check the API key."
            404 -> "Check the base URL and model name."
            429 -> "Rate limited or out of credit."
            else -> null
        }
        return listOfNotNull("HTTP $code", msg, hint).joinToString(" · ")
    }

    /** A short title from the first question. */
    fun title(firstMessage: String): String {
        val oneLine = firstMessage.trim().replace(Regex("\\s+"), " ")
        return if (oneLine.length <= 48) oneLine else oneLine.take(47).trimEnd() + "…"
    }
}

/** Splits a server-sent-event stream into `data:` payloads (multi-line data joined with \n). */
class SseReader {
    private val data = StringBuilder()
    private var event: String? = null

    /** Feed one line (without the newline); returns a completed (event, data) pair at a blank line. */
    fun line(line: String): Pair<String?, String>? {
        if (line.isEmpty()) {
            if (data.isEmpty()) { event = null; return null }
            val out = event to data.toString()
            data.setLength(0); event = null
            return out
        }
        if (line.startsWith(":")) return null
        val field = line.substringBefore(':')
        var value = line.substringAfter(':', "")
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "data" -> { if (data.isNotEmpty()) data.append('\n'); data.append(value) }
            "event" -> event = value
        }
        return null
    }
}
