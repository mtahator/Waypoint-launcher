package app.olauncher.features.worldclock.data

import android.content.Context
import app.olauncher.core.WaypointPrefs
import app.olauncher.features.worldclock.domain.City
import app.olauncher.features.worldclock.domain.CitySearch
import app.olauncher.features.worldclock.domain.WorldClockCodec
import app.olauncher.features.worldclock.domain.WorldClockEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** World clock list in preferences; the city asset is loaded only by the settings screen. */
class WorldClockRepository(private val context: Context, private val prefs: WaypointPrefs) {

    fun entries(): List<WorldClockEntry> = prefs.worldClocks

    fun add(city: City): Boolean {
        val list = entries()
        if (list.size >= WorldClockCodec.MAX_ENTRIES || list.any { it.zoneId == city.zoneId && it.label == city.name }) return false
        save(list + WorldClockEntry(city.zoneId, city.name, list.size))
        return true
    }

    fun remove(index: Int) = save(entries().filterIndexed { i, _ -> i != index })

    fun rename(index: Int, label: String) =
        save(entries().mapIndexed { i, e -> if (i == index) e.copy(label = label.trim().ifEmpty { e.label }) else e })

    fun move(index: Int, delta: Int) {
        val list = entries().toMutableList()
        val target = index + delta
        if (index !in list.indices || target !in list.indices) return
        list.add(target, list.removeAt(index))
        save(list)
    }

    private fun save(list: List<WorldClockEntry>) {
        prefs.worldClocks = list.mapIndexed { i, e -> e.copy(position = i) }
    }

    suspend fun loadSearch(io: CoroutineDispatcher): CitySearch = withContext(io) {
        val cities = context.assets.open("cities.tsv").bufferedReader(Charsets.UTF_8).useLines { CitySearch.parseTsv(it) }
        CitySearch(cities, ZoneId.getAvailableZoneIds())
    }
}
