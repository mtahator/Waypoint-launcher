package app.olauncher.features.assistant.domain

import org.json.JSONArray
import org.json.JSONObject

/**
 * One fact about the user. [core] facts (name, where they live, how they like answers) go with every
 * message; the rest only when they're relevant to it. [source]: "you" (typed or imported) or "chat" (learned).
 */
data class MemoryItem(
    val id: Long, val tag: String, val text: String, val core: Boolean = false,
    val source: String = SOURCE_USER, val updated: Long = 0,
) {
    companion object {
        const val SOURCE_USER = "you"
        const val SOURCE_CHAT = "chat"
    }
}

/**
 * The user's memory: a long "about me" is organised once into short tagged facts; each message then gets
 * the core facts plus the ones that match it (keyword relevance on the phone, any language), never the
 * whole profile. After chats, new durable facts are learned. Pure: no I/O.
 */
object Memories {

    /** Suggested tags; the model may add its own (lower-case, one or two words). */
    val TAGS = listOf(
        "identity", "location", "education", "work", "skills", "research", "goals",
        "interests", "lifestyle", "health", "people", "preferences", "style", "other",
    )

    /** Characters of memory per message (≈ 400 tokens), core facts first. */
    const val BUDGET = 1_600

    // ---- organising a long text

    fun organizePrompt(raw: String): String =
        "Turn the user's notes about themselves into a memory for an assistant. Reply with JSON only:\n" +
            "{\"items\": [{\"tag\": \"…\", \"text\": \"…\", \"core\": true|false}]}\n" +
            "Rules:\n" +
            "- one fact per item, short and self-contained (one sentence, at most ~25 words); keep names, numbers, places\n" +
            "- tag: one of ${TAGS.joinToString(", ")} (or a short new tag if none fits)\n" +
            "- \"style\": how the user wants answers (tone, rigour, format); \"preferences\": likes, dislikes, habits\n" +
            "- core = true only for a few facts that matter in almost every chat (name, age, city, language, " +
            "how they want answers); everything else false\n" +
            "- merge repeats, drop filler, never invent; write in the language of the notes\n\n" +
            "Notes:\n" + raw.take(24_000)

    data class Draft(val tag: String, val text: String, val core: Boolean)

