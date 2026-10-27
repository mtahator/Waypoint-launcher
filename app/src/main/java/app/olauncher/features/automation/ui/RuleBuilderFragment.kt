package app.olauncher.features.automation.ui

import android.app.TimePickerDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.navigation.fragment.findNavController
import app.olauncher.R
import app.olauncher.core.ui.ExternalFlow
import app.olauncher.core.ui.PageBuilder
import app.olauncher.core.ui.WpPageFragment
import app.olauncher.core.ui.chooseOne
import app.olauncher.core.ui.pickApp
import app.olauncher.core.ui.pickApps
import app.olauncher.core.ui.promptText
import app.olauncher.features.automation.domain.ActionSpec
import app.olauncher.features.automation.domain.AppEventType
import app.olauncher.features.automation.domain.AutomationRule
import app.olauncher.features.automation.domain.ConditionSpec
import app.olauncher.features.automation.domain.MatchMode
import app.olauncher.features.automation.domain.RuleStatus
import app.olauncher.features.automation.domain.ToggleMode
import app.olauncher.features.automation.domain.TriggerSpec
import app.olauncher.features.gestures.domain.CORNER_GESTURE_EVENT_KEY
import app.olauncher.helper.createDialog
import app.olauncher.helper.showToast
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.UUID

/** Step 1 Trigger → Step 2 Conditions → Step 3 Actions → Review & validate, on one scrolling page. */
class RuleBuilderFragment : WpPageFragment() {
    override val pageTitle = R.string.wp_rule_builder

