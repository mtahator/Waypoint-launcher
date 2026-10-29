package app.olauncher.features.space.domain

/**
 * Cuts a recording into utterance-sized pieces at its pauses. On-device recognizers stop at the first
 * long pause (or ignore segmented sessions), so a 1-minute note fed in one go comes back as its first
 * sentence; fed piece by piece, every piece is transcribed. Pure.
 */
object SpeechChunks {
    const val FRAME_MS = 30
    const val FRAME_BYTES = Wav.BYTES_PER_SECOND * FRAME_MS / 1000

    /** Mean absolute amplitude (0..1) of a 16-bit little-endian frame. */
    fun energy(buf: ByteArray, from: Int, len: Int): Float {
        var sum = 0L
        var count = 0
        var i = from
        val end = from + len
        while (i + 1 < end) {
            val s = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
            sum += if (s < 0) -s else s
            count++
            i += 2
        }
        return if (count == 0) 0f else sum.toFloat() / count / 32768f
    }

    /**
     * The level below which a frame counts as a pause: a few times the noise floor, but never more than
     * a third of the way up to the speech level (so a note with few pauses isn't all "pause").
     */
    fun silenceThreshold(energies: FloatArray): Float {
        if (energies.isEmpty()) return 0f
        val sorted = energies.sortedArray()
        fun pct(p: Double) = sorted[(sorted.size * p).toInt().coerceAtMost(sorted.size - 1)]
        val floor = pct(0.05)
        val speech = pct(0.9)
        return maxOf(minOf(floor * 3f, floor + (speech - floor) / 3f), 0.002f)
    }

    /**
     * Frame ranges (start inclusive, end exclusive) covering all of [energies], each at most [maxMs]
     * long, cut in the middle of the longest pause found after [minMs]. Pieces with no speech are left out.
     */
    fun split(energies: FloatArray, minMs: Int = 3_000, maxMs: Int = 15_000): List<IntRange> {
        val n = energies.size
        if (n == 0) return emptyList()
        val minF = (minMs / FRAME_MS).coerceAtLeast(1)
        val maxF = (maxMs / FRAME_MS).coerceAtLeast(minF + 1)
        val quiet = silenceThreshold(energies)
        val out = ArrayList<IntRange>()
        var start = 0
        while (start < n) {
            var end = n
            if (n - start > maxF) {
                end = cut(energies, quiet, start + minF, start + maxF)
            }
            if ((start until end).any { energies[it] >= quiet }) out += start until end
            start = end
        }
        return out
    }

    /** Best cut in [from, to): the middle of the longest pause, else the quietest frame. */
    private fun cut(e: FloatArray, quiet: Float, from: Int, to: Int): Int {
        var bestLen = 0
        var bestMid = -1
        var runStart = -1
        for (i in from..to) {
            val silent = i < to && e[i] < quiet
            if (silent && runStart < 0) runStart = i
            if (!silent && runStart >= 0) {
                val len = i - runStart
                if (len > bestLen) { bestLen = len; bestMid = runStart + len / 2 }
                runStart = -1
            }
        }
        if (bestMid >= 0) return bestMid
        var q = from
        for (i in from until to) if (e[i] < e[q]) q = i
        return q.coerceAtLeast(from + 1)
    }

    /** Milliseconds of speech (frames above the pause level) in [range]. */
    fun speechMs(energies: FloatArray, range: IntRange, quiet: Float): Int =
        range.count { it in energies.indices && energies[it] >= quiet } * FRAME_MS

    fun words(text: String) = text.split(Regex("\\s+")).count { w -> w.any(Char::isLetterOrDigit) }

    /**
     * People say ~2–3 words a second; under one word a second of speech means the recognizer stopped
     * listening part-way (it does this after an internal pause), so the piece should be split and redone.
     */
    fun looksIncomplete(words: Int, speechMs: Int): Boolean = speechMs >= 2_500 && words < speechMs / 1000.0 * 1.0
}
