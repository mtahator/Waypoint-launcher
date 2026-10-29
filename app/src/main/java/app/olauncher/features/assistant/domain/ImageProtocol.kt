package app.olauncher.features.assistant.domain

import org.json.JSONArray
import org.json.JSONObject

/**
 * Image generation requests/responses, pure (no I/O):
 * - [ImageApi.CHAT]: image-capable chat models (OpenRouter, e.g. google/gemini-2.5-flash-image-preview):
 *   `POST {base}/chat/completions` with `modalities: ["image","text"]`; images come back as data URLs in
 *   `choices[0].message.images[].image_url.url`.
 * - [ImageApi.OPENAI_IMAGES]: `POST {base}/images/generations` (gpt-image-1, dall-e-3, Imagen via Gemini's
 *   OpenAI-compatible endpoint); `data[].b64_json` or `data[].url`.
 */
enum class ImageApi { AUTO, CHAT, OPENAI_IMAGES }

/** A generated image: inline bytes, or a URL still to download. */
sealed class ImageData {
    class Bytes(val bytes: ByteArray, val mime: String) : ImageData()
    data class Remote(val url: String) : ImageData()
}

data class ImageResult(val images: List<ImageData>, val text: String?)

object ImageProtocol {

    /** AUTO picks the chat style for OpenRouter and the Images endpoint elsewhere. */
    fun resolve(api: ImageApi, baseUrl: String): ImageApi = when (api) {
        ImageApi.AUTO -> if (baseUrl.contains("openrouter", ignoreCase = true)) ImageApi.CHAT else ImageApi.OPENAI_IMAGES
        else -> api
    }

    /** The `/image` command (or `/img`, `/draw`) at the start of a message; returns the prompt. */
    fun command(text: String): String? {
        val t = text.trimStart()
        val m = Regex("""^/(image|img|draw|تصویر)\b\s*""", RegexOption.IGNORE_CASE).find(t) ?: return null
        return t.substring(m.range.last + 1).trim().takeIf { it.isNotEmpty() }
    }

    fun request(api: ImageApi, baseUrl: String, apiKey: String?, model: String, prompt: String, size: String = "1024x1024"): ChatRequest {
        val base = baseUrl.trim().trimEnd('/')
        val headers = buildMap {
            put("Content-Type", "application/json")
            put("Accept", "application/json")
            if (!apiKey.isNullOrBlank()) put("Authorization", "Bearer ${apiKey.trim()}")
        }
        return when (resolve(api, baseUrl)) {
            ImageApi.CHAT -> {
                val body = JSONObject()
                    .put("model", model)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                    .put("modalities", JSONArray().put("image").put("text"))
                    .put("stream", false)
                ChatRequest("$base/chat/completions", headers, body.toString())
            }
            else -> {
                val body = JSONObject().put("model", model).put("prompt", prompt).put("n", 1)
                if (size.isNotBlank()) body.put("size", size)
                // dall-e models default to URLs; ask for inline data (gpt-image-1 always returns b64)
                if (model.startsWith("dall-e")) body.put("response_format", "b64_json")
                ChatRequest("$base/images/generations", headers, body.toString())
            }
        }
    }

    fun parse(api: ImageApi, baseUrl: String, body: String): ImageResult {
        val o = JSONObject(body)
        return when (resolve(api, baseUrl)) {
            ImageApi.CHAT -> {
                val msg = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                val images = ArrayList<ImageData>()
                msg?.optJSONArray("images")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val url = arr.optJSONObject(i)?.optJSONObject("image_url")?.optString("url").orEmpty()
                        image(url)?.let { images += it }
                    }
                }
                // some models put image parts inside content
                msg?.optJSONArray("content")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val part = arr.optJSONObject(i) ?: continue
                        if (part.optString("type") == "image_url") image(part.optJSONObject("image_url")?.optString("url").orEmpty())?.let { images += it }
                    }
                }
                val text = msg?.opt("content")?.let { it as? String }?.takeIf { it.isNotBlank() }
                ImageResult(images, text)
            }
            else -> {
                val arr = o.optJSONArray("data") ?: JSONArray()
                val images = (0 until arr.length()).mapNotNull { i ->
                    val d = arr.optJSONObject(i) ?: return@mapNotNull null
                    val b64 = d.optString("b64_json")
                    when {
                        b64.isNotEmpty() -> ImageData.Bytes(Base64Lite.decode(b64), "image/png")
                        d.optString("url").isNotEmpty() -> image(d.optString("url"))
                        else -> null
                    }
                }
                val revised = arr.optJSONObject(0)?.optString("revised_prompt")?.takeIf { it.isNotEmpty() }
                ImageResult(images, revised)
            }
        }
    }

    /** data:image/png;base64,… → bytes; http(s) → remote. */
    fun image(url: String): ImageData? {
        if (url.startsWith("data:")) {
            val comma = url.indexOf(',')
            if (comma < 0) return null
            val meta = url.substring(5, comma)
            val mime = meta.substringBefore(';').ifEmpty { "image/png" }
            if (!meta.contains("base64")) return null
            return runCatching { ImageData.Bytes(Base64Lite.decode(url.substring(comma + 1)), mime) }.getOrNull()
        }
        if (url.startsWith("https://") || url.startsWith("http://")) return ImageData.Remote(url)
        return null
    }
}

/** Base64 decoding without java.util.Base64 (API 26+): ignores whitespace, accepts URL-safe alphabet. */
object Base64Lite {
    private val TABLE = IntArray(128) { -1 }.also { t ->
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".forEachIndexed { i, c -> t[c.code] = i }
        t['-'.code] = 62; t['_'.code] = 63
    }

    fun decode(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(s.length * 3 / 4)
        var buf = 0
        var bits = 0
        for (c in s) {
            if (c == '=') break
            val v = if (c.code < 128) TABLE[c.code] else -1
            if (v < 0) { if (c.isWhitespace()) continue else throw IllegalArgumentException("bad base64") }
            buf = (buf shl 6) or v
            bits += 6
            if (bits >= 8) { bits -= 8; out.write((buf shr bits) and 0xFF) }
        }
        return out.toByteArray()
    }
}
