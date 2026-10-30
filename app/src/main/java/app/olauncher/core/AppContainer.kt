package app.olauncher.core

import android.content.Context
import app.olauncher.features.appearance.platform.GlassBackdrop
import app.olauncher.features.appearance.platform.SystemGrayscaleController
import app.olauncher.features.appearance.ui.AppearanceController
import app.olauncher.features.assistant.data.ChatRepository
import app.olauncher.features.assistant.platform.ApiKeyStore
import app.olauncher.features.assistant.domain.Memories
import app.olauncher.features.assistant.domain.MemoryItem
import app.olauncher.features.assistant.platform.ChatEngine
import app.olauncher.features.assistant.platform.ToolHost
import app.olauncher.features.assistant.platform.ToolRunner
import app.olauncher.features.assistant.platform.ImageClient
import app.olauncher.features.quickanswers.platform.ExchangeRateRepository
import app.olauncher.features.space.data.NotesRepository
import app.olauncher.features.space.platform.SpaceEngine
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
    val braveKeys by lazy { ApiKeyStore(prefs, ApiKeyStore.Slot.BRAVE) }
    val chatEngine: ChatEngine by lazy { ChatEngine(prefs, apiKeys, braveKeys, chats, ImageClient(app), scope, dispatchers.io, localAction = { space.chatRequest(it) },
        tools = ToolRunner(app, object : ToolHost {
            override suspend fun addReminder(title: String, due: Long, steps: List<String>) = space.toolReminder(title, due, steps)
            override suspend fun searchNotes(query: String) = space.searchText(query)
            override suspend fun remember(tag: String, text: String): String {
                val t = text.trim()
                if (t.length < 3) return "Nothing to remember"
                val tagged = Memories.normalizeTag(tag.ifBlank { Memories.guessTag(t) ?: "other" })
                chats.addMemory(tagged, t, source = MemoryItem.SOURCE_CHAT)
                chatEngine.memoryEdited()
                return "Saved to memory under $tagged"
            }
        })) }
    val space: SpaceEngine by lazy {
        SpaceEngine(app, prefs, apiKeys, chatEngine, NotesRepository(app, dispatchers.io), scope, dispatchers.io)
    }
    val exchangeRates by lazy { ExchangeRateRepository(app, prefs, dispatchers.io) }
    val backdrop by lazy { GlassBackdrop(app, prefs, dispatchers.io) }
    val appearance by lazy { AppearanceController(prefs, backdrop) }
    val grayscale by lazy { SystemGrayscaleController(app) }

}

val Context.waypoint: AppContainer get() = (applicationContext as WaypointApplication).container
