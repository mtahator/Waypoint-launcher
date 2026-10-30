package app.olauncher.helper

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.MenuRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import app.olauncher.data.Prefs
import app.olauncher.databinding.DialogBaseBinding

/**
 * Shows a popup menu hanging off the end edge of this view.
 * [configure] can add or tweak items before the menu is shown.
 */
fun View.showPopupMenu(
    @MenuRes menuRes: Int = 0,
    configure: (Menu) -> Unit = {},
    onItemClick: (MenuItem) -> Unit,
): PopupMenu {
    val popup = PopupMenu(context, this, Gravity.END)
    if (menuRes != 0) popup.menuInflater.inflate(menuRes, popup.menu)
    configure(popup.menu)
    popup.setOnMenuItemClickListener { item ->
        onItemClick(item)
        true
    }
    popup.show()
    return popup
}

/** A ScrollView no taller than the visible window (above the keyboard) minus [reserved] px. */
private class KeyboardAwareScroll(context: Context, private val reserved: Int) : android.widget.ScrollView(context) {
    private val frame = android.graphics.Rect()
    init {
        isVerticalScrollBarEnabled = false; isFillViewport = false
        // re-measure when the keyboard opens or closes
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets -> v.post { v.requestLayout() }; insets }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        getWindowVisibleDisplayFrame(frame)
        // the keyboard's height from the insets too: dialogs don't always resize for it
        val ime = androidx.core.view.ViewCompat.getRootWindowInsets(this)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0
        val screen = resources.displayMetrics.heightPixels
        val visible = minOf(frame.height().takeIf { it > 0 } ?: screen, screen - ime)
        val max = (visible - reserved).coerceAtLeast((120 * resources.displayMetrics.density).toInt())
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST))
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // keep the focused field in view after the keyboard opens
        findFocus()?.let { f -> post { requestChildFocus(f, f) } }
    }
}

/**
 * App dialog: shows without bringing back a hidden status bar, and blurs the
 * screen behind it on Android 12+, fading blur and dialog out together on dismiss.
 */
class OlDialog(context: Context) : AlertDialog(context) {

    private var blur: WindowBlur? = null

    companion object {
        private val open = LinkedHashSet<OlDialog>()

        /** Waypoint: closes every open dialog, e.g. when the launcher returns Home or a page closes. */
        fun dismissAll() {
            open.toList().forEach { runCatching { it.dismiss() } }
            open.clear()
        }
    }

    fun showRespectingStatusBar() {
        val window = window
        if (window == null || Prefs(context).showStatusBar) {
            show()
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            show()
            window.hideStatusBar()
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
        blur = window?.let { WindowBlur(it).apply { fadeIn() } }
        open += this
    }

    override fun dismiss() {
        open -= this
        val blur = blur ?: return super.dismiss()
        this.blur = null
        blur.fadeOut { super.dismiss() }
    }
}

/**
 * Builds a dialog using the app's own layout: a title row with a close icon,
 * an optional [message] or custom [content], and a text [action] at the end.
 * [content] receives the container so the inflated view keeps its XML margins.
 */
fun Context.createDialog(
    @StringRes title: Int,
    @StringRes action: Int,
    @StringRes message: Int = 0,
    @StringRes neutral: Int = 0,
    onNeutral: () -> Unit = {},
    onAction: () -> Unit = {},
    content: ((ViewGroup) -> View)? = null,
): OlDialog {
    val dialog = OlDialog(this)
    val binding = DialogBaseBinding.inflate(LayoutInflater.from(dialog.context))
    binding.tvTitle.setText(title)
    binding.tvAction.setText(action)
    if (message != 0) {
        binding.tvMessage.setText(message)
        binding.tvMessage.isVisible = true
    }
    if (neutral != 0) {
        binding.tvNeutral.setText(neutral)
        binding.tvNeutral.isVisible = true
    }
    content?.let {
        // custom content scrolls, capped to the space left above the keyboard, so the actions below it
        // (Save, Delete) are always on screen
        val scroll = KeyboardAwareScroll(dialog.context, (220 * dialog.context.resources.displayMetrics.density).toInt())
        scroll.addView(it(binding.contentContainer))
        binding.contentContainer.addView(scroll, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        binding.contentContainer.isVisible = true
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        // editors: Save / Delete right under the title, where the keyboard can never cover them
        val actions = binding.tvAction.parent as ViewGroup
        val root = binding.root
        root.removeView(actions)
        root.addView(actions, 1)
        (actions.layoutParams as ViewGroup.MarginLayoutParams).apply { topMargin = (6 * dialog.context.resources.displayMetrics.density).toInt(); bottomMargin = 0 }
        binding.tvAction.setTextColor(0xFFD71921.toInt())
    }
    dialog.setView(binding.root)
    binding.ivClose.setOnClickListener { dialog.dismiss() }
    binding.tvNeutral.setOnClickListener {
        onNeutral()
        dialog.dismiss()
    }
    binding.tvAction.setOnClickListener {
        onAction()
        dialog.dismiss()
    }
    return dialog
}

/** Title with a close icon, a message and a single action. */
fun Context.showMessageDialog(
    @StringRes title: Int,
    @StringRes message: Int,
    @StringRes action: Int,
    onAction: () -> Unit,
): OlDialog {
    val dialog = createDialog(title, action, message = message, onAction = onAction)
    dialog.showRespectingStatusBar()
    return dialog
}
