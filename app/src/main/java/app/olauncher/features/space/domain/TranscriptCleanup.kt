package app.olauncher.features.space.domain

/**
 * Turns the recognizer's raw words into readable text with the chat model: fixes misheard words from
 * context, adds punctuation and paragraphs, drops fillers, and never summarises. Long recordings
 * (meetings) go in parts, each with the end of the previous part for continuity. Pure.
 */
object TranscriptCleanup {
    const val PART_CHARS = 5_000

    /** Splits [raw] into parts of about [max] chars, at line breaks (recognizer pieces) or else at words. */
    fun parts(raw: String, max: Int = PART_CHARS): List<String> {
        val units = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }.flatMap { line ->
            if (line.length <= max) listOf(line) else line.split(' ').chunkedByLength(max)
        }
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (u in units) {
            if (cur.isNotEmpty() && cur.length + 1 + u.length > max) { out += cur.toString(); cur.clear() }
            if (cur.isNotEmpty()) cur.append('\n')
            cur.append(u)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    private fun List<String>.chunkedByLength(max: Int): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (w in this) {
            if (cur.isNotEmpty() && cur.length + 1 + w.length > max) { out += cur.toString(); cur.clear() }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(w)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    fun prompt(part: String, previous: String, index: Int, total: Int): String = buildString {
        append("You are cleaning up an automatic speech-to-text transcript")
        if (total > 1) append(" (part ${index + 1} of $total of a long recording, e.g. a meeting or lecture)")
        append(". Rewrite it as clean, readable text:\n")
        append("- fix words the recognizer obviously misheard, using the context\n")
        append("- add punctuation, capitalisation and paragraphs (a new paragraph when the topic or speaker changes)\n")
        append("- remove fillers (um, uh, you know) and accidental repeats\n")
        append("- if it is clearly a conversation, start each change of speaker on a new line; don't invent names\n")
        append("- keep EVERYTHING that was said, in the same order and the same language. Don't summarise, don't add, don't answer questions in it\n")
        append("Reply with the cleaned text only, no preface or comments.\n")
        if (previous.isNotBlank()) append("\nThe previous part ended with (for context only, don't repeat it):\n\"…${previous.takeLast(300)}\"\n")
        append("\nRaw transcript:\n").append(part)
    }

    /**
     * The model's reply, or null if it isn't a faithful clean-up (much shorter = summarised, much longer =
     * made up, or a refusal), in which case the raw part is kept.
     */
    fun accept(raw: String, reply: String): String? {
        var t = reply.trim()
        t = t.removePrefix("```text").removePrefix("```").removeSuffix("```").trim()
        t = t.replace(Regex("^(here(’|')?s|here is)[^\n]*:\\s*\n", RegexOption.IGNORE_CASE), "").trim()
        if (t.isEmpty()) return null
        val rawWords = words(raw)
        val newWords = words(t)
        if (rawWords < 8) return t.takeIf { newWords <= rawWords * 2 + 6 }
        return t.takeIf { newWords >= rawWords * 0.6 && newWords <= rawWords * 1.4 + 10 }
    }

    private fun words(s: String) = s.split(Regex("\\s+")).count { it.any(Char::isLetterOrDigit) }
}
