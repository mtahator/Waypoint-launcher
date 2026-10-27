package app.olauncher.features.automation.domain

enum class Outcome { SUCCESS, SKIPPED, FAILED }

/** Machine-readable reasons; the UI maps them to localized strings. Never carry private content. */
enum class ReasonCode {
    OK,
    DRY_RUN,
    CONDITION_DAY,
    CONDITION_TIME,
    CONDITION_SOURCE,
    CONDITION_TEXT,
    CONDITION_AMOUNT,
    COOLDOWN,
    DUPLICATE,
    LOOP_PREVENTED,
    APP_NOT_INSTALLED,
    PERMISSION_MISSING,
    NOT_AVAILABLE,
    NOT_APPLICABLE,
    INVALID_RULE,
    ACTION_ERROR,
    POSTED_AS_NOTIFICATION,
    ;

    companion object {
        fun fromName(name: String?): ReasonCode = entries.firstOrNull { it.name == name } ?: ACTION_ERROR
    }
}

sealed interface ActionResult {
    data object Success : ActionResult
    data class Skipped(val reason: ReasonCode) : ActionResult
    data class Failed(val reason: ReasonCode) : ActionResult
}

data class ExecutionRecord(
    val id: String,
    val ruleId: String,
    val startedAt: Long,
    val finishedAt: Long,
    val outcome: Outcome,
    val reasonCode: ReasonCode,
    /** Short, non-private detail such as "action 2 of 3". */
    val detail: String = "",
)
