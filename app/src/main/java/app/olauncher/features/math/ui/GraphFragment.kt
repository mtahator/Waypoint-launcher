package app.olauncher.features.math.ui

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.core.ui.focusAndShowKeyboard
import app.olauncher.core.waypoint
import app.olauncher.features.appearance.ui.GlassStyler
import app.olauncher.features.math.domain.MathProgram
import app.olauncher.features.quickanswers.domain.QuickAnswers
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.isDarkThemeOn
import app.olauncher.ui.BaseFragment
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Graphing calculator in the spirit of Desmos: a plane on top, a list of expressions below.
 * Each line is live: `y = a sin(bx)`, `f(x) = …`, `x^2 + y^2 = r^2`, `(2, 3)`, `a = 1` (a slider).
 * Undefined letters get one-tap "add slider" chips. Lines are saved as you type.
 */
class GraphFragment : BaseFragment() {

    private class Row(
        val root: LinearLayout, val dot: View, val field: EditText, val caption: TextView,
        val chips: LinearLayout, val sliderBox: LinearLayout, val slider: SeekBar,
        val minLabel: TextView, val maxLabel: TextView,
    ) {
        var sliderMin = -10.0
        var sliderMax = 10.0
        var settingText = false
    }

    private val rows = mutableListOf<Row>()
    private val hidden = mutableSetOf<Int>()
    private lateinit var graph: GraphView
    private lateinit var list: LinearLayout
    private var program: MathProgram? = null
    private var glass = false
    private var dark = true
    private var ink = 0
    private var focusedField: EditText? = null
    private lateinit var scroll: ScrollView

    /** Scrolls so the focused row sits at the top of the list, above the keyboard. */
    private fun revealFocused(scroll: ScrollView) {
        val f = focusedField ?: return
        val row = rows.firstOrNull { it.field === f }?.root ?: return
        scroll.smoothScrollTo(0, (row.top - dp(4)).coerceAtLeast(0))
    }

    private val prefs get() = requireContext().waypoint.prefs

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        glass = prefs.glassTheme
        dark = ctx.isDarkThemeOn()
        ink = ctx.getColorFromAttr(R.attr.primaryColor)

