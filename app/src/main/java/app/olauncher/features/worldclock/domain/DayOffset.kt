package app.olauncher.features.worldclock.domain

import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

object DayOffset {
    /**
     * Calendar-day difference between [zoneId] and [localZone] at [now]: +1 when the city is already
     * on tomorrow's date, -1 when it is still yesterday. Returns null for an unknown/removed zone id.
     */
    fun daysBetween(zoneId: String, now: Instant, localZone: ZoneId): Int? {
        val zone = parseZone(zoneId) ?: return null
        val local = now.atZone(localZone).toLocalDate()
        val remote = now.atZone(zone).toLocalDate()
        return ChronoUnit.DAYS.between(local, remote).toInt()
    }

    /** Offset from UTC in minutes for [zoneId] at [now] (DST-aware), or null for an invalid zone. */
    fun utcOffsetMinutes(zoneId: String, now: Instant): Int? =
        parseZone(zoneId)?.rules?.getOffset(now)?.totalSeconds?.div(60)

    fun parseZone(zoneId: String): ZoneId? = try {
        ZoneId.of(zoneId)
    } catch (_: DateTimeException) {
        null
    }

    fun isValid(zoneId: String): Boolean = parseZone(zoneId) != null
}
