package app.olauncher.features.space.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import app.olauncher.features.assistant.domain.ApiFormat
import app.olauncher.features.assistant.domain.ChatProtocol
import app.olauncher.features.assistant.platform.ApiConfig
import app.olauncher.features.assistant.platform.ApiException
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks the chat model about an image (used for screenshots when "AI title & summary" is on, e.g. for
 * scripts on-device OCR can't read). The image is downscaled to 1024 px JPEG first. Blocking I/O.
 */
object Vision {

    fun ask(config: ApiConfig, prompt: String, image: File): String {
        val b64 = Base64.encodeToString(jpeg(image), Base64.NO_WRAP)
        val base = config.baseUrl.trim().trimEnd('/')
        val (url, body, headers) = when (config.format) {
            ApiFormat.OPENAI -> Triple("$base/chat/completions", JSONObject().put("model", config.model).put("stream", false).put("messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray()
                    .put(JSONObject().put("type", "text").put("text", prompt))
                    .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))))))),
                mapOf("Authorization" to "Bearer ${config.apiKey.orEmpty().trim()}"))
            ApiFormat.ANTHROPIC -> Triple("$base/messages", JSONObject().put("model", config.model).put("max_tokens", 1024).put("messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray()
                    .put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", b64)))
                    .put(JSONObject().put("type", "text").put("text", prompt))))),
                mapOf("x-api-key" to config.apiKey.orEmpty().trim(), "anthropic-version" to "2023-06-01"))
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 120_000
            setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> if (v.isNotBlank()) setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) throw ApiException(ChatProtocol.errorMessage(conn.responseCode, conn.errorStream?.bufferedReader()?.use { it.readText() }))
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return ChatProtocol.fullText(config.format, text).orEmpty()
        } finally {
            conn.disconnect()
        }
    }

    private fun jpeg(file: File): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: throw ApiException("Can't read image")
        val scale = 1024f / maxOf(bmp.width, bmp.height)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
    }
}
