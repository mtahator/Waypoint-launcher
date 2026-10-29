package app.olauncher.features.assistant.platform

import app.olauncher.features.assistant.domain.ApiFormat
import app.olauncher.features.assistant.domain.ChatProtocol
import app.olauncher.features.assistant.domain.SseReader
import app.olauncher.features.assistant.domain.Turn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class ApiConfig(val format: ApiFormat, val baseUrl: String, val model: String, val apiKey: String?)

open class ApiException(message: String) : IOException(message)

/**
 * Streams a reply from the configured provider (server-sent events), calling [onDelta] with each piece
 * of text. Blocking I/O: call on an IO dispatcher. Stop = cancel the job and disconnect.
 */
object ChatClient {

    /** [onConnection] receives the socket so a Stop button can close it even while a read blocks. */
    suspend fun stream(
        config: ApiConfig,
        turns: List<Turn>,
        onConnection: (HttpURLConnection) -> Unit = {},
        webPlugin: Boolean = false,
        onCitations: (List<Pair<String, String>>) -> Unit = {},
        onDelta: (String) -> Unit,
    ) {
        val req = ChatProtocol.request(config.format, config.baseUrl, config.apiKey, config.model, turns, webPlugin = webPlugin)
        val conn = (URL(req.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 120_000
            useCaches = false
            req.headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        onConnection(conn)
        try {
            conn.outputStream.use { it.write(req.body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }
                throw ApiException(ChatProtocol.errorMessage(code, err))
            }
            val type = conn.contentType.orEmpty()
            if (!type.contains("event-stream")) {
                // Some servers ignore "stream": read the whole JSON answer instead
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                onDelta(ChatProtocol.fullText(config.format, body) ?: throw ApiException("Empty reply"))
                if (webPlugin) ChatProtocol.citations(body).takeIf { it.isNotEmpty() }?.let(onCitations)
                return
            }
            val sse = SseReader()
            var got = false
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = reader.readLine() ?: break
                    val event = sse.line(line) ?: continue
                    if (event.second == "[DONE]") break
                    if (event.first == "error" || event.second.contains("\"type\":\"error\"")) {
                        throw ApiException(ChatProtocol.errorMessage(code, event.second))
                    }
                    ChatProtocol.delta(config.format, event.second)?.let { got = true; onDelta(it) }
                    if (webPlugin) ChatProtocol.citations(event.second).takeIf { it.isNotEmpty() }?.let(onCitations)
                }
            }
            if (!got) throw ApiException("The model returned no text")
        } finally {
            conn.disconnect()
        }
    }
}
