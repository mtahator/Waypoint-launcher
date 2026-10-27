package app.olauncher.features.worldclock

import app.olauncher.features.worldclock.domain.City
import app.olauncher.features.worldclock.domain.CitySearch
import app.olauncher.features.worldclock.domain.DayOffset
import app.olauncher.features.worldclock.domain.WorldClockCodec
import app.olauncher.features.worldclock.domain.WorldClockEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class WorldClockDomainTest {
    private val london = ZoneId.of("Europe/London")

    @Test
    fun codecRoundTripsLabelsWithSeparators() {
        val entries = listOf(
            WorldClockEntry("Asia/Tokyo", "Tokyo; office|HQ 100%", 0),
            WorldClockEntry("America/New_York", "NYC", 1),
        )
        assertEquals(entries, WorldClockCodec.decode(WorldClockCodec.encode(entries)))
    }

    @Test
    fun codecIsDefensive() {
        assertTrue(WorldClockCodec.decode(null).isEmpty())
        assertTrue(WorldClockCodec.decode("garbage").isEmpty())
        assertTrue(WorldClockCodec.decode("v9;Asia/Tokyo|Tokyo").isEmpty())
        val decoded = WorldClockCodec.decode("v1;Asia/Tokyo|Tokyo;broken;|nolabel;Europe/Paris|Paris")
        assertEquals(listOf("Asia/Tokyo", "Europe/Paris"), decoded.map { it.zoneId })
        assertEquals(listOf(0, 1), decoded.map { it.position })
        assertTrue(WorldClockCodec.decode(WorldClockCodec.encode(emptyList())).isEmpty())
    }

    @Test
    fun dayOffsetAcrossMidnight() {
        // 23:30 in London on 3 Oct = 07:30 next day in Tokyo, 18:30 same day in New York
        val now = Instant.parse("2026-10-03T22:30:00Z")
        assertEquals(1, DayOffset.daysBetween("Asia/Tokyo", now, london))
        assertEquals(0, DayOffset.daysBetween("America/New_York", now, london))
        val early = Instant.parse("2026-10-03T00:30:00Z") // 01:30 London
        assertEquals(-1, DayOffset.daysBetween("America/Los_Angeles", early, london))
    }

    @Test
    fun dateLineExtremes() {
        val now = Instant.parse("2026-06-15T12:00:00Z")
        // Kiritimati is UTC+14, Pago Pago UTC-11: 25 hours apart, so always different dates at noon UTC
        assertEquals(1, DayOffset.daysBetween("Pacific/Kiritimati", now, ZoneId.of("UTC")))
        assertEquals(0, DayOffset.daysBetween("Pacific/Pago_Pago", now, ZoneId.of("UTC")))
        assertEquals(-1, DayOffset.daysBetween("Pacific/Pago_Pago", now, ZoneId.of("Pacific/Kiritimati")))
    }

    @Test
    fun dstTransitionsChangeOffset() {
        // Europe/London: BST ends last Sunday of October (25 Oct 2026 01:00 UTC)
        assertEquals(60, DayOffset.utcOffsetMinutes("Europe/London", Instant.parse("2026-10-25T00:59:00Z")))
        assertEquals(0, DayOffset.utcOffsetMinutes("Europe/London", Instant.parse("2026-10-25T01:00:00Z")))
        // America/New_York: DST starts 8 Mar 2026 07:00 UTC
        assertEquals(-300, DayOffset.utcOffsetMinutes("America/New_York", Instant.parse("2026-03-08T06:59:00Z")))
        assertEquals(-240, DayOffset.utcOffsetMinutes("America/New_York", Instant.parse("2026-03-08T07:00:00Z")))
        // last Sunday of March for London
        assertEquals(0, DayOffset.utcOffsetMinutes("Europe/London", Instant.parse("2026-03-29T00:59:00Z")))
        assertEquals(60, DayOffset.utcOffsetMinutes("Europe/London", Instant.parse("2026-03-29T01:00:00Z")))
    }

    @Test
    fun invalidZoneHandledWithoutCrash() {
        assertNull(DayOffset.daysBetween("Mars/Olympus_Mons", Instant.now(), london))
        assertNull(DayOffset.utcOffsetMinutes("", Instant.now()))
        assertFalse(DayOffset.isValid("Not/AZone"))
        assertTrue(DayOffset.isValid("Asia/Kolkata"))
    }

    @Test
    fun citySearchMatchesAccentsAndZoneIds() {
        val cities = CitySearch.parseTsv(
            sequenceOf("# comment", "São Paulo\tBrazil\tAmerica/Sao_Paulo", "London\tUnited Kingdom\tEurope/London", "bad line")
        )
        assertEquals(2, cities.size)
        val search = CitySearch(cities, setOf("America/Sao_Paulo", "Europe/London", "America/Argentina/Salta", "Etc/GMT+5"))
        assertEquals("São Paulo", search.search("sao").first().name)
        assertEquals("Europe/London", search.search("LOND").first().zoneId)
        assertEquals("America/Argentina/Salta", search.search("salta").single().zoneId)
        assertTrue(search.search("gmt+5").isEmpty())
        assertTrue(search.search("  ").isEmpty())
    }

    @Test
    fun citySearchRanksPrefixFirst() {
        val search = CitySearch(
            listOf(City("Newark", "US", "America/New_York"), City("Ark", "X", "Europe/London")),
            emptySet(),
        )
        assertEquals("Ark", search.search("ark").first().name)
    }
}
