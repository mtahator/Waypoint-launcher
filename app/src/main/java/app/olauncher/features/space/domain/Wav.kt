package app.olauncher.features.space.domain

/** 16-bit PCM WAV helpers: the format every speech API accepts. Pure. */
object Wav {
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BYTES_PER_SAMPLE = 2
    const val BYTES_PER_SECOND = SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE
    const val HEADER = 44

    /** A 44-byte RIFF header for [pcmBytes] of audio. */
    fun header(pcmBytes: Long, sampleRate: Int = SAMPLE_RATE, channels: Int = CHANNELS): ByteArray {
        val b = ByteArray(HEADER)
        fun str(at: Int, s: String) = s.forEachIndexed { i, c -> b[at + i] = c.code.toByte() }
        fun int(at: Int, v: Long) { for (i in 0 until 4) b[at + i] = ((v shr (8 * i)) and 0xFF).toByte() }
        fun short(at: Int, v: Int) { b[at] = (v and 0xFF).toByte(); b[at + 1] = ((v shr 8) and 0xFF).toByte() }
        val byteRate = sampleRate * channels * BYTES_PER_SAMPLE
        str(0, "RIFF"); int(4, 36 + pcmBytes); str(8, "WAVE")
        str(12, "fmt "); int(16, 16); short(20, 1); short(22, channels)
        int(24, sampleRate.toLong()); int(28, byteRate.toLong()); short(32, channels * BYTES_PER_SAMPLE); short(34, 16)
        str(36, "data"); int(40, pcmBytes)
        return b
    }

    fun durationMs(pcmBytes: Long): Long = pcmBytes * 1000 / BYTES_PER_SECOND

    /**
     * Splits [totalPcm] bytes into chunks of at most [maxSeconds] (APIs cap uploads around 25 MB),
     * on sample boundaries. Returns (offset, length) pairs into the PCM data.
     */
    fun chunks(totalPcm: Long, maxSeconds: Int = 300): List<Pair<Long, Long>> {
        val size = maxSeconds.toLong() * BYTES_PER_SECOND
        val out = ArrayList<Pair<Long, Long>>()
        var off = 0L
        while (off < totalPcm) {
            out += off to minOf(size, totalPcm - off)
            off += size
        }
        return out
    }

    /** Peak level 0..1 of a 16-bit little-endian buffer (for the recording meter). */
    fun level(buf: ByteArray, len: Int): Float {
        var peak = 0
        var i = 0
        while (i + 1 < len) {
            val s = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
            val a = if (s < 0) -s else s
            if (a > peak) peak = a
            i += 2
        }
        return (peak / 32768f).coerceIn(0f, 1f)
    }
}
