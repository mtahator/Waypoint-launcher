package app.olauncher.features.space.platform

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import app.olauncher.features.space.domain.Wav
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.RandomAccessFile

/**
 * Records 16 kHz mono PCM straight into a WAV file on a background thread (the header is patched at the
 * end). One recording at a time; [state] drives the UI meter and timer.
 */
object Recorder {

    data class State(val recording: Boolean = false, val startedAt: Long = 0, val level: Float = 0f, val file: File? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    @Volatile private var running = false
    private var thread: Thread? = null

    /** Requires RECORD_AUDIO (checked by the caller). */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(file: File): Boolean {
        if (running) return false
        val min = AudioRecord.getMinBufferSize(Wav.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Wav.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, min * 4)
        }.getOrNull() ?: return false
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        file.parentFile?.mkdirs()
        running = true
        _state.value = State(true, System.currentTimeMillis(), 0f, file)
        thread = Thread({
            val buf = ByteArray(min)
            var total = 0L
            RandomAccessFile(file, "rw").use { out ->
                out.setLength(0)
                out.write(Wav.header(0))
                rec.startRecording()
                var lastLevel = 0L
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    out.write(buf, 0, n)
                    total += n
                    val now = System.currentTimeMillis()
                    if (now - lastLevel > 80) {
                        lastLevel = now
                        _state.value = _state.value.copy(level = Wav.level(buf, n))
                    }
                }
                runCatching { rec.stop() }
                rec.release()
                out.seek(0)
                out.write(Wav.header(total))
            }
        }, "space-recorder").also { it.start() }
        return true
    }

    /** Stops and waits for the file to be finalised. Returns (file, durationMs) or null. */
    @Synchronized
    fun stop(): Pair<File, Long>? {
        if (!running) return null
        running = false
        thread?.join(3000)
        thread = null
        val file = _state.value.file
        _state.value = State()
        if (file == null || !file.exists()) return null
        val pcm = (file.length() - Wav.HEADER).coerceAtLeast(0)
        return file to Wav.durationMs(pcm)
    }
}
