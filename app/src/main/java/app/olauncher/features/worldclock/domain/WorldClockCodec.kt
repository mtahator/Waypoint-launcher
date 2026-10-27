package app.olauncher.features.worldclock.domain

/**
 * Compact, versioned preferences encoding: `v1;Europe/London|London;Asia/Tokyo|Tokyo`.
 * Labels are percent-escaped for the separator characters so any user label survives a round trip.
 * Decoding is defensive: malformed items are skipped, never thrown.
 */
object WorldClockCodec {
    private const val VERSION = "v1"
    const val MAX_ENTRIES = 20

    fun encode(entries: List<WorldClockEntry>): String =
        entries.sortedBy { it.position }.joinToString(separator = ";", prefix = "$VERSION;") {
            escape(it.zoneId) + "|" + escape(it.label)
        }.removeSuffix(";")

    fun decode(raw: String?): List<WorldClockEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        val parts = raw.split(';')
        if (parts.first() != VERSION) return emptyList()
        return parts.drop(1).mapNotNull { item ->
            val fields = item.split('|')
            if (fields.size != 2) return@mapNotNull null
            val zone = unescape(fields[0]).trim()
            if (zone.isEmpty()) return@mapNotNull null
            zone to unescape(fields[1])
        }.take(MAX_ENTRIES).mapIndexed { index, (zone, label) -> WorldClockEntry(zone, label, index) }
    }

    private fun escape(s: String) = s.replace("%", "%25").replace(";", "%3B").replace("|", "%7C")
    private fun unescape(s: String) = s.replace("%7C", "|").replace("%3B", ";").replace("%25", "%")
}
