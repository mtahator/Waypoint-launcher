package app.olauncher.features.automation.data.json

import app.olauncher.features.automation.domain.ActionSpec
import app.olauncher.features.automation.domain.AppEventType
import app.olauncher.features.automation.domain.AutomationRule
import app.olauncher.features.automation.domain.ConditionSpec
import app.olauncher.features.automation.domain.MatchMode
import app.olauncher.features.automation.domain.RuleStatus
import app.olauncher.features.automation.domain.ToggleMode
import app.olauncher.features.automation.domain.TriggerSpec
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalTime

/** Thrown for unknown discriminators or malformed definitions. The rule is shown as invalid, not crashed on. */
class InvalidRuleDefinition(message: String) : Exception(message)

/**
 * Hand-written mapper for the rule `definition_json` column. Discriminators are stable strings
 * (`"type": "time"`), never class names, so refactors cannot break stored rules.
 */
object RuleJsonMapper {

    fun definitionToJson(rule: AutomationRule): String = JSONObject()
        .put("trigger", trigger(rule.trigger))
        .put("conditions", JSONArray().apply { rule.conditions.forEach { put(condition(it)) } })
        .put("actions", JSONArray().apply { rule.actions.forEach { put(action(it)) } })
        .put("cooldown_ms", rule.cooldownMillis)
        .toString()

    @Throws(InvalidRuleDefinition::class)
    fun ruleFromJson(
        id: String, name: String, status: RuleStatus, json: String, schemaVersion: Int, createdAt: Long, updatedAt: Long,
    ): AutomationRule {
        try {
            val root = JSONObject(migrate(json, schemaVersion))
            val conditions = root.optJSONArray("conditions") ?: JSONArray()
            val actions = root.optJSONArray("actions") ?: JSONArray()
            return AutomationRule(
                id = id,
                name = name,
                status = status,
                trigger = trigger(root.getJSONObject("trigger")),
                conditions = (0 until conditions.length()).map { condition(conditions.getJSONObject(it)) },
                actions = (0 until actions.length()).map { action(actions.getJSONObject(it)) },
                cooldownMillis = root.optLong("cooldown_ms", 0L).coerceAtLeast(0L),
                schemaVersion = AutomationRule.CURRENT_SCHEMA_VERSION,
                createdAt = createdAt,
                updatedAt = updatedAt,
            )
        } catch (e: JSONException) {
            throw InvalidRuleDefinition("malformed json")
        }
    }

    /** Older schema versions are upgraded here. v0 (pre-release) stored "cooldown" in seconds. */
    private fun migrate(json: String, schemaVersion: Int): String {
        if (schemaVersion >= AutomationRule.CURRENT_SCHEMA_VERSION) return json
        val root = JSONObject(json)
        if (schemaVersion == 0 && root.has("cooldown")) {
            root.put("cooldown_ms", root.optLong("cooldown") * 1000L)
            root.remove("cooldown")
        }
        return root.toString()
    }

    // ---- triggers
    private fun trigger(t: TriggerSpec): JSONObject = when (t) {
        is TriggerSpec.Gesture -> JSONObject().put("type", "gesture").put("event_key", t.eventKey)
        is TriggerSpec.AppEvent -> JSONObject().put("type", "app_event").put("event", t.event.key)
        is TriggerSpec.Time -> JSONObject().put("type", "time")
            .put("local_time", t.localTime.toString())
            .put("days", days(t.days))
            .put("zone_id", t.zoneId ?: JSONObject.NULL)
            .put("precise", t.precise)
        is TriggerSpec.NotificationReceived -> JSONObject().put("type", "notification")
            .put("packages", JSONArray(t.packages.sorted()))
    }

    private fun trigger(o: JSONObject): TriggerSpec = when (val type = o.getString("type")) {
        "gesture" -> TriggerSpec.Gesture(o.getString("event_key"))
        "app_event" -> TriggerSpec.AppEvent(
            AppEventType.fromKey(o.getString("event")) ?: throw InvalidRuleDefinition("unknown app event")
        )
        "time" -> TriggerSpec.Time(
            localTime = time(o.getString("local_time")),
            days = days(o.getJSONArray("days")),
            zoneId = if (o.isNull("zone_id")) null else o.optString("zone_id").ifBlank { null },
            precise = o.optBoolean("precise", false),
        )
        "notification" -> TriggerSpec.NotificationReceived(strings(o.getJSONArray("packages")))
        else -> throw InvalidRuleDefinition("unknown trigger type: $type")
    }

