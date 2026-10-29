package app.olauncher.features.space.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.InputType
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.olauncher.R
import app.olauncher.core.ui.chooseOne
import app.olauncher.core.ui.focusAndShowKeyboard
import app.olauncher.core.ui.styleInput
import app.olauncher.core.waypoint
import app.olauncher.features.space.data.Goal
import app.olauncher.features.space.data.Task
import app.olauncher.features.space.domain.GoalPeriod
import app.olauncher.features.space.domain.ReminderParser
import app.olauncher.helper.createDialog
import kotlinx.coroutines.launch
import java.util.Calendar

/** Small dialogs for to-dos (title, when, remind) and goals (title, target, period). */
internal object SpaceEditors {

    fun task(f: Fragment, task: Task?) {
        val ctx = f.requireContext()
        val space = ctx.waypoint.space
        var due = task?.due ?: 0L
        var remind = task?.remind ?: false
        lateinit var title: EditText
        lateinit var whenRow: TextView
        lateinit var remindRow: TextView
        fun refresh() {
            whenRow.text = if (due > 0) DateUtils.formatDateTime(ctx, due, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY)
            else ctx.getString(R.string.wp_task_no_date)
            remindRow.text = ctx.getString(if (remind && due > 0) R.string.wp_task_remind_on else R.string.wp_task_remind_off)
        }
        val dialog = ctx.createDialog(if (task == null) R.string.wp_add_todo else R.string.wp_edit_todo, R.string.okay, onAction = {
            val text = title.text.toString().trim()
            if (text.isEmpty()) return@createDialog
            // "call mom tomorrow at 5" typed as a title also sets the time
            var finalTitle = text
            if (due == 0L) {
                val (d, rest) = ReminderParser.extractTime(text, System.currentTimeMillis(), java.time.ZoneId.systemDefault())
                if (d != null && rest.isNotBlank()) { due = d; remind = true; finalTitle = rest.replaceFirstChar { it.uppercase() } }
            }
            f.viewLifecycleOwner.lifecycleScope.launch {
                if (task == null) space.addTask(finalTitle, due, remind && due > 0)
                else space.updateTask(task.id, finalTitle, due, remind && due > 0)
            }
        }, neutral = if (task != null) R.string.wp_delete else 0, onNeutral = {
            if (task != null) f.viewLifecycleOwner.lifecycleScope.launch { space.deleteTask(task.id) }
        }, content = { parent ->
            LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.MarginLayoutParams(-1, -2).apply { topMargin = (14 * resources.displayMetrics.density).toInt() }
                title = EditText(context).apply {
                    setText(task?.title.orEmpty()); setHint(R.string.wp_task_hint); styleInput()
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                }
                addView(title)
                whenRow = row(this) {
                    val cal = Calendar.getInstance().apply { if (due > 0) timeInMillis = due else { add(Calendar.HOUR_OF_DAY, 1); set(Calendar.MINUTE, 0) } }
                    DatePickerDialog(ctx, { _, y, m, d ->
                        cal.set(y, m, d)
                        TimePickerDialog(ctx, { _, h, min ->
                            cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, min); cal.set(Calendar.SECOND, 0)
                            due = cal.timeInMillis; remind = true; refresh()
                        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), DateFormat.is24HourFormat(ctx)).show()
                    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                }
                remindRow = row(this) { if (due > 0) { remind = !remind; refresh() } }
                row(this, ctx.getString(R.string.wp_task_clear_date)) { due = 0; remind = false; refresh() }
                refresh()
            }
        })
        dialog.showRespectingStatusBar()
        if (task == null) title.focusAndShowKeyboard()
    }

    private fun row(parent: LinearLayout, text: String = "", onClick: () -> Unit) = TextView(parent.context, null, 0, R.style.TextSmall).apply {
        this.text = text
        textSize = 16f
        minHeight = (44 * resources.displayMetrics.density).toInt()
        gravity = Gravity.CENTER_VERTICAL
        setOnClickListener { onClick() }
        parent.addView(this)
    }

    fun goal(f: Fragment) {
        val ctx = f.requireContext()
        var period = GoalPeriod.WEEK
        lateinit var title: EditText
        lateinit var target: EditText
        lateinit var periodRow: TextView
        fun label(p: GoalPeriod) = ctx.getString(when (p) {
            GoalPeriod.DAY -> R.string.wp_goal_per_day; GoalPeriod.WEEK -> R.string.wp_goal_per_week
            GoalPeriod.MONTH -> R.string.wp_goal_per_month; GoalPeriod.TOTAL -> R.string.wp_goal_in_total
        })
        ctx.createDialog(R.string.wp_add_goal, R.string.okay, onAction = {
            val t = title.text.toString().trim()
            val n = target.text.toString().toIntOrNull() ?: 1
            if (t.isNotEmpty()) f.viewLifecycleOwner.lifecycleScope.launch { ctx.waypoint.space.addGoal(t, n, period) }
        }, content = { parent ->
            LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.MarginLayoutParams(-1, -2).apply { topMargin = (14 * resources.displayMetrics.density).toInt() }
                title = EditText(context).apply { setHint(R.string.wp_goal_hint); styleInput(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
                addView(title)
                val line = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
                target = EditText(context).apply { setText("3"); styleInput(); inputType = InputType.TYPE_CLASS_NUMBER; minEms = 2 }
                line.addView(target)
                periodRow = TextView(context, null, 0, R.style.TextSmall).apply {
                    textSize = 16f; setPadding((12 * resources.displayMetrics.density).toInt(), 0, 0, 0); text = label(period)
                    setOnClickListener {
                        val all = GoalPeriod.values().toList()
                        ctx.chooseOne(R.string.wp_goal_period, all.map { label(it) }) { i -> period = all[i]; text = label(period) }
                    }
                }
                line.addView(periodRow)
                addView(line)
            }
        }).showRespectingStatusBar()
        title.focusAndShowKeyboard()
    }

    fun goalOptions(f: Fragment, g: Goal) {
        val ctx = f.requireContext()
        val space = ctx.waypoint.space
        ctx.chooseOne(R.string.wp_filter_goals, listOf(ctx.getString(R.string.wp_goal_checkin), ctx.getString(R.string.wp_goal_undo), ctx.getString(R.string.wp_delete))) { i ->
            f.viewLifecycleOwner.lifecycleScope.launch {
                when (i) { 0 -> space.checkIn(g.id); 1 -> space.undoCheckIn(g.id); else -> space.deleteGoal(g.id) }
            }
        }
    }
}