        val root = FrameLayout(ctx).apply {
            if (!glass) setBackgroundColor(ctx.getColorFromAttr(R.attr.primaryShadeDarkColor))
        }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(44), dp(12), dp(8))
        }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        // Header: title + actions
        val header = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(4), dp(10)) }
        val title = TextView(ctx, null, 0, R.style.TextLarge).apply { setText(R.string.wp_graph_title) }
        if (glass) { GlassStyler.styleClock(title); title.isAllCaps = true; title.textSize = 26f }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(action(R.string.wp_graph_fit) { graph.resetView() })
        header.addView(action(R.string.wp_graph_clear) { setLines(listOf("")); hidden.clear(); recompute() })
        column.addView(header)

        graph = GraphView(ctx).apply {
            dark = this@GraphFragment.dark
            panelColor = when {
                glass && dark -> 0xB3000000.toInt()
                glass -> 0xCCFFFFFF.toInt()
                dark -> 0x14FFFFFF
                else -> 0x0A000000
            }
            contentDescription = getString(R.string.wp_graph_title)
        }
        column.addView(graph, LinearLayout.LayoutParams(-1, 0, 1.15f))

        column.addView(keyBar(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        scroll = ScrollView(ctx).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(24)) }
        scroll.addView(list)
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val add = action(R.string.wp_graph_add) { addRow(rows.size, "", focus = true); persistAndRecompute() }
        add.setPadding(dp(16), dp(12), dp(16), dp(12))
        list.addView(add)

        // From the search box: show only that equation (a fresh sheet); from Settings: the saved sheet
        // (only on first creation: after rotation the saved sheet already holds the edits)
        val fromSearch = arguments?.getStringArrayList(ARG_LINES)?.takeIf { it.isNotEmpty() && savedInstanceState == null }
        val lines: List<String> = fromSearch ?: prefs.graphLines
        setLines(lines.ifEmpty { listOf("") })
        if (fromSearch != null) prefs.graphLines = lines
        recompute()

        // Edge-to-edge (Android 15+ ignores adjustResize): make room for status bar and keyboard ourselves,
        // and shrink the plane while typing so the line being edited stays visible above the keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val typing = insets.isVisible(WindowInsetsCompat.Type.ime())
            column.setPadding(dp(12), bars.top + dp(8), dp(12), max(ime.bottom, bars.bottom) + dp(4))
            (graph.layoutParams as LinearLayout.LayoutParams).let { lp ->
                val w = if (typing) 0.62f else 1.15f
                if (lp.weight != w) { lp.weight = w; graph.layoutParams = lp }
            }
            if (typing) scroll.post { revealFocused(scroll) }
            insets
        }
        return root
    }

    override fun onResume() {
        super.onResume()
        @Suppress("DEPRECATION")
        requireActivity().window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    override fun onPause() {
        @Suppress("DEPRECATION")
        requireActivity().window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        super.onPause()
    }

    // ---- rows

    private fun setLines(lines: List<String>) {
        rows.forEach { list.removeView(it.root) }
        rows.clear()
        lines.forEachIndexed { i, t -> addRow(i, t, focus = false) }
    }

    private fun addRow(index: Int, text: String, focus: Boolean) {
        val ctx = requireContext()
        val rootRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        val line = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        val dot = View(ctx)
        line.addView(dot, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(8); marginEnd = dp(12) })
        val field = EditText(ctx).apply {
            setText(text)
            setTextColor(ink)
            setHintTextColor((ink and 0x00FFFFFF) or 0x55000000)
            setHint(R.string.wp_graph_hint)
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            isSingleLine = true
            textDirection = View.TEXT_DIRECTION_LTR
            background = fieldBackground()
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setShadowLayer(0f, 0f, 0f, 0)
        }
        line.addView(field, LinearLayout.LayoutParams(0, -2, 1f))
        val delete = TextView(ctx, null, 0, R.style.TextMedium).apply {
            this.text = "×"; setTextColor(ink); alpha = 0.6f
            setPadding(dp(14), dp(6), dp(10), dp(6))
            contentDescription = getString(R.string.wp_delete)
        }
        line.addView(delete)
        rootRow.addView(line)

        val caption = TextView(ctx, null, 0, R.style.TextSmall).apply {
            setPadding(dp(46), dp(4), dp(8), 0); typeface = Typeface.MONOSPACE; textSize = 13f; isVisible = false
        }
        rootRow.addView(caption)
        val chips = LinearLayout(ctx).apply { setPadding(dp(42), dp(4), 0, 0); isVisible = false }
        rootRow.addView(chips)

        val sliderBox = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(42), 0, dp(8), 0); isVisible = false }
        val minLabel = smallLabel()
        val maxLabel = smallLabel()
        val slider = SeekBar(ctx).apply { max = 1000 }
        sliderBox.addView(minLabel)
        sliderBox.addView(slider, LinearLayout.LayoutParams(0, -2, 1f))
        sliderBox.addView(maxLabel)
        rootRow.addView(sliderBox)

        val row = Row(rootRow, dot, field, caption, chips, sliderBox, slider, minLabel, maxLabel)
        rows.add(index, row)
        list.addView(rootRow, index)

        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) { if (!row.settingText) persistAndRecompute() }
        })
        field.setOnFocusChangeListener { v, has ->
            if (has) { focusedField = v as EditText; scroll.post { revealFocused(scroll) } }
        }
        field.setOnEditorActionListener { _, action, event ->
            val enter = action == EditorInfo.IME_ACTION_NEXT || event?.keyCode == KeyEvent.KEYCODE_ENTER
            if (enter) { addRow(rows.indexOf(row) + 1, "", focus = true); persistAndRecompute() }
            enter
        }
        delete.setOnClickListener {
            val i = rows.indexOf(row)
            if (rows.size == 1) { field.setText(""); return@setOnClickListener }
            rows.removeAt(i); list.removeView(rootRow)
            // shift hidden indexes after the deleted row
            val shifted = hidden.filter { it != i }.map { if (it > i) it - 1 else it }
            hidden.clear(); hidden.addAll(shifted)
            persistAndRecompute()
        }
        dot.setOnClickListener {
            val i = rows.indexOf(row)
            if (!hidden.add(i)) hidden.remove(i)
            recompute()
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val name = program?.lines?.getOrNull(rows.indexOf(row))?.sliderName ?: return
                val v = row.sliderMin + (row.sliderMax - row.sliderMin) * p / 1000.0
                val step = niceStep(row.sliderMax - row.sliderMin)
                val snapped = (v / step).roundToInt() * step
                row.settingText = true
                field.setText("$name = ${QuickAnswers.plain(snapped, 6)}")
                row.settingText = false
                persistAndRecompute()
            }
            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) = Unit
        })
        if (focus) field.focusAndShowKeyboard()
    }

    private fun persistAndRecompute() {
        prefs.graphLines = rows.map { it.field.text.toString() }
        recompute()
    }

    private fun recompute() {
        val p = MathProgram(rows.map { it.field.text.toString() })
        program = p
        graph.hidden = hidden.toSet()
        graph.program = p
        p.lines.forEachIndexed { i, line -> bind(rows[i], i, line) }
    }

    private fun bind(row: Row, index: Int, line: MathProgram.Line) {
        val color = graph.colorFor(index)
        val off = index in hidden
        row.dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (line.plottable && !off) setColor(color) else setColor(0)
            setStroke(dp(2), if (line.plottable) color else (ink and 0x00FFFFFF) or 0x40000000)
        }
        row.dot.alpha = if (line.kind == MathProgram.Kind.EMPTY) 0.4f else 1f

        val captionText: String? = when (line.kind) {
            MathProgram.Kind.ERROR -> line.error
            MathProgram.Kind.VALUE -> line.error ?: "= ${QuickAnswers.format(line.value ?: Double.NaN)}"
            MathProgram.Kind.VARIABLE -> if (line.sliderName == null && line.value != null) "= ${QuickAnswers.format(line.value)}" else null
            MathProgram.Kind.POINT -> line.point?.let { "(${QuickAnswers.format(it.first, 6)}, ${QuickAnswers.format(it.second, 6)})" }
            else -> null
        }
        row.caption.isVisible = captionText != null
        row.caption.text = captionText
        row.caption.setTextColor(if (line.kind == MathProgram.Kind.ERROR) 0xFFFF6B5E.toInt() else ink)
        row.caption.alpha = if (line.kind == MathProgram.Kind.ERROR) 1f else 0.75f

        // "add slider" chips for undefined letters
        row.chips.removeAllViews()
        row.chips.isVisible = line.missing.isNotEmpty()
        if (line.missing.isNotEmpty()) {
            row.chips.addView(smallLabel().apply { setText(R.string.wp_graph_add_slider) })
            line.missing.forEach { name ->
                row.chips.addView(chip(name) {
                    addRow(rows.size, "$name = 1", focus = false)
                    persistAndRecompute()
                })
            }
        }

        // slider
        val name = line.sliderName
        val v = line.value
        row.sliderBox.isVisible = name != null && v != null
        if (name != null && v != null) {
            if (v < row.sliderMin || v > row.sliderMax) {
                val m = max(10.0, niceCeil(abs(v) * 1.5))
                row.sliderMin = -m; row.sliderMax = m
            }
            row.minLabel.text = QuickAnswers.format(row.sliderMin, 4)
            row.maxLabel.text = QuickAnswers.format(row.sliderMax, 4)
            row.slider.progress = (((v - row.sliderMin) / (row.sliderMax - row.sliderMin)) * 1000).roundToInt()
        }
    }

    // ---- key bar

    private fun keyBar(): View {
        val ctx = requireContext()
        val scroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val bar = LinearLayout(ctx).apply { setPadding(dp(4), 0, dp(4), 0) }
        KEYS.forEach { (label, insert) -> bar.addView(chip(label) { insert(insert) }) }
        scroll.addView(bar)
        return scroll
    }

    private fun insert(text: String) {
        val f = focusedField ?: rows.lastOrNull()?.field ?: return
        if (!f.hasFocus()) f.requestFocus()
        val start = f.selectionStart.coerceAtLeast(0)
        val end = f.selectionEnd.coerceAtLeast(0)
        f.text.replace(minOf(start, end), maxOf(start, end), text)
        // put the cursor inside "()" for functions
        if (text.endsWith("()")) f.setSelection(minOf(start, end) + text.length - 1)
    }

    // ---- small views

    private fun action(text: Int, onClick: () -> Unit) = TextView(requireContext(), null, 0, R.style.TextSmall).apply {
        setText(text); isAllCaps = true; setTextColor(ink)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        if (glass) GlassStyler.styleCaption(this)
        setOnClickListener { onClick() }
    }

    private fun chip(text: String, onClick: () -> Unit) = TextView(requireContext(), null, 0, R.style.TextSmall).apply {
        this.text = text
        setTextColor(ink)
        typeface = Typeface.MONOSPACE
        textSize = 15f
        gravity = Gravity.CENTER
        minWidth = dp(44)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply {
            cornerRadius = dp(100).toFloat()
            setColor((ink and 0x00FFFFFF) or 0x1A000000)
        }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }
        setOnClickListener { onClick() }
    }

    private fun smallLabel() = TextView(requireContext(), null, 0, R.style.TextSmall).apply {
        typeface = Typeface.MONOSPACE; textSize = 12f; setTextColor(ink); alpha = 0.6f
        setPadding(dp(4), 0, dp(8), 0)
    }

    private fun fieldBackground() = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(if (dark) 0x26000000 else 0x14000000)
        setStroke(dp(1).coerceAtLeast(1), (ink and 0x00FFFFFF) or 0x33000000)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        const val ARG_LINES = "lines"

        private val KEYS = listOf(
            "x" to "x", "y" to "y", "=" to "=", "^" to "^", "²" to "^2", "√" to "sqrt()", "(" to "(", ")" to ")",
            "π" to "pi", "e" to "e", "sin" to "sin()", "cos" to "cos()", "tan" to "tan()", "ln" to "ln()",
            "log" to "log()", "|x|" to "abs()", "a" to "a", "b" to "b", "," to ",", "°" to "°", "!" to "!",
        )

        private fun niceStep(span: Double): Double {
            val raw = span / 200
            val mag = Math.pow(10.0, Math.floor(Math.log10(raw)))
            return mag * when { raw / mag < 2 -> 1.0; raw / mag < 5 -> 2.0; else -> 5.0 }
        }

        private fun niceCeil(v: Double): Double {
            val mag = Math.pow(10.0, Math.floor(Math.log10(v)))
            return Math.ceil(v / mag) * mag
        }
    }
}
