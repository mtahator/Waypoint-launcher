package app.olauncher.features.space

import app.olauncher.features.space.domain.Transcription
import app.olauncher.features.space.domain.TranscriptionApi
import app.olauncher.features.space.domain.Wav
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceDomainTest {
    @Test fun wavHeader() {
        val h = Wav.header(32_000)
        assertEquals(44, h.size)
        assertEquals("RIFF", String(h, 0, 4)); assertEquals("WAVE", String(h, 8, 4)); assertEquals("data", String(h, 36, 4))
        val dataLen = (h[40].toInt() and 0xFF) or ((h[41].toInt() and 0xFF) shl 8) or ((h[42].toInt() and 0xFF) shl 16)
        assertEquals(32_000, dataLen)
        val rate = (h[24].toInt() and 0xFF) or ((h[25].toInt() and 0xFF) shl 8)
        assertEquals(16_000, rate)
        assertEquals(1000L, Wav.durationMs(32_000))
    }

    @Test fun chunks() {
        val total = Wav.BYTES_PER_SECOND * 700L
        val c = Wav.chunks(total, 300)
        assertEquals(3, c.size)
        assertEquals(0L, c[0].first); assertEquals(Wav.BYTES_PER_SECOND * 300L, c[0].second)
        assertEquals(Wav.BYTES_PER_SECOND * 100L, c[2].second)
        assertEquals(total, c.sumOf { it.second })
        assertTrue(Wav.chunks(0).isEmpty())
    }

    @Test fun level() {
        val buf = byteArrayOf(0, 0, 0xFF.toByte(), 0x7F) // 0, 32767
        assertEquals(1f, Wav.level(buf, 4), 0.001f)
    }

    @Test fun resolve() {
        assertEquals(TranscriptionApi.CHAT_AUDIO, Transcription.resolve(TranscriptionApi.AUTO, "https://openrouter.ai/api/v1"))
        assertEquals(TranscriptionApi.WHISPER, Transcription.resolve(TranscriptionApi.AUTO, "https://api.groq.com/openai/v1"))
    }

    @Test fun chatRequest() {
        val r = Transcription.chatRequest("https://openrouter.ai/api/v1", "k", "google/gemini-2.5-flash", "QUJD")
        val content = JSONObject(r.body).getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("input_audio", content.getJSONObject(1).getString("type"))
        assertEquals("wav", content.getJSONObject(1).getJSONObject("input_audio").getString("format"))
        assertEquals("https://openrouter.ai/api/v1/chat/completions", r.url)
    }

    @Test fun parse() {
        assertEquals("hello there", Transcription.parseWhisper("""{"text":" hello there "}"""))
        assertEquals("salam", Transcription.parseChat("""{"choices":[{"message":{"content":"salam"}}]}"""))
        val (t, s) = Transcription.parseSummary("**Groceries for Friday**\n\nBuy milk and bread.\n- milk")
        assertEquals("Groceries for Friday", t)
        assertTrue(s.startsWith("Buy milk"))
        assertEquals("one two three four five six…", Transcription.fallbackTitle("one two three four five six seven"))
    }

    @Test fun multipart() {
        val (head, tail) = Transcription.multipart("B", "whisper-1", "note.wav")
        assertTrue(head.contains("name=\"model\"\r\n\r\nwhisper-1"))
        assertTrue(head.endsWith("Content-Type: audio/wav\r\n\r\n"))
        assertEquals("\r\n--B--\r\n", tail)
    }
}
