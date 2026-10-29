package app.olauncher.features.assistant.platform

import android.content.Context
import app.olauncher.features.assistant.domain.ChatProtocol
import app.olauncher.features.assistant.domain.ImageApi
import app.olauncher.features.assistant.domain.ImageData
import app.olauncher.features.assistant.domain.ImageProtocol
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class ImageConfig(val api: ImageApi, val baseUrl: String, val model: String, val apiKey: String?, val size: String)

/**
 * Generates images with the configured provider and saves them in app storage (files/chat_images).
 * Blocking I/O: call on an IO dispatcher. Returns the saved file names and any text the model added.
 */
class ImageClient(private val context: Context) {

    val dir: File get() = File(context.filesDir, "chat_images").apply { mkdirs() }

    fun generate(config: ImageConfig, prompt: String, namePrefix: String, onConnection: (HttpURLConnection) -> Unit = {}): Pair<List<String>, String?> {
        val req = ImageProtocol.request(config.api, config.baseUrl, config.apiKey, config.model, prompt, config.size)
        val conn = (URL(req.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 15_000; readTimeout = 180_000; useCaches = false
            req.headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        onConnection(conn)
        val body = try {
            conn.outputStream.use { it.write(req.body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) throw ApiException(ChatProtocol.errorMessage(code, conn.errorStream?.bufferedReader()?.use { it.readText() }))
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val result = ImageProtocol.parse(config.api, config.baseUrl, body)
        if (result.images.isEmpty()) throw ApiException(result.text ?: "The model returned no image. Is it an image model?")
        val names = result.images.mapIndexed { i, img ->
            val (bytes, mime) = when (img) {
                is ImageData.Bytes -> img.bytes to img.mime
                is ImageData.Remote -> download(img.url)
            }
            val ext = when { mime.contains("jpeg") || mime.contains("jpg") -> "jpg"; mime.contains("webp") -> "webp"; else -> "png" }
            val name = "$namePrefix-$i.$ext"
            File(dir, name).writeBytes(bytes)
            name
        }
        return names to result.text
    }

    private fun download(url: String): Pair<ByteArray, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 60_000 }
        try {
            if (conn.responseCode !in 200..299) throw ApiException("Couldn't download the image (HTTP ${conn.responseCode})")
            return conn.inputStream.use { it.readBytes() } to (conn.contentType ?: "image/png")
        } finally {
            conn.disconnect()
        }
    }

    fun file(name: String) = File(dir, name)

    /** Deletes image files no message refers to any more. */
    fun prune(keep: Set<String>) {
        dir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
    }
}
