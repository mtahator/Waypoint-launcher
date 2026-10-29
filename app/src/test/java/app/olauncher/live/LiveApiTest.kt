package app.olauncher.live

import app.olauncher.features.assistant.domain.ApiFormat
import app.olauncher.features.assistant.domain.Turn
import app.olauncher.features.assistant.platform.ApiConfig
import app.olauncher.features.assistant.platform.ChatClient
import app.olauncher.features.websearch.domain.SearchEngine
import app.olauncher.features.websearch.domain.WebSearch
import app.olauncher.features.websearch.platform.WebSearchClient
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Optional end-to-end check against a real provider, run in CI only when the WAYFINDER_LIVE_KEY secret is
 * set (skipped otherwise). Exercises the same code the phone runs: streaming chat, the search planner,
 * DuckDuckGo search, page reading and a cited answer. Writes app/build/live-report.md for the job summary.
 * The key comes from the environment and is never printed.
 */
class LiveApiTest {
    private val key = System.getenv("WAYFINDER_LIVE_KEY").orEmpty()
    private val url = System.getenv("WAYFINDER_LIVE_URL").takeUnless { it.isNullOrBlank() } ?: "https://api.gapgpt.app/v1"
    private val model = System.getenv("WAYFINDER_LIVE_MODEL").takeUnless { it.isNullOrBlank() } ?: "gpt-4o-mini"
    private val report = StringBuilder("## Wayfinder live check\n\nProvider `$url`, model `$model`\n\n")

    private fun log(line: String) { report.append(line).append('\n'); println(line) }

    private fun ask(prompt: String): String = runBlocking {
        val sb = StringBuilder()
        ChatClient.stream(ApiConfig(ApiFormat.OPENAI, url, model, key), listOf(Turn(Turn.Role.USER, prompt))) { sb.append(it) }
        sb.toString()
    }

    @Test fun endToEnd() {
        assumeTrue("WAYFINDER_LIVE_KEY not set", key.isNotBlank())
        var failures = 0
        fun step(name: String, block: () -> String) {
            runCatching(block).onSuccess { log("- ✅ **$name**: $it") }.onFailure { failures++; log("- ❌ **$name**: ${it.message?.replace(key, "***")}") }
        }

        step("Chat (streaming)") { ask("Reply with the single word: ready").trim().take(60).ifEmpty { error("empty reply") } }

        val question = "What is the weather in Nottingham right now?"
        var queries = listOf(question)
        step("Planner") {
            val reply = ask(WebSearch.plannerPrompt(question, "", null, java.time.LocalDate.now().toString(), false))
            val plan = if (WebSearch.hasPlan(reply)) WebSearch.parsePlan(reply, false) else WebSearch.heuristicPlan(question, false)
            if (plan.queries.isNotEmpty()) queries = plan.queries
            "search=${plan.search}, queries=${plan.queries}" + if (!WebSearch.hasPlan(reply)) " (model reply wasn't JSON: on-phone fallback used)" else ""
        }

        var results = emptyList<app.olauncher.features.websearch.domain.SearchResult>()
        step("DuckDuckGo search") {
            results = WebSearch.merge(queries.map { WebSearchClient.search(SearchEngine.DUCKDUCKGO, it, null) }, 6)
            if (results.isEmpty()) error("no results")
            "${results.size} results: " + results.take(3).joinToString { it.url }
        }

        step("Read pages") {
            results = WebSearchClient.read(results)
            "${results.count { it.content.isNotBlank() }} of ${minOf(4, results.size)} pages read"
        }

        step("Answer with citations") {
            val today = java.time.LocalDate.now().toString()
            val answer = runBlocking {
                val sb = StringBuilder()
                ChatClient.stream(ApiConfig(ApiFormat.OPENAI, url, model, key), listOf(
                    Turn(Turn.Role.SYSTEM, WebSearch.sourcesPrompt(results, today)),
                    Turn(Turn.Role.USER, question),
                )) { sb.append(it) }
                sb.toString()
            }
            val cites = WebSearch.CITATION.findAll(answer).count()
            "$cites citations. \n\n> " + answer.trim().replace("\n", "\n> ").take(900)
        }

        File("build").mkdirs()
        File("build/live-report.md").writeText(report.toString())
        if (failures > 0) throw AssertionError("$failures live step(s) failed (see report)")
    }
}
