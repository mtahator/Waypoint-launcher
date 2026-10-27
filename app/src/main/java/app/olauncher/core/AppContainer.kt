package app.olauncher.core

import android.content.Context
import app.olauncher.features.appearance.platform.SystemGrayscaleController
import app.olauncher.features.appearance.ui.AppearanceController
import app.olauncher.features.automation.data.AutomationDatabase
import app.olauncher.features.automation.data.AutomationRepository
import app.olauncher.features.automation.domain.AutomationEngine
import app.olauncher.features.automation.domain.AutomationEvent
import app.olauncher.features.automation.domain.ConditionEvaluator
import app.olauncher.features.automation.platform.ActionExecutor
import app.olauncher.features.automation.platform.AutomationCoordinator
import app.olauncher.features.automation.platform.AutomationNotifier
import app.olauncher.features.automation.platform.NextTimeTriggerScheduler
import app.olauncher.features.transactions.data.LedgerDatabase
import app.olauncher.features.transactions.data.TransactionRepository
import app.olauncher.features.transactions.domain.CapturedNotification
import app.olauncher.features.transactions.domain.ParserRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Manual, lazy service locator. Creating it costs nothing: every member is `lazy`, so a feature
 * the user never enables never allocates its objects, opens its database, or registers anything.
 */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    val prefs by lazy { WaypointPrefs(app) }
    val dispatchers by lazy { AppDispatchers() }
    val scope by lazy { CoroutineScope(SupervisorJob() + dispatchers.default) }

    val appearance by lazy { AppearanceController(prefs) }
    val grayscale by lazy { SystemGrayscaleController(app) }

    val automationRepository by lazy { AutomationRepository({ AutomationDatabase(app) }, dispatchers.io) }
    val transactions by lazy { TransactionRepository({ LedgerDatabase(app) }, dispatchers.io) }
    val parsers by lazy { ParserRegistry.default() }

    val notifier by lazy { AutomationNotifier(app) }
    val scheduler by lazy { NextTimeTriggerScheduler(app, prefs) }
    val coordinator by lazy { AutomationCoordinator(app, prefs, { automationRepository }, { scheduler }) }

    private val actionExecutor by lazy {
        ActionExecutor(app, prefs, notifier, grayscale, { transactions }, parsers, emit = { fire(it) })
    }

    val engine: AutomationEngine by lazy {
        AutomationEngine(
            rules = automationRepository,
            actions = actionExecutor,
            history = automationRepository,
            evaluator = ConditionEvaluator { title, text ->
                parsers.parse(CapturedNotification("", "", "", 0L, title, text, redacted = false)).hasAmount
            },
            dispatcher = dispatchers.engine,
        )
    }

    /**
     * Entry point for every event source. Returns immediately; when no enabled rule listens to this
     * trigger type, the engine (and its database) is never created.
     */
    fun fire(event: AutomationEvent) {
        if (prefs.enabledRuleCount == 0 || event.triggerType !in prefs.enabledRuleTypes) return
        engine.emit(event)
    }
}

val Context.waypoint: AppContainer get() = (applicationContext as WaypointApplication).container
