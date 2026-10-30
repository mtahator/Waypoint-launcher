package app.olauncher.features.space.domain

import java.time.Instant
import java.time.ZoneId

/**
 * Recognises a chat message that asks Wayfinder to set a reminder or add a to-do ("set a reminder for
 * tonight that I must do the dishes", "remind me to call mom at 5", "add a to-do: buy milk", Persian
 * "یادم بنداز فردا…"), so it's done on the phone instead of being answered or searched. Pure.
 */
object ReminderRequest {

    data class Request(val title: String, val due: Long?, val remind: Boolean, val repeat: RepeatRule = RepeatRule.NONE)

    private val REMINDER = Regex(
        """^\s*(?:(?:can you|could you|please|hey|ok)\s+)*(?:set|create|add|make|schedule)\s+(?:me\s+)?(?:a\s+|an\s+)?(?:reminder|alarm|alert)s?\b""" +
            """|^\s*(?:(?:can you|could you|please)\s+)*remind me\b|^\s*reminder\s*:""",
        RegexOption.IGNORE_CASE,
    )
    private val TODO = Regex(
        """^\s*(?:(?:can you|could you|please)\s+)*(?:add|put|create|make)\s+(?:a\s+)?(?:new\s+)?(?:to-?do|todo|task)s?\b|^\s*(?:to-?do|todo|task)\s*:""",
        RegexOption.IGNORE_CASE,
    )
    /** "add buy milk to my to-do list": the item is in the middle. */
    private val TODO_LIST = Regex(
        """^\s*(?:(?:can you|could you|please)\s+)*(?:add|put)\s+(.+?)\s+(?:to|on|in)\s+(?:my\s+)?(?:to-?do|todo|task)s?(?:\s+list)?\s*[.!]?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val FA = Regex("""^\s*(?:لطفا\s+|لطفاً\s+)?(?:یادم بنداز|یادآوری کن|یادآور(?:ی)? (?:بذار|بگذار|تنظیم کن)|یه یادآوری|به من یادآوری کن)""")
    private val FA_TODO = Regex("""^\s*(?:لطفا\s+|لطفاً\s+)?(?:به (?:لیست )?کارهام اضافه کن|یه کار اضافه کن)""")

    /** Fillers between the command and what to do: "for", "that I must", "to", "me to", "about". */
    private val LEAD = Regex(
        """^(?:[\s:,\-–]+|(?:for|that|to|about|me|i'll|i will|i|we|you|must|need to|needs to|have to|has to|should|got to|gotta)\b)+""",
        RegexOption.IGNORE_CASE,
    )
    private val TAIL_LIST = Regex("""\s+to\s+(?:my\s+)?(?:to-?do|todo|task)s?(?:\s+list)?\s*$""", RegexOption.IGNORE_CASE)
    private val FA_LEAD = Regex("""^(?:[\s:،,]+|(?:که|برای|باید|برام|واسم)\s)+""")

    fun parse(text: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): Request? {
        val msg = ReminderParser.normalizeDigits(text.trim())
        val reminder = REMINDER.find(msg) ?: FA.find(msg)
        val list = if (reminder == null) TODO_LIST.find(msg) else null
        val todo = if (reminder == null && list == null) TODO.find(msg) ?: FA_TODO.find(msg) else null
        var rest = when {
            reminder != null -> msg.substring(reminder.range.last + 1)
            list != null -> list.groupValues[1]
            todo != null -> msg.substring(todo.range.last + 1)
            else -> return null
        }
        val isReminder = reminder != null
        val (rule, noRepeat) = RepeatParser.extract(rest)
        val (due, left) = ReminderParser.extractTime(noRepeat, now, zone)
        rest = left
        // strip leftovers on both sides: "for  that I must do the dishes!" → "do the dishes"
        repeat(4) { rest = LEAD.replace(rest, "").let { FA_LEAD.replace(it, "") } }
        rest = rest.replace(TAIL_LIST, "").trim().trimEnd('.', '!', '?', ',', '؟', '!').trim()
            .replace(Regex("""\s+(?:for|at|on|by|please)$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
        if (rest.length < 2) return null
        // a time that has already passed today ("tonight" at 23:00) means the next one
        val fixed = due?.let { if (it < now) Instant.ofEpochMilli(it).atZone(zone).plusDays(1).toInstant().toEpochMilli() else it }
        return Request(rest.replaceFirstChar { it.uppercase() }, fixed, isReminder && fixed != null, rule)
    }
}
