package app.olauncher.features.assistant.platform

import app.olauncher.features.assistant.domain.ApiFormat
import app.olauncher.features.assistant.domain.ChatProtocol
import app.olauncher.features.assistant.domain.SseReader
import app.olauncher.features.assistant.domain.ToolCall
import app.olauncher.features.assistant.domain.ToolCallCollector
import app.olauncher.features.assistant.domain.ToolSpec
import app.olauncher.features.assistant.domain.Turn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class ApiConfig(val format: ApiFormat, val baseUrl: String, val model: String, val apiKey: String?)

/** [code]: the HTTP status when the server answered with an error (0 = not an HTTP error). */
open class ApiException(message: String, val code: Int = 0) : IOException(message)

/**
 * Streams a reply from the configured provider (server-sent events), calling [onDelta] with each piece
 * of text. Blocking I/O: call on an IO dispatcher. Stop = cancel the job and disconnect.
 */
object ChatClient {

    /**
     * [onConnection] receives the socket so a Stop button can close it even while a read blocks.
     * A connection dropped before any text arrived (reset, EOF, TLS hiccup) is retried once on a fresh socket.
     * With [tools] offered, returns the calls the model made (empty = it answered in text).
     */
    suspend fun stream(
        config: ApiConfig,
        turns: List<Turn>,
        onConnection: (HttpURLConnection) -> Unit = {},
        webPlugin: Boolean = false,
        onCitations: (List<Pair<String, String>>) -> Unit = {},
        tools: List<ToolSpec> = emptyList(),
        onDelta: (String) -> Unit,
    ): List<ToolCall> {
        var got = false
        val track: (String) -> Unit = { got = true; onDelta(it) }
        return try {
            once(config, turns, onConnection, webPlugin, onCitations, tools, track)
        } catch (e: IOException) {
            if (e is ApiException || got) throw friendly(e, config)
            currentCoroutineContext().ensureActive()
            kotlinx.coroutines.delay(400)
            try {
                once(config, turns, onConnection, webPlugin, onCitations, tools, track)
            } catch (e2: IOException) {
                throw friendly(e2, config)
            }
        }
    }

    /** Network errors in words a person can act on; API errors pass through. */
    fun friendly(e: IOException, config: ApiConfig): IOException {
        if (e is ApiException) return e
        val host = runCatching { URL(config.baseUrl.trim()).host }.getOrNull().orEmpty().ifBlank { config.baseUrl }
        val why = when (e) {
            is java.net.UnknownHostException -> "can't find $host. Check the API URL and that you're online"
            is java.net.SocketTimeoutException -> "$host didn't answer in time"
            is javax.net.ssl.SSLException -> "the secure connection to $host failed (${e.message ?: "TLS"})"
            else -> "the connection to $host was cut (${e.message ?: e.javaClass.simpleName}). A VPN, firewall or the provider may be blocking it"
        }
        return ApiException(why.replaceFirstChar { it.uppercase() })
    }

    private suspend fun once(
        config: ApiConfig,
        turns: List<Turn>,
        onConnection: (HttpURLConnection) -> Unit,
        webPlugin: Boolean,
        onCitations: (List<Pair<String, String>>) -> Unit,
        tools: List<ToolSpec>,
        onDelta: (String) -> Unit,
    ): List<ToolCall> {
        val req = ChatProtocol.request(config.format, config.baseUrl, config.apiKey, config.model, turns, webPlugin = webPlugin, tools = tools)
        val collector = ToolCallCollector(config.format)
        val conn = (URL(req.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 120_000
            useCaches = false
            // a pooled keep-alive socket the server already closed shows up as "Connection reset"
            setRequestProperty("Connection", "close")
            req.headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        onConnection(conn)
        try {
            conn.outputStream.use { it.write(req.body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }
                throw ApiException(ChatProtocol.errorMessage(code, err), code)
            }
            val type = conn.contentType.orEmpty()
            if (!type.contains("event-stream")) {
                // Some servers ignore "stream": read the whole JSON answer instead
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                collector.feed(body)
                val text = ChatProtocol.fullText(config.format, body)
                if (text == null && collector.calls().isEmpty()) throw ApiException("Empty reply")
                text?.let(onDelta)
                if (webPlugin) ChatProtocol.citations(body).takeIf { it.isNotEmpty() }?.let(onCitations)
                return collector.calls()
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
                    if (tools.isNotEmpty()) collector.feed(event.second)
                    if (webPlugin) ChatProtocol.citations(event.second).takeIf { it.isNotEmpty() }?.let(onCitations)
                }
            }
            val calls = collector.calls()
            if (!got && calls.isEmpty()) throw ApiException("The model returned no text")
            return calls
        } finally {
            conn.disconnect()
        }
    }
}
