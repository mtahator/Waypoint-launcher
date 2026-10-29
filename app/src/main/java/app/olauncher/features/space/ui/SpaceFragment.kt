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
import android.widget.EditText
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
import androidx.recyclerview.widget.GridLayoutManager
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
 * Space, laid out like a calm dashboard: a Today card (date, what's due), Next up to-dos, a strip of goal
 * rings, then captures grouped by day. Search covers everything; filters are icon tabs with counts;
 * screenshots show as a grid. The dock (Write · Record · Add) never moves, so recording is one tap away.
 */
class SpaceFragment : Fragment() {

    private enum class Filter(val label: Int, val icon: Int) {
        ALL(R.string.wp_filter_all, R.drawable.ic_wayfinder), VOICE(R.string.wp_filter_voice, R.drawable.ic_mic),
        NOTES(R.string.wp_filter_notes, R.drawable.ic_note), SCREENS(R.string.wp_filter_screens, R.drawable.ic_image),
        TODO(R.string.wp_filter_todo, R.drawable.ic_check_circle), GOALS(R.string.wp_filter_goals, R.drawable.ic_target),
    }

    private sealed class Row {
        data class Header(val text: String, val count: Int = -1) : Row()
        object Today : Row()
        data class TaskRow(val task: Task) : Row()
        data class GoalStrip(val goals: List<Pair<Goal, Goals.Progress>>) : Row()
        data class GoalCard(val goal: Goal, val progress: Goals.Progress) : Row()
        data class NoteRow(val note: Note) : Row()
        data class ShotTile(val note: Note) : Row()
        data class Empty(val text: String) : Row()
    }

    private lateinit var style: ChatStyle
    private lateinit var list: RecyclerView
    private lateinit var record: ImageView
    private lateinit var timer: TextView
    private lateinit var meter: LevelMeter
    private lateinit var filters: LinearLayout
    private lateinit var categories: LinearLayout
    private lateinit var search: EditText
    private var rows: List<Row> = emptyList()
    private var filter = Filter.ALL
    private var category: String? = null
    private var query = ""
    private var showDone = false
    private var notes: List<Note> = emptyList()
    private var tasks: List<Task> = emptyList()
    private var goals: List<Goal> = emptyList()

    private val space get() = requireContext().waypoint.space
    private val red = 0xFFD71921.toInt()

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewLifecycleOwner.lifecycleScope.launch { space.importImage(uri, screenshot = false) }
    }

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

        // search
        search = EditText(ctx).apply {
            setHint(R.string.wp_space_search); setTextColor(style.ink); setHintTextColor(style.faint)
            textSize = 15f; isSingleLine = true; textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            background = style.rounded(style.assistantBubble.takeIf { it != 0 } ?: style.codeBg, 100, style.hairline)
            setPadding(style.dp(18), style.dp(10), style.dp(18), style.dp(10))
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(e: android.text.Editable?) { query = e?.toString().orEmpty().trim(); render() }
            })
        }
        column.addView(search, LinearLayout.LayoutParams(-1, -2).apply { setMargins(style.dp(16), 0, style.dp(16), style.dp(10)) })

        filters = LinearLayout(ctx).apply { setPadding(style.dp(12), 0, style.dp(12), 0) }
        column.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(filters) })
        categories = LinearLayout(ctx).apply { setPadding(style.dp(14), style.dp(8), style.dp(14), 0) }
        column.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(categories) })

        list = RecyclerView(ctx).apply {
            layoutManager = GridLayoutManager(ctx, 2).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int) = if (rows.getOrNull(position) is Row.ShotTile) 1 else 2
                }
            }
            adapter = Adapter(); clipToPadding = false; itemAnimator = null
        }
        column.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        // dock: Write · RECORD · Add
        val dock = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, style.dp(6), 0, style.dp(10)) }
        meter = LevelMeter(ctx).apply { isVisible = false }
        dock.addView(meter, LinearLayout.LayoutParams(style.dp(220), style.dp(30)))
        timer = style.caption("").apply { textSize = 14f; isVisible = false; setPadding(0, style.dp(2), 0, style.dp(6)) }
        dock.addView(timer)
        // equal-width sides keep Record exactly in the middle of the screen
        val buttons = LinearLayout(ctx).apply { gravity = Gravity.CENTER }
        val sideParams = { LinearLayout.LayoutParams(style.dp(84), -2) }
        buttons.addView(side(R.drawable.ic_note, R.string.wp_space_write) { (activity as ChatActivity).show(TextNoteFragment.newInstance(0)) }, sideParams())
        record = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER
            background = style.rounded(red, 100)
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            setOnClickListener { toggle() }
        }
        buttons.addView(record, LinearLayout.LayoutParams(style.dp(76), style.dp(76)).apply { marginStart = style.dp(24); marginEnd = style.dp(24) })
        buttons.addView(side(R.drawable.ic_plus, R.string.wp_space_add) { addMenu() }, sideParams())
        dock.addView(buttons, LinearLayout.LayoutParams(-1, -2))
        column.addView(dock)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            column.setPadding(0, bars.top + style.dp(16), 0, bars.bottom)
            list.setPadding(style.dp(10), style.dp(4), style.dp(10), style.dp(8))
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
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(style.dp(52), style.dp(52)))
        addView(style.caption(getString(label)).apply { setPadding(0, style.dp(4), 0, 0); gravity = Gravity.CENTER; maxLines = 1 })
        contentDescription = getString(label)
        setOnClickListener { onClick() }
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

    // ---- layout model

    private fun matches(text: String) = query.isEmpty() || text.contains(query, ignoreCase = true)
    private fun inCat(c: String) = category == null || c == category

    private fun render() {
        if (view == null) return
        val now = System.currentTimeMillis()
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        fun day(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

        val ns = notes.filter { inCat(it.category) && matches(it.text) }
        val ts = tasks.filter { inCat(it.category) && matches(it.title) }
        val gs = goals.filter { query.isEmpty() || it.title.contains(query, true) }.map { it to Goals.progress(it.period, it.target, it.checkins, now) }

        // filter tabs with counts
        filters.removeAllViews()
        val counts = mapOf(
            Filter.ALL to -1, Filter.VOICE to ns.count { it.kind == Note.Kind.VOICE }, Filter.NOTES to ns.count { it.kind == Note.Kind.TEXT },
            Filter.SCREENS to ns.count { it.kind == Note.Kind.SCREENSHOT }, Filter.TODO to ts.count { !it.done }, Filter.GOALS to gs.size,
        )
        Filter.values().forEach { f -> filters.addView(tab(f, counts[f] ?: -1)) }
        val cats = (notes.map { it.category } + tasks.map { it.category }).filter { it.isNotBlank() }.distinct().sorted()
        categories.removeAllViews()
        categories.isVisible = cats.size > 1 || category != null
        if (category != null && category !in cats) category = null
        cats.forEach { c -> categories.addView(chip(c, c == category) { category = if (category == c) null else c; render() }) }

        val out = ArrayList<Row>()
        val open = ts.filter { !it.done }
        fun byDay(items: List<Note>, tiles: Boolean) {
            items.groupBy { day(it.created) }.toSortedMap(compareByDescending { it }).forEach { (d, list) ->
                out += Row.Header(dayLabel(d, today), list.size)
                list.forEach { out += if (tiles) Row.ShotTile(it) else Row.NoteRow(it) }
            }
        }
        when (filter) {
            Filter.ALL -> {
                if (query.isEmpty() && category == null) out += Row.Today
                val next = open.sortedWith(compareBy({ it.due == 0L }, { it.due })).take(3)
                if (next.isNotEmpty()) { out += Row.Header(getString(R.string.wp_next_up), open.size); next.forEach { out += Row.TaskRow(it) } }
                if (gs.isNotEmpty() && category == null) { out += Row.Header(getString(R.string.wp_filter_goals), gs.size); out += Row.GoalStrip(gs) }
                byDay(ns, tiles = false)
            }
            Filter.VOICE -> byDay(ns.filter { it.kind == Note.Kind.VOICE }, false)
            Filter.NOTES -> byDay(ns.filter { it.kind == Note.Kind.TEXT }, false)
            Filter.SCREENS -> byDay(ns.filter { it.kind == Note.Kind.SCREENSHOT }, true)
            Filter.TODO -> {
                val endOfToday = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val groups = listOf(
                    R.string.wp_todo_overdue to open.filter { it.due in 1 until now },
                    R.string.wp_todo_today to open.filter { it.due in now until endOfToday },
                    R.string.wp_todo_upcoming to open.filter { it.due >= endOfToday }.sortedBy { it.due },
                    R.string.wp_todo_someday to open.filter { it.due == 0L },
                )
                groups.forEach { (label, list) -> if (list.isNotEmpty()) { out += Row.Header(getString(label), list.size); list.forEach { out += Row.TaskRow(it) } } }
                val done = ts.filter { it.done }
                if (done.isNotEmpty()) {
                    out += Row.Header(getString(if (showDone) R.string.wp_todo_done_hide else R.string.wp_todo_done_show), done.size)
                    if (showDone) done.forEach { out += Row.TaskRow(it) }
                }
            }
            Filter.GOALS -> gs.forEach { (g, p) -> out += Row.GoalCard(g, p) }
        }
        if (out.isEmpty() || (out.size == 1 && out[0] is Row.Today)) {
            out += Row.Empty(getString(if (query.isNotEmpty()) R.string.wp_space_no_results else when (filter) {
                Filter.TODO -> R.string.wp_space_empty_todo; Filter.GOALS -> R.string.wp_space_empty_goals; else -> R.string.wp_space_empty
            }))
        }
        rows = out
        list.adapter?.notifyDataSetChanged()
    }

    private fun dayLabel(d: java.time.LocalDate, today: java.time.LocalDate): String = when (d) {
        today -> getString(R.string.wp_day_today)
        today.minusDays(1) -> getString(R.string.wp_day_yesterday)
        else -> d.format(java.time.format.DateTimeFormatter.ofPattern(if (d.year == today.year) "EEE d MMM" else "d MMM yyyy", java.util.Locale.getDefault()))
    }

    private fun tab(f: Filter, count: Int) = LinearLayout(requireContext()).apply {
        val on = f == filter
        gravity = Gravity.CENTER_VERTICAL
        background = if (on) style.rounded(red, 100) else style.rounded(0, 100, style.hairline)
        setPadding(style.dp(12), style.dp(7), style.dp(14), style.dp(7))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = style.dp(6) }
        val ink = if (on) 0xFFFFFFFF.toInt() else style.ink
        addView(ImageView(context).apply { setImageResource(f.icon); imageTintList = android.content.res.ColorStateList.valueOf(ink) },
            LinearLayout.LayoutParams(style.dp(16), style.dp(16)).apply { marginEnd = style.dp(6) })
        addView(TextView(context, null, 0, R.style.TextSmall).apply {
            text = getString(f.label) + if (count > 0) "  $count" else ""
            textSize = 13f; setTextColor(ink)
        })
        setOnClickListener { filter = f; render(); list.scrollToPosition(0) }
    }

    private fun chip(text: String, on: Boolean, onClick: () -> Unit) = TextView(requireContext(), null, 0, R.style.TextSmall).apply {
        this.text = text
        textSize = 12f
        setTextColor(if (on) 0xFFFFFFFF.toInt() else style.faint)
        background = if (on) style.rounded(style.ink and 0x00FFFFFF or 0x55000000, 100) else style.rounded(0, 100, style.hairline)
        setPadding(style.dp(12), style.dp(5), style.dp(12), style.dp(5))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = style.dp(6) }
        setOnClickListener { onClick() }
    }

    // ---- list

    private inner class Holder(val root: LinearLayout) : RecyclerView.ViewHolder(root)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.Header -> 0; is Row.Today -> 1; is Row.TaskRow -> 2; is Row.GoalStrip -> 3; is Row.GoalCard -> 4
            is Row.NoteRow -> 5; is Row.ShotTile -> 6; is Row.Empty -> 7
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(LinearLayout(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(-1, -2)
        })

        override fun onBindViewHolder(h: Holder, position: Int) {
            val root = h.root
            root.removeAllViews(); root.setOnClickListener(null); root.setOnLongClickListener(null)
            root.background = null; root.setPadding(0, 0, 0, 0); root.orientation = LinearLayout.HORIZONTAL
            (root.layoutParams as RecyclerView.LayoutParams).setMargins(0, 0, 0, 0)
            when (val r = rows[position]) {
                is Row.Header -> bindHeader(root, r)
                is Row.Today -> bindToday(root)
                is Row.TaskRow -> bindTask(root, r.task)
                is Row.GoalStrip -> bindGoalStrip(root, r.goals)
                is Row.GoalCard -> bindGoalCard(root, r.goal, r.progress)
                is Row.NoteRow -> bindNote(root, r.note)
                is Row.ShotTile -> bindShot(root, r.note)
                is Row.Empty -> root.addView(style.caption(r.text).apply { gravity = Gravity.CENTER; setPadding(style.dp(28), style.dp(36), style.dp(28), style.dp(36)) },
                    LinearLayout.LayoutParams(-1, -2))
            }
        }
    }

    private fun card(root: LinearLayout, radius: Int = 18) {
        root.background = style.rounded(style.assistantBubble, radius, style.hairline)
        root.setPadding(style.dp(14), style.dp(12), style.dp(14), style.dp(12))
        (root.layoutParams as RecyclerView.LayoutParams).setMargins(style.dp(4), style.dp(4), style.dp(4), style.dp(4))
        root.gravity = Gravity.CENTER_VERTICAL
    }

    private fun bindHeader(root: LinearLayout, r: Row.Header) {
        root.gravity = Gravity.CENTER_VERTICAL
        root.setPadding(style.dp(8), style.dp(18), style.dp(8), style.dp(6))
        root.addView(style.caption(r.text.uppercase()).apply { letterSpacing = 0.14f; setTextColor(style.ink) }, LinearLayout.LayoutParams(0, -2, 1f))
        if (r.count > 0) root.addView(style.caption(r.count.toString()))
        if (r.text == getString(R.string.wp_todo_done_show) || r.text == getString(R.string.wp_todo_done_hide)) root.setOnClickListener { showDone = !showDone; render() }
        if (r.text == getString(R.string.wp_next_up)) root.setOnClickListener { filter = Filter.TODO; render() }
        if (r.text == getString(R.string.wp_filter_goals) && filter == Filter.ALL) root.setOnClickListener { filter = Filter.GOALS; render() }
    }

    /** Today: the date in dot-matrix, and a one-line brief of what's due. */
    private fun bindToday(root: LinearLayout) {
        card(root, 22)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.START
        root.setPadding(style.dp(18), style.dp(16), style.dp(18), style.dp(16))
        val ctx = root.context
        val now = System.currentTimeMillis()
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        root.addView(app.olauncher.features.appearance.ui.DotMatrixView(ctx).apply {
            text = today.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH)).uppercase()
            cellPx = 4.2f * style.density; fill = 0.82f; color = style.ink
        })
        val endOfToday = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val open = tasks.filter { !it.done }
        val overdue = open.count { it.due in 1 until now }
        val dueToday = open.count { it.due in now until endOfToday }
        val next = open.filter { it.due >= now && it.remind }.minByOrNull { it.due }
        val parts = buildList {
            add(resources.getQuantityString(R.plurals.wp_todos_today, dueToday, dueToday))
            if (overdue > 0) add(getString(R.string.wp_overdue_count, overdue))
            val onTrack = goals.count { Goals.progress(it.period, it.target, it.checkins, now).complete }
            if (goals.isNotEmpty()) add(getString(R.string.wp_goals_on_track, onTrack, goals.size))
        }
        root.addView(TextView(ctx, null, 0, R.style.TextSmall).apply {
            text = parts.joinToString("  ·  "); setTextColor(style.ink); textSize = 14f; setPadding(0, style.dp(12), 0, 0)
        })
        if (next != null) root.addView(style.caption("🔔 " + android.text.format.DateUtils.formatDateTime(ctx, next.due,
            android.text.format.DateUtils.FORMAT_SHOW_TIME or android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY or android.text.format.DateUtils.FORMAT_ABBREV_ALL) + "  " + next.title).apply {
            setPadding(0, style.dp(6), 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (overdue > 0) { root.setOnClickListener { filter = Filter.TODO; render() } }
    }

    private fun bindNote(root: LinearLayout, n: Note) {
        card(root)
        val ctx = root.context
        root.addView(FrameLayout(ctx).apply {
            background = style.rounded(style.codeBg, 100)
            addView(ImageView(ctx).apply {
                setImageResource(when (n.kind) { Note.Kind.VOICE -> R.drawable.ic_mic; Note.Kind.TEXT -> R.drawable.ic_note; Note.Kind.SCREENSHOT -> R.drawable.ic_image })
                imageTintList = android.content.res.ColorStateList.valueOf(if (n.kind == Note.Kind.VOICE) red else style.ink)
            }, FrameLayout.LayoutParams(style.dp(18), style.dp(18), Gravity.CENTER))
        }, LinearLayout.LayoutParams(style.dp(36), style.dp(36)).apply { marginEnd = style.dp(12) })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(ctx, null, 0, R.style.TextMedium).apply {
            text = n.displayTitle; setTextColor(style.ink); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        })
        val time = android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(n.created))
        val status = when (n.status) {
            Note.Status.TRANSCRIBING -> getString(if (n.kind == Note.Kind.SCREENSHOT) R.string.wp_space_reading else R.string.wp_space_transcribing) +
                space.progress.value[n.id]?.let { " $it" }.orEmpty()
            Note.Status.CLEANING -> getString(R.string.wp_space_polishing) + space.progress.value[n.id]?.let { " $it" }.orEmpty()
            Note.Status.SUMMARIZING -> getString(R.string.wp_space_summarizing)
            Note.Status.ERROR -> getString(R.string.wp_space_failed)
            Note.Status.RECORDED -> getString(R.string.wp_space_not_transcribed)
            Note.Status.DONE -> null
        }
        val dur = if (n.kind == Note.Kind.VOICE) (n.durationMs / 1000).let { "%d:%02d".format(it / 60, it % 60) } else null
        col.addView(style.caption(listOfNotNull(time, dur, n.category.ifBlank { null }, status).joinToString(" · ")).apply {
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
            thumb(space.imageFile(name), 160)?.let { bmp ->
                root.addView(ImageView(ctx).apply {
                    setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                    background = style.rounded(style.codeBg, 10)
                }, LinearLayout.LayoutParams(style.dp(52), style.dp(52)).apply { marginStart = style.dp(10) })
            }
        }
        root.setOnClickListener { open(n) }
    }

    /** Screenshot grid tile (two per row). */
    private fun bindShot(root: LinearLayout, n: Note) {
        card(root, 16)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(style.dp(6), style.dp(6), style.dp(6), style.dp(10))
        val ctx = root.context
        val bmp = n.images.firstOrNull()?.let { thumb(space.imageFile(it), 420) }
        root.addView(ImageView(ctx).apply {
            if (bmp != null) setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = style.rounded(style.codeBg, 12)
        }, LinearLayout.LayoutParams(-1, style.dp(200)))
        root.addView(TextView(ctx, null, 0, R.style.TextSmall).apply {
            text = n.displayTitle; setTextColor(style.ink); textSize = 13f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(style.dp(4), style.dp(8), style.dp(4), 0)
        })
        if (n.category.isNotBlank()) root.addView(style.caption(n.category).apply { setPadding(style.dp(4), style.dp(2), 0, 0) })
        root.setOnClickListener { open(n) }
    }

    private fun open(n: Note) {
        val a = activity as ChatActivity
        if (n.kind == Note.Kind.TEXT) a.show(TextNoteFragment.newInstance(n.id)) else a.show(NoteFragment.newInstance(n.id))
    }

    private val thumbs = android.util.LruCache<String, android.graphics.Bitmap>(40)
    private fun thumb(file: java.io.File, size: Int): android.graphics.Bitmap? = thumbs.get(file.name + size) ?: run {
        if (!file.exists()) return null
        val b = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, b)
        var s = 1; while (minOf(b.outWidth, b.outHeight) / (s * 2) >= size) s *= 2
        android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = s })?.also { thumbs.put(file.name + size, it) }
    }

    private fun bindTask(root: LinearLayout, t: Task) {
        card(root)
        root.setPadding(style.dp(10), style.dp(10), style.dp(14), style.dp(10))
        val ctx = root.context
        root.addView(ImageView(ctx).apply {
            setImageResource(if (t.done) R.drawable.ic_check_circle else R.drawable.ic_circle)
            scaleType = ImageView.ScaleType.CENTER
            imageTintList = android.content.res.ColorStateList.valueOf(if (t.done) red else style.ink)
            setOnClickListener { viewLifecycleOwner.lifecycleScope.launch { space.setTaskDone(t.id, !t.done) } }
            contentDescription = getString(if (t.done) R.string.wp_task_undo else R.string.wp_task_done)
        }, LinearLayout.LayoutParams(style.dp(40), style.dp(40)).apply { marginEnd = style.dp(6) })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(ctx, null, 0, R.style.TextMedium).apply {
            text = t.title; setTextColor(if (t.done) style.faint else style.ink); textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            paintFlags = if (t.done) paintFlags or Paint.STRIKE_THRU_TEXT_FLAG else paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        })
        if (t.category.isNotBlank()) col.addView(style.caption(t.category).apply { setPadding(0, style.dp(2), 0, 0) })
        root.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        if (t.due > 0) {
            val overdue = !t.done && t.due < System.currentTimeMillis()
            val label = DateUtils.formatDateTime(ctx, t.due, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL)
            root.addView(TextView(ctx, null, 0, R.style.TextSmall).apply {
                text = (if (t.remind) "🔔 " else "") + label; textSize = 12f
                setTextColor(if (overdue) 0xFFFFFFFF.toInt() else style.ink)
                background = if (overdue) style.rounded(red, 100) else style.rounded(0, 100, style.hairline)
                setPadding(style.dp(10), style.dp(5), style.dp(10), style.dp(5))
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = style.dp(8) })
        }
        root.setOnClickListener { SpaceEditors.task(this, t) }
    }

    /** Goals as a horizontal strip of ring tiles: tap the ring to check in. */
    private fun bindGoalStrip(root: LinearLayout, gs: List<Pair<Goal, Goals.Progress>>) {
        val ctx = root.context
        val scroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(ctx).apply { setPadding(style.dp(4), 0, style.dp(4), 0) }
        gs.forEach { (g, p) ->
            row.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                background = style.rounded(style.assistantBubble, 18, style.hairline)
                setPadding(style.dp(12), style.dp(12), style.dp(12), style.dp(12))
                addView(ring(ctx, g, p), LinearLayout.LayoutParams(style.dp(64), style.dp(64)))
                addView(TextView(ctx, null, 0, R.style.TextSmall).apply {
                    text = g.title; setTextColor(style.ink); textSize = 13f; maxLines = 1; gravity = Gravity.CENTER
                    ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, style.dp(8), 0, 0)
                })
                addView(style.caption(progressLabel(g, p)).apply { gravity = Gravity.CENTER })
                setOnClickListener { SpaceEditors.goalOptions(this@SpaceFragment, g) }
            }, LinearLayout.LayoutParams(style.dp(124), -2).apply { marginEnd = style.dp(8) })
        }
        scroll.addView(row)
        root.addView(scroll, LinearLayout.LayoutParams(-1, -2))
    }

    private fun bindGoalCard(root: LinearLayout, g: Goal, p: Goals.Progress) {
        card(root, 20)
        val ctx = root.context
        root.addView(ring(ctx, g, p), LinearLayout.LayoutParams(style.dp(56), style.dp(56)).apply { marginEnd = style.dp(14) })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(ctx, null, 0, R.style.TextMedium).apply { text = g.title; setTextColor(style.ink); textDirection = View.TEXT_DIRECTION_FIRST_STRONG })
        col.addView(style.caption(progressLabel(g, p)).apply { setPadding(0, style.dp(4), 0, 0) })
        root.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        root.setOnClickListener { SpaceEditors.goalOptions(this, g) }
    }

    private fun ring(ctx: android.content.Context, g: Goal, p: Goals.Progress) = ProgressRing(ctx, p.fraction, if (p.complete) "✓" else "+1", style.ink, red).apply {
        contentDescription = getString(R.string.wp_goal_checkin)
        setOnClickListener { viewLifecycleOwner.lifecycleScope.launch { space.checkIn(g.id) } }
    }

    private fun progressLabel(g: Goal, p: Goals.Progress): String {
        val per = getString(when (g.period) {
            app.olauncher.features.space.domain.GoalPeriod.DAY -> R.string.wp_goal_today
            app.olauncher.features.space.domain.GoalPeriod.WEEK -> R.string.wp_goal_this_week
            app.olauncher.features.space.domain.GoalPeriod.MONTH -> R.string.wp_goal_this_month
            app.olauncher.features.space.domain.GoalPeriod.TOTAL -> R.string.wp_goal_total
        })
        return "${p.done}/${p.target} $per" + if (p.streak > 1) " · ${getString(R.string.wp_goal_streak, p.streak)}" else ""
    }
}

/** Goal ring: red arc for progress, label in the middle (+1 or ✓). */
internal class ProgressRing(context: android.content.Context, private val fraction: Float, private val label: String, private val ink: Int, private val accent: Int) : View(context) {
    private val d = resources.displayMetrics.density
    private val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 5 * d; color = (ink and 0x00FFFFFF) or 0x26000000 }
    private val arc = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 5 * d; strokeCap = android.graphics.Paint.Cap.ROUND; color = accent }
    private val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 15 * d; textAlign = android.graphics.Paint.Align.CENTER; typeface = android.graphics.Typeface.MONOSPACE }
    private val box = android.graphics.RectF()
    override fun onDraw(canvas: android.graphics.Canvas) {
        val pad = arc.strokeWidth / 2 + d
        box.set(pad, pad, width - pad, height - pad)
        canvas.drawArc(box, 0f, 360f, false, track)
        if (fraction > 0f) canvas.drawArc(box, -90f, 360f * fraction, false, arc)
        canvas.drawText(label, width / 2f, height / 2f - (text.descent() + text.ascent()) / 2, text)
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

