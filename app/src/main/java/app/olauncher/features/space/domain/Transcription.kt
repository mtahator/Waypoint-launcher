package app.olauncher.features.space.domain

import app.olauncher.features.assistant.domain.ChatRequest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Speech-to-text requests, pure:
 * - [TranscriptionApi.WHISPER]: `POST {base}/audio/transcriptions` (multipart: model + file), like OpenAI
 *   (gpt-4o-mini-transcribe, whisper-1) and Groq (whisper-large-v3-turbo); reply `{"text": …}`.
 * - [TranscriptionApi.CHAT_AUDIO]: a chat model that accepts audio (OpenRouter / Gemini, e.g.
 *   google/gemini-2.5-flash): `input_audio` content part with base64 WAV; reply in choices[0].message.content.
 */
/** LOCAL = Android's on-device recognizer (nothing leaves the phone); the others use the user's API. */
enum class TranscriptionApi { LOCAL, AUTO, WHISPER, CHAT_AUDIO }

object Transcription {

    fun resolve(api: TranscriptionApi, baseUrl: String): TranscriptionApi = when (api) {
        TranscriptionApi.AUTO -> if (baseUrl.contains("openrouter", true) || baseUrl.contains("generativelanguage", true))
            TranscriptionApi.CHAT_AUDIO else TranscriptionApi.WHISPER
        else -> api
    }

    const val INSTRUCTION = "Transcribe this audio verbatim in its original language. Return only the transcript text, " +
        "no comments, no timestamps. If there is no speech, return an empty response."

    /** Chat-style request with the audio inline ([base64Wav] without a data: prefix). */
    fun chatRequest(baseUrl: String, apiKey: String?, model: String, base64Wav: String): ChatRequest {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", INSTRUCTION))
            .put(JSONObject().put("type", "input_audio").put("input_audio", JSONObject().put("data", base64Wav).put("format", "wav")))
        val body = JSONObject().put("model", model).put("stream", false)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        return ChatRequest("${baseUrl.trim().trimEnd('/')}/chat/completions", headers(apiKey, "application/json"), body.toString())
    }

    fun whisperUrl(baseUrl: String) = "${baseUrl.trim().trimEnd('/')}/audio/transcriptions"

    fun headers(apiKey: String?, contentType: String): Map<String, String> = buildMap {
        put("Content-Type", contentType)
        put("Accept", "application/json")
        if (!apiKey.isNullOrBlank()) put("Authorization", "Bearer ${apiKey.trim()}")
    }

    /** Multipart preamble/epilogue around the WAV bytes for the Whisper-style endpoint. */
    fun multipart(boundary: String, model: String, fileName: String): Pair<String, String> {
        val head = buildString {
            append("--$boundary\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n$model\r\n")
            append("--$boundary\r\nContent-Disposition: form-data; name=\"response_format\"\r\n\r\njson\r\n")
            append("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n")
            append("Content-Type: audio/wav\r\n\r\n")
        }
        return head to "\r\n--$boundary--\r\n"
    }

    fun parseWhisper(body: String): String = JSONObject(body).optString("text").trim()

    fun parseChat(body: String): String {
        val c = JSONObject(body).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.opt("content")
        return when (c) {
            is String -> c
            is JSONArray -> (0 until c.length()).joinToString("") { c.optJSONObject(it)?.optString("text").orEmpty() }
            else -> ""
        }.trim()
    }

    /** Prompt for a short title + summary of a transcript. */
    fun summaryPrompt(transcript: String): String =
        "Here is a voice note transcript. On the first line write a short title (at most 6 words). " +
            "Then, after a blank line, a summary of 1–3 sentences, then any action items as a short list " +
            "(omit the list if there are none). Use the same language as the transcript. No preamble.\n\n" +
            "Transcript:\n$transcript"

    /** Splits a model reply into (title, summary). */
    fun parseSummary(reply: String): Pair<String, String> {
        val lines = reply.trim().lines()
        val title = lines.firstOrNull().orEmpty().trim().trim('#', '*', ' ', '"').take(80)
        val summary = lines.drop(1).joinToString("\n").trim()
        return title to summary
    }

    /** Fallback title from the transcript itself. */
    fun fallbackTitle(transcript: String): String {
        val t = transcript.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return ""
        val words = t.split(' ').take(6).joinToString(" ")
        return if (words.length < t.length) "$words…" else words
    }
}
