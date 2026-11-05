package app.olauncher.features.widgets.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import app.olauncher.features.appearance.domain.Ink
import app.olauncher.features.appearance.platform.BackdropSampler
import kotlin.math.max
import kotlin.random.Random

/**
 * A frosted glass plaque (docs/widget-design.md §2): the blurred backdrop right under the card, fixed to the
 * screen so it stays put while the card slides; a tint (dark or light glass); fine grain; a sheen at the
 * top; a hairline edge brighter at the top. One shader draw per layer, nothing offscreen.
 */
class FrostedDrawable(private val host: View, private val sampler: BackdropSampler?, private val radiusPx: Float) : Drawable() {
    /** Light glass (white tint, dark ink) or dark glass (black tint, light ink); set by the ink pass. */
    var light = false
        set(value) { if (field != value) { field = value; invalidateSelf() } }

    private val d = host.resources.displayMetrics.density
    private val rect = RectF()
    private val backdrop = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grain = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(noise(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = max(1f, d) }
    private val matrix = Matrix()
    private val loc = IntArray(2)
    private var shaderFor: Bitmap? = null

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        rect.set(bounds)
        sheen.shader = LinearGradient(0f, rect.top, 0f, rect.top + rect.height() * 0.55f, 0x17FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        edgeShader()
    }

    private fun edgeShader() {
        val top = if (light) 0x33FFFFFF else 0x3DFFFFFF
        val bottom = if (light) 0x1A000000 else 0x0FFFFFFF
        edge.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, top, bottom, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        val snap = sampler?.snapshot
        val exact = snap?.exact == true
        if (exact) {
            val bmp = snap!!.bitmap
            if (shaderFor !== bmp) { backdrop.shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP); shaderFor = bmp }
            val root = host.rootView
            host.getLocationInWindow(loc)
            val scale = max(root.width.toFloat() / bmp.width, root.height.toFloat() / bmp.height)
            matrix.setScale(scale, scale)
            matrix.postTranslate((root.width - bmp.width * scale) / 2f - loc[0], (root.height - bmp.height * scale) / 2f - loc[1])
            backdrop.shader.setLocalMatrix(matrix)
            canvas.drawRoundRect(rect, radiusPx, radiusPx, backdrop)
        }
        // without the pixels behind (system wallpaper), the glass is a little denser
        val a = if (light) Ink.LIGHT_TINT else Ink.DARK_TINT
        val alpha = ((if (exact) a else a + 0.10f) * 255).toInt().coerceIn(0, 255)
        tint.color = (if (light) 0x00FFFFFF else 0x00000000) or (alpha shl 24)
        canvas.drawRoundRect(rect, radiusPx, radiusPx, tint)
        grain.alpha = if (light) 14 else 10
        canvas.drawRoundRect(rect, radiusPx, radiusPx, grain)
        canvas.drawRoundRect(rect, radiusPx, radiusPx, sheen)
        edgeShader()
        val h = edge.strokeWidth / 2f
        canvas.drawRoundRect(rect.left + h, rect.top + h, rect.right - h, rect.bottom - h, radiusPx, radiusPx, edge)
    }

    override fun getOutline(outline: Outline) { outline.setRoundRect(bounds, radiusPx) }
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        private var tile: Bitmap? = null

        /** A 96 px tile of monochrome grain, made once. */
        private fun noise(): Bitmap = tile ?: run {
            val n = 96; val r = Random(7)
            val px = IntArray(n * n) { val v = r.nextInt(256); (r.nextInt(256) shl 24) or (v shl 16) or (v shl 8) or v }
            Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).also { tile = it }
        }
    }
}
