package app.olauncher.features.assistant.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.LruCache
import app.olauncher.features.assistant.domain.Tools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.floor

/**
 * A small static map for a chat answer: OpenStreetMap tiles around the places, drawn in grey (inverted in
 * dark mode, to match the app), numbered red pins and a dot for where the user is.
 */
object MapCard {

    private val tiles = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun render(map: Tools.MapData, width: Int, height: Int, density: Float, dark: Boolean): Bitmap? = withContext(Dispatchers.IO) {
        if (width <= 0 || height <= 0 || map.pins.isEmpty()) return@withContext null
        // tiles drawn a little larger than 1:1 so street names stay readable on dense screens
        val s = (density / 1.25f).coerceIn(1f, 2.6f)
        val fw = width / s.toDouble(); val fh = height / s.toDouble()
        val z = Tools.fitZoom(map.all, fw, fh)
        val xs = map.all.map { Tools.worldX(it.lon, z) }; val ys = map.all.map { Tools.worldY(it.lat, z) }
        val cx = (xs.min() + xs.max()) / 2; val cy = (ys.min() + ys.max()) / 2
        val left = cx - fw / 2; val top = cy - fh / 2
        val n = 1 shl z
        val tx0 = floor(left / 256).toInt(); val tx1 = floor((left + fw) / 256).toInt()
        val ty0 = floor(top / 256).toInt().coerceAtLeast(0); val ty1 = floor((top + fh) / 256).toInt().coerceAtMost(n - 1)
        val wanted = (ty0..ty1).flatMap { ty -> (tx0..tx1).map { tx -> tx to ty } }
        val loaded = coroutineScope {
            wanted.map { (tx, ty) -> async { Triple(tx, ty, tile(z, ((tx % n) + n) % n, ty)) } }.awaitAll()
        }
        if (loaded.all { it.third == null }) return@withContext null

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(if (dark) 0xFF1B1B1B.toInt() else 0xFFE9E9E9.toInt())
        val gray = ColorMatrix().apply { setSaturation(0f) }
        if (dark) gray.postConcat(ColorMatrix(floatArrayOf(
            -0.85f, 0f, 0f, 0f, 235f,
            0f, -0.85f, 0f, 0f, 235f,
            0f, 0f, -0.85f, 0f, 235f,
            0f, 0f, 0f, 1f, 0f,
        )))
        val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(gray) }
        for ((tx, ty, bmp) in loaded) {
            bmp ?: continue
            val x = ((tx * 256 - left) * s).toFloat(); val y = ((ty * 256 - top) * s).toFloat()
            c.drawBitmap(bmp, null, RectF(x, y, x + 256 * s, y + 256 * s), tilePaint)
        }
        fun px(p: Tools.Pin) = ((Tools.worldX(p.lon, z) - left) * s).toFloat() to ((Tools.worldY(p.lat, z) - top) * s).toFloat()

        // where the user is: blue dot with a white ring
        map.origin?.let { o ->
            val (x, y) = px(o)
            c.drawCircle(x, y, 9 * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x553D7BFF })
            c.drawCircle(x, y, 6 * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
            c.drawCircle(x, y, 4.2f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3D7BFF.toInt() })
        }
        // places: numbered red pins, last first so pin 1 is on top
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD71921.toInt() }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); textSize = 11 * density; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
        }
        map.pins.withIndex().reversed().forEach { (i, p) ->
            val (x, y) = px(p)
            c.drawCircle(x, y, 11.5f * density, ring)
            c.drawCircle(x, y, 10 * density, fill)
            if (map.pins.size > 1) c.drawText("${i + 1}", x, y - (label.ascent() + label.descent()) / 2, label)
        }
        // OpenStreetMap's licence asks for this
        val attr = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt(); textSize = 9 * density }
        val text = "© OpenStreetMap"
        val tw = attr.measureText(text)
        c.drawRect(width - tw - 8 * density, height - 14 * density, width.toFloat(), height.toFloat(),
            Paint().apply { color = 0xB3FFFFFF.toInt() })
        c.drawText(text, width - tw - 4 * density, height - 4 * density, attr)
        out
    }

    private fun tile(z: Int, x: Int, y: Int): Bitmap? {
        val key = "$z/$x/$y"
        tiles.get(key)?.let { return it }
        return runCatching {
            val conn = (URL("https://tile.openstreetmap.org/$key.png").openConnection() as HttpURLConnection).apply {
                connectTimeout = 6_000; readTimeout = 8_000
                setRequestProperty("User-Agent", "Waypoint-Launcher/1.1 (Android; personal assistant)")
            }
            try {
                if (conn.responseCode != 200) null else conn.inputStream.use { BitmapFactory.decodeStream(it) }
            } finally { conn.disconnect() }
        }.getOrNull()?.also { tiles.put(key, it) }
    }
}