    // ---- conditions
    private fun condition(c: ConditionSpec): JSONObject = when (c) {
        is ConditionSpec.DayOfWeekIn -> JSONObject().put("type", "day_of_week").put("days", days(c.days))
        is ConditionSpec.TimeBetween -> JSONObject().put("type", "time_between")
            .put("start", c.start.toString()).put("end", c.end.toString())
        is ConditionSpec.SourcePackageIn -> JSONObject().put("type", "source_package")
            .put("packages", JSONArray(c.packages.sorted()))
        is ConditionSpec.TextMatches -> JSONObject().put("type", "text_matches")
            .put("mode", when (c.mode) {
                MatchMode.CONTAINS -> "contains"
                MatchMode.STARTS_WITH -> "starts_with"
                MatchMode.SAFE_REGEX -> "safe_regex"
            })
            .put("pattern", c.pattern).put("ignore_case", c.ignoreCase)
        ConditionSpec.AmountExtractable -> JSONObject().put("type", "amount_extractable")
    }

    private fun condition(o: JSONObject): ConditionSpec = when (val type = o.getString("type")) {
        "day_of_week" -> ConditionSpec.DayOfWeekIn(days(o.getJSONArray("days")))
        "time_between" -> ConditionSpec.TimeBetween(time(o.getString("start")), time(o.getString("end")))
        "source_package" -> ConditionSpec.SourcePackageIn(strings(o.getJSONArray("packages")))
        "text_matches" -> ConditionSpec.TextMatches(
            mode = when (o.getString("mode")) {
                "contains" -> MatchMode.CONTAINS
                "starts_with" -> MatchMode.STARTS_WITH
                "safe_regex" -> MatchMode.SAFE_REGEX
                else -> throw InvalidRuleDefinition("unknown match mode")
            },
            pattern = o.getString("pattern"),
            ignoreCase = o.optBoolean("ignore_case", true),
        )
        "amount_extractable" -> ConditionSpec.AmountExtractable
        else -> throw InvalidRuleDefinition("unknown condition type: $type")
    }

    // ---- actions
    private fun action(a: ActionSpec): JSONObject = when (a) {
        is ActionSpec.ShowNotification -> JSONObject().put("type", "show_notification").put("title", a.title).put("text", a.text)
        is ActionSpec.SetLauncherMonochrome -> JSONObject().put("type", "set_launcher_monochrome").put("mode", toggle(a.mode))
        is ActionSpec.SetSystemGrayscale -> JSONObject().put("type", "set_system_grayscale").put("mode", toggle(a.mode))
        is ActionSpec.OpenApp -> JSONObject().put("type", "open_app")
            .put("package", a.packageName).put("user", a.userHandleId).put("label", a.label)
        ActionSpec.SaveTransaction -> JSONObject().put("type", "save_transaction")
    }

    private fun action(o: JSONObject): ActionSpec = when (val type = o.getString("type")) {
        "show_notification" -> ActionSpec.ShowNotification(o.optString("title"), o.optString("text"))
        "set_launcher_monochrome" -> ActionSpec.SetLauncherMonochrome(toggle(o.getString("mode")))
        "set_system_grayscale" -> ActionSpec.SetSystemGrayscale(toggle(o.getString("mode")))
        "open_app" -> ActionSpec.OpenApp(o.getString("package"), o.optLong("user", 0L), o.optString("label"))
        "save_transaction" -> ActionSpec.SaveTransaction
        else -> throw InvalidRuleDefinition("unknown action type: $type")
    }

    // ---- helpers
    private fun toggle(m: ToggleMode) = when (m) {
        ToggleMode.ON -> "on"
        ToggleMode.OFF -> "off"
        ToggleMode.TOGGLE -> "toggle"
    }

    private fun toggle(s: String) = when (s) {
        "on" -> ToggleMode.ON
        "off" -> ToggleMode.OFF
        "toggle" -> ToggleMode.TOGGLE
        else -> throw InvalidRuleDefinition("unknown toggle mode")
    }

    private fun days(days: Set<DayOfWeek>) = JSONArray(days.sorted().map { it.value })

    private fun days(a: JSONArray): Set<DayOfWeek> = (0 until a.length()).mapTo(LinkedHashSet()) {
        val v = a.getInt(it)
        if (v !in 1..7) throw InvalidRuleDefinition("bad day")
        DayOfWeek.of(v)
    }

    private fun strings(a: JSONArray): Set<String> = (0 until a.length()).mapTo(LinkedHashSet()) { a.getString(it) }

    private fun time(s: String): LocalTime = try {
        LocalTime.parse(s)
    } catch (_: java.time.format.DateTimeParseException) {
        throw InvalidRuleDefinition("bad time")
    }
}
