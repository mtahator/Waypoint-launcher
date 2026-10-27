package app.olauncher.features.automation

import app.olauncher.features.automation.domain.ActionResult
import app.olauncher.features.automation.domain.ActionRunner
import app.olauncher.features.automation.domain.ActionSpec
import app.olauncher.features.automation.domain.AppEventType
import app.olauncher.features.automation.domain.AutomationEngine
import app.olauncher.features.automation.domain.AutomationEvent
import app.olauncher.features.automation.domain.AutomationRule
import app.olauncher.features.automation.domain.ConditionEvaluator
import app.olauncher.features.automation.domain.ConditionSpec
import app.olauncher.features.automation.domain.ExecutionRecord
import app.olauncher.features.automation.domain.Outcome
import app.olauncher.features.automation.domain.ReasonCode
import app.olauncher.features.automation.domain.RuleStatus
import app.olauncher.features.automation.domain.ToggleMode
import app.olauncher.features.automation.domain.TriggerSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId

class AutomationEngineTest {
    private var now = 1_000_000L
    private val ran = mutableListOf<Pair<String, ActionSpec>>()
    private val records = mutableListOf<ExecutionRecord>()
    private var results: (ActionSpec) -> ActionResult = { ActionResult.Success }
    private var rules = listOf<AutomationRule>()

    private val engine = AutomationEngine(
        rules = { type -> rules.filter { it.trigger.typeKey == type && it.isEnabled } },
        actions = ActionRunner { action, ctx -> if (!ctx.dryRun) ran += ctx.rule.id to action; results(action) },
        history = { records += it },
        evaluator = ConditionEvaluator { _, _ -> false },
        clock = { now },
        zone = { ZoneId.of("UTC") },
    )

    private fun rule(
        id: String, created: Long, trigger: TriggerSpec = TriggerSpec.Gesture("corner_swipe"),
        actions: List<ActionSpec> = listOf(ActionSpec.ShowNotification("t", id)),
        conditions: List<ConditionSpec> = emptyList(), status: RuleStatus = RuleStatus.ENABLED, cooldown: Long = 0,
    ) = AutomationRule(id, id, status, trigger, conditions, actions, cooldown, createdAt = created, updatedAt = 0)

    private fun gesture(at: Long = now) = AutomationEvent.Gesture("corner_swipe", at)

    @Test
    fun disabledRulesNeverRunAndOrderIsByCreation() = runBlocking {
        rules = listOf(rule("late", 20), rule("early", 10), rule("off", 5, status = RuleStatus.DISABLED))
        engine.handle(gesture())
        assertEquals(listOf("early", "late"), ran.map { it.first })
        assertEquals(2, records.size)
    }

    @Test
    fun conditionFailureIsRecordedAsSkipped() = runBlocking {
        // 1_000_000 ms after epoch is a Thursday
        rules = listOf(rule("r", 1, conditions = listOf(ConditionSpec.DayOfWeekIn(setOf(DayOfWeek.MONDAY)))))
        val out = engine.handle(gesture())
        assertEquals(Outcome.SKIPPED, out.single().outcome)
        assertEquals(ReasonCode.CONDITION_DAY, out.single().reasonCode)
        assertTrue(ran.isEmpty())
    }

    @Test
    fun dedupAndCooldown() = runBlocking {
        rules = listOf(rule("r", 1))
        engine.handle(gesture())
        assertEquals(ReasonCode.DUPLICATE, engine.handle(gesture()).single().reasonCode)
        now += 11_000
        assertEquals(Outcome.SUCCESS, engine.handle(gesture()).single().outcome)

        rules = listOf(rule("c", 1, cooldown = 60_000))
        engine.handle(gesture())
        now += 30_000
        assertEquals(ReasonCode.COOLDOWN, engine.handle(gesture()).single().reasonCode)
        now += 31_000
        assertEquals(Outcome.SUCCESS, engine.handle(gesture()).single().outcome)
    }

    @Test
    fun ruleNeverFiresOnItsOwnEventAndChainDepthIsBounded() = runBlocking {
        val trigger = TriggerSpec.AppEvent(AppEventType.MONOCHROME_CHANGED)
        rules = listOf(rule("self", 1, trigger), rule("other", 2, trigger))
        val out = engine.handle(AutomationEvent.App(AppEventType.MONOCHROME_CHANGED, now, causedByRuleId = "self", chainDepth = 1))
        assertEquals(ReasonCode.LOOP_PREVENTED, out.first { it.ruleId == "self" }.reasonCode)
        assertEquals(listOf("other"), ran.map { it.first })
        assertTrue(engine.handle(AutomationEvent.App(AppEventType.MONOCHROME_CHANGED, now, chainDepth = 3)).isEmpty())
    }

    @Test
    fun failedActionStopsOnlyThatRule() = runBlocking {
        results = { a -> if (a is ActionSpec.OpenApp) ActionResult.Failed(ReasonCode.APP_NOT_INSTALLED) else ActionResult.Success }
        rules = listOf(
            rule("broken", 1, actions = listOf(ActionSpec.OpenApp("gone", 0), ActionSpec.SetLauncherMonochrome(ToggleMode.ON))),
            rule("fine", 2),
        )
        val out = engine.handle(gesture())
        val broken = out.first { it.ruleId == "broken" }
        assertEquals(Outcome.FAILED, broken.outcome)
        assertEquals(ReasonCode.APP_NOT_INSTALLED, broken.reasonCode)
        assertEquals("1/2", broken.detail)
        assertEquals(Outcome.SUCCESS, out.first { it.ruleId == "fine" }.outcome)
        assertEquals(listOf("broken", "fine"), ran.map { it.first }) // second action of "broken" never ran
    }

    @Test
    fun failedActionIsNeverReportedAsSuccessEvenWhenItThrows() = runBlocking {
        results = { throw IllegalStateException("boom") }
        rules = listOf(rule("r", 1))
        assertEquals(Outcome.FAILED, engine.handle(gesture()).single().outcome)
    }

    @Test
    fun timeEventsOnlyRunDueRules() = runBlocking {
        val t = TriggerSpec.Time(java.time.LocalTime.NOON, DayOfWeek.entries.toSet())
        rules = listOf(rule("due", 1, t), rule("notDue", 2, t))
        engine.handle(AutomationEvent.TimeFired(setOf("due"), now, now))
        assertEquals(listOf("due"), ran.map { it.first })
    }

    @Test
    fun dryRunDoesNotExecuteOrRecord() = runBlocking {
        val r = rule("r", 1)
        val record = engine.dryRun(r, gesture())
        assertEquals(ReasonCode.DRY_RUN, record.reasonCode)
        assertTrue(ran.isEmpty())
        assertTrue(records.isEmpty())
    }
}
