package app.olauncher.features.space.ui

import android.graphics.Paint
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import app.olauncher.core.ui.chooseOne
import app.olauncher.core.waypoint
import app.olauncher.features.assistant.ui.AssistantSettingsFragment
import app.olauncher.features.assistant.ui.ChatActivity
import app.olauncher.features.assistant.ui.ChatStyle
import app.olauncher.features.space.data.Goal
import app.olauncher.features.space.data.Note
import app.olauncher.features.space.data.Task
import app.olauncher.features.space.domain.Goals
import app.olauncher.features.space.platform.Recorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Space: one place for voice notes, written notes, screenshots, to-dos, goals and reminders, sorted into
 * categories automatically. Record is one tap; the Home pin opens here ready to record.
 */
class SpaceFragment : Fragment() {

    private enum class Filter(val label: Int) { ALL(R.string.wp_filter_all), VOICE(R.string.wp_filter_voice), NOTES(R.string.wp_filter_notes),
        SCREENS(R.string.wp_filter_screens), TODO(R.string.wp_filter_todo), GOALS(R.string.wp_filter_goals) }

    private sealed class Row {
        data class Header(val text: String) : Row()
        data class NoteRow(val note: Note) : Row()
        data class TaskRow(val task: Task) : Row()
        data class GoalRow(val goal: Goal, val progress: Goals.Progress) : Row()
    }

    private lateinit var style: ChatStyle
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var record: ImageView
    private lateinit var timer: TextView
    private lateinit var meter: LevelMeter
    private lateinit var filters: LinearLayout
    private lateinit var categories: LinearLayout
    private var rows: List<Row> = emptyList()
    private var filter = Filter.ALL
    private var category: String? = null
    private var notes: List<Note> = emptyList()
    private var tasks: List<Task> = emptyList()
    private var goals: List<Goal> = emptyList()

