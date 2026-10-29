package app.olauncher.features.space.domain

import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId

/** What the AI (optional) returns for a voice note, written note or screenshot. */
data class Analysis(val title: String, val summary: String, val category: String?, val tasks: List<ParsedTask>)

object SpaceAi {

    /** Content beyond this is cut (start and end kept) so an hour-long meeting still fits a model's context. */
    const val MAX_CHARS = 40_000

    /** [long]: a meeting or lecture, which gets an overview plus key points, decisions and action items. */
    fun prompt(kind: String, text: String, now: LocalDateTime, long: Boolean = false): String =
        "Analyse this $kind. Reply with JSON only:\n" +
            "{\"title\": \"≤6 words\", \"summary\": ${if (long) "\"2-3 sentence overview, then lines starting with '- ' for the key points, decisions and action items\"" else "\"1-3 sentences\""}, \"category\": one of ${Categories.ALL.joinToString(", ", "[", "]") { "\"$it\"" }}, " +
            "\"tasks\": [{\"title\": \"…\", \"due\": \"YYYY-MM-DDTHH:MM\" or null}]}\n" +
            "tasks: things the user must do or asked to be reminded of (\"remind me…\", \"remember to…\", deadlines). " +
            "Resolve relative times against now = $now (local). Use the language of the content for title/summary/tasks.\n\n" +
            "Content:\n${clip(text)}"

    fun clip(text: String): String =
        if (text.length <= MAX_CHARS) text
        else text.take(MAX_CHARS * 3 / 4) + "\n[…]\n" + text.takeLast(MAX_CHARS / 4)

    fun parse(reply: String, zone: ZoneId = ZoneId.systemDefault()): Analysis? {
        val s = reply.indexOf('{'); val e = reply.lastIndexOf('}')
        if (s !in 0 until e) return null
        val o = runCatching { JSONObject(reply.substring(s, e + 1)) }.getOrNull() ?: return null
        val tasks = o.optJSONArray("tasks")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val t = a.optJSONObject(i) ?: return@mapNotNull null
                val title = t.optString("title").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val due = t.optString("due").takeIf { it.isNotBlank() && it != "null" }?.let {
                    runCatching { LocalDateTime.parse(it.take(16)).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
                }
                ParsedTask(title, due)
            }
        }.orEmpty()
        return Analysis(o.optString("title").trim(), o.optString("summary").trim(), Categories.normalize(o.optString("category")), tasks)
    }
}
