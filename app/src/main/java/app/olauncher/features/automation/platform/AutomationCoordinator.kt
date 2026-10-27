package app.olauncher.features.automation.platform

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import app.olauncher.core.WaypointPrefs
import app.olauncher.features.automation.data.AutomationRepository
import app.olauncher.features.automation.domain.TriggerSpec
import app.olauncher.features.notifications.platform.NotificationCaptureService

/**
 * Keeps the cheap, always-loaded caches (prefs) in sync with the rule database after every CRUD:
 * enabled-rule count and trigger types (so the home path never opens the DB), watched notification
 * packages, the single next alarm, and whether the notification listener should be bound.
 */
class AutomationCoordinator(
    private val context: Context,
    private val prefs: WaypointPrefs,
    private val repository: () -> AutomationRepository,
    private val scheduler: () -> NextTimeTriggerScheduler,
) {
    suspend fun onRulesChanged() {
        val enabled = repository().allEnabledRules()
        prefs.enabledRuleCount = enabled.size
        prefs.enabledRuleTypes = enabled.mapTo(HashSet()) { it.trigger.typeKey }
        prefs.rulePackages = enabled.flatMapTo(HashSet()) {
            (it.trigger as? TriggerSpec.NotificationReceived)?.packages.orEmpty()
        }
        scheduler().reschedule(enabled)
        updateListenerBinding()
    }

    suspend fun rescheduleTimeRules(after: Long = System.currentTimeMillis()) {
        if (TriggerSpec.TYPE_TIME !in prefs.enabledRuleTypes) {
            scheduler().reschedule(emptyList())
            return
        }
        scheduler().reschedule(repository().allEnabledRules(), after)
    }

    /** Listener idles (unbinds) when neither a notification rule nor ledger capture needs it. */
    fun updateListenerBinding() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val needed = needsListener()
        if (needed && NotificationCaptureService.hasAccess(context)) {
            runCatching {
                NotificationListenerService.requestRebind(ComponentName(context, NotificationCaptureService::class.java))
            }
        } else if (!needed) {
            NotificationCaptureService.requestIdle()
        }
    }

    fun needsListener(): Boolean =
        (prefs.ledgerCaptureEnabled && prefs.capturePackages.isNotEmpty()) || prefs.rulePackages.isNotEmpty()
}
