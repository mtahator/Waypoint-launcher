package app.olauncher.features.worldclock.domain

import java.text.Normalizer
import java.util.Locale

data class City(val name: String, val country: String, val zoneId: String)

/** Offline city search over the bundled `cities.tsv` plus raw IANA zone ids. Pure; no I/O. */
class CitySearch(cities: List<City>, availableZoneIds: Set<String>) {

    private val indexed: List<Pair<String, City>> = buildList {
        cities.forEach { add(normalize("${it.name} ${it.country}") to it) }
        val covered = cities.mapTo(HashSet()) { it.zoneId }
        // Zones with no bundled city remain searchable by id, e.g. "America/Argentina/Salta".
        availableZoneIds.asSequence()
            .filter { it !in covered && it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }
            .forEach { zone ->
                val name = zone.substringAfterLast('/').replace('_', ' ')
                add(normalize("$name $zone") to City(name, zone.substringBefore('/'), zone))
            }
    }

    fun search(query: String, limit: Int = 50): List<City> {
        val q = normalize(query).trim()
        if (q.isEmpty()) return emptyList()
        return indexed.asSequence()
            .filter { (key, _) -> key.contains(q) }
            .sortedWith(compareBy({ !it.first.startsWith(q) }, { it.second.name.length }))
            .map { it.second }
            .distinctBy { it.name to it.zoneId }
            .take(limit)
            .toList()
    }

    companion object {
        fun parseTsv(lines: Sequence<String>): List<City> = lines.mapNotNull { line ->
            if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
            val f = line.split('\t')
            if (f.size < 3) null else City(f[0].trim(), f[1].trim(), f[2].trim())
        }.toList()

        fun normalize(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .lowercase(Locale.ROOT)
    }
}
