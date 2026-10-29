package app.olauncher.features.websearch.platform

import app.olauncher.features.assistant.platform.ApiException
import app.olauncher.features.websearch.domain.SearchEngine
import app.olauncher.features.websearch.domain.SearchResult
import app.olauncher.features.websearch.domain.WebSearch
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs web searches and reads result pages, on the phone. DuckDuckGo's HTML endpoint needs no key
 * (the approach of small open-source libraries such as duckduckgo-search); Brave Search uses the user's
 * key. Only the search query is sent to the engine; pages are fetched directly. Blocking I/O.
 */
object WebSearchClient {

    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    fun search(engine: SearchEngine, query: String, braveKey: String?, limit: Int = 6): List<SearchResult> = when (engine) {
        SearchEngine.BRAVE -> {
            if (braveKey.isNullOrBlank()) throw ApiException("Add a Brave Search key in Wayfinder Setup")
            val url = "https://api.search.brave.com/res/v1/web/search?count=$limit&q=" + URLEncoder.encode(query, "UTF-8")
            WebSearch.parseBrave(get(url, mapOf("Accept" to "application/json", "X-Subscription-Token" to braveKey.trim())), limit)
        }
        else -> {
            val html = post("https://html.duckduckgo.com/html/", "q=" + URLEncoder.encode(query, "UTF-8") + "&kl=wt-wt")
            WebSearch.parseDuckDuckGo(html, limit).ifEmpty {
                if (html.contains("anomaly", true) || html.contains("captcha", true)) throw ApiException("DuckDuckGo is rate-limiting searches; try again in a minute or use Brave")
                emptyList()
            }
        }
    }

    /** Reads up to [count] pages in parallel (6 s each) and returns the results with page text. */
    fun read(results: List<SearchResult>, count: Int = 4, maxChars: Int = 3500): List<SearchResult> {
        val pool = Executors.newFixedThreadPool(count.coerceAtLeast(1))
        try {
            val futures = results.mapIndexed { i, r ->
                if (i >= count) null else pool.submit<String> { runCatching { WebSearch.htmlToText(page(r.url), maxChars) }.getOrDefault("") }
            }
            return results.mapIndexed { i, r ->
                val text = futures[i]?.let { f -> runCatching { f.get(8, TimeUnit.SECONDS) }.getOrDefault("") }.orEmpty()
                r.copy(content = text)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun page(url: String): String {
        val conn = open(url).apply { setRequestProperty("Accept", "text/html,application/xhtml+xml") }
        try {
            if (conn.responseCode !in 200..299) return ""
            val type = conn.contentType.orEmpty()
            if (type.isNotEmpty() && !type.contains("html") && !type.contains("text")) return ""
            // cap the download: pages can be huge
            val bytes = conn.inputStream.use { input -> input.readNBytesCompat(1_500_000) }
            val charset = Regex("charset=([\\w-]+)").find(type)?.groupValues?.get(1) ?: "UTF-8"
            return runCatching { String(bytes, charset(charset)) }.getOrElse { String(bytes) }
        } finally {
            conn.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 6000; readTimeout = 6000; instanceFollowRedirects = true
        setRequestProperty("User-Agent", UA)
        setRequestProperty("Accept-Language", "en;q=0.9,*;q=0.5")
    }

    private fun get(url: String, headers: Map<String, String>): String {
        val conn = open(url).apply { headers.forEach { (k, v) -> setRequestProperty(k, v) } }
        try {
            if (conn.responseCode !in 200..299) throw ApiException("Search failed (HTTP ${conn.responseCode})")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }

    private fun post(url: String, form: String): String {
        val conn = open(url).apply {
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Referer", "https://html.duckduckgo.com/")
        }
        try {
            conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) throw ApiException("Search failed (HTTP ${conn.responseCode})")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }
}
