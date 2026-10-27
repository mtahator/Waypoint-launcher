package app.olauncher.features.automation.domain

/** Something that may fire rules. Events produced by a rule's actions carry [causedByRuleId]. */
sealed interface AutomationEvent {
    val occurredAt: Long
    val causedByRuleId: String?
    val chainDepth: Int
    val triggerType: String

    /** Stable identity used for short-window dedup. Must not contain private notification content. */
    val fingerprint: String

    data class Gesture(
        val eventKey: String,
        override val occurredAt: Long,
        override val causedByRuleId: String? = null,
        override val chainDepth: Int = 0,
    ) : AutomationEvent {
        override val triggerType get() = TriggerSpec.TYPE_GESTURE
        override val fingerprint get() = "g:$eventKey:${occurredAt / 1000}"
    }

    data class App(
        val type: AppEventType,
        override val occurredAt: Long,
        override val causedByRuleId: String? = null,
        override val chainDepth: Int = 0,
    ) : AutomationEvent {
        override val triggerType get() = TriggerSpec.TYPE_APP_EVENT
        override val fingerprint get() = "a:${type.key}:${occurredAt / 1000}"
    }

    /** Fired by the single scheduled alarm; [dueRuleIds] are the rules that alarm was scheduled for. */
    data class TimeFired(
        val dueRuleIds: Set<String>,
        val scheduledAt: Long,
        override val occurredAt: Long,
        override val causedByRuleId: String? = null,
        override val chainDepth: Int = 0,
    ) : AutomationEvent {
        override val triggerType get() = TriggerSpec.TYPE_TIME
        override val fingerprint get() = "t:$scheduledAt"
    }

    data class NotificationReceived(
        val packageName: String,
        val notificationKey: String,
        val title: String,
        val text: String,
        val postTime: Long,
        val redacted: Boolean,
        override val occurredAt: Long,
        override val causedByRuleId: String? = null,
        override val chainDepth: Int = 0,
    ) : AutomationEvent {
        override val triggerType get() = TriggerSpec.TYPE_NOTIFICATION
        // Content hash only; the text itself never leaves memory.
        override val fingerprint get() = "n:$packageName:$notificationKey:${(title + "\u0000" + text).hashCode()}"

        override fun toString() = "NotificationReceived(package=$packageName, redacted=$redacted)"
    }
}
