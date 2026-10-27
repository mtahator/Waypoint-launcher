package app.olauncher.features.automation.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.olauncher.core.waypoint
import app.olauncher.features.automation.domain.AutomationEvent
import kotlinx.coroutines.launch

/** Fired by the single time-trigger alarm. Runs the due rules, then schedules the next alarm. */
class TimeTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NextTimeTriggerScheduler.ACTION_FIRE) return
        val container = context.waypoint
        val pending = goAsync()
        container.scope.launch {
            try {
                val prefs = container.prefs
                val due = prefs.scheduledAlarmRules
                if (due.isNotEmpty()) {
                    container.engine.handle(
                        AutomationEvent.TimeFired(due, prefs.scheduledAlarmAt, System.currentTimeMillis())
                    )
                }
                container.coordinator.rescheduleTimeRules(after = maxOf(System.currentTimeMillis(), prefs.scheduledAlarmAt) + 1_000L)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Re-arms the time-trigger alarm after reboot, app update, or a clock/time-zone change.
 * Disabled in the manifest and only enabled while at least one time rule exists.
 */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> Unit
            else -> return
        }
        val container = context.waypoint
        val pending = goAsync()
        container.scope.launch {
            try {
                container.coordinator.rescheduleTimeRules()
            } finally {
                pending.finish()
            }
        }
    }
}
