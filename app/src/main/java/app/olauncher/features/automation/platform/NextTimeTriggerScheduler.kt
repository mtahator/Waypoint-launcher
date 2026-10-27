package app.olauncher.features.automation.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import app.olauncher.core.WaypointPrefs
import app.olauncher.features.automation.domain.AutomationRule
import app.olauncher.features.automation.domain.NextTimeCalculator
import java.time.Instant
import java.time.ZoneId

/**
 * Keeps at most ONE alarm for all time rules: the earliest next fire. Inexact (10-minute window)
 * unless a rule asked for precision and the user allowed exact alarms. The boot receiver is
 * component-disabled whenever no time rule exists, so the app is never woken at boot needlessly.
 */
class NextTimeTriggerScheduler(private val context: Context, private val prefs: WaypointPrefs) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    fun reschedule(enabledRules: List<AutomationRule>, now: Long = System.currentTimeMillis()) {
        val next = NextTimeCalculator.nextFire(enabledRules, Instant.ofEpochMilli(now), ZoneId.systemDefault())
        val pending = pendingIntent()
        alarms.cancel(pending)
        setBootReceiverEnabled(next != null)
        if (next == null) {
            prefs.scheduledAlarmAt = 0L
            prefs.scheduledAlarmRules = emptySet()
            return
        }
        val at = next.at.toEpochMilli()
        prefs.scheduledAlarmAt = at
        prefs.scheduledAlarmRules = next.ruleIds
        try {
            if (next.precise && canScheduleExact()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            else alarms.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, pending)
        } catch (_: SecurityException) {
            alarms.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, pending)
        }
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, TimeTriggerReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun setBootReceiverEnabled(enabled: Boolean) {
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val component = ComponentName(context, RescheduleReceiver::class.java)
        if (context.packageManager.getComponentEnabledSetting(component) != state)
            context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
    }

    companion object {
        const val ACTION_FIRE = "app.waypoint.action.TIME_TRIGGER"
        const val WINDOW_MS = 10 * 60 * 1000L
    }
}
