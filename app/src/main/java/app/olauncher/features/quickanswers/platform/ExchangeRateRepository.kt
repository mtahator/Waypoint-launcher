package app.olauncher.features.quickanswers.platform

import android.content.Context
import app.olauncher.core.WaypointPrefs
import app.olauncher.features.quickanswers.domain.ExchangeRates
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Exchange rates for currency answers. Network is used only when a currency conversion is typed, the
 * setting allows it, and the cached rates are older than [MAX_AGE_MS]. The request is a fixed URL for
 * USD-based rates: no amount, query or identifier is ever sent. Rates are cached in app storage.
 */
class ExchangeRateRepository(
    private val context: Context,
    private val prefs: WaypointPrefs,
    private val io: CoroutineDispatcher,
) {
    @Volatile
    private var memory: ExchangeRates? = null
    private val file get() = File(context.filesDir, "fx/latest.json")

    /** Cached rates (any age), without touching the network. */
    suspend fun cached(): ExchangeRates? = memory ?: withContext(io) { readFile()?.also { memory = it } }

    /** Fresh-enough rates, fetching once if needed and allowed. Falls back to stale cache offline. */
    suspend fun get(now: Long = System.currentTimeMillis()): ExchangeRates? {
        val c = cached()
        if (c != null && now - c.fetchedAt < MAX_AGE_MS) return c
        if (!prefs.onlineRates) return c
        return withContext(io) { runCatching { fetch(now) }.getOrNull() } ?: c
    }

    private fun fetch(now: Long): ExchangeRates? {
        val conn = (URL(URL_LATEST).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000; readTimeout = 6000
            setRequestProperty("Accept", "application/json")
            useCaches = false
        }
        try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val rates = parse(body, now) ?: return null
            file.parentFile?.mkdirs()
            file.writeText(JSONObject().put("fetchedAt", now).put("body", body).toString())
            memory = rates
            return rates
        } finally {
            conn.disconnect()
        }
    }

    private fun readFile(): ExchangeRates? = runCatching {
        if (!file.exists()) return null
        val o = JSONObject(file.readText())
        parse(o.getString("body"), o.getLong("fetchedAt"))
    }.getOrNull()

    /** Clears cached rates (Privacy & data). */
    suspend fun clear() = withContext(io) { memory = null; file.delete() }

    companion object {
        /** ExchangeRate-API open access endpoint: no key, daily updates, includes IRR (official rate). */
        const val URL_LATEST = "https://open.er-api.com/v6/latest/USD"
        const val MAX_AGE_MS = 6 * 60 * 60 * 1000L

        fun parse(body: String, fetchedAt: Long): ExchangeRates? {
            val o = JSONObject(body)
            if (o.optString("result") != "success") return null
            val r = o.getJSONObject("rates")
            val map = HashMap<String, Double>(r.length())
            r.keys().forEach { k -> map[k] = r.getDouble(k) }
            return ExchangeRates(o.optString("base_code", "USD"), map, fetchedAt, o.optLong("time_last_update_unix") * 1000)
        }
    }
}
