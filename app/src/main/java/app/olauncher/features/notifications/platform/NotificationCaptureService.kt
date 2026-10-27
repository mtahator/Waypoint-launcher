package app.olauncher.features.notifications.platform

import android.app.Notification
import android.content.Context
import android.content.SharedPreferences
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import app.olauncher.core.WaypointPrefs
import app.olauncher.core.waypoint
import app.olauncher.features.automation.domain.AutomationEvent
import app.olauncher.features.transactions.domain.CapturedNotification
import kotlinx.coroutines.launch

/**
 * Reads notifications ONLY from apps the user selected. Non-selected packages are rejected with
 * one in-memory set lookup before anything else happens. Titles/texts are never logged or stored
 * raw; only parsed fields reach the ledger. When nothing needs the listener it unbinds itself.
 */
class NotificationCaptureService : NotificationListenerService(), SharedPreferences.OnSharedPreferenceChangeListener {

    @Volatile
    private var selected: Set<String> = emptySet()
    @Volatile
    private var ledgerPackages: Set<String> = emptySet()
    @Volatile
    private var rulePackages: Set<String> = emptySet()

    private val lastHashByKey = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 128
    }

    override fun onListenerConnected() {
        instance = this
        status = NotificationAccessStatus.CONNECTED
        val prefs = waypoint.prefs
        prefs.registerListener(this)
        refresh(prefs)
        if (!waypoint.coordinator.needsListener()) requestUnbind()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        status = if (hasAccess(this)) NotificationAccessStatus.IDLE else NotificationAccessStatus.NO_ACCESS
        runCatching { waypoint.prefs.unregisterListener(this) }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        runCatching { waypoint.prefs.unregisterListener(this) }
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        if (key == WaypointPrefs.CAPTURE_PACKAGES || key == WaypointPrefs.RULE_PACKAGES || key == WaypointPrefs.LEDGER_CAPTURE)
            refresh(waypoint.prefs)
    }

    private fun refresh(prefs: WaypointPrefs) {
        ledgerPackages = if (prefs.ledgerCaptureEnabled) prefs.capturePackages else emptySet()
        rulePackages = prefs.rulePackages
        selected = ledgerPackages + rulePackages
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg !in selected) return // O(1) reject: nothing else is touched for other apps
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")
        val text = big ?: lines ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

        val hash = (title + "\u0000" + text).hashCode()
        synchronized(lastHashByKey) {
            if (lastHashByKey[sbn.key] == hash) return // same content re-posted (update without change)
            lastHashByKey[sbn.key] = hash
        }
        val captured = CapturedNotification(
            packageName = pkg,
            sourceLabel = labelFor(pkg),
            key = sbn.key,
            postTime = sbn.postTime,
            title = title,
            text = text,
            redacted = isRedacted(title, text),
        )
        val toLedger = pkg in ledgerPackages
        val toRules = pkg in rulePackages
        val container = waypoint
        container.scope.launch(container.dispatchers.capture) {
            if (toLedger) {
                runCatching {
                    container.transactions.saveCaptured(captured, container.parsers.parse(captured), System.currentTimeMillis())
                }
            }
            if (toRules) container.fire(
                AutomationEvent.NotificationReceived(
                    packageName = pkg, notificationKey = captured.key, title = title, text = text,
                    postTime = captured.postTime, redacted = captured.redacted, occurredAt = System.currentTimeMillis(),
                )
            )
        }
    }

    private fun labelFor(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    companion object {
        @Volatile
        private var instance: NotificationCaptureService? = null

        @Volatile
        var status: NotificationAccessStatus = NotificationAccessStatus.UNKNOWN
            private set

        fun hasAccess(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        fun currentStatus(context: Context): NotificationAccessStatus = when {
            !hasAccess(context) -> NotificationAccessStatus.NO_ACCESS
            instance != null -> NotificationAccessStatus.CONNECTED
            else -> NotificationAccessStatus.IDLE
        }

        fun requestIdle() {
            instance?.let { runCatching { it.requestUnbind() } }
        }

        private val REDACTION_HINTS = listOf("sensitive notification content hidden", "content hidden", "contents hidden")

        /** Android 15+ may redact sensitive content (e.g. OTPs) for untrusted listeners. */
        fun isRedacted(title: String, text: String): Boolean {
            if (title.isBlank() && text.isBlank()) return true
            val t = text.lowercase()
            return REDACTION_HINTS.any { t.contains(it) }
        }
    }
}

enum class NotificationAccessStatus { UNKNOWN, NO_ACCESS, IDLE, CONNECTED }
