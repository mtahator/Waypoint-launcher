package app.olauncher.features.automation.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Source of enabled rules for a trigger type (cached by the repository, invalidated on CRUD). */
fun interface RuleSource {
    suspend fun enabledRules(triggerType: String): List<AutomationRule>
}

fun interface ExecutionSink {
    suspend fun record(record: ExecutionRecord)
}

data class ActionContext(val rule: AutomationRule, val event: AutomationEvent, val dryRun: Boolean)

/** Executes one whitelisted action. Implemented by the platform layer; faked in tests. */
fun interface ActionRunner {
    suspend fun run(action: ActionSpec, context: ActionContext): ActionResult
}

/**
 * The rule engine. Events go through a bounded in-process flow; one collector on a single-threaded
 * dispatcher handles them sequentially, so rules for one event run in deterministic `createdAt` order.
 */
class AutomationEngine(
    private val rules: RuleSource,
    private val actions: ActionRunner,
    private val history: ExecutionSink,
    private val evaluator: ConditionEvaluator,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    dispatcher: CoroutineDispatcher? = null,
) {
    private val events = MutableSharedFlow<AutomationEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val dedup = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > DEDUP_CAPACITY
    }
    private val lastRun = HashMap<String, Long>()
    private val mutex = Mutex()

    init {
        if (dispatcher != null) {
            CoroutineScope(SupervisorJob() + dispatcher).launch {
                events.collect { event -> runCatching { handle(event) } }
            }
        }
    }

    /** Fire-and-forget entry point; never blocks the caller (main thread safe). */
    fun emit(event: AutomationEvent) {
        events.tryEmit(event)
    }

    /** Processes one event synchronously in the caller's coroutine. Public for tests and receivers. */
    suspend fun handle(event: AutomationEvent): List<ExecutionRecord> = mutex.withLock { handleLocked(event) }

    private suspend fun handleLocked(event: AutomationEvent): List<ExecutionRecord> {
        if (event.chainDepth >= MAX_CHAIN_DEPTH) return emptyList()
        val candidates = rules.enabledRules(event.triggerType)
            .filter { it.isEnabled && triggerMatches(it.trigger, it.id, event) }
            .sortedBy { it.createdAt }
        val out = ArrayList<ExecutionRecord>(candidates.size)
        for (rule in candidates) {
            val record = runRule(rule, event, dryRun = false)
            history.record(record)
            out += record
        }
        return out
    }

    /** "Run test": evaluates conditions against a synthetic event; actions report what they would do. */
    suspend fun dryRun(rule: AutomationRule, event: AutomationEvent): ExecutionRecord =
        runRule(rule, event, dryRun = true)

    private suspend fun runRule(rule: AutomationRule, event: AutomationEvent, dryRun: Boolean): ExecutionRecord {
        val started = clock()
        fun done(outcome: Outcome, reason: ReasonCode, detail: String = "") =
            ExecutionRecord(UUID.randomUUID().toString(), rule.id, started, clock(), outcome, reason, detail)

        if (event.causedByRuleId == rule.id) return done(Outcome.SKIPPED, ReasonCode.LOOP_PREVENTED)

        val now = Instant.ofEpochMilli(event.occurredAt).atZone(zone())
        evaluator.firstFailure(rule.conditions, event, now)?.let { return done(Outcome.SKIPPED, it) }

        if (!dryRun) {
            val key = rule.id + "|" + event.fingerprint
            val seen = dedup[key]
            if (seen != null && started - seen < DEDUP_WINDOW_MS) return done(Outcome.SKIPPED, ReasonCode.DUPLICATE)
            val previous = lastRun[rule.id]
            if (previous != null && rule.cooldownMillis > 0 && started - previous < rule.cooldownMillis)
                return done(Outcome.SKIPPED, ReasonCode.COOLDOWN)
            dedup[key] = started
            lastRun[rule.id] = started
        }

        val context = ActionContext(rule, event, dryRun)
        var skipped: ReasonCode? = null
        rule.actions.forEachIndexed { index, action ->
            val result = try {
                actions.run(action, context)
            } catch (_: Exception) {
                ActionResult.Failed(ReasonCode.ACTION_ERROR)
            }
            when (result) {
                ActionResult.Success -> Unit
                is ActionResult.Skipped -> if (skipped == null) skipped = result.reason
                // A failed action stops the remaining actions of this rule only.
                is ActionResult.Failed -> return done(Outcome.FAILED, result.reason, "${index + 1}/${rule.actions.size}")
            }
        }
        val skip = skipped
        return when {
            dryRun -> done(Outcome.SUCCESS, ReasonCode.DRY_RUN)
            skip != null && rule.actions.size == 1 -> done(Outcome.SKIPPED, skip)
            else -> done(Outcome.SUCCESS, skip ?: ReasonCode.OK)
        }
    }

    companion object {
        const val MAX_CHAIN_DEPTH = 3
        const val DEDUP_CAPACITY = 256
        const val DEDUP_WINDOW_MS = 10_000L

        fun triggerMatches(trigger: TriggerSpec, ruleId: String, event: AutomationEvent): Boolean = when (trigger) {
            is TriggerSpec.Gesture -> event is AutomationEvent.Gesture && event.eventKey == trigger.eventKey
            is TriggerSpec.AppEvent -> event is AutomationEvent.App && event.type == trigger.event
            is TriggerSpec.Time -> event is AutomationEvent.TimeFired && ruleId in event.dueRuleIds
            is TriggerSpec.NotificationReceived ->
                event is AutomationEvent.NotificationReceived && event.packageName in trigger.packages
        }
    }
}
