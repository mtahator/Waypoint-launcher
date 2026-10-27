package app.olauncher.features.automation.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import app.olauncher.R

/** Posts automation notifications on the low-importance "Automations" channel. */
class AutomationNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun canPost(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.wp_channel_automations), NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun post(title: String, text: String, contentIntent: PendingIntent? = null): Boolean {
        if (!canPost()) return false
        ensureChannel()
        val builder = newBuilder()
        val notification = builder
            .setSmallIcon(R.drawable.ic_stat_waypoint)
            .setContentTitle(title.ifBlank { context.getString(R.string.app_name) })
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .apply { if (contentIntent != null) setContentIntent(contentIntent) }
            .build()
        return try {
            manager.notify(nextId(), notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun newBuilder(): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, CHANNEL_ID) else Notification.Builder(context)

    private fun nextId(): Int = (System.currentTimeMillis() and 0x3FFFFFFF).toInt()

    companion object {
        const val CHANNEL_ID = "automations"
    }
}
