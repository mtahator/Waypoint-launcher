package app.olauncher.features.assistant

import app.olauncher.features.assistant.domain.ImageApi
import app.olauncher.features.assistant.domain.ImageData
import app.olauncher.features.assistant.domain.ImageProtocol
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageProtocolTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    private val b64 = java.util.Base64.getEncoder().encodeToString(png)

    @Test fun autoResolves() {
        assertEquals(ImageApi.CHAT, ImageProtocol.resolve(ImageApi.AUTO, "https://openrouter.ai/api/v1"))
        assertEquals(ImageApi.OPENAI_IMAGES, ImageProtocol.resolve(ImageApi.AUTO, "https://api.openai.com/v1"))
    }

    @Test fun command() {
        assertEquals("a red fox", ImageProtocol.command("/image a red fox"))
        assertEquals("cat", ImageProtocol.command("  /IMG cat"))
        assertNull(ImageProtocol.command("/image"))
        assertNull(ImageProtocol.command("draw a cat"))
        assertNull(ImageProtocol.command("/imagine x"))
    }

    @Test fun chatRequest() {
        val r = ImageProtocol.request(ImageApi.AUTO, "https://openrouter.ai/api/v1/", "k", "google/gemini-2.5-flash-image-preview", "fox")
        assertEquals("https://openrouter.ai/api/v1/chat/completions", r.url)
        val b = JSONObject(r.body)
        assertEquals("image", b.getJSONArray("modalities").getString(0))
        assertEquals("Bearer k", r.headers["Authorization"])
    }

    @Test fun imagesRequest() {
        val r = ImageProtocol.request(ImageApi.AUTO, "https://api.openai.com/v1", "k", "dall-e-3", "fox")
        assertEquals("https://api.openai.com/v1/images/generations", r.url)
        assertEquals("b64_json", JSONObject(r.body).getString("response_format"))
    }

    @Test fun parseChat() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"Here you go","images":[{"type":"image_url","image_url":{"url":"data:image/png;base64,$b64"}}]}}]}"""
        val r = ImageProtocol.parse(ImageApi.CHAT, "", body)
        assertEquals("Here you go", r.text)
        assertEquals(1, r.images.size)
        val img = r.images[0] as ImageData.Bytes
        assertArrayEquals(png, img.bytes); assertEquals("image/png", img.mime)
    }

    @Test fun parseImages() {
        val r = ImageProtocol.parse(ImageApi.OPENAI_IMAGES, "", """{"data":[{"b64_json":"$b64","revised_prompt":"a fox"}]}""")
        assertArrayEquals(png, (r.images[0] as ImageData.Bytes).bytes)
        assertEquals("a fox", r.text)
        val u = ImageProtocol.parse(ImageApi.OPENAI_IMAGES, "", """{"data":[{"url":"https://x/y.png"}]}""")
        assertTrue(u.images[0] is ImageData.Remote)
    }
}
