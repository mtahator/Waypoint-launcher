package app.olauncher.features.automation

import app.olauncher.features.automation.domain.ActionSpec
import app.olauncher.features.automation.domain.AppEventType
import app.olauncher.features.automation.domain.AutomationRule
import app.olauncher.features.automation.domain.ConditionSpec
import app.olauncher.features.automation.domain.MatchMode
import app.olauncher.features.automation.domain.RuleStatus
import app.olauncher.features.automation.domain.RuleValidator
import app.olauncher.features.automation.domain.ToggleMode
import app.olauncher.features.automation.domain.TriggerSpec
import app.olauncher.features.automation.domain.ValidationEnvironment
import app.olauncher.features.automation.domain.ValidationIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

class RuleValidatorTest {
    private class Env(var installed: Boolean = true, var grayscale: Boolean = false) : ValidationEnvironment {
        override fun isAppInstalled(packageName: String, userHandleId: Long) = installed
        override fun isSystemGrayscaleAvailable() = grayscale
        override fun isValidZone(zoneId: String) = zoneId.contains('/')
    }

    private val env = Env()
    private val validator = RuleValidator(env)

    private fun rule(
        trigger: TriggerSpec = TriggerSpec.Gesture("corner_swipe"),
        conditions: List<ConditionSpec> = emptyList(),
        actions: List<ActionSpec> = listOf(ActionSpec.SetLauncherMonochrome(ToggleMode.TOGGLE)),
        name: String = "Rule",
        status: RuleStatus = RuleStatus.ENABLED,
    ) = AutomationRule("id", name, status, trigger, conditions, actions, createdAt = 0, updatedAt = 0)

    private fun errors(r: AutomationRule) = validator.validate(r).errors
    private fun warnings(r: AutomationRule) = validator.validate(r).warnings

    @Test
    fun validRuleHasNoIssues() {
        val result = validator.validate(rule())
        assertTrue(result.canEnable)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun basicStructure() {
        assertTrue(ValidationIssue.EMPTY_NAME in errors(rule(name = "  ")))
        assertTrue(ValidationIssue.NO_ACTIONS in errors(rule(actions = emptyList())))
        val six = List(6) { ActionSpec.ShowNotification("t", "x") }
        assertTrue(ValidationIssue.TOO_MANY_ACTIONS in errors(rule(actions = six)))
        assertTrue(ValidationIssue.EMPTY_NOTIFICATION_TEXT in errors(rule(actions = listOf(ActionSpec.ShowNotification(" ", "")))))
    }

    @Test
    fun openAppNotInstalledIsErrorWhenEnabledWarningWhenDisabled() {
        env.installed = false
        val action = listOf(ActionSpec.OpenApp("com.spotify.music", 0))
        assertTrue(ValidationIssue.APP_NOT_INSTALLED in errors(rule(actions = action)))
        val disabled = rule(actions = action, status = RuleStatus.DISABLED)
        assertFalse(ValidationIssue.APP_NOT_INSTALLED in errors(disabled))
        assertTrue(ValidationIssue.APP_NOT_INSTALLED in warnings(disabled))
    }

    @Test
    fun saveTransactionNeedsNotificationTrigger() {
        assertTrue(ValidationIssue.SAVE_TRANSACTION_NEEDS_NOTIFICATION_TRIGGER in errors(rule(actions = listOf(ActionSpec.SaveTransaction))))
        val ok = rule(TriggerSpec.NotificationReceived(setOf("com.bank")), actions = listOf(ActionSpec.SaveTransaction))
        assertTrue(errors(ok).isEmpty())
    }

    @Test
    fun regexGuards() {
        val n = TriggerSpec.NotificationReceived(setOf("com.bank"))
        fun regex(p: String) = errors(rule(n, listOf(ConditionSpec.TextMatches(MatchMode.SAFE_REGEX, p))))
        assertTrue(regex("Paid \\d+").isEmpty())
        assertTrue(ValidationIssue.REGEX_TOO_LONG in regex("a".repeat(201)))
        assertTrue(ValidationIssue.REGEX_DANGEROUS in regex("(a+)+$"))
        assertTrue(ValidationIssue.REGEX_DANGEROUS in regex("(\\w*)*x"))
        assertTrue(ValidationIssue.REGEX_INVALID in regex("([unclosed"))
        assertTrue(ValidationIssue.EMPTY_PATTERN in errors(rule(n, listOf(ConditionSpec.TextMatches(MatchMode.CONTAINS, "")))))
    }

    @Test
    fun notificationConditionsNeedNotificationTrigger() {
        assertTrue(ValidationIssue.NOTIFICATION_CONDITION_WRONG_TRIGGER in errors(rule(conditions = listOf(ConditionSpec.AmountExtractable))))
        assertTrue(ValidationIssue.NOTIFICATION_NO_PACKAGES in errors(rule(TriggerSpec.NotificationReceived(emptySet()))))
    }

    @Test
    fun grayscaleWithoutPermissionIsWarning() {
        val r = rule(actions = listOf(ActionSpec.SetSystemGrayscale(ToggleMode.ON)))
        assertEquals(listOf(ValidationIssue.GRAYSCALE_PERMISSION_MISSING), warnings(r))
        assertTrue(validator.validate(r).canEnable)
        env.grayscale = true
        assertTrue(warnings(r).isEmpty())
    }

    @Test
    fun timeTriggerNeedsDaysAndValidZone() {
        assertTrue(ValidationIssue.TIME_NO_DAYS in errors(rule(TriggerSpec.Time(LocalTime.NOON, emptySet()))))
        assertTrue(ValidationIssue.INVALID_ZONE in errors(rule(TriggerSpec.Time(LocalTime.NOON, setOf(DayOfWeek.MONDAY), "Nowhere"))))
        assertTrue(errors(rule(TriggerSpec.Time(LocalTime.NOON, setOf(DayOfWeek.MONDAY), "Europe/London"))).isEmpty())
    }

    @Test
    fun selfLoopIsAnError() {
        val loop = rule(TriggerSpec.AppEvent(AppEventType.MONOCHROME_CHANGED), actions = listOf(ActionSpec.SetLauncherMonochrome(ToggleMode.TOGGLE)))
        assertTrue(ValidationIssue.SELF_LOOP in errors(loop))
        assertFalse(validator.validate(loop).canEnable)
    }
}
