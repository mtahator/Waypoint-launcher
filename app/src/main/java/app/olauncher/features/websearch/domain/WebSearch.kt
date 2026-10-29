package app.olauncher.features.websearch.domain

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/** One search hit (and, after reading the page, its text). */
data class SearchResult(val title: String, val url: String, val snippet: String, val content: String = "")

/** A cited source shown under an answer. */
data class Source(val title: String, val url: String) {
    val host: String get() = runCatching { java.net.URI(url).host.removePrefix("www.") }.getOrNull() ?: url
}

enum class WebSearchMode { OFF, AUTO, ALWAYS }
enum class SearchEngine { DUCKDUCKGO, BRAVE, OPENROUTER }

/** What the planner decided for one question. */
data class SearchPlan(val search: Boolean, val queries: List<String>, val useAboutMe: Boolean)

/**
 * Perplexity-style answering, pure parts: parse search engines' results, turn pages into text, plan
 * searches with the model, build the cited-sources prompt, and find [n] citations in the answer.
 */
object WebSearch {

    // ---- DuckDuckGo HTML endpoint (no key): html.duckduckgo.com/html/?q=…

    private val DDG_LINK = Regex("""<a[^>]+class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    private val DDG_SNIPPET = Regex("""class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</(?:a|td|div)>""", RegexOption.DOT_MATCHES_ALL)

    fun parseDuckDuckGo(html: String, limit: Int = 8): List<SearchResult> {
        val links = DDG_LINK.findAll(html).toList()
        val snippets = DDG_SNIPPET.findAll(html).map { clean(it.groupValues[1]) }.toList()
        return links.mapIndexedNotNull { i, m ->
            val url = ddgTarget(decodeEntities(m.groupValues[1])) ?: return@mapIndexedNotNull null
            if (url.contains("duckduckgo.com/y.js") || url.contains("ad_provider")) return@mapIndexedNotNull null // ads
            SearchResult(clean(m.groupValues[2]), url, snippets.getOrElse(i) { "" })
        }.distinctBy { it.url }.take(limit)
    }

    /** DDG wraps links as //duckduckgo.com/l/?uddg=<encoded>&rut=… */
    fun ddgTarget(href: String): String? {
        val h = if (href.startsWith("//")) "https:$href" else href
        val uddg = Regex("""[?&]uddg=([^&]+)""").find(h)?.groupValues?.get(1)
        val url = if (uddg != null) URLDecoder.decode(uddg, "UTF-8") else h
        return url.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    // ---- Brave Search API (key): api.search.brave.com/res/v1/web/search

    fun parseBrave(json: String, limit: Int = 8): List<SearchResult> {
        val arr = JSONObject(json).optJSONObject("web")?.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            SearchResult(clean(o.optString("title")), url, clean(o.optString("description")))
        }.take(limit)
    }

    // ---- page text

    private val DROP = Regex("""<(script|style|noscript|svg|head|nav|footer|form|iframe|template)\b[^>]*>.*?</\1>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val MAIN = Regex("""<(article|main)\b[^>]*>(.*?)</\1>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val BLOCK = Regex("""</?(p|div|br|li|h[1-6]|tr|section|article|blockquote|pre)\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("""<[^>]+>""")
    private val COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)

    /** Readable text of a page: main/article content if present, scripts and chrome removed. */
    fun htmlToText(html: String, maxChars: Int = 4000): String {
        var h = COMMENT.replace(html, " ")
        h = DROP.replace(h, " ")
        MAIN.findAll(h).maxByOrNull { it.groupValues[2].length }?.let { if (it.groupValues[2].length > 400) h = it.groupValues[2] }
        h = BLOCK.replace(h, "\n")
        h = TAG.replace(h, " ")
        val text = decodeEntities(h).lines().map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
            .filter { it.length > 1 }.joinToString("\n")
        return if (text.length <= maxChars) text else text.take(maxChars).substringBeforeLast(' ') + "…"
    }

    fun clean(html: String): String = decodeEntities(TAG.replace(html, "")).replace(Regex("\\s+"), " ").trim()

    fun decodeEntities(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&#x27;", "'").replace("&apos;", "'").replace("&nbsp;", " ")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value }

    // ---- planning

    /**
     * Asks the model whether the latest question needs the web, which searches to run, and whether the
     * user's "About you" is relevant. Answer is JSON only.
     */
    fun plannerPrompt(question: String, recent: String, aboutMe: String?, today: String, forceSearch: Boolean): String = buildString {
        append("You decide how to answer the user's latest message. Today is $today.\n")
        append("Reply with JSON only: {\"search\": true|false, \"queries\": [\"…\"], \"use_about_me\": true|false}.\n")
        append("- search: true if the answer needs current, local, factual or niche information from the web ")
        append("(news, weather, prices, schedules, recent events, specific facts you might not know). False for chit-chat, ")
        append("writing, maths, code or general knowledge.\n")
        if (forceSearch) append("- The user asked to search the web: search must be true.\n")
        append("- queries: 1 to 3 short, specific web search queries (in the most useful language), resolving what the user ")
        append("refers to from the conversation (e.g. a city mentioned earlier). Empty if search is false.\n")
        if (aboutMe != null) {
            append("- use_about_me: true only if the user's background below matters for this answer ")
            append("(e.g. their location for \"weather here\", their preferences). Use it in queries when relevant.\n")
            append("User background:\n$aboutMe\n")
        } else append("- use_about_me: false.\n")
        if (recent.isNotBlank()) append("\nRecent conversation:\n$recent\n")
        append("\nLatest message:\n$question")
    }

    fun parsePlan(reply: String, forceSearch: Boolean): SearchPlan {
        val start = reply.indexOf('{'); val end = reply.lastIndexOf('}')
        val o = if (start in 0 until end) runCatching { JSONObject(reply.substring(start, end + 1)) }.getOrNull() else null
        val queries = o?.optJSONArray("queries")?.let { a -> (0 until a.length()).map { a.optString(it).trim() } }
            ?.filter { it.isNotEmpty() }?.distinct()?.take(3).orEmpty()
        val search = (o?.optBoolean("search") ?: false) || forceSearch
        return SearchPlan(search && (queries.isNotEmpty() || forceSearch), queries, o?.optBoolean("use_about_me") ?: false)
    }

    private val NEEDS_WEB = Regex(
        "\\b(weather|temperature|forecast|news|today|tonight|tomorrow|yesterday|now|current|currently|latest|recent|price|prices|" +
            "cost|stock|score|result|results|schedule|open|opening hours|near me|release|released|election|exchange rate|who won|" +
            "2025|2026|2027)\\b|هوا|دما|اخبار|خبر|امروز|الان|فردا|قیمت|نرخ|جدید|آخرین",
        RegexOption.IGNORE_CASE,
    )

    /**
     * On-phone fallback when the model's plan can't be read (some providers ignore the JSON format):
     * search for questions that look time-sensitive or factual-live, using the question itself.
     */
    fun heuristicPlan(question: String, forced: Boolean): SearchPlan {
        val q = question.trim().replace(Regex("\\s+"), " ")
        val search = forced || NEEDS_WEB.containsMatchIn(q)
        return SearchPlan(search, if (search) listOf(q.take(120)) else emptyList(), useAboutMe = true)
    }

    /** True when [reply] contains a readable plan object. */
    fun hasPlan(reply: String): Boolean {
        val start = reply.indexOf('{'); val end = reply.lastIndexOf('}')
        return start in 0 until end && runCatching { JSONObject(reply.substring(start, end + 1)).has("search") }.getOrDefault(false)
    }

    // ---- answering with sources

    fun sourcesPrompt(results: List<SearchResult>, today: String): String = buildString {
        append("Web results for the user's latest message (today is $today). Answer using them and cite every claim that ")
        append("comes from a source with its number in square brackets, like [1] or [2][3]. Prefer the most recent and ")
        append("authoritative sources. If they don't contain the answer, say so briefly and answer from general knowledge, ")
        append("clearly marked. Don't list the sources at the end; the app shows them.\n\n")
        results.forEachIndexed { i, r ->
            append("[${i + 1}] ${r.title}\nURL: ${r.url}\n")
            append((r.content.ifBlank { r.snippet }).trim()).append("\n\n")
        }
    }

    fun encodeSources(sources: List<Source>): String =
        JSONArray().apply { sources.forEach { put(JSONObject().put("title", it.title).put("url", it.url)) } }.toString()

    fun decodeSources(json: String): List<Source> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Source(it.optString("title"), it.optString("url")) } }
    }.getOrDefault(emptyList())

    /** Positions of [n] citations (1-based n) in an answer. */
    val CITATION = Regex("""\[(\d{1,2})]""")

    /** Results merged across queries: deduplicated by URL, interleaved so each query contributes. */
    fun merge(perQuery: List<List<SearchResult>>, limit: Int): List<SearchResult> {
        val out = LinkedHashMap<String, SearchResult>()
        var i = 0
        while (out.size < limit && perQuery.any { i < it.size }) {
            for (list in perQuery) list.getOrNull(i)?.let { r -> out.putIfAbsent(r.url.trimEnd('/'), r) }
            i++
        }
        return out.values.take(limit)
    }
}
