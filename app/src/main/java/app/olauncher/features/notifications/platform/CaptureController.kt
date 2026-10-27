package app.olauncher.features.notifications.platform

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import app.olauncher.core.WaypointPrefs

/** Binds the notification listener only while ledger capture is on with at least one source app. */
class CaptureController(private val context: Context, private val prefs: WaypointPrefs) {

    fun needsListener(): Boolean = prefs.ledgerCaptureEnabled && prefs.capturePackages.isNotEmpty()

    fun updateListenerBinding() {
        if (needsListener() && NotificationCaptureService.hasAccess(context)) {
            runCatching {
                NotificationListenerService.requestRebind(ComponentName(context, NotificationCaptureService::class.java))
            }
        } else if (!needsListener()) {
            NotificationCaptureService.requestIdle()
        }
    }
}
