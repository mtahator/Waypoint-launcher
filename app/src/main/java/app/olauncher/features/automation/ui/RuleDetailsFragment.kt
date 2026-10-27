package app.olauncher.features.automation.ui

import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import app.olauncher.R
import app.olauncher.core.ui.PageBuilder
import app.olauncher.core.ui.WpPageFragment
import app.olauncher.core.ui.openPage
import app.olauncher.features.automation.data.RuleRow
import app.olauncher.features.automation.domain.AutomationEvent
import app.olauncher.features.automation.domain.AutomationRule
import app.olauncher.features.automation.domain.ExecutionRecord
import app.olauncher.features.automation.domain.RuleStatus
import app.olauncher.features.automation.domain.TriggerSpec
import app.olauncher.helper.createDialog
import app.olauncher.helper.showToast

class RuleDetailsFragment : WpPageFragment() {
    override val pageTitle = R.string.wp_rule

    private var row: RuleRow? = null
    private var history: List<ExecutionRecord> = emptyList()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val ruleId get() = requireArguments().getString(ARG_RULE_ID).orEmpty()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        load()
    }

    private fun load() = launchUi {
        row = container.automationRepository.rule(ruleId)
        history = container.automationRepository.history(ruleId)
        rebuild()
    }

    override fun build(page: PageBuilder) {
        val r = row ?: return
        val ctx = requireContext()
        val rule = r.rule
        binding.pageTitle.text = r.name
        if (rule == null) {
            page.card {
                note(getString(R.string.wp_rule_invalid_explainer))
                row(getString(R.string.delete)) { confirmDelete() }
            }
            return
        }
        val validation = RuleActions.validate(container, this, rule.copy(status = RuleStatus.ENABLED))
        page.card {
            toggle(getString(R.string.wp_enabled), rule.isEnabled) { on ->
                if (on && !validation.canEnable) {
                    ctx.showToast(R.string.wp_fix_errors_first)
                    rebuild()
                    return@toggle
                }
                launchUi {
                    val updated = rule.copy(status = if (on) RuleStatus.ENABLED else RuleStatus.DISABLED, updatedAt = System.currentTimeMillis())
                    RuleActions.save(container, this@RuleDetailsFragment, updated)
                    RuleActions.requestNotificationPermissionIfNeeded(this@RuleDetailsFragment, updated, notificationPermission)
                    load()
                }
            }
            if (validation.errors.isNotEmpty()) note(validation.errors.joinToString("\n") { "• " + AutomationText.issue(ctx, it) })
            if (validation.warnings.isNotEmpty()) note(validation.warnings.joinToString("\n") { "• " + AutomationText.issue(ctx, it) })
        }
        page.card {
            header(getString(R.string.wp_when))
            note(AutomationText.trigger(ctx, rule.trigger))
            if (rule.trigger is TriggerSpec.Time) note(getString(R.string.wp_time_inexact_note))
            if (rule.conditions.isNotEmpty()) {
                header(getString(R.string.wp_if))
                note(rule.conditions.joinToString("\n") { "• " + AutomationText.condition(ctx, it) })
            }
            header(getString(R.string.wp_then))
            note(rule.actions.mapIndexed { i, a -> "${i + 1}. " + AutomationText.action(ctx, a) }.joinToString("\n"))
        }
        page.card {
            row(getString(R.string.wp_run_test), subtitle = getString(R.string.wp_run_test_hint)) { runTest(rule) }
            row(getString(R.string.wp_edit)) { openPage(R.id.ruleBuilderFragment, bundleOf(ARG_RULE_ID to rule.id)) }
            row(getString(R.string.delete)) { confirmDelete() }
        }
        page.card {
            header(getString(R.string.wp_history))
            if (history.isEmpty()) note(getString(R.string.wp_no_history))
            history.forEach { e ->
                row(
                    AutomationText.outcome(ctx, e.outcome) + " · " + AutomationText.reason(ctx, e.reasonCode),
                    subtitle = DateUtils.formatDateTime(ctx, e.startedAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME) +
                        (if (e.detail.isNotEmpty()) " · " + getString(R.string.wp_failed_at_action, e.detail) else ""),
                )
            }
        }
    }

    private fun runTest(rule: AutomationRule) = launchUi {
        val now = System.currentTimeMillis()
        val event: AutomationEvent = when (val t = rule.trigger) {
            is TriggerSpec.Gesture -> AutomationEvent.Gesture(t.eventKey, now)
            is TriggerSpec.AppEvent -> AutomationEvent.App(t.event, now)
            is TriggerSpec.Time -> AutomationEvent.TimeFired(setOf(rule.id), now, now)
            is TriggerSpec.NotificationReceived -> AutomationEvent.NotificationReceived(
                packageName = t.packages.firstOrNull().orEmpty(), notificationKey = "test",
                title = getString(R.string.wp_test_notification_title), text = getString(R.string.wp_test_notification_text),
                postTime = now, redacted = false, occurredAt = now,
            )
        }
        val result = container.engine.dryRun(rule, event)
        val ctx = requireContext()
        ctx.showToast(
            getString(R.string.wp_test_result, AutomationText.outcome(ctx, result.outcome), AutomationText.reason(ctx, result.reasonCode)),
            android.widget.Toast.LENGTH_LONG,
        )
    }

    private fun confirmDelete() {
        requireContext().createDialog(R.string.wp_delete_rule, R.string.delete, message = R.string.wp_delete_rule_message, onAction = {
            launchUi {
                container.automationRepository.delete(ruleId)
                container.coordinator.onRulesChanged()
                findNavController().popBackStack()
            }
        }).showRespectingStatusBar()
    }

    companion object {
        const val ARG_RULE_ID = "rule_id"
    }
}
