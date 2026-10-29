package app.olauncher.features.space.ui

import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.core.waypoint
import app.olauncher.features.assistant.ui.AssistantSettingsFragment
import app.olauncher.features.assistant.ui.ChatActivity
import app.olauncher.features.assistant.ui.ChatStyle
import app.olauncher.features.space.data.Note
import app.olauncher.features.space.platform.Recorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Space: record a voice note with one tap; it's transcribed and summarised into a searchable list. */
class SpaceFragment : Fragment() {

    private lateinit var style: ChatStyle
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var record: TextView
    private lateinit var timer: TextView
    private lateinit var meter: LevelMeter
    private var items: List<Note> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        style = ChatStyle(ctx)
        val root = FrameLayout(ctx).apply { if (!style.glass) setBackgroundColor(style.background) }
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        val header = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(style.dp(20), 0, style.dp(8), style.dp(8)) }
        header.addView(style.tabs(space = true) { (activity as ChatActivity).showTab(it) }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(style.action(getString(R.string.wp_settings_short)) { (activity as ChatActivity).show(AssistantSettingsFragment()) })
        column.addView(header)

        empty = style.caption(getString(R.string.wp_space_empty)).apply { gravity = Gravity.CENTER; setPadding(style.dp(32), style.dp(48), style.dp(32), 0) }
        column.addView(empty)

        list = RecyclerView(ctx).apply { layoutManager = LinearLayoutManager(ctx); adapter = Adapter(); clipToPadding = false }
        column.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        // recorder bar: level meter + timer + the big red button
        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, style.dp(8), 0, style.dp(12)) }
        meter = LevelMeter(ctx).apply { isVisible = false }
        bar.addView(meter, LinearLayout.LayoutParams(style.dp(220), style.dp(36)))
        timer = style.caption("").apply { textSize = 14f; isVisible = false; setPadding(0, style.dp(2), 0, style.dp(8)) }
        bar.addView(timer)
        record = TextView(ctx, null, 0, R.style.TextLarge).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 24f
            background = style.rounded(0xFFD71921.toInt(), 100)
            setOnClickListener { toggle() }
        }
        bar.addView(record, LinearLayout.LayoutParams(style.dp(76), style.dp(76)))
        column.addView(bar)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            column.setPadding(0, bars.top + style.dp(16), 0, bars.bottom)
            list.setPadding(style.dp(8), 0, style.dp(8), style.dp(8))
            insets
        }
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val space = requireContext().waypoint.space
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Recorder.state.collect { bindRecorder(it) } }
                launch { while (true) { tick(); delay(500) } }
                reload()
                space.changes.collect { reload() }
            }
        }
    }

    private fun toggle() {
        val a = activity as ChatActivity
        if (Recorder.state.value.recording) a.stopRecording() else a.startRecording()
    }

    private fun bindRecorder(s: Recorder.State) {
        record.text = if (s.recording) "■" else "●"
        record.contentDescription = getString(if (s.recording) R.string.wp_space_stop else R.string.wp_space_record)
        meter.isVisible = s.recording
        timer.isVisible = s.recording
        meter.push(s.level)
        tick()
    }

    private fun tick() {
        val s = Recorder.state.value
        if (!s.recording) return
        val sec = (System.currentTimeMillis() - s.startedAt) / 1000
        timer.text = "%02d:%02d".format(sec / 60, sec % 60)
    }

    private suspend fun reload() {
        items = requireContext().waypoint.space.notes.all()
        if (view == null) return
        list.adapter?.notifyDataSetChanged()
        empty.isVisible = items.isEmpty()
    }

    private inner class Holder(val root: LinearLayout, val title: TextView, val meta: TextView, val preview: TextView) : RecyclerView.ViewHolder(root)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(style.dp(16), style.dp(14), style.dp(16), style.dp(14))
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(style.dp(8), style.dp(4), style.dp(8), style.dp(4)) }
                background = style.rounded(style.assistantBubble, 18, style.hairline)
                isClickable = true; isFocusable = true
            }
            val title = TextView(ctx, null, 0, R.style.TextMedium).apply {
                setTextColor(style.ink); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            }
            val meta = style.caption()
            val preview = TextView(ctx, null, 0, R.style.TextSmall).apply {
                setTextColor(style.faint); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, style.dp(4), 0, 0); textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            }
            root.addView(title); root.addView(meta); root.addView(preview)
            return Holder(root, title, meta, preview)
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val n = items[position]
            h.title.text = n.displayTitle
            val sec = n.durationMs / 1000
            val status = when (n.status) {
                Note.Status.TRANSCRIBING -> " · " + getString(R.string.wp_space_transcribing)
                Note.Status.SUMMARIZING -> " · " + getString(R.string.wp_space_summarizing)
                Note.Status.ERROR -> " · " + getString(R.string.wp_space_failed)
                Note.Status.RECORDED -> " · " + getString(R.string.wp_space_not_transcribed)
                Note.Status.DONE -> ""
            }
            h.meta.text = "${DateUtils.getRelativeTimeSpanString(n.created, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)}" +
                " · %d:%02d".format(sec / 60, sec % 60) + status
            h.meta.setTextColor(if (n.status == Note.Status.ERROR) 0xFFFF6B5E.toInt() else style.faint)
            val text = n.summary.ifBlank { n.transcript }.replace(Regex("\\s+"), " ")
            h.preview.text = text
            h.preview.isVisible = text.isNotBlank()
            h.root.setOnClickListener { (activity as ChatActivity).show(NoteFragment.newInstance(n.id)) }
        }
    }
}

/** A tiny scrolling bar meter of the input level, Nothing-style dots. */
internal class LevelMeter(context: android.content.Context) : View(context) {
    private val levels = FloatArray(28)
    private var head = 0
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD71921.toInt() }

    fun push(level: Float) {
        levels[head] = level; head = (head + 1) % levels.size
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val n = levels.size
        val step = width.toFloat() / n
        val r = step * 0.32f
        for (i in 0 until n) {
            val v = levels[(head + i) % n]
            val h = (height * (0.12f + 0.88f * kotlin.math.sqrt(v))).coerceAtLeast(r * 2)
            val x = step * (i + 0.5f)
            canvas.drawRoundRect(x - r, (height - h) / 2, x + r, (height + h) / 2, r, r, paint)
        }
    }
}
