package app.olauncher.features.space

import app.olauncher.features.space.domain.SpeechChunks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechChunksTest {
    private fun frames(ms: Int) = ms / SpeechChunks.FRAME_MS

    /** Speech (0.2) and pauses (0.001), given as alternating millisecond lengths starting with speech. */
    private fun audio(vararg ms: Int): FloatArray {
        val out = ArrayList<Float>()
        ms.forEachIndexed { i, len -> repeat(frames(len)) { out += if (i % 2 == 0) 0.2f else 0.001f } }
        return out.toFloatArray()
    }

    @Test fun shortNoteIsOnePiece() {
        val e = audio(5_000, 500, 4_000)
        assertEquals(listOf(0 until e.size), SpeechChunks.split(e))
    }

    @Test fun oneMinuteNoteIsCutAtPausesAndCoversEverySpokenFrame() {
        // a minute of sentences with pauses of different lengths
        val e = audio(6_000, 400, 7_000, 1_200, 5_000, 300, 8_000, 900, 6_000, 600, 9_000, 1_000, 7_000, 500, 8_000)
        val pieces = SpeechChunks.split(e)
        assertTrue(pieces.size >= 4)
        pieces.forEach { assertTrue("piece too long: $it", it.count() <= frames(15_000)) }
        // contiguous, nothing dropped
        assertEquals(0, pieces.first().first)
        assertEquals(e.size - 1, pieces.last().last)
        pieces.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
        // every cut lands in a pause, not mid-word
        pieces.drop(1).forEach { assertTrue(e[it.first] < 0.01f) }
    }

    @Test fun continuousSpeechIsStillSplitToTheLimit() {
        val e = FloatArray(frames(40_000)) { 0.2f + (it % 7) * 0.01f }
        val pieces = SpeechChunks.split(e)
        assertTrue(pieces.size >= 3)
        assertEquals(e.size, pieces.sumOf { it.count() })
    }

    @Test fun silentPiecesAreSkipped() {
        val e = audio(4_000, 30_000, 4_000)
        val pieces = SpeechChunks.split(e)
        pieces.forEach { r -> assertTrue(r.any { e[it] > 0.1f }) }
        assertTrue(pieces.any { 0 in it } && pieces.any { e.size - 1 in it })
    }

    @Test fun energyOfPcm() {
        val buf = byteArrayOf(0x00, 0x40, 0x00, 0xC0.toByte()) // +16384, -16384
        assertEquals(0.5f, SpeechChunks.energy(buf, 0, 4), 0.001f)
    }
}
