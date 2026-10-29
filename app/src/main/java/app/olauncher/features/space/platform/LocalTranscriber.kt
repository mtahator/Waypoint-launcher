package app.olauncher.features.space.platform

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import app.olauncher.features.assistant.platform.ApiException
import app.olauncher.features.space.domain.SpeechChunks
import app.olauncher.features.space.domain.Wav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Transcribes a saved WAV entirely on the phone with Android's on-device speech recognizer
 * (Android 13+: the recognizer reads our audio from a pipe; no network, nothing uploaded). Long notes
 * are cut at their pauses and recognised piece by piece, then joined.
 */
object LocalTranscriber {

    fun isSupported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    data class Languages(val installed: List<String>, val available: List<String>, val pending: List<String>)

    /** Which languages the on-device recognizer has (installed) or can download. */
    suspend fun languages(context: Context): Languages? {
        if (!isSupported(context)) return null
        return withContext(Dispatchers.Main) { support(context) }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun support(context: Context): Languages? = suspendCancellableCoroutine { cont ->
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        r.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(s: RecognitionSupport) {
                r.destroy()
                if (cont.isActive) cont.resume(Languages(s.installedOnDeviceLanguages, s.supportedOnDeviceLanguages, s.pendingOnDeviceLanguages))
            }
            override fun onError(error: Int) { r.destroy(); if (cont.isActive) cont.resume(null) }
        })
        cont.invokeOnCancellation { r.destroy() }
    }

    /** Asks the system to download the on-device model for [language] (shows its own progress). */
    fun download(context: Context, language: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        r.triggerModelDownload(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, language))
        r.destroy()
    }

    suspend fun transcribe(context: Context, wav: File, language: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) throw ApiException("On-device transcription needs Android 13 or newer")
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) throw ApiException("This phone has no on-device speech recognizer")
        // The recognizer ends its session at the first long pause, so feed the note one utterance at a time.
        val pcm = withContext(Dispatchers.IO) { wav.readBytes() }
        val bytes = (pcm.size - Wav.HEADER).coerceAtLeast(0)
        val frames = bytes / SpeechChunks.FRAME_BYTES
        val energies = FloatArray(frames) { SpeechChunks.energy(pcm, Wav.HEADER + it * SpeechChunks.FRAME_BYTES, SpeechChunks.FRAME_BYTES) }
        val pieces = SpeechChunks.split(energies)
        val parts = ArrayList<String>()
        for ((i, range) in pieces.withIndex()) {
            val from = Wav.HEADER + range.first * SpeechChunks.FRAME_BYTES
            val to = if (i == pieces.lastIndex && range.last == frames - 1) pcm.size else Wav.HEADER + (range.last + 1) * SpeechChunks.FRAME_BYTES
            val text = withContext(Dispatchers.Main) {
                // one retry with a fresh recognizer if a piece fails for a reason other than the language
                runCatching { run(context, pcm, from, to, language) }.getOrElse { e ->
                    if (e is LanguageException || e is kotlinx.coroutines.CancellationException) throw e
                    run(context, pcm, from, to, language)
                }
            }
            if (text.isNotBlank()) parts += text
        }
        return parts.joinToString(" ").trim()
    }

    private class LanguageException(message: String) : ApiException(message)

    /** One recognizer session over pcm[from, to), followed by a second of silence so it can finish. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun run(context: Context, pcm: ByteArray, from: Int, to: Int, language: String): String = suspendCancellableCoroutine { cont ->
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val pipe = ParcelFileDescriptor.createPipe()
        val parts = ArrayList<String>()
        var partial = ""
        var writer: Thread? = null

        var cleaned = false
        fun cleanup() {
            if (cleaned) return
            cleaned = true
            runCatching { recognizer.destroy() }
            runCatching { pipe[0].close() }
            writer?.interrupt()
        }

        fun finish(error: Throwable?) {
            if (!cont.isActive) return
            cleanup()
            // a session that ended without a final result still heard something: keep its last partial
            if (parts.isEmpty() && partial.isNotBlank()) parts += partial
            if (error != null) cont.resumeWithException(error) else cont.resume(parts.joinToString(" ").trim())
        }

        fun first(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onSegmentResults(segment: Bundle) {
                first(segment).takeIf { it.isNotBlank() }?.let { parts += it; partial = "" }
            }
            override fun onEndOfSegmentedSession() = finish(null)
            override fun onResults(results: Bundle) {
                first(results).takeIf { it.isNotBlank() }?.let { parts += it; partial = "" }
                finish(null)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                first(partialResults).takeIf { it.isNotBlank() }?.let { partial = it }
            }
            override fun onError(error: Int) {
                when (error) {
                    // no (more) speech in this piece: whatever we have is its transcript
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> finish(null)
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        finish(LanguageException("The on-device model for $language isn't installed. Download it in Wayfinder Setup → Space."))
                    else -> finish(ApiException("On-device recognition failed (error $error)"))
                }
            }
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, Wav.CHANNELS)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, Wav.SAMPLE_RATE)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }
        recognizer.startListening(intent)

        // feed the piece plus trailing silence, then close so the session ends
        writer = Thread({
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                    var off = from
                    while (off < to && !Thread.currentThread().isInterrupted) {
                        val n = minOf(8192, to - off)
                        out.write(pcm, off, n)
                        off += n
                    }
                    if (!Thread.currentThread().isInterrupted) out.write(ByteArray(Wav.BYTES_PER_SECOND))
                }
            }
        }, "space-local-stt").also { it.start() }

        cont.invokeOnCancellation { context.mainExecutor.execute { cleanup() } }
    }
}
