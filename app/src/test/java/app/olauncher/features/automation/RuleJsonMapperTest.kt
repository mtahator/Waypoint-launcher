package app.olauncher.features.automation

import app.olauncher.features.automation.data.json.InvalidRuleDefinition
import app.olauncher.features.automation.data.json.RuleJsonMapper
import app.olauncher.features.automation.domain.ActionSpec
import app.olauncher.features.automation.domain.AppEventType
import app.olauncher.features.automation.domain.AutomationRule
import app.olauncher.features.automation.domain.ConditionSpec
import app.olauncher.features.automation.domain.MatchMode
import app.olauncher.features.automation.domain.RuleStatus
import app.olauncher.features.automation.domain.ToggleMode
import app.olauncher.features.automation.domain.TriggerSpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

class RuleJsonMapperTest {
    private fun roundTrip(r: AutomationRule): AutomationRule =
        RuleJsonMapper.ruleFromJson(r.id, r.name, r.status, RuleJsonMapper.definitionToJson(r), r.schemaVersion, r.createdAt, r.updatedAt)

    private fun rule(trigger: TriggerSpec, conditions: List<ConditionSpec>, actions: List<ActionSpec>) =
        AutomationRule("id", "name", RuleStatus.ENABLED, trigger, conditions, actions, 5_000, createdAt = 1, updatedAt = 2)

    @Test
    fun everySpecTypeRoundTrips() {
        val triggers = listOf(
            TriggerSpec.Gesture("corner_swipe"),
            TriggerSpec.AppEvent(AppEventType.LAUNCHER_OPENED),
            TriggerSpec.AppEvent(AppEventType.MONOCHROME_CHANGED),
            TriggerSpec.Time(LocalTime.of(7, 45), setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), "Europe/London", precise = true),
            TriggerSpec.Time(LocalTime.of(22, 0), DayOfWeek.entries.toSet(), null),
            TriggerSpec.NotificationReceived(setOf("com.a", "com.b")),
        )
        val conditions = listOf(
            ConditionSpec.DayOfWeekIn(setOf(DayOfWeek.SUNDAY)),
            ConditionSpec.TimeBetween(LocalTime.of(22, 0), LocalTime.of(6, 0)),
            ConditionSpec.SourcePackageIn(setOf("com.bank")),
            ConditionSpec.TextMatches(MatchMode.CONTAINS, "Paid \"quoted\"", true),
            ConditionSpec.TextMatches(MatchMode.STARTS_WITH, "x", false),
            ConditionSpec.TextMatches(MatchMode.SAFE_REGEX, "\\d+", true),
            ConditionSpec.AmountExtractable,
        )
        val actions = listOf(
            ActionSpec.ShowNotification("Title", "Text ✓ فارسی"),
            ActionSpec.SetLauncherMonochrome(ToggleMode.TOGGLE),
            ActionSpec.SetSystemGrayscale(ToggleMode.OFF),
            ActionSpec.OpenApp("com.spotify.music", 10, "Spotify"),
            ActionSpec.SaveTransaction,
        )
        triggers.forEach { t ->
            val r = rule(t, conditions, actions)
            assertEquals(r, roundTrip(r))
        }
    }

    @Test
    fun discriminatorsAreStableStrings() {
        val json = JSONObject(RuleJsonMapper.definitionToJson(rule(TriggerSpec.Gesture("k"), emptyList(), listOf(ActionSpec.SaveTransaction))))
        assertEquals("gesture", json.getJSONObject("trigger").getString("type"))
        assertEquals("save_transaction", json.getJSONArray("actions").getJSONObject(0).getString("type"))
    }

    @Test(expected = InvalidRuleDefinition::class)
    fun unknownDiscriminatorIsInvalidNotACrash() {
        RuleJsonMapper.ruleFromJson("id", "n", RuleStatus.ENABLED, """{"trigger":{"type":"teleport"},"actions":[]}""", 1, 0, 0)
    }

    @Test(expected = InvalidRuleDefinition::class)
    fun malformedJsonIsInvalid() {
        RuleJsonMapper.ruleFromJson("id", "n", RuleStatus.ENABLED, "{not json", 1, 0, 0)
    }

    @Test
    fun olderSchemaIsMigrated() {
        val v0 = """{"trigger":{"type":"gesture","event_key":"k"},"conditions":[],"actions":[{"type":"save_transaction"}],"cooldown":30}"""
        val r = RuleJsonMapper.ruleFromJson("id", "n", RuleStatus.ENABLED, v0, 0, 0, 0)
        assertEquals(30_000L, r.cooldownMillis)
        assertEquals(AutomationRule.CURRENT_SCHEMA_VERSION, r.schemaVersion)
        assertTrue(r.trigger is TriggerSpec.Gesture)
    }
}
