package app.olauncher.features.assistant

import app.olauncher.features.assistant.domain.ApiFormat
import app.olauncher.features.assistant.domain.ChatProtocol
import app.olauncher.features.assistant.domain.SseReader
import app.olauncher.features.assistant.domain.Turn
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatProtocolTest {
    private val turns = listOf(
        Turn(Turn.Role.SYSTEM, "Be brief."),
        Turn(Turn.Role.USER, "Hi"),
        Turn(Turn.Role.ASSISTANT, "Hello!"),
        Turn(Turn.Role.USER, "2+2?"),
    )

    @Test fun openAiRequest() {
        val r = ChatProtocol.request(ApiFormat.OPENAI, "https://api.openai.com/v1/", "sk-1", "gpt-4o-mini", turns)
        assertEquals("https://api.openai.com/v1/chat/completions", r.url)
        assertEquals("Bearer sk-1", r.headers["Authorization"])
        val b = JSONObject(r.body)
        assertEquals("gpt-4o-mini", b.getString("model"))
        assertEquals(4, b.getJSONArray("messages").length())
        assertEquals("system", b.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertTrue(b.getBoolean("stream"))
    }

    @Test fun anthropicRequest() {
        val r = ChatProtocol.request(ApiFormat.ANTHROPIC, "https://api.anthropic.com/v1", "k", "claude-sonnet-5-5", turns)
        assertEquals("https://api.anthropic.com/v1/messages", r.url)
        assertEquals("k", r.headers["x-api-key"])
        assertEquals("2023-06-01", r.headers["anthropic-version"])
        val b = JSONObject(r.body)
        assertEquals("Be brief.", b.getString("system"))
        assertEquals(3, b.getJSONArray("messages").length())
    }

    @Test fun noKeyNoAuthHeader() {
        val r = ChatProtocol.request(ApiFormat.OPENAI, "http://127.0.0.1:11434/v1", "", "llama3.2", turns)
        assertNull(r.headers["Authorization"])
    }

    @Test fun deltas() {
        assertEquals("Hel", ChatProtocol.delta(ApiFormat.OPENAI, """{"choices":[{"delta":{"content":"Hel"}}]}"""))
        assertNull(ChatProtocol.delta(ApiFormat.OPENAI, """{"choices":[{"delta":{"role":"assistant"}}]}"""))
        assertNull(ChatProtocol.delta(ApiFormat.OPENAI, """{"choices":[{"delta":{"content":null}}]}"""))
        assertNull(ChatProtocol.delta(ApiFormat.OPENAI, "[DONE]"))
        assertEquals("lo", ChatProtocol.delta(ApiFormat.ANTHROPIC, """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lo"}}"""))
        assertNull(ChatProtocol.delta(ApiFormat.ANTHROPIC, """{"type":"message_start"}"""))
    }

    @Test fun sse() {
        val r = SseReader()
        assertNull(r.line("event: content_block_delta"))
        assertNull(r.line("data: {\"a\":1}"))
        assertEquals("content_block_delta" to "{\"a\":1}", r.line(""))
        assertNull(r.line(": keep-alive"))
        assertNull(r.line(""))
        r.line("data: [DONE]")
        assertEquals(null to "[DONE]", r.line(""))
    }

    @Test fun errors() {
        assertEquals("HTTP 401 · Invalid key · Check the API key.", ChatProtocol.errorMessage(401, """{"error":{"message":"Invalid key"}}"""))
        assertTrue(ChatProtocol.errorMessage(404, null).contains("model"))
    }

    @Test fun titles() {
        assertEquals("What is reeded glass?", ChatProtocol.title("  What is\nreeded glass? "))
        assertEquals(48, ChatProtocol.title("x".repeat(100)).length)
    }

    @Test fun fullText() {
        assertEquals("4", ChatProtocol.fullText(ApiFormat.OPENAI, """{"choices":[{"message":{"content":"4"}}]}"""))
        assertEquals("ab", ChatProtocol.fullText(ApiFormat.ANTHROPIC, """{"content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}"""))
    }
}

class ChatCitationsTest {
    @Test fun openRouterCitations() {
        val c = ChatProtocol.citations("""{"choices":[{"delta":{"annotations":[{"type":"url_citation","url_citation":{"url":"https://a.com","title":"A"}}]}}]}""")
        assertEquals(listOf("A" to "https://a.com"), c)
        val r = ChatProtocol.request(ApiFormat.OPENAI, "https://openrouter.ai/api/v1", "k", "m", listOf(Turn(Turn.Role.USER, "x")), webPlugin = true)
        assertEquals("web", JSONObject(r.body).getJSONArray("plugins").getJSONObject(0).getString("id"))
    }
}
