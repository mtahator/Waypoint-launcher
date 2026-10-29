package app.olauncher.features.assistant.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.TextView
import app.olauncher.R
import app.olauncher.core.waypoint
import app.olauncher.features.appearance.ui.GlassStyler
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.isDarkThemeOn
import kotlin.math.roundToInt

/** Shared look for the chat screens: Nothing-style monochrome with the one red accent. */
internal class ChatStyle(val context: Context) {
    val glass = context.waypoint.prefs.glassTheme
    val dark = context.isDarkThemeOn()
    val ink = context.getColorFromAttr(R.attr.primaryColor)
    val background = context.getColorFromAttr(R.attr.primaryShadeDarkColor)
    /** Without Glass: the phone's wallpaper shows through this veil, which keeps text readable on any picture. */
    val scrim = if (dark) 0x73000000 else 0x8CFFFFFF.toInt()
    val faint = (ink and 0x00FFFFFF) or 0x99000000.toInt()
    val hairline = (ink and 0x00FFFFFF) or 0x33000000
    val codeBg = (ink and 0x00FFFFFF) or 0x1F000000
    val userBubble = if (dark) 0xFF262626.toInt() else 0xFFE9E9E9.toInt()
    // cards and bubbles: frosted on Glass, a soft tint over the wallpaper otherwise
    val assistantBubble = if (glass) (if (dark) 0x8C000000.toInt() else 0xB3FFFFFF.toInt()) else (if (dark) 0x59000000 else 0x80FFFFFF.toInt())
    val density = context.resources.displayMetrics.density

    fun dp(v: Int) = (v * density).roundToInt()

    fun rounded(color: Int, radiusDp: Int, stroke: Int = 0) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
        if (stroke != 0) setStroke(dp(1).coerceAtLeast(1), stroke)
    }

    fun title(text: CharSequence) = TextView(context, null, 0, R.style.TextLarge).apply {
        this.text = text
        setTextColor(ink)
        if (glass) { GlassStyler.styleClock(this); isAllCaps = true; textSize = 26f }
    }

    /** "CHATS  SPACE" header: the current tab is bright, the other faint; tap to switch. */
    fun tabs(space: Boolean, onSelect: (Boolean) -> Unit) = android.widget.LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        listOf(false to R.string.wp_chats, true to R.string.wp_space).forEach { (isSpace, label) ->
            addView(title(context.getString(label)).apply {
                alpha = if (isSpace == space) 1f else 0.38f
                if (!glass) textSize = 24f
                setPadding(0, 0, dp(18), 0)
                setOnClickListener { if (isSpace != space) onSelect(isSpace) }
            })
        }
    }

    fun action(text: CharSequence, onClick: () -> Unit) = TextView(context, null, 0, R.style.TextSmall).apply {
        this.text = text
        isAllCaps = true
        setTextColor(ink)
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.12f
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setOnClickListener { onClick() }
    }

    fun caption(text: CharSequence = "") = TextView(context, null, 0, R.style.TextSmall).apply {
        this.text = text
        setTextColor(faint)
        typeface = Typeface.MONOSPACE
        textSize = 12f
    }
}
