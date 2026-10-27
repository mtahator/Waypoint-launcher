package app.olauncher.features.automation.ui

import android.content.Context
import android.text.format.DateFormat
import app.olauncher.R
import app.olauncher.features.automation.domain.ActionSpec
import app.olauncher.features.automation.domain.AppEventType
import app.olauncher.features.automation.domain.ConditionSpec
import app.olauncher.features.automation.domain.MatchMode
import app.olauncher.features.automation.domain.Outcome
import app.olauncher.features.automation.domain.ReasonCode
import app.olauncher.features.automation.domain.ToggleMode
import app.olauncher.features.automation.domain.TriggerSpec
import app.olauncher.features.automation.domain.ValidationIssue
import app.olauncher.features.gestures.domain.CORNER_GESTURE_EVENT_KEY
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Human-readable descriptions of rules and results. All copy comes from strings.xml. */
object AutomationText {

    fun time(context: Context, t: LocalTime): String =
        DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a").format(t)

    fun days(context: Context, days: Set<DayOfWeek>): String = when {
        days.size == 7 -> context.getString(R.string.wp_every_day)
        days == setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> context.getString(R.string.wp_weekends)
        days == DayOfWeek.entries.toSet() - setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> context.getString(R.string.wp_weekdays)
        days.isEmpty() -> context.getString(R.string.wp_no_days)
        else -> days.sorted().joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
    }

