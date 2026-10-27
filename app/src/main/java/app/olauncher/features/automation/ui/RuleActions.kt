package app.olauncher.features.automation.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.Fragment
import app.olauncher.core.AppContainer
import app.olauncher.features.automation.domain.ActionSpec
import app.olauncher.features.automation.domain.AutomationRule
import app.olauncher.features.automation.domain.RuleStatus
import app.olauncher.features.automation.domain.RuleValidationResult
import app.olauncher.features.automation.domain.RuleValidator
import app.olauncher.features.automation.platform.AndroidValidationEnvironment

/** Shared save/enable logic for the builder and the details screen. */
object RuleActions {

    fun validate(container: AppContainer, fragment: Fragment, rule: AutomationRule): RuleValidationResult =
        RuleValidator(AndroidValidationEnvironment(fragment.requireContext(), container.grayscale)).validate(rule)

    /** Saves [rule]; refuses to save it ENABLED if validation has errors. Returns the stored status. */
    suspend fun save(container: AppContainer, fragment: Fragment, rule: AutomationRule): RuleStatus {
        val status = if (rule.isEnabled && !validate(container, fragment, rule).canEnable) RuleStatus.DISABLED else rule.status
        container.automationRepository.save(rule.copy(status = status))
        container.coordinator.onRulesChanged()
        return status
    }

    /** Just-in-time POST_NOTIFICATIONS request when an enabled rule may post notifications. */
    fun requestNotificationPermissionIfNeeded(fragment: Fragment, rule: AutomationRule, launcher: ActivityResultLauncher<String>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !rule.isEnabled) return
        val posts = rule.actions.any { it is ActionSpec.ShowNotification || it is ActionSpec.OpenApp }
        if (posts && fragment.requireContext().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
