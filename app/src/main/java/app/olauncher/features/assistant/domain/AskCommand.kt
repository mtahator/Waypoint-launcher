package app.olauncher.features.assistant.domain

/**
 * Drawer search command: a query that starts with "?" (or the Persian/Arabic "؟") is a question for
 * Gemini. "?what is reeded glass" → "what is reeded glass". Pure; no I/O.
 */
object AskCommand {

    private val PREFIXES = charArrayOf('?', '؟')

    /** True as soon as the query starts with the command prefix (even before the question is typed). */
    fun isCommand(query: CharSequence?): Boolean =
        query?.trimStart()?.firstOrNull()?.let { it in PREFIXES } == true

    /** The question to send, or null if [query] isn't a command or the question is blank. */
    fun question(query: CharSequence?): String? {
        if (!isCommand(query)) return null
        return query.toString().trimStart().drop(1).trim().takeIf { it.isNotEmpty() }
    }
}
