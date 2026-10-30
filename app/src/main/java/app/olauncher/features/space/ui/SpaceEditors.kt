package app.olauncher.features.space.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.InputType
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.olauncher.R
import app.olauncher.core.ui.chooseOne
import app.olauncher.core.ui.focusAndShowKeyboard
import app.olauncher.core.ui.promptText
import app.olauncher.core.ui.styleInput
import app.olauncher.core.waypoint
import app.olauncher.features.assistant.ui.ChatStyle
import app.olauncher.features.space.data.Goal
import app.olauncher.features.space.data.Task
import app.olauncher.features.space.domain.GoalPeriod
import app.olauncher.features.space.domain.Projects
import app.olauncher.features.space.domain.ReminderParser
import app.olauncher.features.space.domain.RepeatParser
import app.olauncher.features.space.domain.RepeatRule
import app.olauncher.helper.createDialog
import app.olauncher.helper.showToast
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Editors for to-dos and goals, built from tappable chips so the common choices are one tap:
 * when (Today, Tomorrow, Next week…), time, repeat, reminder; habits (n times a day/week/month) or projects
 * (finish by a date, update % done).
 */
internal object SpaceEditors {

    private val zone get() = ZoneId.systemDefault()
    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    // ---- small building blocks

    /** A caption above a group of chips. */
    private fun label(parent: LinearLayout, text: Int) {
        val st = ChatStyle(parent.context)
        parent.addView(st.caption(parent.context.getString(text).uppercase()).apply {
            letterSpacing = 0.14f; setPadding(dp(context, 2), dp(context, 18), 0, dp(context, 8))
        })
    }

