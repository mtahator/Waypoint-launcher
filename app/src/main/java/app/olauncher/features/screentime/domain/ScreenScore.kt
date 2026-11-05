package app.olauncher.features.screentime.domain

/**
 * Today's phone use as three rings to close and a score (like activity rings, but for a calmer day):
 *
 * - Budget: how much of the day's screen-time budget is still left (full = good, it drains as you use).
 * - Focus: the longest break from the phone since the day started (from [Goals.dayStartsHour]), against a goal.
 * - Mindful: of the time in apps that pull or that serve a purpose, the share that served a purpose.
 *
 * Score = 40 % budget + 30 % focus + 30 % mindful, 0..100. Pure.
 */
object ScreenScore {
    enum class Kind { PULL, PURPOSE, NEUTRAL }

    data class Use(val begin: Long, val end: Long, val pkg: String)
    data class Goals(val budgetMin: Int = 180, val breakMin: Int = 90, val purposePct: Int = 60, val dayStartsHour: Int = 7)
    data class AppUse(val pkg: String, val ms: Long, val kind: Kind)

    data class Summary(
        val totalMs: Long,
        val budget: Float,
        val focus: Float,
        val mindful: Float,
        val score: Int,
        val longestBreakMs: Long,
        val longestSessionMs: Long,
        val sessions: Int,
        val pickups: Int,
        val lateNightMs: Long,
        val purposeShare: Float,
        /** Minutes of use in each hour of the day, 0..60. */
        val hourly: List<Int>,
        val apps: List<AppUse>,
        val goals: Goals,
    ) {
        val overBudget get() = totalMs > goals.budgetMin * MIN
        val leftMs get() = (goals.budgetMin * MIN - totalMs).coerceAtLeast(0)
    }

    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    /** Uses closer than this are one session (switching apps, a glance at Home). */
    private const val JOIN_MS = 60_000L

    /** Overlapping or touching intervals merged, in order. */
    fun union(uses: List<Use>, dayStart: Long, now: Long): List<LongArray> {
        val spans = uses.mapNotNull { u ->
            val b = maxOf(u.begin, dayStart); val e = minOf(u.end, now)
            if (e > b) longArrayOf(b, e) else null
        }.sortedBy { it[0] }
        val out = ArrayList<LongArray>()
        for (s in spans) {
            val last = out.lastOrNull()
            if (last != null && s[0] <= last[1]) last[1] = maxOf(last[1], s[1]) else out += s.copyOf()
        }
        return out
    }

    fun summarize(uses: List<Use>, kindOf: (String) -> Kind, screenOns: Int, dayStart: Long, now: Long, goals: Goals = Goals()): Summary {
        val spans = union(uses, dayStart, now)
        val total = spans.sumOf { it[1] - it[0] }
        // sessions: spans joined across short gaps
        val sessions = ArrayList<LongArray>()
        for (s in spans) {
            val last = sessions.lastOrNull()
            if (last != null && s[0] - last[1] < JOIN_MS) last[1] = s[1] else sessions += s.copyOf()
        }
        val longestSession = sessions.maxOfOrNull { it[1] - it[0] } ?: 0L
        // breaks: from the start of the waking day to the first session, between sessions, and until now
        val wake = dayStart + goals.dayStartsHour * HOUR
        var longestBreak = 0L
        if (now > wake) {
            var cursor = wake
            for (s in sessions) {
                if (s[1] <= wake) continue
                longestBreak = maxOf(longestBreak, s[0] - cursor)
                cursor = maxOf(cursor, s[1])
            }
            longestBreak = maxOf(longestBreak, now - cursor)
        }
        // late night: before 6:00 and after 23:00
        val late = spans.sumOf { s -> overlap(s, dayStart, dayStart + 6 * HOUR) + overlap(s, dayStart + 23 * HOUR, dayStart + 24 * HOUR) }
        val hourly = (0 until 24).map { h -> (spans.sumOf { overlap(it, dayStart + h * HOUR, dayStart + (h + 1) * HOUR) } / MIN).toInt().coerceIn(0, 60) }
        // time per app (each app's own intervals, merged so a double-counted activity doesn't inflate it)
        val apps = uses.groupBy { it.pkg }.map { (pkg, list) ->
            AppUse(pkg, union(list, dayStart, now).sumOf { it[1] - it[0] }, kindOf(pkg))
        }.filter { it.ms >= MIN }.sortedByDescending { it.ms }
        val pull = apps.filter { it.kind == Kind.PULL }.sumOf { it.ms }
        val purpose = apps.filter { it.kind == Kind.PURPOSE }.sumOf { it.ms }
        val share = if (pull + purpose == 0L) 1f else purpose.toFloat() / (pull + purpose)

        val budget = (1f - total.toFloat() / (goals.budgetMin * MIN)).coerceIn(0f, 1f)
        val focus = (longestBreak.toFloat() / (goals.breakMin * MIN)).coerceIn(0f, 1f)
        val mindful = (share / (goals.purposePct / 100f)).coerceIn(0f, 1f)
        val score = (100 * (0.4f * budget + 0.3f * focus + 0.3f * mindful)).let { Math.round(it) }.coerceIn(0, 100)
        return Summary(total, budget, focus, mindful, score, longestBreak, longestSession, sessions.size,
            if (screenOns > 0) screenOns else sessions.size, late, share, hourly, apps, goals)
    }

    private fun overlap(s: LongArray, a: Long, b: Long) = (minOf(s[1], b) - maxOf(s[0], a)).coerceAtLeast(0)

    /** "1h 49m", "3h", "12m", "0m". */
    fun duration(ms: Long): String {
        val m = ms / MIN
        return when { m < 60 -> "${m}m"; m % 60 == 0L -> "${m / 60}h"; else -> "${m / 60}h ${m % 60}m" }
    }
}
