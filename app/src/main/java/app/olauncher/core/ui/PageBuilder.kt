package app.olauncher.core.ui

import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.core.waypoint
import app.olauncher.databinding.ItemWpRowBinding
import app.olauncher.features.appearance.ui.GlassStyler
import app.olauncher.helper.createDialog
import app.olauncher.helper.getColorFromAttr

/** Handle to a built row so pages can update it in place. */
class Row(val binding: ItemWpRowBinding) {
    var value: CharSequence?
        get() = binding.rowValue.text
        set(v) {
            binding.rowValue.text = v
            binding.rowValue.isVisible = !v.isNullOrEmpty()
        }
    var subtitle: CharSequence?
        get() = binding.rowSubtitle.text
        set(v) {
            binding.rowSubtitle.text = v
            binding.rowSubtitle.isVisible = !v.isNullOrEmpty()
        }
    var title: CharSequence?
        get() = binding.rowTitle.text
        set(v) {
            binding.rowTitle.text = v
        }

    fun setEnabled(enabled: Boolean) {
        binding.root.isEnabled = enabled
        binding.root.alpha = if (enabled) 1f else 0.45f
    }
}

/** Builds the sparse, text-first rows used by every Waypoint settings screen. */
class PageBuilder(private val container: LinearLayout) {
    val context: Context get() = container.context
    private val inflater = LayoutInflater.from(context)
    private val glass = context.waypoint.prefs.glassTheme

    fun clear() = container.removeAllViews()

    fun card(build: PageBuilder.() -> Unit): LinearLayout {
        val card = inflater.inflate(R.layout.item_wp_card, container, false) as LinearLayout
        if (glass) card.background = GlassStyler.drawable(context, GlassStyler.Surface.CARD, 28f)
        container.addView(card)
        PageBuilder(card).build()
        return card
    }

    fun header(text: CharSequence): TextView = TextView(context, null, 0, R.style.TextLarge).apply {
        this.text = text
        setShadowLayer(0f, 0f, 0f, 0)
        textSize = 20f
        markHeading()
        setPaddingRelative(dp(8), dp(8), dp(8), dp(8))
        container.addView(this)
    }

    fun note(text: CharSequence): TextView = TextView(context, null, 0, R.style.TextSmallLight).apply {
        this.text = text
        textSize = 14f
        setShadowLayer(0f, 0f, 0f, 0)
        setLineSpacing(dp(2).toFloat(), 1f)
        setPaddingRelative(dp(8), dp(6), dp(8), dp(6))
        setTextIsSelectable(true)
        container.addView(this)
    }

    fun row(
        title: CharSequence,
        value: CharSequence? = null,
        subtitle: CharSequence? = null,
        onLongClick: ((Row) -> Unit)? = null,
        onClick: ((Row) -> Unit)? = null,
    ): Row {
        val b = ItemWpRowBinding.inflate(inflater, container, false)
        val row = Row(b)
        row.title = title
        row.value = value
        row.subtitle = subtitle
        if (onClick != null) b.root.setOnClickListener { onClick(row) } else {
            b.root.isClickable = false
            b.root.background = null
        }
        if (onLongClick != null) b.root.setOnLongClickListener { onLongClick(row); true }
        container.addView(b.root)
        return row
    }

    /** On/Off row; the value text mirrors upstream's settings style. */
    fun toggle(title: CharSequence, checked: Boolean, subtitle: CharSequence? = null, onChange: (Boolean) -> Unit): Row {
        var state = checked
        val row = row(title, onOff(state), subtitle) { r ->
            state = !state
            r.value = onOff(state)
            onChange(state)
        }
        row.binding.root.contentDescription = "$title, ${onOff(state)}"
        return row
    }

    fun onOff(on: Boolean): String = context.getString(if (on) R.string.on else R.string.off)

    fun add(view: View): View {
        container.addView(view)
        return view
    }

    fun space(heightDp: Int = 8) {
        container.addView(View(context), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)))
    }

    fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun TextView.markHeading() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) isAccessibilityHeading = true
    }
}

/** Single-line (or multi-line) text prompt in the app's dialog style. */
fun Context.promptText(
    @StringRes title: Int,
    initial: String = "",
    @StringRes hint: Int = 0,
    multiLine: Boolean = false,
    numeric: Boolean = false,
    onDone: (String) -> Unit,
) {
    lateinit var field: EditText
    createDialog(title, R.string.okay, onAction = { onDone(field.text.toString()) }, content = { parent ->
        EditText(parent.context).apply {
            field = this
            setText(initial)
            setSelection(initial.length)
            if (hint != 0) setHint(hint)
            inputType = when {
                numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                multiLine -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            if (!multiLine) maxLines = 1
            styleInput()
            layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (16 * resources.displayMetrics.density).toInt() }
        }
    }).showRespectingStatusBar()
}

/** Simple choice list in the app's dialog style. */
fun Context.chooseOne(@StringRes title: Int, options: List<CharSequence>, onChoose: (Int) -> Unit) {
    lateinit var dialog: app.olauncher.helper.OlDialog
    dialog = createDialog(title, R.string.close, content = { parent ->
        LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            val builder = PageBuilder(this)
            options.forEachIndexed { i, label ->
                builder.row(label) {
                    onChoose(i)
                    dialog.dismiss()
                }
            }
            layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (12 * resources.displayMetrics.density).toInt() }
        }
    })
    dialog.showRespectingStatusBar()
}

/** Theme-aware text colors for EditTexts created in code (matches upstream's primaryColor tokens). */
fun EditText.styleInput() {
    setTextColor(context.getColorFromAttr(R.attr.primaryColor))
    setHintTextColor(context.getColorFromAttr(R.attr.primaryColorTrans50))
}
