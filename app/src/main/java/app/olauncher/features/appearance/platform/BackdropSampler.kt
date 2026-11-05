package app.olauncher.features.appearance.platform

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import app.olauncher.core.WaypointPrefs
import app.olauncher.features.appearance.domain.AppearanceState
import app.olauncher.features.appearance.domain.Ink
import app.olauncher.helper.isDarkThemeOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * What is behind Waypoint's window, kept small (1/8 of the screen) and blurred once, so widgets can show it
 * through frosted glass and every text can pick a readable colour for exactly where it sits.
 *
 * Sources: the Glass backdrop (Waypoint's own, so exact), a plain background colour (exact), or the system
 * wallpaper where Android lets a launcher read it. When it doesn't (Android 13+ usually), the wallpaper's
 * colour hints give one overall estimate and [exact] is false.
 */
class BackdropSampler(private val context: Context, private val prefs: WaypointPrefs, private val glass: GlassBackdrop) {

    /** [map] is the brightness map (see [Ink.brightnessMap]), made once with the snapshot. */
    class Snapshot(val bitmap: Bitmap, val map: FloatArray, val exact: Boolean, val key: String)

    @Volatile var snapshot: Snapshot? = null
        private set

    private fun key(): String {
        val dark = context.isDarkThemeOn()
        val state = AppearanceState.resolve(prefs.launcherMonochrome, prefs.oledBlack, dark)
        val wallpaperId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            runCatching { WallpaperManager.getInstance(context).getWallpaperId(WallpaperManager.FLAG_SYSTEM) }.getOrDefault(0) else 0
        return when {
            !state.showWallpaper -> "solid|${state.backgroundArgb}"
            prefs.glassTheme && prefs.glassBackground != WaypointPrefs.BG_WALLPAPER ->
                "glass|${prefs.glassBackground}|${prefs.glassMonochrome}|${prefs.glassRibWidth}|$dark|${prefs.glassPhotoStamp}"
            else -> "wall|$wallpaperId|${prefs.glassTheme}|$dark"
        }
    }

    /** Re-reads the backdrop when something changed; cheap when nothing did. */
    suspend fun refresh(): Snapshot? {
        val key = key()
        snapshot?.takeIf { it.key == key }?.let { return it }
        val made = withContext(Dispatchers.Default) { runCatching { make(key) }.getOrNull() }
        snapshot = made
        return made
    }

    private suspend fun make(key: String): Snapshot? {
        val m = context.resources.displayMetrics
        val w = max(8, m.widthPixels / 8); val h = max(8, m.heightPixels / 8)
        val dark = context.isDarkThemeOn()
        val (bitmap, exact) = when {
            key.startsWith("solid|") -> solid(key.substringAfter('|').toInt()) to true
            key.startsWith("glass|") -> (glass.load()?.let { scaled(it, w, h) } ?: solid(if (dark) 0xFF0E0E0E.toInt() else 0xFFEDEDED.toInt())) to true
            else -> wallpaper(w, h)?.let { it to true } ?: (solid(estimate(dark)) to false)
        }
        val bw = bitmap.width; val bh = bitmap.height
        val px = IntArray(bw * bh).also { bitmap.getPixels(it, 0, bw, 0, 0, bw, bh) }
        if (key.startsWith("wall|") && prefs.glassTheme && exact) scrim(px, dark)
        if (bw > 1) Ink.blur(px, bw, bh, 5)
        val out = Bitmap.createBitmap(px, bw, bh, Bitmap.Config.ARGB_8888)
        return Snapshot(out, Ink.brightnessMap(px, bw, bh), exact, key)
    }

    private fun solid(color: Int) = Bitmap.createBitmap(intArrayOf(color or 0xFF000000.toInt()), 1, 1, Bitmap.Config.ARGB_8888)

    private fun scaled(src: Bitmap, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val scale = max(w.toFloat() / src.width, h.toFloat() / src.height)
        val matrix = android.graphics.Matrix().apply {
            setScale(scale, scale); postTranslate((w - src.width * scale) / 2f, (h - src.height * scale) / 2f)
        }
        Canvas(out).drawBitmap(src, matrix, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** The system wallpaper's pixels, where the platform allows it (it usually doesn't on Android 13+). */
    @android.annotation.SuppressLint("MissingPermission")
    private fun wallpaper(w: Int, h: Int): Bitmap? = runCatching {
        val d = WallpaperManager.getInstance(context).drawable ?: return null
        val iw = d.intrinsicWidth.coerceAtLeast(1); val ih = d.intrinsicHeight.coerceAtLeast(1)
        val src = Bitmap.createBitmap(max(1, iw / 8), max(1, ih / 8), Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, src.width, src.height); d.draw(Canvas(src))
        scaled(src, w, h).also { src.recycle() }
    }.getOrNull()

    /** One colour standing for the wallpaper, from Android's colour hints. */
    private fun estimate(dark: Boolean): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) runCatching {
            val colors = WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            if (colors != null) {
                val darkText = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    (colors.colorHints and android.app.WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
                val primary = colors.primaryColor.toArgb()
                return if (darkText && Ink.luminance(primary) < 0.4f) 0xFFC8C8C8.toInt() else primary
            }
        }
        return if (dark) 0xFF202020.toInt() else 0xFFD8D8D8.toInt()
    }

    /** Glass over the wallpaper: the window's scrim, as GlassStyler draws it. */
    private fun scrim(px: IntArray, dark: Boolean) {
        val a = if (dark) 0.4f else 0.35f
        val v = if (dark) 0 else 255
        for (i in px.indices) {
            val c = px[i]
            val r = (((c shr 16) and 0xFF) * (1 - a) + v * a).toInt(); val g = (((c shr 8) and 0xFF) * (1 - a) + v * a).toInt(); val b = ((c and 0xFF) * (1 - a) + v * a).toInt()
            px[i] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        }
    }

    /**
     * Average luminance behind a rectangle given in window pixels, for a window of [winW]×[winH]
     * (the snapshot is centre-cropped to the window, like the backdrop itself).
     */
    fun luminance(left: Int, top: Int, right: Int, bottom: Int, winW: Int, winH: Int): Float? {
        val s = snapshot ?: return null
        val bw = s.bitmap.width; val bh = s.bitmap.height
        val scale = max(winW.toFloat() / bw, winH.toFloat() / bh)
        val ox = (winW - bw * scale) / 2f; val oy = (winH - bh * scale) / 2f
        return Ink.average(s.map, bw, bh, ((left - ox) / scale).toInt(), ((top - oy) / scale).toInt(),
            ((right - ox) / scale).toInt() + 1, ((bottom - oy) / scale).toInt() + 1)
    }
}
