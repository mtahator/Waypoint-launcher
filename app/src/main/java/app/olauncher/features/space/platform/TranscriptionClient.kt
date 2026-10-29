package app.olauncher.features.space.platform

import android.util.Base64
import app.olauncher.features.assistant.domain.ChatProtocol
import app.olauncher.features.assistant.platform.ApiException
import app.olauncher.features.space.domain.Transcription
import app.olauncher.features.space.domain.TranscriptionApi
import app.olauncher.features.space.domain.Wav
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

data class TranscribeConfig(val api: TranscriptionApi, val baseUrl: String, val model: String, val apiKey: String?)

/** Sends a WAV to the configured speech-to-text, in ≤5-minute chunks, and joins the text. Blocking I/O. */
object TranscriptionClient {

    fun transcribe(config: TranscribeConfig, wav: File, onProgress: (Int, Int) -> Unit = { _, _ -> }): String {
        val pcm = (wav.length() - Wav.HEADER).coerceAtLeast(0)
        val parts = Wav.chunks(pcm)
        if (parts.isEmpty()) return ""
        val api = Transcription.resolve(config.api, config.baseUrl)
        return parts.mapIndexed { i, (off, len) ->
            onProgress(i + 1, parts.size)
            val bytes = Wav.header(len) + read(wav, Wav.HEADER + off, len.toInt())
            if (api == TranscriptionApi.CHAT_AUDIO) chat(config, bytes) else whisper(config, bytes)
        }.filter { it.isNotBlank() }.joinToString("\n\n")
    }

    private fun read(f: File, offset: Long, len: Int): ByteArray = RandomAccessFile(f, "r").use { r ->
        r.seek(offset); ByteArray(len).also { r.readFully(it) }
    }

    private fun chat(config: TranscribeConfig, wav: ByteArray): String {
        val req = Transcription.chatRequest(config.baseUrl, config.apiKey, config.model, Base64.encodeToString(wav, Base64.NO_WRAP))
        val body = post(req.url, req.headers) { it.write(req.body.toByteArray(Charsets.UTF_8)) }
        return Transcription.parseChat(body)
    }

    private fun whisper(config: TranscribeConfig, wav: ByteArray): String {
        val boundary = "waypoint${System.nanoTime()}"
        val (head, tail) = Transcription.multipart(boundary, config.model, "note.wav")
        val headers = Transcription.headers(config.apiKey, "multipart/form-data; boundary=$boundary")
        val body = post(Transcription.whisperUrl(config.baseUrl), headers) { out ->
            out.write(head.toByteArray(Charsets.UTF_8)); out.write(wav); out.write(tail.toByteArray(Charsets.UTF_8))
        }
        return Transcription.parseWhisper(body)
    }

    private fun post(url: String, headers: Map<String, String>, write: (java.io.OutputStream) -> Unit): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 20_000; readTimeout = 300_000; useCaches = false
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.use(write)
            val code = conn.responseCode
            if (code !in 200..299) throw ApiException(ChatProtocol.errorMessage(code, conn.errorStream?.bufferedReader()?.use { it.readText() }))
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
