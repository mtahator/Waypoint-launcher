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
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
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

    /**
     * [onProgress] gets (pieces done, pieces total, text so far) after each piece, so a long recording
     * (a meeting) shows its text as it comes and nothing is lost if it's interrupted. [log] collects one
     * line per piece (length, words, retries), shown under the note's "Transcription details".
     */
    suspend fun transcribe(
        context: Context, wav: File, language: String, log: StringBuilder? = null,
        onProgress: suspend (done: Int, total: Int, text: String) -> Unit = { _, _, _ -> },
    ): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) throw ApiException("On-device transcription needs Android 13 or newer")
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) throw ApiException("This phone has no on-device speech recognizer")
        // The recognizer stops listening after a pause (sometimes while still reading the audio), so the
        // note goes in short utterance-sized pieces with long silences shortened; a piece whose text is
        // too short for the speech in it is split again and redone.
        val energies = withContext(Dispatchers.IO) { energies(wav) }
        val quiet = SpeechChunks.silenceThreshold(energies)
        val pieces = SpeechChunks.split(energies, minMs = 1_500, maxMs = 8_000)
        val total = (wav.length() - Wav.HEADER).coerceAtLeast(0)
        val parts = ArrayList<String>()
        RandomAccessFile(wav, "r").use { file ->
            suspend fun pcm(range: IntRange): ByteArray {
                val start = range.first.toLong() * SpeechChunks.FRAME_BYTES
                val end = if (range.last == energies.size - 1) total else (range.last + 1).toLong() * SpeechChunks.FRAME_BYTES
                return withContext(Dispatchers.IO) { ByteArray((end - start).toInt()).also { file.seek(Wav.HEADER + start); file.readFully(it) } }
            }

            suspend fun recognize(range: IntRange, depth: Int): String {
                val speechMs = SpeechChunks.speechMs(energies, range, quiet)
                var text = session(context, pcm(range), quiet, language)
                var tries = 1
                // nothing back for a piece with clear speech: a fresh recognizer once more
                if (text.isBlank() && speechMs >= 800) { delay(250); text = session(context, pcm(range), quiet, language); tries++ }
                val words = SpeechChunks.words(text)
                var note = ""
                if (depth < 2 && SpeechChunks.looksIncomplete(words, speechMs)) {
                    val sub = SpeechChunks.split(energies.copyOfRange(range.first, range.last + 1), minMs = 700,
                        maxMs = (range.count() * SpeechChunks.FRAME_MS / 2).coerceAtLeast(1_400))
                        .map { (it.first + range.first)..(it.last + range.first) }
                    if (sub.size > 1) {
                        val again = sub.map { recognize(it, depth + 1) }.filter { it.isNotBlank() }.joinToString(" ")
                        if (SpeechChunks.words(again) > words) { note = " → split ${sub.size}: ${SpeechChunks.words(again)}w"; text = again }
                    }
                }
                if (depth == 0) log?.append("%5.1fs  speech %4.1fs  %3dw%s%s\n".format(
                    range.count() * SpeechChunks.FRAME_MS / 1000f, speechMs / 1000f, words, if (tries > 1) "  retried" else "", note))
                return text
            }

            for ((i, range) in pieces.withIndex()) {
                recognize(range, 0).takeIf { it.isNotBlank() }?.let { parts += it }
                onProgress(i + 1, pieces.size, parts.joinToString("\n"))
            }
        }
        log?.insert(0, "${pieces.size} pieces, ${energies.size * SpeechChunks.FRAME_MS / 1000}s audio\n")
        return parts.joinToString("\n").trim()
    }

    /** All of [pcm] through recognizer sessions, resuming wherever one stopped reading early. */
    private suspend fun session(context: Context, pcm: ByteArray, quiet: Float, language: String): String {
        val piece = ArrayList<String>()
        var from = 0
        var attempts = 0
        while (from < pcm.size - Wav.BYTES_PER_SECOND / 3 && attempts < 12) {
            attempts++
            val r = withContext(Dispatchers.Main) {
                // one retry with a fresh recognizer if a session fails for a reason other than the language
                runCatching { run(context, pcm, from, quiet, language) }.getOrElse { e ->
                    if (e is LanguageException || e is kotlinx.coroutines.CancellationException) throw e
                    delay(300)
                    run(context, pcm, from, quiet, language)
                }
            }
            if (r.text.isNotBlank()) piece += r.text
            // the recognizer service needs a moment between sessions
            delay(120)
            if (r.resumeAt <= from + Wav.BYTES_PER_SECOND / 3) break
            from = r.resumeAt
        }
        return piece.joinToString(" ").trim()
    }

    /** Per-frame energies of the WAV, read in a stream (an hour of audio is ~115 MB). */
    private fun energies(wav: File): FloatArray {
        val frames = ((wav.length() - Wav.HEADER).coerceAtLeast(0) / SpeechChunks.FRAME_BYTES).toInt()
        val out = FloatArray(frames)
        java.io.BufferedInputStream(java.io.FileInputStream(wav), 64 * 1024).use { input ->
            input.skip(Wav.HEADER.toLong())
            val buf = ByteArray(SpeechChunks.FRAME_BYTES)
            for (f in 0 until frames) {
                var n = 0
                while (n < buf.size) { val k = input.read(buf, n, buf.size - n); if (k < 0) break; n += k }
                out[f] = SpeechChunks.energy(buf, 0, n)
            }
        }
        return out
    }

    private class LanguageException(message: String) : ApiException(message)

    /** A session's text, and where in the piece to resume if it ended before hearing all of it (else the end). */
    private class Session(val text: String, val resumeAt: Int)

    /**
     * One recognizer session over pcm[from..], with pauses longer than [MAX_PAUSE_MS] shortened and a
     * second of silence at the end so it can finish.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun run(context: Context, pcm: ByteArray, from: Int, quiet: Float, language: String): Session = suspendCancellableCoroutine { cont ->
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val pipe = ParcelFileDescriptor.createPipe()
        val parts = ArrayList<String>()
        var partial = ""
        var writer: Thread? = null
        // (bytes written to the pipe, position in pcm) after each frame, to tell how far the recognizer got
        val marks = java.util.Collections.synchronizedList(ArrayList<LongArray>())
        val written = java.util.concurrent.atomic.AtomicLong()
        val fed = java.util.concurrent.atomic.AtomicBoolean()

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
            val resumeAt = if (fed.get()) pcm.size else {
                // the writer is still blocked on a full pipe: what's in the pipe's buffer wasn't heard yet
                val heard = written.get() - PIPE_BUFFER
                synchronized(marks) { marks.lastOrNull { it[0] <= heard }?.get(1)?.toInt() } ?: from
            }
            cleanup()
            // a session that ended without a final result still heard something: keep its last partial
            if (parts.isEmpty() && partial.isNotBlank()) parts += partial
            if (error != null) cont.resumeWithException(error) else cont.resume(Session(parts.joinToString(" ").trim(), resumeAt))
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
                    // no (more) speech: whatever we have is the transcript of what it heard
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
            // hints; recognizers that honour them wait longer before deciding the speaker has finished
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
        }
        recognizer.startListening(intent)

        writer = Thread({
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                    val frame = SpeechChunks.FRAME_BYTES
                    val maxPause = MAX_PAUSE_MS / SpeechChunks.FRAME_MS
                    var silentRun = 0
                    var off = from
                    while (off < pcm.size && !Thread.currentThread().isInterrupted) {
                        val n = minOf(frame, pcm.size - off)
                        silentRun = if (SpeechChunks.energy(pcm, off, n) < quiet) silentRun + 1 else 0
                        if (silentRun <= maxPause) {
                            out.write(pcm, off, n)
                            written.addAndGet(n.toLong())
                        }
                        off += n
                        marks += longArrayOf(written.get(), off.toLong())
                    }
                    if (!Thread.currentThread().isInterrupted) {
                        // all speech is in the pipe: treat the piece as heard, even if the session ends during the tail
                        fed.set(true)
                        out.write(ByteArray(Wav.BYTES_PER_SECOND))
                    }
                }
            }
        }, "space-local-stt").also { it.start() }

        cont.invokeOnCancellation { context.mainExecutor.execute { cleanup() } }
    }

    /** Pauses are cut to this, so the recognizer never hears a silence long enough to stop. */
    private const val MAX_PAUSE_MS = 450

    /** Roughly what a pipe holds before the writer blocks (data the recognizer hasn't read yet). */
    private const val PIPE_BUFFER = 64 * 1024L
}