    private var loaded = false
    private var id = UUID.randomUUID().toString()
    private var createdAt = System.currentTimeMillis()
    private var name = ""
    private var status = RuleStatus.DISABLED
    private var trigger: TriggerSpec = TriggerSpec.Gesture(CORNER_GESTURE_EVENT_KEY)
    private val conditions = mutableListOf<ConditionSpec>()
    private val actions = mutableListOf<ActionSpec>()
    private var cooldownMs = 0L

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (loaded) return
        val editId = arguments?.getString(RuleDetailsFragment.ARG_RULE_ID)
        if (editId == null) {
            loaded = true
            rebuild()
            return
        }
        launchUi {
            container.automationRepository.rule(editId)?.rule?.let { r ->
                id = r.id; createdAt = r.createdAt; name = r.name; status = r.status; trigger = r.trigger
                conditions.clear(); conditions += r.conditions
                actions.clear(); actions += r.actions
                cooldownMs = r.cooldownMillis
            }
            loaded = true
            rebuild()
        }
    }

    private fun draft() = AutomationRule(
        id = id, name = name.trim(), status = status, trigger = trigger, conditions = conditions.toList(),
        actions = actions.toList(), cooldownMillis = cooldownMs, createdAt = createdAt, updatedAt = System.currentTimeMillis(),
    )

    override fun build(page: PageBuilder) {
        if (!loaded) return
        val ctx = requireContext()
        page.card {
            row(getString(R.string.wp_rule_name), value = name.ifBlank { getString(R.string.wp_tap_to_set) }) {
                ctx.promptText(R.string.wp_rule_name, initial = name) { name = it; rebuild() }
            }
        }

        // Step 1: trigger
        page.card {
            header(getString(R.string.wp_step_trigger))
            row(AutomationText.trigger(ctx, trigger), value = getString(R.string.wp_change)) { chooseTrigger() }
            (trigger as? TriggerSpec.Time)?.let { t ->
                row(getString(R.string.wp_time), AutomationText.time(ctx, t.localTime)) { pickTime(t.localTime) { trigger = t.copy(localTime = it); rebuild() } }
                row(getString(R.string.wp_days), AutomationText.days(ctx, t.days)) { pickDays(t.days) { trigger = t.copy(days = it); rebuild() } }
                toggle(getString(R.string.wp_precise), t.precise, getString(R.string.wp_precise_hint)) { on ->
                    trigger = t.copy(precise = on)
                    if (on && !container.scheduler.canScheduleExact() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        ctx.showToast(R.string.wp_exact_alarm_permission)
                        ExternalFlow.start(this@RuleBuilderFragment,
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${ctx.packageName}".toUri()))
                    }
                }
                note(getString(R.string.wp_time_inexact_note))
            }
            (trigger as? TriggerSpec.NotificationReceived)?.let { t ->
                row(getString(R.string.wp_apps), t.packages.size.toString()) {
                    ctx.pickApps(this@RuleBuilderFragment, t.packages) { trigger = TriggerSpec.NotificationReceived(it); rebuild() }
                }
                note(getString(R.string.wp_notification_trigger_note))
            }
        }

        // Step 2: conditions
        page.card {
            header(getString(R.string.wp_step_conditions))
            if (conditions.isEmpty()) note(getString(R.string.wp_no_conditions))
            conditions.forEachIndexed { i, c ->
                row(AutomationText.condition(ctx, c), value = getString(R.string.wp_remove)) { conditions.removeAt(i); rebuild() }
            }
            row(getString(R.string.wp_add_condition), value = "+") { addCondition() }
        }

        // Step 3: actions
        page.card {
            header(getString(R.string.wp_step_actions))
            if (actions.isEmpty()) note(getString(R.string.wp_no_actions))
            actions.forEachIndexed { i, a ->
                row("${i + 1}. " + AutomationText.action(ctx, a)) { actionMenu(i) }
            }
            if (actions.size < AutomationRule.MAX_ACTIONS) row(getString(R.string.wp_add_action), value = "+") { addAction() }
            val cooldowns = listOf(0L, 60_000L, 300_000L, 900_000L, 3_600_000L)
            row(getString(R.string.wp_cooldown), cooldownLabel(cooldownMs)) {
                ctx.chooseOne(R.string.wp_cooldown, cooldowns.map { cooldownLabel(it) }) { cooldownMs = cooldowns[it]; rebuild() }
            }
        }

        // Review
        val validation = RuleActions.validate(container, this, draft().copy(status = RuleStatus.ENABLED))
        page.card {
            header(getString(R.string.wp_step_review))
            if (validation.errors.isEmpty() && validation.warnings.isEmpty()) note(getString(R.string.wp_rule_valid))
            if (validation.errors.isNotEmpty()) note(validation.errors.joinToString("\n") { "• " + AutomationText.issue(ctx, it) })
            if (validation.warnings.isNotEmpty()) note(validation.warnings.joinToString("\n") { "⚠ " + AutomationText.issue(ctx, it) })
            if (validation.canEnable) row(getString(R.string.wp_save_and_enable)) { save(RuleStatus.ENABLED) }
            row(getString(R.string.wp_save_disabled)) { save(RuleStatus.DISABLED) }
        }
    }

    private fun cooldownLabel(ms: Long): String = when (ms) {
        0L -> getString(R.string.wp_cooldown_none)
        3_600_000L -> getString(R.string.wp_cooldown_hour)
        else -> getString(R.string.wp_cooldown_minutes, (ms / 60_000L).toInt())
    }

    private fun save(target: RuleStatus) {
        if (name.isBlank()) {
            requireContext().showToast(R.string.wp_issue_name)
            return
        }
        launchUi {
            val rule = draft().copy(status = target)
            val stored = RuleActions.save(container, this@RuleBuilderFragment, rule)
            RuleActions.requestNotificationPermissionIfNeeded(this@RuleBuilderFragment, rule.copy(status = stored), notificationPermission)
            requireContext().showToast(if (stored == RuleStatus.ENABLED) R.string.wp_rule_saved_enabled else R.string.wp_rule_saved)
            findNavController().popBackStack()
        }
    }

    private fun chooseTrigger() {
        val options: List<Pair<Int, () -> Unit>> = listOf(
            R.string.wp_trigger_corner to { trigger = TriggerSpec.Gesture(CORNER_GESTURE_EVENT_KEY); onTriggerChanged() },
            R.string.wp_trigger_home_opened to { trigger = TriggerSpec.AppEvent(AppEventType.LAUNCHER_OPENED); onTriggerChanged() },
            R.string.wp_trigger_monochrome_changed to { trigger = TriggerSpec.AppEvent(AppEventType.MONOCHROME_CHANGED); onTriggerChanged() },
            R.string.wp_trigger_time_option to {
                trigger = TriggerSpec.Time(LocalTime.of(8, 0), DayOfWeek.entries.toSet()); onTriggerChanged()
            },
            R.string.wp_trigger_notification_option to {
                requireContext().pickApps(this, emptySet()) { trigger = TriggerSpec.NotificationReceived(it); onTriggerChanged() }
            },
        )
        requireContext().chooseOne(R.string.wp_step_trigger, options.map { getString(it.first) }) { options[it].second() }
    }

    /** Drops conditions/actions that only make sense for notification triggers. */
    private fun onTriggerChanged() {
        if (trigger !is TriggerSpec.NotificationReceived) {
            conditions.removeAll { it is ConditionSpec.SourcePackageIn || it is ConditionSpec.TextMatches || it is ConditionSpec.AmountExtractable }
            actions.removeAll { it is ActionSpec.SaveTransaction }
        }
        rebuild()
    }

    private fun addCondition() {
        val ctx = requireContext()
        val isNotification = trigger is TriggerSpec.NotificationReceived
        val options = mutableListOf<Pair<Int, () -> Unit>>(
            R.string.wp_cond_option_days to {
                pickDays(DayOfWeek.entries.toSet() - setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) {
                    conditions += ConditionSpec.DayOfWeekIn(it); rebuild()
                }
            },
            R.string.wp_cond_option_time to {
                pickTime(LocalTime.of(9, 0)) { start ->
                    pickTime(LocalTime.of(17, 0)) { end -> conditions += ConditionSpec.TimeBetween(start, end); rebuild() }
                }
            },
        )
        if (isNotification) {
            options += R.string.wp_cond_option_source to {
                ctx.pickApps(this, emptySet()) { if (it.isNotEmpty()) { conditions += ConditionSpec.SourcePackageIn(it); rebuild() } }
            }
            options += R.string.wp_cond_option_contains to { promptPattern(MatchMode.CONTAINS) }
            options += R.string.wp_cond_option_starts to { promptPattern(MatchMode.STARTS_WITH) }
            options += R.string.wp_cond_option_regex to { promptPattern(MatchMode.SAFE_REGEX) }
            options += R.string.wp_cond_option_amount to { conditions += ConditionSpec.AmountExtractable; rebuild() }
        }
        ctx.chooseOne(R.string.wp_add_condition, options.map { getString(it.first) }) { options[it].second() }
    }

    private fun promptPattern(mode: MatchMode) {
        requireContext().promptText(R.string.wp_pattern, hint = R.string.wp_pattern_hint) {
            if (it.isNotEmpty()) conditions += ConditionSpec.TextMatches(mode, it.take(200), ignoreCase = true)
            rebuild()
        }
    }

    private fun addAction() {
        val ctx = requireContext()
        val options = mutableListOf<Pair<Int, () -> Unit>>(
            R.string.wp_act_option_notification to {
                ctx.promptText(R.string.wp_notification_title) { title ->
                    ctx.promptText(R.string.wp_notification_text, multiLine = true) { text ->
                        actions += ActionSpec.ShowNotification(title.take(80), text.take(300)); rebuild()
                    }
                }
            },
            R.string.wp_act_option_monochrome to { chooseMode { actions += ActionSpec.SetLauncherMonochrome(it); rebuild() } },
            R.string.wp_act_option_grayscale to { chooseMode { actions += ActionSpec.SetSystemGrayscale(it); rebuild() } },
            R.string.wp_act_option_open_app to {
                ctx.pickApp(this) { app -> actions += ActionSpec.OpenApp(app.packageName, app.userSerial, app.label); rebuild() }
            },
        )
        if (trigger is TriggerSpec.NotificationReceived)
            options += R.string.wp_act_option_save_tx to { actions += ActionSpec.SaveTransaction; rebuild() }
        ctx.chooseOne(R.string.wp_add_action, options.map { getString(it.first) }) { options[it].second() }
    }

    private fun chooseMode(onChosen: (ToggleMode) -> Unit) {
        val modes = listOf(ToggleMode.ON, ToggleMode.OFF, ToggleMode.TOGGLE)
        val labels = listOf(R.string.wp_mode_on, R.string.wp_mode_off, R.string.wp_mode_toggle).map { getString(it) }
        requireContext().chooseOne(R.string.wp_mode, labels) { onChosen(modes[it]) }
    }

    private fun actionMenu(index: Int) {
        val options = buildList<Pair<String, () -> Unit>> {
            if (index > 0) add(getString(R.string.wp_move_up) to { actions.add(index - 1, actions.removeAt(index)); rebuild() })
            if (index < actions.size - 1) add(getString(R.string.wp_move_down) to { actions.add(index + 1, actions.removeAt(index)); rebuild() })
            add(getString(R.string.wp_remove) to { actions.removeAt(index); rebuild() })
        }
        requireContext().chooseOne(R.string.wp_action, options.map { it.first }) { options[it].second() }
    }

    private fun pickTime(initial: LocalTime, onPicked: (LocalTime) -> Unit) {
        TimePickerDialog(requireContext(), { _, h, m -> onPicked(LocalTime.of(h, m)) },
            initial.hour, initial.minute, DateFormat.is24HourFormat(requireContext())).show()
    }

    private fun pickDays(initial: Set<DayOfWeek>, onPicked: (Set<DayOfWeek>) -> Unit) {
        val chosen = initial.toMutableSet()
        requireContext().createDialog(R.string.wp_days, R.string.wp_done, onAction = { onPicked(chosen.toSet()) }, content = { parent ->
            LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = (12 * resources.displayMetrics.density).toInt() }
                val b = PageBuilder(this)
                DayOfWeek.entries.forEach { d ->
                    b.toggle(d.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()), d in chosen) { on ->
                        if (on) chosen += d else chosen -= d
                    }
                }
            }
        }).showRespectingStatusBar()
    }
}
