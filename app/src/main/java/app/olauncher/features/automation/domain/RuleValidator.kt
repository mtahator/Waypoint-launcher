package app.olauncher.features.automation.domain

enum class ValidationIssue {
    EMPTY_NAME,
    NO_ACTIONS,
    TOO_MANY_ACTIONS,
    APP_NOT_INSTALLED,
    SAVE_TRANSACTION_NEEDS_NOTIFICATION_TRIGGER,
    REGEX_TOO_LONG,
    REGEX_DANGEROUS,
    REGEX_INVALID,
    EMPTY_PATTERN,
    GRAYSCALE_PERMISSION_MISSING,
    TIME_NO_DAYS,
    CONDITION_DAYS_EMPTY,
    NOTIFICATION_NO_PACKAGES,
    GESTURE_NO_EVENT,
    SELF_LOOP,
    EMPTY_NOTIFICATION_TEXT,
    NOTIFICATION_CONDITION_WRONG_TRIGGER,
    INVALID_ZONE,
}

data class RuleValidationResult(val errors: List<ValidationIssue>, val warnings: List<ValidationIssue>) {
    val canEnable get() = errors.isEmpty()
}

/** What the validator needs to know about the device, behind an interface for tests. */
interface ValidationEnvironment {
    fun isAppInstalled(packageName: String, userHandleId: Long): Boolean
    fun isSystemGrayscaleAvailable(): Boolean
    fun isValidZone(zoneId: String): Boolean
}

class RuleValidator(private val env: ValidationEnvironment) {

    fun validate(rule: AutomationRule): RuleValidationResult {
        val errors = mutableListOf<ValidationIssue>()
        val warnings = mutableListOf<ValidationIssue>()
        val trigger = rule.trigger

        if (rule.name.isBlank()) errors += ValidationIssue.EMPTY_NAME
        if (rule.actions.isEmpty()) errors += ValidationIssue.NO_ACTIONS
        if (rule.actions.size > AutomationRule.MAX_ACTIONS) errors += ValidationIssue.TOO_MANY_ACTIONS

        when (trigger) {
            is TriggerSpec.Time -> {
                if (trigger.days.isEmpty()) errors += ValidationIssue.TIME_NO_DAYS
                if (trigger.zoneId != null && !env.isValidZone(trigger.zoneId)) errors += ValidationIssue.INVALID_ZONE
            }
            is TriggerSpec.NotificationReceived ->
                if (trigger.packages.isEmpty()) errors += ValidationIssue.NOTIFICATION_NO_PACKAGES
            is TriggerSpec.Gesture -> if (trigger.eventKey.isBlank()) errors += ValidationIssue.GESTURE_NO_EVENT
            is TriggerSpec.AppEvent -> Unit
        }

        for (c in rule.conditions) when (c) {
            is ConditionSpec.TextMatches -> {
                if (trigger !is TriggerSpec.NotificationReceived) errors += ValidationIssue.NOTIFICATION_CONDITION_WRONG_TRIGGER
                if (c.pattern.isEmpty()) errors += ValidationIssue.EMPTY_PATTERN
                else if (c.mode == MatchMode.SAFE_REGEX) when (SafeRegex.check(c.pattern)) {
                    SafeRegex.Check.TooLong -> errors += ValidationIssue.REGEX_TOO_LONG
                    SafeRegex.Check.Dangerous -> errors += ValidationIssue.REGEX_DANGEROUS
                    SafeRegex.Check.Invalid -> errors += ValidationIssue.REGEX_INVALID
                    SafeRegex.Check.Ok -> Unit
                }
            }
            is ConditionSpec.SourcePackageIn, ConditionSpec.AmountExtractable ->
                if (trigger !is TriggerSpec.NotificationReceived) errors += ValidationIssue.NOTIFICATION_CONDITION_WRONG_TRIGGER
            is ConditionSpec.DayOfWeekIn -> if (c.days.isEmpty()) errors += ValidationIssue.CONDITION_DAYS_EMPTY
            is ConditionSpec.TimeBetween -> Unit
        }

        for (a in rule.actions) when (a) {
            is ActionSpec.OpenApp -> if (!env.isAppInstalled(a.packageName, a.userHandleId)) {
                if (rule.isEnabled) errors += ValidationIssue.APP_NOT_INSTALLED else warnings += ValidationIssue.APP_NOT_INSTALLED
            }
            ActionSpec.SaveTransaction -> if (trigger !is TriggerSpec.NotificationReceived)
                errors += ValidationIssue.SAVE_TRANSACTION_NEEDS_NOTIFICATION_TRIGGER
            is ActionSpec.SetSystemGrayscale -> if (!env.isSystemGrayscaleAvailable())
                warnings += ValidationIssue.GRAYSCALE_PERMISSION_MISSING
            is ActionSpec.ShowNotification -> if (a.title.isBlank() && a.text.isBlank())
                errors += ValidationIssue.EMPTY_NOTIFICATION_TEXT
            is ActionSpec.SetLauncherMonochrome -> Unit
        }

        // A rule reacting to monochrome changes must not itself change monochrome: it would feed itself.
        if (trigger is TriggerSpec.AppEvent && trigger.event == AppEventType.MONOCHROME_CHANGED &&
            rule.actions.any { it is ActionSpec.SetLauncherMonochrome }
        ) errors += ValidationIssue.SELF_LOOP

        return RuleValidationResult(errors.distinct(), warnings.distinct())
    }
}
