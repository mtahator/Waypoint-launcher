package app.olauncher.features.worldclock.domain

/** One city on the world clock list. [zoneId] is always an IANA id, never a fixed offset. */
data class WorldClockEntry(val zoneId: String, val label: String, val position: Int)
