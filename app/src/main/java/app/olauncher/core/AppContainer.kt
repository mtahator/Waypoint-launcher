package app.olauncher.core

import android.content.Context
import app.olauncher.features.appearance.platform.GlassBackdrop
import app.olauncher.features.appearance.platform.SystemGrayscaleController
import app.olauncher.features.appearance.ui.AppearanceController
import app.olauncher.features.assistant.data.ChatRepository
import app.olauncher.features.assistant.platform.ApiKeyStore
import app.olauncher.features.assistant.platform.ChatEngine
import app.olauncher.features.notifications.platform.CaptureController
import app.olauncher.features.quickanswers.platform.ExchangeRateRepository
import app.olauncher.features.transactions.data.LedgerDatabase
import app.olauncher.features.transactions.data.TransactionRepository
import app.olauncher.features.transactions.domain.ParserRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Manual, lazy service locator. Creating it costs nothing: every member is `lazy`, so a feature
 * the user never enables never allocates its objects, opens its database, or registers anything.
 */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    val prefs by lazy { WaypointPrefs(app).also { it.migrate() } }
    val dispatchers by lazy { AppDispatchers() }
    val scope by lazy { CoroutineScope(SupervisorJob() + dispatchers.default) }

    val apiKeys by lazy { ApiKeyStore(prefs) }
    val chats by lazy { ChatRepository(app, dispatchers.io) }
    val chatEngine by lazy { ChatEngine(prefs, apiKeys, chats, scope, dispatchers.io) }
    val exchangeRates by lazy { ExchangeRateRepository(app, prefs, dispatchers.io) }
    val backdrop by lazy { GlassBackdrop(app, prefs, dispatchers.io) }
    val appearance by lazy { AppearanceController(prefs, backdrop) }
    val grayscale by lazy { SystemGrayscaleController(app) }

    val transactions by lazy { TransactionRepository({ LedgerDatabase(app) }, dispatchers.io) }
    val parsers by lazy { ParserRegistry.default() }
    val capture by lazy { CaptureController(app, prefs) }
}

val Context.waypoint: AppContainer get() = (applicationContext as WaypointApplication).container
