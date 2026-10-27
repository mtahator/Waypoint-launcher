package app.olauncher.features.appearance.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import app.olauncher.core.WaypointPrefs
import app.olauncher.features.appearance.domain.AuroraBackdrop
import app.olauncher.features.appearance.domain.ReededGlass
import app.olauncher.helper.isDarkThemeOn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * The Glass theme's own background: the built-in Aurora or a photo the user picked, seen through reeded
 * glass. (Android 13+ doesn't let launchers read the system wallpaper's pixels, so the effect is rendered
 * on a backdrop Waypoint owns.) Rendered once at half resolution off the main thread, cached in memory
 * and on disk, and re-rendered only when a setting or the photo changes.
 */
class GlassBackdrop(
    private val context: Context,
    private val prefs: WaypointPrefs,
    private val io: CoroutineDispatcher,
) {
    @Volatile
    private var cached: Pair<String, Bitmap>? = null
    private val dir get() = File(context.filesDir, "glass").apply { mkdirs() }
    private val sourceFile get() = File(dir, "source.jpg")
    private val renderFile get() = File(dir, "backdrop.png")
    private val keyFile get() = File(dir, "backdrop.key")

    private fun size(): Pair<Int, Int> {
        val m = context.resources.displayMetrics
        return max(1, m.widthPixels / 2) to max(1, m.heightPixels / 2)
    }

    private fun key(): String {
        val (w, h) = size()
        val photo = if (prefs.glassBackground == WaypointPrefs.BG_PHOTO) prefs.glassPhotoStamp else 0L
        return "v1|${prefs.glassBackground}|${prefs.glassMonochrome}|${prefs.glassRibWidth}|${context.isDarkThemeOn()}|${w}x$h|$photo"
    }

    /** Ready-to-use bitmap for the current settings, or null if it still needs loading/rendering. */
    fun cachedBitmap(): Bitmap? = cached?.takeIf { it.first == key() }?.second

    suspend fun load(): Bitmap? = withContext(io) {
        val key = key()
        cachedBitmap() ?: runCatching { fromDisk(key) ?: render(key) }.getOrNull()?.also { cached = key to it }
    }

    private fun fromDisk(key: String): Bitmap? {
        if (!renderFile.exists() || !keyFile.exists() || keyFile.readText() != key) return null
        return BitmapFactory.decodeFile(renderFile.path)
    }

    private fun render(key: String): Bitmap {
        val (w, h) = size()
        val src = if (prefs.glassBackground == WaypointPrefs.BG_PHOTO && sourceFile.exists())
            photoPixels(w, h) ?: AuroraBackdrop.render(w, h) else AuroraBackdrop.render(w, h)
        val ribDiv = when (prefs.glassRibWidth) { 0 -> 34; 2 -> 14; else -> 23 }
        val out = ReededGlass.apply(
            src, w, h,
            ReededGlass.Params(
                ribPx = w / ribDiv,
                monochrome = prefs.glassMonochrome,
                darken = if (context.isDarkThemeOn()) 0.18f else -0.22f,
            ),
        )
        val bmp = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        renderFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        keyFile.writeText(key)
        return bmp
    }

    /** Copies a picked photo (downscaled) into app storage; no permission needed with the photo picker. */
    suspend fun importPhoto(uri: Uri): Boolean = withContext(io) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
            val bmp = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@runCatching false
            sourceFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            bmp.recycle()
            prefs.glassPhotoStamp = System.currentTimeMillis()
            true
        }.getOrDefault(false)
    }

    private fun photoPixels(w: Int, h: Int): IntArray? {
        val photo = BitmapFactory.decodeFile(sourceFile.path) ?: return null
        val scaled = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val scale = max(w.toFloat() / photo.width, h.toFloat() / photo.height)
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate((w - photo.width * scale) / 2f, (h - photo.height * scale) / 2f)
        }
        Canvas(scaled).drawBitmap(photo, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        photo.recycle()
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        scaled.recycle()
        return px
    }
}

/** Draws the backdrop centre-cropped to the window. */
class BackdropDrawable(private val bitmap: Bitmap) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val matrix = Matrix()

    override fun onBoundsChange(bounds: Rect) {
        val scale = max(bounds.width().toFloat() / bitmap.width, bounds.height().toFloat() / bitmap.height)
        matrix.setScale(scale, scale)
        matrix.postTranslate(
            bounds.left + (bounds.width() - bitmap.width * scale) / 2f,
            bounds.top + (bounds.height() - bitmap.height * scale) / 2f,
        )
    }

    override fun draw(canvas: Canvas) = canvas.drawBitmap(bitmap, matrix, paint)
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
