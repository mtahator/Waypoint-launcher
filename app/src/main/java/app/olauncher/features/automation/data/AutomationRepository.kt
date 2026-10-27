package app.olauncher.features.automation.data

import android.content.ContentValues
import android.database.Cursor
import app.olauncher.features.automation.data.json.InvalidRuleDefinition
import app.olauncher.features.automation.data.json.RuleJsonMapper
import app.olauncher.features.automation.domain.AutomationRule
import app.olauncher.features.automation.domain.ExecutionRecord
import app.olauncher.features.automation.domain.ExecutionSink
import app.olauncher.features.automation.domain.Outcome
import app.olauncher.features.automation.domain.ReasonCode
import app.olauncher.features.automation.domain.RuleSource
import app.olauncher.features.automation.domain.RuleStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** A stored rule; [rule] is null when its definition could not be decoded (shown as invalid). */
data class RuleRow(
    val id: String,
    val name: String,
    val status: RuleStatus,
    val triggerType: String,
    val rule: AutomationRule?,
    val lastExecution: ExecutionRecord?,
)

class AutomationRepository(
    private val dbProvider: () -> AutomationDatabase,
    private val io: CoroutineDispatcher,
) : RuleSource, ExecutionSink {

    private val db by lazy { dbProvider().writableDatabase }
    private val enabledCache = ConcurrentHashMap<String, List<AutomationRule>>()

    override suspend fun enabledRules(triggerType: String): List<AutomationRule> =
        enabledCache[triggerType] ?: withContext(io) {
            val rules = queryRules("status = ? AND trigger_type = ?", arrayOf(RuleStatus.ENABLED.name, triggerType))
                .mapNotNull { it.rule }
            enabledCache[triggerType] = rules
            rules
        }

    suspend fun allRules(): List<RuleRow> = withContext(io) {
        val last = HashMap<String, ExecutionRecord>()
        db.rawQuery(
            "SELECT e.* FROM executions e JOIN (SELECT rule_id, MAX(started_at) m FROM executions GROUP BY rule_id) x " +
                "ON e.rule_id = x.rule_id AND e.started_at = x.m", null
        ).use { c -> while (c.moveToNext()) execution(c).let { last[it.ruleId] = it } }
        queryRules(null, null).map { it.copy(lastExecution = last[it.id]) }
    }

    suspend fun allEnabledRules(): List<AutomationRule> = withContext(io) {
        queryRules("status = ?", arrayOf(RuleStatus.ENABLED.name)).mapNotNull { it.rule }
    }

    suspend fun rule(id: String): RuleRow? = withContext(io) { queryRules("id = ?", arrayOf(id)).firstOrNull() }

    suspend fun save(rule: AutomationRule) = withContext(io) {
        val values = ContentValues().apply {
            put("id", rule.id)
            put("name", rule.name)
            put("status", rule.status.name)
            put("trigger_type", rule.trigger.typeKey)
            put("definition_json", RuleJsonMapper.definitionToJson(rule))
            put("schema_version", rule.schemaVersion)
            put("created_at", rule.createdAt)
            put("updated_at", rule.updatedAt)
        }
        // UPDATE first: INSERT OR REPLACE would delete the row and cascade away its history.
        if (db.update("rules", values, "id = ?", arrayOf(rule.id)) == 0) db.insertOrThrow("rules", null, values)
        enabledCache.clear()
    }

    suspend fun setStatus(id: String, status: RuleStatus, now: Long) = withContext(io) {
        db.update("rules", ContentValues().apply {
            put("status", status.name); put("updated_at", now)
        }, "id = ?", arrayOf(id))
        enabledCache.clear()
    }

    suspend fun delete(id: String) = withContext(io) {
        db.delete("rules", "id = ?", arrayOf(id)) // executions cascade
        enabledCache.clear()
    }

    suspend fun deleteAll() = withContext(io) {
        db.delete("executions", null, null)
        db.delete("rules", null, null)
        enabledCache.clear()
    }

    suspend fun clearHistory() = withContext(io) { db.delete("executions", null, null) }

    override suspend fun record(record: ExecutionRecord) = withContext(io) {
        db.beginTransaction()
        try {
            db.insert("executions", null, ContentValues().apply {
                put("id", record.id)
                put("rule_id", record.ruleId)
                put("started_at", record.startedAt)
                put("finished_at", record.finishedAt)
                put("outcome", record.outcome.name)
                put("reason_code", record.reasonCode.name)
                put("detail", record.detail)
            })
            // Prune: last 50 per rule, 1,000 overall.
            db.execSQL(
                "DELETE FROM executions WHERE rule_id = ? AND id NOT IN " +
                    "(SELECT id FROM executions WHERE rule_id = ? ORDER BY started_at DESC LIMIT $PER_RULE)",
                arrayOf(record.ruleId, record.ruleId)
            )
            db.execSQL(
                "DELETE FROM executions WHERE id NOT IN (SELECT id FROM executions ORDER BY started_at DESC LIMIT $TOTAL)"
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        Unit
    }

    suspend fun history(ruleId: String): List<ExecutionRecord> = withContext(io) {
        db.query("executions", null, "rule_id = ?", arrayOf(ruleId), null, null, "started_at DESC", PER_RULE.toString())
            .use { c -> buildList { while (c.moveToNext()) add(execution(c)) } }
    }

    private fun queryRules(selection: String?, args: Array<String>?): List<RuleRow> =
        db.query("rules", null, selection, args, null, null, "created_at ASC").use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getString(c.getColumnIndexOrThrow("id"))
                    val name = c.getString(c.getColumnIndexOrThrow("name"))
                    val status = runCatching { RuleStatus.valueOf(c.getString(c.getColumnIndexOrThrow("status"))) }
                        .getOrDefault(RuleStatus.DISABLED)
                    val rule = try {
                        RuleJsonMapper.ruleFromJson(
                            id, name, status,
                            c.getString(c.getColumnIndexOrThrow("definition_json")),
                            c.getInt(c.getColumnIndexOrThrow("schema_version")),
                            c.getLong(c.getColumnIndexOrThrow("created_at")),
                            c.getLong(c.getColumnIndexOrThrow("updated_at")),
                        )
                    } catch (_: InvalidRuleDefinition) {
                        null
                    }
                    add(RuleRow(id, name, status, c.getString(c.getColumnIndexOrThrow("trigger_type")), rule, null))
                }
            }
        }

    private fun execution(c: Cursor) = ExecutionRecord(
        id = c.getString(c.getColumnIndexOrThrow("id")),
        ruleId = c.getString(c.getColumnIndexOrThrow("rule_id")),
        startedAt = c.getLong(c.getColumnIndexOrThrow("started_at")),
        finishedAt = c.getLong(c.getColumnIndexOrThrow("finished_at")),
        outcome = runCatching { Outcome.valueOf(c.getString(c.getColumnIndexOrThrow("outcome"))) }.getOrDefault(Outcome.FAILED),
        reasonCode = ReasonCode.fromName(c.getString(c.getColumnIndexOrThrow("reason_code"))),
        detail = c.getString(c.getColumnIndexOrThrow("detail")),
    )

    companion object {
        const val PER_RULE = 50
        const val TOTAL = 1000
    }
}