    /** A horizontally scrolling row of chips; [items] are (label, selected, onClick). */
    private fun chips(parent: LinearLayout, items: List<Triple<String, Boolean, () -> Unit>>): LinearLayout {
        val ctx = parent.context
        val st = ChatStyle(ctx)
        val row = LinearLayout(ctx)
        items.forEach { (text, on, click) ->
            row.addView(TextView(ctx, null, 0, R.style.TextSmall).apply {
                this.text = text
                textSize = 14f
                setTextColor(if (on) 0xFFFFFFFF.toInt() else st.ink)
                background = if (on) st.rounded(0xFFD71921.toInt(), 100) else st.rounded(0, 100, st.hairline)
                gravity = Gravity.CENTER
                minHeight = dp(ctx, 38)
                setPadding(dp(ctx, 14), dp(ctx, 7), dp(ctx, 14), dp(ctx, 7))
                setOnClickListener { click() }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(ctx, 8) })
        }
        parent.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; isFillViewport = false; addView(row) })
        return row
    }

    private fun summary(parent: LinearLayout): TextView {
        val st = ChatStyle(parent.context)
        return st.caption().apply { textSize = 13f; setPadding(dp(context, 2), dp(context, 8), 0, 0) }.also { parent.addView(it) }
    }

    private fun box(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.MarginLayoutParams(-1, -2).apply { topMargin = dp(ctx, 12) }
    }

    private fun dateOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    private fun timeOf(ms: Long): LocalTime = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()
    private fun at(date: LocalDate, time: LocalTime) = date.atTime(time).atZone(zone).toInstant().toEpochMilli()

    fun repeatLabel(ctx: Context, rule: RepeatRule): String = when (rule.kind) {
        RepeatRule.Kind.NONE -> ctx.getString(R.string.wp_repeat_never)
        else -> rule.describe()
    }

    // ---- to-dos

    fun task(f: Fragment, task: Task?) {
        val ctx = f.requireContext()
        val space = ctx.waypoint.space
        var due = task?.due ?: 0L
        var remind = task?.remind ?: false
        var rule = task?.rule ?: RepeatRule.NONE
        val steps = ArrayList<app.olauncher.features.space.platform.SpaceEngine.Step>()
        var suggesting = false
        lateinit var title: EditText
        lateinit var dynamic: LinearLayout
        lateinit var stepsBox: LinearLayout

        fun renderSteps() {
            stepsBox.removeAllViews()
            val st = ChatStyle(ctx)
            label(stepsBox, R.string.wp_task_steps)
            steps.forEachIndexed { i, s ->
                stepsBox.addView(LinearLayout(ctx).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(ctx, 40)
                    addView(android.widget.ImageView(ctx).apply {
                        setImageResource(if (s.done) R.drawable.ic_check_circle else R.drawable.ic_circle)
                        imageTintList = android.content.res.ColorStateList.valueOf(if (s.done) 0xFFD71921.toInt() else st.ink)
                        scaleType = android.widget.ImageView.ScaleType.CENTER
                        setOnClickListener { steps[i] = s.copy(done = !s.done); renderSteps() }
                    }, LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 36)))
                    addView(TextView(ctx, null, 0, R.style.TextSmall).apply {
                        text = s.title; textSize = 15f; setTextColor(if (s.done) st.faint else st.ink)
                        paintFlags = if (s.done) paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG else paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                        setPadding(dp(ctx, 6), 0, dp(ctx, 6), 0)
                        setOnClickListener {
                            ctx.promptText(R.string.wp_task_step_edit, s.title) { t -> if (t.isNotBlank()) { steps[i] = s.copy(title = t.trim()); renderSteps() } }
                        }
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(TextView(ctx, null, 0, R.style.TextSmall).apply {
                        text = "✕"; textSize = 15f; setTextColor(st.faint); gravity = Gravity.CENTER
                        contentDescription = ctx.getString(R.string.wp_delete)
                        setOnClickListener { steps.removeAt(i); renderSteps() }
                    }, LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40)))
                })
            }
            chips(stepsBox, listOfNotNull(
                Triple(ctx.getString(R.string.wp_task_step_add), false, {
                    ctx.promptText(R.string.wp_task_step_add, "", hint = R.string.wp_task_step_hint) { t ->
                        // several lines = several steps
                        t.lines().map { it.trim().trimStart('-', '•', '*').trim() }.filter { it.isNotEmpty() }
                            .forEach { steps += app.olauncher.features.space.platform.SpaceEngine.Step(0, it, false) }
                        renderSteps()
                    }
                }),
                if (ctx.waypoint.chatEngine.isConfigured()) Triple(ctx.getString(if (suggesting) R.string.wp_task_step_suggesting else R.string.wp_task_step_suggest), suggesting, {
                    val t = title.text.toString().trim()
                    if (t.isEmpty()) ctx.showToast(ctx.getString(R.string.wp_task_step_need_title))
                    else if (!suggesting) {
                        suggesting = true; renderSteps()
                        f.viewLifecycleOwner.lifecycleScope.launch {
                            val got = space.suggestSteps(t)
                            suggesting = false
                            if (got.isEmpty()) ctx.showToast(ctx.getString(R.string.wp_task_step_failed))
                            got.filter { g -> steps.none { it.title.equals(g, true) } }.forEach { steps += app.olauncher.features.space.platform.SpaceEngine.Step(0, it, false) }
                            renderSteps()
                        }
                    }
                }) else null,
            ))
        }

        fun render() {
            dynamic.removeAllViews()
            val today = LocalDate.now(zone)
            val d = if (due > 0) dateOf(due) else null
            val time = if (due > 0) timeOf(due) else LocalTime.of(9, 0)
            fun pick(date: LocalDate?) { due = if (date == null) 0 else at(date, time); if (date == null) { remind = false; rule = RepeatRule.NONE } else remind = true; render() }
            label(dynamic, R.string.wp_task_when)
            chips(dynamic, listOf(
                Triple(ctx.getString(R.string.wp_day_today), d == today, { pick(today) }),
                Triple(ctx.getString(R.string.wp_task_tomorrow), d == today.plusDays(1), { pick(today.plusDays(1)) }),
                Triple(ctx.getString(R.string.wp_task_next_week), d == today.plusWeeks(1), { pick(today.plusWeeks(1)) }),
                Triple(if (d != null && d != today && d != today.plusDays(1) && d != today.plusWeeks(1))
                    d.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")) else ctx.getString(R.string.wp_task_pick_date), false, {
                    val base = d ?: today
                    DatePickerDialog(ctx, { _, y, m, day -> pick(LocalDate.of(y, m + 1, day)) }, base.year, base.monthValue - 1, base.dayOfMonth).show()
                }),
                Triple(ctx.getString(R.string.wp_task_no_date_chip), d == null, { pick(null) }),
            ))
            if (d != null) {
                label(dynamic, R.string.wp_task_time)
                chips(dynamic, listOf(
                    Triple(DateFormat.getTimeFormat(ctx).format(java.util.Date(due)), true, {
                        TimePickerDialog(ctx, { _, h, min -> due = at(dateOf(due), LocalTime.of(h, min)); render() },
                            time.hour, time.minute, DateFormat.is24HourFormat(ctx)).show()
                    }),
                    Triple(ctx.getString(if (remind) R.string.wp_task_remind_on else R.string.wp_task_remind_off), remind, { remind = !remind; render() }),
                ))
                label(dynamic, R.string.wp_task_repeat)
                val dow = d.dayOfWeek
                chips(dynamic, listOf(
                    Triple(ctx.getString(R.string.wp_repeat_never), !rule.repeats, { rule = RepeatRule.NONE; render() }),
                    Triple(ctx.getString(R.string.wp_repeat_daily), rule.kind == RepeatRule.Kind.DAILY, { rule = RepeatRule(RepeatRule.Kind.DAILY); render() }),
                    Triple(ctx.getString(R.string.wp_repeat_weekdays), rule.kind == RepeatRule.Kind.WEEKDAYS, { rule = RepeatRule(RepeatRule.Kind.WEEKDAYS); render() }),
                    Triple(if (rule.kind == RepeatRule.Kind.WEEKLY) rule.describe() else ctx.getString(R.string.wp_repeat_weekly),
                        rule.kind == RepeatRule.Kind.WEEKLY, { chooseDays(ctx, rule.days.ifEmpty { setOf(dow) }) { days -> rule = RepeatRule(RepeatRule.Kind.WEEKLY, days); render() } }),
                    Triple(ctx.getString(R.string.wp_repeat_monthly), rule.kind == RepeatRule.Kind.MONTHLY, { rule = RepeatRule(RepeatRule.Kind.MONTHLY); render() }),
                    Triple(if (rule.kind == RepeatRule.Kind.EVERY_N_DAYS) rule.describe() else ctx.getString(R.string.wp_repeat_every_n),
                        rule.kind == RepeatRule.Kind.EVERY_N_DAYS, {
                        ctx.promptText(R.string.wp_repeat_every_n, rule.every.takeIf { rule.kind == RepeatRule.Kind.EVERY_N_DAYS }?.toString() ?: "2", numeric = true) { s ->
                            s.toIntOrNull()?.takeIf { it in 1..365 }?.let { rule = RepeatRule(RepeatRule.Kind.EVERY_N_DAYS, every = it); render() }
                        }
                    }),
                ))
            }
            summary(dynamic).text = when {
                due == 0L -> ctx.getString(R.string.wp_task_summary_someday)
                else -> DateUtils.formatDateTime(ctx, due, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME) +
                    (if (rule.repeats) "  ·  ↻ " + rule.describe() else "") + (if (remind) "  ·  🔔" else "")
            }
        }

        val dialog = ctx.createDialog(if (task == null) R.string.wp_add_todo else R.string.wp_edit_todo, R.string.wp_save, onAction = {
            var text = title.text.toString().trim()
            if (text.isEmpty()) return@createDialog
            // "gym every monday at 7" typed in the title sets the repeat and the time too
            val (typedRule, noRepeat) = RepeatParser.extract(text)
            if (typedRule.repeats && !rule.repeats) { rule = typedRule; text = noRepeat }
            if (due == 0L) {
                val (d, rest) = ReminderParser.extractTime(text, System.currentTimeMillis(), zone)
                if (d != null && rest.isNotBlank()) { due = d; remind = true; text = rest }
            }
            if (rule.repeats && due == 0L) due = RepeatRule.advance(0, rule, System.currentTimeMillis())
            val finalTitle = text.replaceFirstChar { it.uppercase() }
            val repeat = if (due > 0) rule.encode() else ""
            val finalSteps = steps.toList()
            // the app scope: the dialog (and maybe the page) may be gone before this finishes
            ctx.waypoint.scope.launch {
                val id = if (task == null) space.addTask(finalTitle, due, remind && due > 0, repeat = repeat)
                else { space.updateTask(task.id, finalTitle, due, remind && due > 0, repeat); task.id }
                if (task != null || finalSteps.isNotEmpty()) space.saveSteps(id, finalSteps)
            }
        }, neutral = if (task != null) R.string.wp_delete else 0, onNeutral = {
            if (task != null) f.viewLifecycleOwner.lifecycleScope.launch { space.deleteTask(task.id) }
        }, content = { parent ->
            box(parent.context).also { b ->
                title = EditText(b.context).apply {
                    setText(task?.title.orEmpty()); setHint(R.string.wp_task_hint); styleInput()
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                }
                b.addView(title)
                dynamic = LinearLayout(b.context).apply { orientation = LinearLayout.VERTICAL }
                b.addView(dynamic)
                stepsBox = LinearLayout(b.context).apply { orientation = LinearLayout.VERTICAL }
                b.addView(stepsBox)
                render()
                renderSteps()
                if (task != null) f.viewLifecycleOwner.lifecycleScope.launch { steps.addAll(space.steps(task.id)); renderSteps() }
            }.let { inner ->
                // the editor can grow taller than the screen with many steps: scroll it
                android.widget.ScrollView(parent.context).apply {
                    addView(inner)
                    layoutParams = ViewGroup.MarginLayoutParams(-1, -2)
                    isVerticalScrollBarEnabled = false
                }.also { sv ->
                    val max = (parent.resources.displayMetrics.heightPixels * 0.62f).toInt()
                    sv.viewTreeObserver.addOnGlobalLayoutListener { if (sv.height > max) sv.layoutParams = sv.layoutParams.apply { height = max } }
                }
            }
        })
        dialog.showRespectingStatusBar()
        if (task == null) title.focusAndShowKeyboard()
    }

    private fun chooseDays(ctx: Context, current: Set<DayOfWeek>, onDone: (Set<DayOfWeek>) -> Unit) {
        val all = DayOfWeek.values()
        val names = all.map { it.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()) }.toTypedArray()
        val checked = all.map { it in current }.toBooleanArray()
        android.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.wp_repeat_weekly)
            .setMultiChoiceItems(names, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton(R.string.okay) { _, _ -> all.filterIndexed { i, _ -> checked[i] }.toSet().takeIf { it.isNotEmpty() }?.let(onDone) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- goals

    /** New goal or edit [goal]: a habit (n times per day/week/month, or in total) or a project with a deadline. */
    fun goal(f: Fragment, goal: Goal? = null) {
        val ctx = f.requireContext()
        val space = ctx.waypoint.space
        var kind = goal?.kind ?: Goal.Kind.HABIT
        var period = goal?.period ?: GoalPeriod.WEEK
        var target = goal?.target ?: 3
        var deadline = goal?.deadline?.takeIf { it > 0 } ?: Projects.endOf(LocalDate.now(zone).plusWeeks(2))
        var percent = goal?.percent ?: 0
        lateinit var title: EditText
        lateinit var dynamic: LinearLayout

        fun render() {
            dynamic.removeAllViews()
            if (goal == null) {
                label(dynamic, R.string.wp_goal_type)
                chips(dynamic, listOf(
                    Triple(ctx.getString(R.string.wp_goal_habit), kind == Goal.Kind.HABIT, { kind = Goal.Kind.HABIT; render() }),
                    Triple(ctx.getString(R.string.wp_goal_project), kind == Goal.Kind.PROJECT, { kind = Goal.Kind.PROJECT; render() }),
                ))
            }
            if (kind == Goal.Kind.HABIT) {
                label(dynamic, R.string.wp_goal_how_often)
                chips(dynamic, listOf(
                    Triple("−", false, { if (target > 1) { target--; render() } }),
                    Triple(ctx.resources.getQuantityString(R.plurals.wp_goal_times, target, target), true, {}),
                    Triple("+", false, { if (target < 99) { target++; render() } }),
                ))
                chips(dynamic, GoalPeriod.values().map { p -> Triple(periodLabel(ctx, p).replaceFirstChar { it.uppercase() }, period == p, { period = p; render() }) })
                summary(dynamic).text = ctx.getString(R.string.wp_goal_habit_summary, ctx.resources.getQuantityString(R.plurals.wp_goal_times, target, target), periodLabel(ctx, period).lowercase())
            } else {
                label(dynamic, R.string.wp_goal_deadline)
                val today = LocalDate.now(zone)
                val d = dateOf(deadline)
                fun pick(date: LocalDate) { deadline = Projects.endOf(date); render() }
                val presets = listOf(R.string.wp_goal_1_week to today.plusWeeks(1), R.string.wp_goal_2_weeks to today.plusWeeks(2), R.string.wp_goal_1_month to today.plusMonths(1))
                chips(dynamic, presets.map { (l, date) -> Triple(ctx.getString(l), d == date, { pick(date) }) } + Triple(
                    if (presets.none { it.second == d }) d.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")) else ctx.getString(R.string.wp_task_pick_date),
                    presets.none { it.second == d }, {
                        DatePickerDialog(ctx, { _, y, m, day -> pick(LocalDate.of(y, m + 1, day)) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                    }))
                label(dynamic, R.string.wp_goal_done_so_far)
                progressSlider(dynamic, percent) { percent = it }
                val days = (d.toEpochDay() - today.toEpochDay()).toInt()
                summary(dynamic).text = ctx.resources.getQuantityString(R.plurals.wp_goal_days_left, days.coerceAtLeast(0), days.coerceAtLeast(0))
            }
        }

        ctx.createDialog(if (goal == null) R.string.wp_add_goal else R.string.wp_edit_goal, R.string.wp_save, onAction = {
            val t = title.text.toString().trim()
            if (t.isEmpty()) return@createDialog
            f.viewLifecycleOwner.lifecycleScope.launch {
                when {
                    goal != null -> {
                        space.updateGoal(goal.id, t, target, period, if (kind == Goal.Kind.PROJECT) deadline else 0)
                        if (kind == Goal.Kind.PROJECT) space.setGoalProgress(goal.id, percent)
                    }
                    kind == Goal.Kind.PROJECT -> space.addProject(t, deadline, percent)
                    else -> space.addGoal(t, target, period)
                }
            }
        }, neutral = if (goal != null) R.string.wp_delete else 0, onNeutral = {
            if (goal != null) f.viewLifecycleOwner.lifecycleScope.launch { space.deleteGoal(goal.id) }
        }, content = { parent ->
            box(parent.context).also { b ->
                title = EditText(b.context).apply {
                    setText(goal?.title.orEmpty()); setHint(R.string.wp_goal_hint); styleInput()
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                }
                b.addView(title)
                dynamic = LinearLayout(b.context).apply { orientation = LinearLayout.VERTICAL }
                b.addView(dynamic)
                render()
            }
        }).showRespectingStatusBar()
        if (goal == null) title.focusAndShowKeyboard()
    }

    fun periodLabel(ctx: Context, p: GoalPeriod) = ctx.getString(when (p) {
        GoalPeriod.DAY -> R.string.wp_goal_per_day; GoalPeriod.WEEK -> R.string.wp_goal_per_week
        GoalPeriod.MONTH -> R.string.wp_goal_per_month; GoalPeriod.TOTAL -> R.string.wp_goal_in_total
    })

    /** "45%" with a slider in 5% steps and quick buttons. */
    private fun progressSlider(parent: LinearLayout, start: Int, onChange: (Int) -> Unit) {
        val ctx = parent.context
        val st = ChatStyle(ctx)
        var value = start
        val big = TextView(ctx, null, 0, R.style.TextLarge).apply { textSize = 34f; setTextColor(st.ink); typeface = android.graphics.Typeface.MONOSPACE }
        val bar = SeekBar(ctx).apply {
            max = 20; progress = value / 5
            progressTintList = android.content.res.ColorStateList.valueOf(0xFFD71921.toInt())
            thumbTintList = android.content.res.ColorStateList.valueOf(0xFFD71921.toInt())
        }
        fun set(v: Int) { value = v.coerceIn(0, 100); big.text = "$value%"; bar.progress = value / 5; onChange(value) }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) set(p * 5) }
            override fun onStartTrackingTouch(s: SeekBar) = Unit
            override fun onStopTrackingTouch(s: SeekBar) = Unit
        })
        parent.addView(big)
        parent.addView(bar, LinearLayout.LayoutParams(-1, dp(ctx, 40)))
        chips(parent, listOf(
            Triple("+5%", false, { set(value + 5) }), Triple("+10%", false, { set(value + 10) }),
            Triple("+25%", false, { set(value + 25) }), Triple(ctx.getString(R.string.wp_goal_finished), false, { set(100) }),
        ))
        set(value)
    }

    /** Project: update how much is done (slider and quick steps), with the pace against the deadline. */
    fun projectProgress(f: Fragment, g: Goal) {
        val ctx = f.requireContext()
        val space = ctx.waypoint.space
        var percent = g.percent
        ctx.createDialog(R.string.wp_goal_update, R.string.wp_save, onAction = {
            f.viewLifecycleOwner.lifecycleScope.launch {
                space.setGoalProgress(g.id, percent)
                if (percent >= 100 && g.percent < 100) ctx.showToast(ctx.getString(R.string.wp_goal_done_toast, g.title))
            }
        }, neutral = R.string.wp_memory_edit, onNeutral = { goal(f, g) }, content = { parent ->
            box(parent.context).also { b ->
                b.addView(TextView(b.context, null, 0, R.style.TextMedium).apply { text = g.title; setTextColor(ChatStyle(context).ink) })
                val st = Projects.status(g.created, g.deadline, g.percent, System.currentTimeMillis())
                b.addView(ChatStyle(b.context).caption(paceText(ctx, st)).apply { setPadding(0, dp(context, 4), 0, dp(context, 8)) })
                progressSlider(b, percent) { percent = it }
            }
        }).showRespectingStatusBar()
    }

    /** "On track · 7 days left", "Behind by 20% · 3 days left", "Overdue by 2 days", "Done". */
    fun paceText(ctx: Context, s: Projects.Status): String {
        val left = when {
            s.done -> ctx.getString(R.string.wp_goal_complete)
            s.overdue -> ctx.resources.getQuantityString(R.plurals.wp_goal_overdue_days, -s.daysLeft, -s.daysLeft)
            else -> ctx.resources.getQuantityString(R.plurals.wp_goal_days_left, s.daysLeft, s.daysLeft)
        }
        if (s.done || s.overdue) return left
        val pace = when {
            s.delta >= 5 -> ctx.getString(R.string.wp_goal_ahead, s.delta)
            s.delta <= -5 -> ctx.getString(R.string.wp_goal_behind, -s.delta)
            else -> ctx.getString(R.string.wp_goal_on_track)
        }
        return "$pace  ·  $left"
    }

    /** Habit: check in, undo, edit or delete. Project: update progress, edit or delete. */
    fun goalOptions(f: Fragment, g: Goal) {
        if (g.kind == Goal.Kind.PROJECT) { projectProgress(f, g); return }
        val ctx = f.requireContext()
        val space = ctx.waypoint.space
        ctx.chooseOne(R.string.wp_filter_goals, listOf(ctx.getString(R.string.wp_goal_checkin), ctx.getString(R.string.wp_goal_undo),
            ctx.getString(R.string.wp_memory_edit), ctx.getString(R.string.wp_delete))) { i ->
            when (i) {
                2 -> goal(f, g)
                else -> f.viewLifecycleOwner.lifecycleScope.launch { when (i) { 0 -> space.checkIn(g.id); 1 -> space.undoCheckIn(g.id); else -> space.deleteGoal(g.id) } }
            }
        }
    }
}
