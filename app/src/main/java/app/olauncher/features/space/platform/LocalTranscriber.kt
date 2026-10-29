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
import app.olauncher.features.space.domain.Wav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Transcribes a saved WAV entirely on the phone with Android's on-device speech recognizer
 * (Android 13+: the recognizer reads our audio from a pipe; no network, nothing uploaded). Long notes
 * use a segmented session, so the text arrives in pieces and is joined.
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
        return withContext(Dispatchers.Main) { run(context, wav, language) }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun run(context: Context, wav: File, language: String): String = suspendCancellableCoroutine { cont ->
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val pipe = ParcelFileDescriptor.createPipe()
        val parts = ArrayList<String>()
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
            if (error != null) cont.resumeWithException(error) else cont.resume(parts.joinToString(" ").trim())
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onSegmentResults(segment: Bundle) {
                segment.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { parts += it }
            }
            override fun onEndOfSegmentedSession() = finish(null)
            override fun onResults(results: Bundle) {
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { parts += it }
                finish(null)
            }
            override fun onError(error: Int) {
                when (error) {
                    // no (more) speech: whatever we have is the transcript
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> finish(null)
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        finish(ApiException("The on-device model for $language isn't installed. Download it in Wayfinder Setup → Space."))
                    else -> finish(ApiException("On-device recognition failed (error $error)"))
                }
            }
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, Wav.CHANNELS)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, Wav.SAMPLE_RATE)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }
        recognizer.startListening(intent)

        // feed the PCM (after the WAV header), then close so the session ends
        writer = Thread({
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                    FileInputStream(wav).use { input ->
                        input.skip(Wav.HEADER.toLong())
                        val buf = ByteArray(8192)
                        while (!Thread.currentThread().isInterrupted) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                }
            }
        }, "space-local-stt").also { it.start() }

        cont.invokeOnCancellation { context.mainExecutor.execute { cleanup() } }
    }
}
