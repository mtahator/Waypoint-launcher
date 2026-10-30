package app.olauncher.features.space.platform

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.olauncher.R
import app.olauncher.core.waypoint
import app.olauncher.features.assistant.ui.ChatActivity
import app.olauncher.features.space.data.Task
import kotlinx.coroutines.launch

/**
 * Reminders for Space to-dos: an alarm at the due time shows a notification with Done and Snooze.
 * Exact when the user allowed exact alarms, otherwise within a few minutes. Re-armed after reboot.
 */
object ReminderScheduler {
    private const val CHANNEL = "space_reminders"
    const val ACTION_FIRE = "app.waypoint.reminder.FIRE"
    const val ACTION_DONE = "app.waypoint.reminder.DONE"
    const val ACTION_SNOOZE = "app.waypoint.reminder.SNOOZE"
    const val EXTRA_TASK = "task"

    fun canExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun schedule(context: Context, task: Task) {
        cancel(context, task.id)
        if (!task.remind || task.done || task.due <= System.currentTimeMillis()) return
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(context, task.id)
        runCatching {
            if (canExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.due, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.due, pi)
        }
    }

    fun cancel(context: Context, taskId: Long) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context, taskId))
        NotificationManagerCompat.from(context).cancel(notificationId(taskId))
    }

    private fun alarmIntent(context: Context, id: Long) = PendingIntent.getBroadcast(
        context, notificationId(id), Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_TASK, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notificationId(id: Long) = (5000 + (id % 100_000)).toInt()

    fun show(context: Context, task: Task) {
        channel(context)
        fun action(a: String, req: Int) = PendingIntent.getBroadcast(context, notificationId(task.id) * 10 + req,
            Intent(context, ReminderReceiver::class.java).setAction(a).putExtra(EXTRA_TASK, task.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(context, notificationId(task.id), ChatActivity.intent(context, space = true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_wayfinder)
            .setContentTitle(task.title)
            .setContentText(context.getString(R.string.wp_reminder_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.wp_reminder_done), action(ACTION_DONE, 1))
            .addAction(0, context.getString(R.string.wp_reminder_snooze), action(ACTION_SNOOZE, 2))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notificationId(task.id), n) }
    }

    private fun channel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.wp_reminders), NotificationManager.IMPORTANCE_HIGH))
        }
    }

    /** After boot or an app update: re-arm every pending reminder. */
    suspend fun rescheduleAll(context: Context) {
        context.waypoint.space.notes.tasks().forEach { schedule(context, it) }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val c = app.waypoint
        val pending = goAsync()
        c.scope.launch {
            try {
                when (intent.action) {
                    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> ReminderScheduler.rescheduleAll(app)
                    else -> {
                        val id = intent.getLongExtra(ReminderScheduler.EXTRA_TASK, 0)
                        val task = c.space.notes.task(id) ?: return@launch
                        when (intent.action) {
                            ReminderScheduler.ACTION_FIRE -> if (!task.done) ReminderScheduler.show(app, task)
                            // cancel first: a repeating to-do is rescheduled for its next date by setTaskDone
                            ReminderScheduler.ACTION_DONE -> { ReminderScheduler.cancel(app, id); c.space.setTaskDone(id, true) }
                            ReminderScheduler.ACTION_SNOOZE -> {
                                ReminderScheduler.cancel(app, id)
                                c.space.setTaskDue(id, System.currentTimeMillis() + 10 * 60_000L, remind = true)
                            }
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