    fun parseOrganized(reply: String): List<Draft> {
        val o = jsonObject(reply) ?: return emptyList()
        val arr = o.optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val it = arr.optJSONObject(i) ?: return@mapNotNull null
            val text = it.optString("text").trim().takeIf { t -> t.length >= 3 } ?: return@mapNotNull null
            Draft(normalizeTag(it.optString("tag")), text, it.optBoolean("core", false))
        }.distinctBy { it.text.lowercase() }
    }

    /**
     * Without a model: headings become tags, bullets and sentences become facts. Rougher than the AI
     * version but keeps everything and works offline.
     */
    fun fallbackOrganize(raw: String): List<Draft> {
        val out = ArrayList<Draft>()
        var tag = "other"
        for (line0 in raw.lines()) {
            val line = line0.trim()
            if (line.isEmpty() || line.all { it == '-' || it == '*' || it == '_' }) continue
            val heading = Regex("""^#{1,6}\s*(.+)$""").find(line)?.groupValues?.get(1)
                ?: line.takeIf { it.startsWith("**") && it.endsWith("**") && it.length < 80 }?.trim('*')
            if (heading != null) { tag = guessTag(heading) ?: tag; continue }
            val body = line.removePrefix("-").removePrefix("*").removePrefix("•").trim()
                .replace(Regex("""^\d+[.)]\s*"""), "").replace("**", "").trim()
            if (body.length < 3) continue
            // one fact per sentence (but keep "Name: X." style labels with their value)
            val sentences = body.split(Regex("""(?<=[.!?؟])\s+(?=\p{Lu}|\p{IsArabic})"""))
            for (s in sentences) if (s.trim().length >= 3) out += Draft(guessTag(s) ?: tag, s.trim(), false)
        }
        return out.distinctBy { it.text.lowercase() }
    }

    private val TAG_WORDS = mapOf(
        "identity" to listOf("name", "age", "born", "height", "weight", "language", "نام", "سن"),
        "location" to listOf("live", "location", "city", "address", "accommodation", "house", "street", "زندگی", "شهر", "آدرس"),
        "education" to listOf("university", "degree", "msc", "bsc", "phd", "school", "gpa", "diploma", "دانشگاه", "مدرک"),
        "work" to listOf("work", "job", "intern", "company", "experience", "employ", "director", "کار", "شرکت"),
        "skills" to listOf("skill", "python", "c++", "matlab", "programming", "software", "tools", "مهارت"),
        "research" to listOf("research", "paper", "publication", "thesis", "pinn", "neural", "پژوهش", "مقاله"),
        "goals" to listOf("goal", "ambition", "plan", "vision", "career", "هدف"),
        "interests" to listOf("hobby", "hobbies", "interest", "music", "climb", "sport", "علاقه", "موسیقی"),
        "lifestyle" to listOf("lifestyle", "minimalist", "budget", "spending", "schedule", "سبک زندگی"),
        "style" to listOf("when helping", "answer", "explain", "respond", "rigour", "rigor", "prefer an", "پاسخ"),
    )

    fun guessTag(text: String): String? {
        val t = text.lowercase()
        return TAG_WORDS.entries.maxByOrNull { (_, words) -> words.count { t.contains(it) } }
            ?.takeIf { (_, words) -> words.any { t.contains(it) } }?.key
    }

    fun normalizeTag(tag: String): String =
        tag.trim().lowercase().replace(Regex("""[^\p{L}\p{N} _-]"""), "").replace(Regex("\\s+"), " ").take(24).ifBlank { "other" }

    // ---- choosing what goes with a message

    /**
     * Core facts, then the facts most related to [query] (and a little of the recent conversation), within
     * [budget] characters. A fact is related when it shares words with the message, its tag is named, or the
     * message is about the user ("me", "my", "من") and the fact's tag fits the topic.
     */
    fun select(items: List<MemoryItem>, query: String, recent: String = "", budget: Int = BUDGET): List<MemoryItem> {
        if (items.isEmpty()) return emptyList()
        val q = words(query)
        val r = words(recent)
        val docs = items.map { words(it.text + " " + it.tag) }
        // rarer words count more (idf)
        val df = HashMap<String, Int>()
        docs.forEach { d -> d.toSet().forEach { w -> df[w] = (df[w] ?: 0) + 1 } }
        val n = items.size.toDouble()
        fun idf(w: String) = kotlin.math.ln(1 + n / (df[w] ?: 1))
        val scored = items.mapIndexed { i, item ->
            val d = docs[i].toSet()
            var s = q.toSet().filter { it in d }.sumOf { idf(it) } + 0.3 * r.toSet().filter { it in d }.sumOf { idf(it) }
            if (q.any { it == item.tag || it.startsWith(item.tag) }) s += 2.0
            item to s
        }
        val out = ArrayList<MemoryItem>()
        var used = 0
        for (item in items.filter { it.core }) {
            if (used + item.text.length > budget * 2 / 5) break
            out += item; used += item.text.length + 4
        }
        for ((item, s) in scored.filter { !it.first.core && it.second > 0.0 }.sortedByDescending { it.second }) {
            if (used + item.text.length > budget) continue
            out += item; used += item.text.length + 4
        }
        return out
    }

    /** The system note sent with a message. */
    fun promptBlock(selected: List<MemoryItem>): String? {
        if (selected.isEmpty()) return null
        return "What you know about the user (from their memory; use it only when it helps with this message, " +
            "don't mention it otherwise, and never claim to know more than this):\n" +
            selected.groupBy { it.tag }.entries.joinToString("\n") { (tag, list) -> list.joinToString("\n") { "- [$tag] ${it.text}" } }
    }

    private val STOP = setOf(
        "the", "and", "for", "with", "that", "this", "are", "was", "you", "your", "have", "has", "from", "not", "but",
        "can", "what", "how", "why", "who", "when", "where", "which", "about", "into", "than", "then", "them", "they",
        "would", "could", "should", "will", "just", "also", "very", "more", "most", "some", "any", "all", "our", "out",
        "است", "این", "که", "را", "با", "در", "از", "به", "برای", "هم", "یک", "تا", "یا", "آن", "می", "های", "هست",
    )

    fun words(text: String): List<String> =
        text.lowercase().split(Regex("""[^\p{L}\p{N}+#]+""")).filter { it.length >= 3 && it !in STOP }.map { stem(it) }

    /** Light stemming so "climbing"/"climb", "papers"/"paper" meet. */
    private fun stem(w: String): String = when {
        w.length > 5 && w.endsWith("ing") -> w.dropLast(3)
        w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
        w.length > 4 && w.endsWith("es") -> w.dropLast(2)
        w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
        w.length > 4 && w.endsWith("ها") -> w.dropLast(2)
        else -> w
    }

    // ---- learning from chats

    /** Worth asking the model whether there's something new to remember: the user talks about themselves. */
    fun mentionsSelf(text: String): Boolean {
        val t = " ${text.lowercase()} "
        return Regex("""\b(i|i'm|im|i've|i'll|my|me|mine|myself)\b""").containsMatchIn(t) ||
            Regex("""(من |م |مرا|برام|خودم|هستم|دارم|میخوام|می‌خوام)""").containsMatchIn(t)
    }

    fun learnPrompt(existing: List<MemoryItem>, userMessages: List<String>): String =
        "You maintain an assistant's memory about the user. From the user's messages below, find durable facts " +
            "about them worth remembering for future chats (who they are, where they live, studies, work, projects, " +
            "people, preferences, routines, how they want answers). Ignore one-off requests, questions, and anything " +
            "about other topics. Never store secrets (passwords, keys, card numbers).\n" +
            "Reply with JSON only: {\"add\": [{\"tag\": \"…\", \"text\": \"…\"}], \"update\": [{\"id\": 0, \"text\": \"…\"}], " +
            "\"delete\": [0]}\n" +
            "update/delete only when a message clearly changes or contradicts a fact below (use its id). Usually " +
            "everything is empty: then reply {\"add\":[],\"update\":[],\"delete\":[]}.\n" +
            "Tags: ${TAGS.joinToString(", ")}. One short fact per item, in the user's language.\n\n" +
            "Current memory:\n" +
            existing.take(120).joinToString("\n") { "${it.id}. [${it.tag}] ${it.text}" }.take(6_000).ifBlank { "(empty)" } +
            "\n\nUser's messages:\n" + userMessages.joinToString("\n") { "- " + it.take(1_500) }

    data class Changes(val add: List<Draft>, val update: Map<Long, String>, val delete: Set<Long>) {
        val isEmpty get() = add.isEmpty() && update.isEmpty() && delete.isEmpty()
    }

    fun parseLearn(reply: String, existing: List<MemoryItem>): Changes {
        val o = jsonObject(reply) ?: return Changes(emptyList(), emptyMap(), emptySet())
        val ids = existing.map { it.id }.toSet()
        val known = existing.map { it.text.lowercase() }.toSet()
        val add = o.optJSONArray("add").objects().mapNotNull { a ->
            val text = a.optString("text").trim().takeIf { it.length >= 3 && it.lowercase() !in known } ?: return@mapNotNull null
            Draft(normalizeTag(a.optString("tag")), text, false)
        }
        val update = o.optJSONArray("update").objects().mapNotNull { u ->
            val id = u.optLong("id", -1); val text = u.optString("text").trim()
            if (id in ids && text.length >= 3) id to text else null
        }.toMap()
        val delete = o.optJSONArray("delete")?.let { a -> (0 until a.length()).map { a.optLong(it, -1) }.filter { it in ids }.toSet() }.orEmpty()
        return Changes(add, update, delete)
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun jsonObject(reply: String): JSONObject? {
        val s = reply.indexOf('{'); val e = reply.lastIndexOf('}')
        if (s !in 0 until e) return null
        return runCatching { JSONObject(reply.substring(s, e + 1)) }.getOrNull()
    }
}

/** A user-made command: typing `/name …` sends the rest with this instruction. */
data class ChatCommand(val id: Long, val name: String, val instruction: String)

object Commands {
    /** `/name rest` → (command, rest), when [text] starts with a known command name. */
    fun match(text: String, commands: List<ChatCommand>): Pair<ChatCommand, String>? {
        val t = text.trimStart()
        if (!t.startsWith("/")) return null
        val name = t.drop(1).takeWhile { !it.isWhitespace() }.lowercase()
        val cmd = commands.firstOrNull { it.name.equals(name, true) } ?: return null
        return cmd to t.drop(1 + name.length).trim()
    }

    /** The instruction for the model; `{input}` in it is replaced by what follows the command. */
    fun instruction(cmd: ChatCommand, input: String): String =
        if (cmd.instruction.contains("{input}")) cmd.instruction.replace("{input}", input)
        else "The user ran their command /${cmd.name}. Follow this instruction for their message:\n${cmd.instruction}"

    fun normalizeName(name: String) = name.trim().removePrefix("/").lowercase().replace(Regex("""[^\p{L}\p{N}_-]"""), "").take(24)
}