    fun appLabel(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    fun trigger(context: Context, t: TriggerSpec): String = when (t) {
        is TriggerSpec.Gesture ->
            if (t.eventKey == CORNER_GESTURE_EVENT_KEY) context.getString(R.string.wp_trigger_corner) else context.getString(R.string.wp_trigger_gesture, t.eventKey)
        is TriggerSpec.AppEvent -> context.getString(
            when (t.event) {
                AppEventType.LAUNCHER_OPENED -> R.string.wp_trigger_home_opened
                AppEventType.MONOCHROME_CHANGED -> R.string.wp_trigger_monochrome_changed
            }
        )
        is TriggerSpec.Time -> context.getString(
            if (t.precise) R.string.wp_trigger_time_precise else R.string.wp_trigger_time,
            time(context, t.localTime), days(context, t.days),
        )
        is TriggerSpec.NotificationReceived -> context.getString(
            R.string.wp_trigger_notification, t.packages.joinToString(", ") { appLabel(context, it) }.ifEmpty { "—" }
        )
    }

    fun condition(context: Context, c: ConditionSpec): String = when (c) {
        is ConditionSpec.DayOfWeekIn -> context.getString(R.string.wp_cond_days, days(context, c.days))
        is ConditionSpec.TimeBetween -> context.getString(R.string.wp_cond_time, time(context, c.start), time(context, c.end))
        is ConditionSpec.SourcePackageIn -> context.getString(R.string.wp_cond_source, c.packages.joinToString(", ") { appLabel(context, it) })
        is ConditionSpec.TextMatches -> context.getString(
            when (c.mode) {
                MatchMode.CONTAINS -> R.string.wp_cond_contains
                MatchMode.STARTS_WITH -> R.string.wp_cond_starts_with
                MatchMode.SAFE_REGEX -> R.string.wp_cond_regex
            }, c.pattern
        )
        ConditionSpec.AmountExtractable -> context.getString(R.string.wp_cond_amount)
    }

    private fun toggle(context: Context, m: ToggleMode) = context.getString(
        when (m) {
            ToggleMode.ON -> R.string.wp_mode_on
            ToggleMode.OFF -> R.string.wp_mode_off
            ToggleMode.TOGGLE -> R.string.wp_mode_toggle
        }
    )

    fun action(context: Context, a: ActionSpec): String = when (a) {
        is ActionSpec.ShowNotification -> context.getString(R.string.wp_act_notification, a.title.ifBlank { a.text })
        is ActionSpec.SetLauncherMonochrome -> context.getString(R.string.wp_act_monochrome, toggle(context, a.mode))
        is ActionSpec.SetSystemGrayscale -> context.getString(R.string.wp_act_grayscale, toggle(context, a.mode))
        is ActionSpec.OpenApp -> context.getString(R.string.wp_act_open_app, a.label.ifBlank { appLabel(context, a.packageName) })
        ActionSpec.SaveTransaction -> context.getString(R.string.wp_act_save_transaction)
    }

    fun outcome(context: Context, o: Outcome) = context.getString(
        when (o) {
            Outcome.SUCCESS -> R.string.wp_outcome_success
            Outcome.SKIPPED -> R.string.wp_outcome_skipped
            Outcome.FAILED -> R.string.wp_outcome_failed
        }
    )

    fun reason(context: Context, r: ReasonCode) = context.getString(
        when (r) {
            ReasonCode.OK -> R.string.wp_reason_ok
            ReasonCode.DRY_RUN -> R.string.wp_reason_dry_run
            ReasonCode.CONDITION_DAY -> R.string.wp_reason_day
            ReasonCode.CONDITION_TIME -> R.string.wp_reason_time
            ReasonCode.CONDITION_SOURCE -> R.string.wp_reason_source
            ReasonCode.CONDITION_TEXT -> R.string.wp_reason_text
            ReasonCode.CONDITION_AMOUNT -> R.string.wp_reason_amount
            ReasonCode.COOLDOWN -> R.string.wp_reason_cooldown
            ReasonCode.DUPLICATE -> R.string.wp_reason_duplicate
            ReasonCode.LOOP_PREVENTED -> R.string.wp_reason_loop
            ReasonCode.APP_NOT_INSTALLED -> R.string.wp_reason_app_missing
            ReasonCode.PERMISSION_MISSING -> R.string.wp_reason_permission
            ReasonCode.NOT_AVAILABLE -> R.string.wp_reason_not_available
            ReasonCode.NOT_APPLICABLE -> R.string.wp_reason_not_applicable
            ReasonCode.INVALID_RULE -> R.string.wp_reason_invalid
            ReasonCode.ACTION_ERROR -> R.string.wp_reason_error
            ReasonCode.POSTED_AS_NOTIFICATION -> R.string.wp_reason_posted
        }
    )

    fun issue(context: Context, i: ValidationIssue) = context.getString(
        when (i) {
            ValidationIssue.EMPTY_NAME -> R.string.wp_issue_name
            ValidationIssue.NO_ACTIONS -> R.string.wp_issue_no_actions
            ValidationIssue.TOO_MANY_ACTIONS -> R.string.wp_issue_too_many_actions
            ValidationIssue.APP_NOT_INSTALLED -> R.string.wp_issue_app_missing
            ValidationIssue.SAVE_TRANSACTION_NEEDS_NOTIFICATION_TRIGGER -> R.string.wp_issue_save_tx
            ValidationIssue.REGEX_TOO_LONG -> R.string.wp_issue_regex_long
            ValidationIssue.REGEX_DANGEROUS -> R.string.wp_issue_regex_dangerous
            ValidationIssue.REGEX_INVALID -> R.string.wp_issue_regex_invalid
            ValidationIssue.EMPTY_PATTERN -> R.string.wp_issue_empty_pattern
            ValidationIssue.GRAYSCALE_PERMISSION_MISSING -> R.string.wp_issue_grayscale
            ValidationIssue.TIME_NO_DAYS -> R.string.wp_issue_no_days
            ValidationIssue.CONDITION_DAYS_EMPTY -> R.string.wp_issue_no_days
            ValidationIssue.NOTIFICATION_NO_PACKAGES -> R.string.wp_issue_no_packages
            ValidationIssue.GESTURE_NO_EVENT -> R.string.wp_issue_gesture
            ValidationIssue.SELF_LOOP -> R.string.wp_issue_loop
            ValidationIssue.EMPTY_NOTIFICATION_TEXT -> R.string.wp_issue_notification_text
            ValidationIssue.NOTIFICATION_CONDITION_WRONG_TRIGGER -> R.string.wp_issue_condition_trigger
            ValidationIssue.INVALID_ZONE -> R.string.wp_issue_zone
        }
    )
}
