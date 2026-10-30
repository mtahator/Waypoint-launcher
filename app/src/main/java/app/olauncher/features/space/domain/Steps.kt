package app.olauncher.features.space.domain

import org.json.JSONObject

/** Breaking a to-do into steps with the chat model. Pure. */
object Steps {
    fun prompt(title: String, context: String = ""): String =
        "Break this to-do into 3–7 short, concrete steps, in order, each starting with a verb (at most ~8 words). " +
            "Use the language of the to-do. Reply with JSON only: {\"steps\": [\"…\", \"…\"]}\n\nTo-do: $title" +
            (if (context.isNotBlank()) "\nNotes: ${context.take(1_000)}" else "")

    /** Steps from the model's reply: JSON, or a plain numbered/bulleted list as a fallback. */
    fun parse(reply: String): List<String> {
        val s = reply.indexOf('{'); val e = reply.lastIndexOf('}')
        val json = if (s in 0 until e) runCatching { JSONObject(reply.substring(s, e + 1)).optJSONArray("steps") }.getOrNull() else null
        val raw = if (json != null) (0 until json.length()).map { json.optString(it) }
        else reply.lines().filter { Regex("""^\s*(?:[-*•]|\d+[.)])\s+""").containsMatchIn(it) }
        return raw.map { it.replace(Regex("""^\s*(?:[-*•]|\d+[.)])\s+"""), "").trim().trimEnd('.') }
            .filter { it.length in 2..120 }.distinct().take(10)
    }
}