    private val space get() = requireContext().waypoint.space

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewLifecycleOwner.lifecycleScope.launch { space.importImage(uri, screenshot = false) }
    }

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        style = ChatStyle(ctx)
        val root = FrameLayout(ctx).apply { if (!style.glass) setBackgroundColor(style.background) }
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        val header = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(style.dp(20), 0, style.dp(8), style.dp(6)) }
        header.addView(style.tabs(space = true) { (activity as ChatActivity).showTab(it) }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(style.action(getString(R.string.wp_settings_short)) { (activity as ChatActivity).show(AssistantSettingsFragment()) })
        column.addView(header)

        filters = LinearLayout(ctx).apply { setPadding(style.dp(14), 0, style.dp(14), 0) }
        column.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(filters) })
        categories = LinearLayout(ctx).apply { setPadding(style.dp(14), style.dp(6), style.dp(14), style.dp(4)) }
        column.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(categories) })

        empty = style.caption(getString(R.string.wp_space_empty)).apply { gravity = Gravity.CENTER; setPadding(style.dp(32), style.dp(40), style.dp(32), 0) }
        column.addView(empty)
        list = RecyclerView(ctx).apply { layoutManager = LinearLayoutManager(ctx); adapter = Adapter(); clipToPadding = false; itemAnimator = null }
        column.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        // bottom bar: write · RECORD · add
        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, style.dp(6), 0, style.dp(10)) }
        meter = LevelMeter(ctx).apply { isVisible = false }
        bar.addView(meter, LinearLayout.LayoutParams(style.dp(220), style.dp(32)))
        timer = style.caption("").apply { textSize = 14f; isVisible = false; setPadding(0, style.dp(2), 0, style.dp(6)) }
        bar.addView(timer)
        val buttons = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        buttons.addView(side(R.drawable.ic_note, R.string.wp_space_write) { (activity as ChatActivity).show(TextNoteFragment.newInstance(0)) })
        record = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER
            background = style.rounded(0xFFD71921.toInt(), 100)
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            setOnClickListener { toggle() }
        }
        buttons.addView(record, LinearLayout.LayoutParams(style.dp(76), style.dp(76)).apply { marginStart = style.dp(28); marginEnd = style.dp(28) })
        buttons.addView(side(R.drawable.ic_plus, R.string.wp_space_add) { addMenu() })
        bar.addView(buttons)
        column.addView(bar)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            column.setPadding(0, bars.top + style.dp(16), 0, bars.bottom)
            list.setPadding(style.dp(8), 0, style.dp(8), style.dp(8))
            insets
        }
        return root
    }

    private fun side(icon: Int, label: Int, onClick: () -> Unit) = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        addView(ImageView(context).apply {
            setImageResource(icon); scaleType = ImageView.ScaleType.CENTER
            imageTintList = android.content.res.ColorStateList.valueOf(style.ink)
            background = style.rounded(0, 100, style.hairline)
        }, LinearLayout.LayoutParams(style.dp(52), style.dp(52)))
        addView(style.caption(getString(label)).apply { setPadding(0, style.dp(4), 0, 0) })
        contentDescription = getString(label)
        setOnClickListener { onClick() }
        getChildAt(0).setOnClickListener { onClick() }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
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

    private fun addMenu() {
        val ctx = requireContext()
        ctx.chooseOne(R.string.wp_space_add, listOf(getString(R.string.wp_add_todo), getString(R.string.wp_add_goal), getString(R.string.wp_add_image))) { i ->
            when (i) {
                0 -> SpaceEditors.task(this, null)
                1 -> SpaceEditors.goal(this)
                else -> pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
    }

    private fun bindRecorder(s: Recorder.State) {
        record.setImageResource(if (s.recording) R.drawable.ic_stop else R.drawable.ic_mic)
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
        val repo = space.notes
        notes = repo.all(); tasks = repo.tasks(); goals = repo.goals()
        if (view == null) return
        render()
    }

    private fun render() {
        // filter chips
        filters.removeAllViews()
        Filter.values().forEach { f -> filters.addView(chip(getString(f.label), f == filter) { filter = f; render() }) }
        // category chips: only categories that have something
        val cats = (notes.map { it.category } + tasks.map { it.category }).filter { it.isNotBlank() }.distinct().sorted()
        categories.removeAllViews()
        categories.isVisible = cats.isNotEmpty()
        if (category != null && category !in cats) category = null
        cats.forEach { c -> categories.addView(chip(c, c == category, small = true) { category = if (category == c) null else c; render() }) }

        val now = System.currentTimeMillis()
        fun inCat(c: String) = category == null || c == category
        val out = ArrayList<Row>()
        val open = tasks.filter { inCat(it.category) && (!it.done || filter == Filter.TODO) }
        if ((filter == Filter.ALL || filter == Filter.TODO) && open.isNotEmpty()) {
            out += Row.Header(getString(R.string.wp_filter_todo)); open.forEach { out += Row.TaskRow(it) }
        }
        if ((filter == Filter.ALL && category == null || filter == Filter.GOALS) && goals.isNotEmpty()) {
            out += Row.Header(getString(R.string.wp_filter_goals))
            goals.forEach { g -> out += Row.GoalRow(g, Goals.progress(g.period, g.target, g.checkins, now)) }
        }
        val shown = notes.filter { n ->
            inCat(n.category) && when (filter) {
                Filter.ALL -> true; Filter.VOICE -> n.kind == Note.Kind.VOICE; Filter.NOTES -> n.kind == Note.Kind.TEXT
                Filter.SCREENS -> n.kind == Note.Kind.SCREENSHOT; else -> false
            }
        }
        if (shown.isNotEmpty()) {
            if (filter == Filter.ALL && out.isNotEmpty()) out += Row.Header(getString(R.string.wp_filter_notes_all))
            shown.forEach { out += Row.NoteRow(it) }
        }
        rows = out
        list.adapter?.notifyDataSetChanged()
        empty.isVisible = rows.isEmpty()
    }

    private fun chip(text: String, on: Boolean, small: Boolean = false, onClick: () -> Unit) = TextView(requireContext(), null, 0, R.style.TextSmall).apply {
        this.text = text
        textSize = if (small) 12f else 13f
        setTextColor(if (on) 0xFFFFFFFF.toInt() else style.ink)
        background = if (on) style.rounded(0xFFD71921.toInt(), 100) else style.rounded(0, 100, style.hairline)
        setPadding(style.dp(14), style.dp(if (small) 5 else 8), style.dp(14), style.dp(if (small) 5 else 8))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = style.dp(6) }
        setOnClickListener { onClick() }
    }

    // ---- list

    private inner class Holder(val root: LinearLayout) : RecyclerView.ViewHolder(root)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = when (rows[position]) { is Row.Header -> 0; is Row.NoteRow -> 1; is Row.TaskRow -> 2; is Row.GoalRow -> 3 }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(LinearLayout(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(-1, -2)
        })

        override fun onBindViewHolder(h: Holder, position: Int) {
            h.root.removeAllViews()
            h.root.setOnClickListener(null); h.root.setOnLongClickListener(null)
            h.root.background = null
            h.root.setPadding(0, 0, 0, 0)
            when (val r = rows[position]) {
                is Row.Header -> h.root.addView(style.caption(r.text.uppercase()).apply { letterSpacing = 0.14f; setPadding(style.dp(14), style.dp(16), 0, style.dp(6)) })
                is Row.NoteRow -> bindNote(h.root, r.note)
                is Row.TaskRow -> bindTask(h.root, r.task)
                is Row.GoalRow -> bindGoal(h.root, r.goal, r.progress)
            }
        }
    }

    private fun card(root: LinearLayout) {
        root.background = style.rounded(style.assistantBubble, 18, style.hairline)
        root.setPadding(style.dp(14), style.dp(12), style.dp(14), style.dp(12))
        (root.layoutParams as RecyclerView.LayoutParams).setMargins(style.dp(8), style.dp(4), style.dp(8), style.dp(4))
        root.gravity = Gravity.CENTER_VERTICAL
    }

    private fun bindNote(root: LinearLayout, n: Note) {
        card(root)
        val ctx = root.context
        root.addView(ImageView(ctx).apply {
            setImageResource(when (n.kind) { Note.Kind.VOICE -> R.drawable.ic_mic; Note.Kind.TEXT -> R.drawable.ic_note; Note.Kind.SCREENSHOT -> R.drawable.ic_image })
            imageTintList = android.content.res.ColorStateList.valueOf(style.faint)
        }, LinearLayout.LayoutParams(style.dp(22), style.dp(22)).apply { marginEnd = style.dp(12) })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(ctx, null, 0, R.style.TextMedium).apply {
            text = n.displayTitle; setTextColor(style.ink); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        })
        val when_ = DateUtils.getRelativeTimeSpanString(n.created, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
        val status = when (n.status) {
            Note.Status.TRANSCRIBING -> getString(if (n.kind == Note.Kind.SCREENSHOT) R.string.wp_space_reading else R.string.wp_space_transcribing)
            Note.Status.SUMMARIZING -> getString(R.string.wp_space_summarizing)
            Note.Status.ERROR -> getString(R.string.wp_space_failed)
            Note.Status.RECORDED -> getString(R.string.wp_space_not_transcribed)
            Note.Status.DONE -> null
        }
        val dur = if (n.kind == Note.Kind.VOICE) (n.durationMs / 1000).let { "%d:%02d".format(it / 60, it % 60) } else null
        col.addView(style.caption(listOfNotNull(n.category.ifBlank { null }, when_.toString(), dur, status).joinToString(" · ")).apply {
            setPadding(0, style.dp(2), 0, 0)
            setTextColor(if (n.status == Note.Status.ERROR) 0xFFFF6B5E.toInt() else style.faint)
        })
        val preview = n.summary.ifBlank { n.body.ifBlank { n.transcript } }.replace(Regex("\\s+"), " ")
        if (preview.isNotBlank()) col.addView(TextView(ctx, null, 0, R.style.TextSmall).apply {
            text = preview; setTextColor(style.faint); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, style.dp(4), 0, 0); textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        })
        root.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        n.images.firstOrNull()?.let { name ->
            thumb(space.imageFile(name))?.let { bmp ->
                root.addView(ImageView(ctx).apply {
                    setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                    background = style.rounded(style.codeBg, 10)
                }, LinearLayout.LayoutParams(style.dp(52), style.dp(52)).apply { marginStart = style.dp(10) })
            }
        }
        root.setOnClickListener {
            val a = activity as ChatActivity
            if (n.kind == Note.Kind.TEXT) a.show(TextNoteFragment.newInstance(n.id)) else a.show(NoteFragment.newInstance(n.id))
        }
    }

    private val thumbs = android.util.LruCache<String, android.graphics.Bitmap>(30)
    private fun thumb(file: java.io.File): android.graphics.Bitmap? = thumbs.get(file.name) ?: run {
        if (!file.exists()) return null
        val b = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, b)
        var s = 1; while (maxOf(b.outWidth, b.outHeight) / (s * 2) >= 160) s *= 2
        android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = s })?.also { thumbs.put(file.name, it) }
    }

    private fun bindTask(root: LinearLayout, t: Task) {
        card(root)
        val ctx = root.context
        val check = ImageView(ctx).apply {
            setImageResource(if (t.done) R.drawable.ic_check_circle else R.drawable.ic_circle)
            imageTintList = android.content.res.ColorStateList.valueOf(if (t.done) 0xFFD71921.toInt() else style.ink)
            setOnClickListener { viewLifecycleOwner.lifecycleScope.launch { space.setTaskDone(t.id, !t.done) } }
            contentDescription = getString(if (t.done) R.string.wp_task_undo else R.string.wp_task_done)
        }
        root.addView(check, LinearLayout.LayoutParams(style.dp(30), style.dp(30)).apply { marginEnd = style.dp(10) })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(ctx, null, 0, R.style.TextMedium).apply {
            text = t.title; setTextColor(if (t.done) style.faint else style.ink); textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            paintFlags = if (t.done) paintFlags or Paint.STRIKE_THRU_TEXT_FLAG else paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        })
        if (t.due > 0) {
            val overdue = !t.done && t.due < System.currentTimeMillis()
            val label = DateUtils.formatDateTime(ctx, t.due, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL or DateUtils.FORMAT_SHOW_WEEKDAY)
            col.addView(style.caption((if (t.remind) "🔔 " else "") + label + if (t.category.isNotBlank()) " · ${t.category}" else "").apply {
                setTextColor(if (overdue) 0xFFFF6B5E.toInt() else style.faint); setPadding(0, style.dp(2), 0, 0)
            })
        } else if (t.category.isNotBlank()) col.addView(style.caption(t.category).apply { setPadding(0, style.dp(2), 0, 0) })
        root.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        root.setOnClickListener { SpaceEditors.task(this, t) }
    }

    private fun bindGoal(root: LinearLayout, g: Goal, p: Goals.Progress) {
        card(root)
        val ctx = root.context
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(ctx, null, 0, R.style.TextMedium).apply { text = g.title; setTextColor(style.ink); textDirection = View.TEXT_DIRECTION_FIRST_STRONG })
        col.addView(GoalDots(ctx, p.done, p.target).apply { }, LinearLayout.LayoutParams(-1, style.dp(16)).apply { topMargin = style.dp(8) })
        val per = getString(when (g.period) {
            app.olauncher.features.space.domain.GoalPeriod.DAY -> R.string.wp_goal_today
            app.olauncher.features.space.domain.GoalPeriod.WEEK -> R.string.wp_goal_this_week
            app.olauncher.features.space.domain.GoalPeriod.MONTH -> R.string.wp_goal_this_month
            app.olauncher.features.space.domain.GoalPeriod.TOTAL -> R.string.wp_goal_total
        })
        col.addView(style.caption("${p.done}/${p.target} $per" + if (p.streak > 0) " · ${getString(R.string.wp_goal_streak, p.streak)}" else "").apply { setPadding(0, style.dp(6), 0, 0) })
        root.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(TextView(ctx, null, 0, R.style.TextMedium).apply {
            text = "+1"; gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt())
            background = style.rounded(if (p.complete) 0xFF555555.toInt() else 0xFFD71921.toInt(), 100)
            setOnClickListener { viewLifecycleOwner.lifecycleScope.launch { space.checkIn(g.id) } }
            contentDescription = getString(R.string.wp_goal_checkin)
        }, LinearLayout.LayoutParams(style.dp(52), style.dp(40)).apply { marginStart = style.dp(10) })
        root.setOnClickListener { SpaceEditors.goalOptions(this, g) }
    }
}

/** A tiny scrolling bar meter of the input level, Nothing-style. */
internal class LevelMeter(context: android.content.Context) : View(context) {
    private val levels = FloatArray(28)
    private var head = 0
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD71921.toInt() }

    fun push(level: Float) { levels[head] = level; head = (head + 1) % levels.size; invalidate() }

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

/** Goal progress as a row of dots (filled = done), Nothing-style. */
internal class GoalDots(context: android.content.Context, private val done: Int, private val target: Int) : View(context) {
    private val on = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD71921.toInt() }
    private val off = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x55888888 }
    override fun onDraw(canvas: android.graphics.Canvas) {
        val n = target.coerceIn(1, 30)
        val filled = if (target > 30) (done * 30 / target) else done
        val step = (width.toFloat() / n).coerceAtMost(height * 1.6f)
        val r = (minOf(step, height.toFloat()) * 0.36f)
        for (i in 0 until n) canvas.drawCircle(step * (i + 0.5f), height / 2f, r, if (i < filled) on else off)
    }
}
