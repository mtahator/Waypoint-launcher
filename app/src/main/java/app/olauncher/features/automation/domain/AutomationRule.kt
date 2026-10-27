package app.olauncher.features.automation.domain

import java.time.DayOfWeek
import java.time.LocalTime

enum class RuleStatus { ENABLED, DISABLED }

data class AutomationRule(
    val id: String,
    val name: String,
    val status: RuleStatus,
    val trigger: TriggerSpec,
    val conditions: List<ConditionSpec>,
    val actions: List<ActionSpec>,
    val cooldownMillis: Long = 0L,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val isEnabled get() = status == RuleStatus.ENABLED

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val MAX_ACTIONS = 5
    }
}

enum class AppEventType(val key: String) {
    LAUNCHER_OPENED("launcher_opened"),
    MONOCHROME_CHANGED("monochrome_changed");

    companion object {
        fun fromKey(key: String?): AppEventType? = entries.firstOrNull { it.key == key }
    }
}

sealed interface TriggerSpec {
    val typeKey: String

    data class Gesture(val eventKey: String) : TriggerSpec {
        override val typeKey get() = TYPE_GESTURE
    }

    data class AppEvent(val event: AppEventType) : TriggerSpec {
        override val typeKey get() = TYPE_APP_EVENT
    }

    /** [zoneId] null = follow the device time zone. [precise] asks for an exact alarm when allowed. */
    data class Time(
        val localTime: LocalTime,
        val days: Set<DayOfWeek>,
        val zoneId: String? = null,
        val precise: Boolean = false,
    ) : TriggerSpec {
        override val typeKey get() = TYPE_TIME
    }

    data class NotificationReceived(val packages: Set<String>) : TriggerSpec {
        override val typeKey get() = TYPE_NOTIFICATION
    }

    companion object {
        const val TYPE_GESTURE = "gesture"
        const val TYPE_APP_EVENT = "app_event"
        const val TYPE_TIME = "time"
        const val TYPE_NOTIFICATION = "notification"
    }
}

enum class MatchMode { CONTAINS, STARTS_WITH, SAFE_REGEX }

sealed interface ConditionSpec {
    data class DayOfWeekIn(val days: Set<DayOfWeek>) : ConditionSpec
    /** Inclusive start, exclusive end; wraps past midnight when end <= start (e.g. 22:00–06:00). */
    data class TimeBetween(val start: LocalTime, val end: LocalTime) : ConditionSpec
    data class SourcePackageIn(val packages: Set<String>) : ConditionSpec
    data class TextMatches(val mode: MatchMode, val pattern: String, val ignoreCase: Boolean = true) : ConditionSpec
    data object AmountExtractable : ConditionSpec
}

enum class ToggleMode { ON, OFF, TOGGLE }

sealed interface ActionSpec {
    data class ShowNotification(val title: String, val text: String) : ActionSpec
    data class SetLauncherMonochrome(val mode: ToggleMode) : ActionSpec
    data class SetSystemGrayscale(val mode: ToggleMode) : ActionSpec
    data class OpenApp(val packageName: String, val userHandleId: Long, val label: String = "") : ActionSpec
    data object SaveTransaction : ActionSpec
}
