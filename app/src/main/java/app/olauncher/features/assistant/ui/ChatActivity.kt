package app.olauncher.features.assistant.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import app.olauncher.R
import app.olauncher.core.waypoint
import app.olauncher.features.space.ui.SpaceFragment
import app.olauncher.helper.showToast

/**
 * Wayfinder: a small app of its own (shown in the app list) for conversations with the configured API.
 * Chats are stored on the phone; replies keep streaming if you leave the screen.
 */
class ChatActivity : AppCompatActivity() {

    /** The Glass setting the screens were built with; they're styled at creation, so a change rebuilds them. */
    private var builtWithGlass = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        builtWithGlass = waypoint.prefs.glassTheme
        WindowCompat.setDecorFitsSystemWindows(window, false)
        waypoint.appearance.apply(this)
        setContentView(FrameLayout(this).apply { id = CONTAINER })
        if (savedInstanceState == null) {
            val space = intent?.getBooleanExtra(EXTRA_SPACE, false) == true || intent?.getBooleanExtra(EXTRA_RECORD, false) == true
            supportFragmentManager.beginTransaction().replace(CONTAINER, if (space) SpaceFragment() else ChatListFragment()).commit()
            open(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        // Glass / wallpaper / monochrome may have changed in the launcher's settings
        if (waypoint.prefs.glassTheme != builtWithGlass) { waypoint.appearance.restartFresh(this); return }
        waypoint.appearance.apply(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        open(intent)
    }

    private fun open(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_CONVERSATION, 0L) ?: 0L
        if (id > 0) showConversation(id)
        if (intent?.getBooleanExtra(EXTRA_SETTINGS, false) == true) show(AssistantSettingsFragment())
        when (intent?.getStringExtra(EXTRA_PAGE)) {
            PAGE_MEMORY -> show(MemoryFragment())
            PAGE_COMMANDS -> show(CommandsFragment())
        }
        intent?.removeExtra(EXTRA_PAGE)
        if (intent?.getBooleanExtra(EXTRA_SPACE, false) == true || intent?.getBooleanExtra(EXTRA_RECORD, false) == true) showTab(space = true)
        if (intent?.getBooleanExtra(EXTRA_RECORD, false) == true) startRecording()
    }

    /** Chats / Space tabs: swaps the root screen (no back stack). */
    fun showTab(space: Boolean) {
        supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        val current = supportFragmentManager.findFragmentById(CONTAINER)
        if (space && current is SpaceFragment || !space && current is ChatListFragment) return
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, 0)
            .replace(CONTAINER, if (space) SpaceFragment() else ChatListFragment())
            .commit()
    }

    // ---- Space recording (needs the microphone permission; notifications are optional)

    private val micPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[android.Manifest.permission.RECORD_AUDIO] == true) app.olauncher.features.space.platform.RecordingService.start(this)
        else showToast(getString(R.string.wp_space_mic_needed))
    }

    fun startRecording() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            app.olauncher.features.space.platform.RecordingService.start(this)
            return
        }
        val perms = buildList {
            add(android.Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= 33) add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        micPermission.launch(perms.toTypedArray())
    }

    fun stopRecording() = app.olauncher.features.space.platform.RecordingService.stop(this)

    fun showConversation(id: Long) = show(ChatFragment.newInstance(id))

    fun show(fragment: Fragment) {
        supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.beginTransaction()
            // enter fades only: two see-through screens cross-fading would show through each other
            .setCustomAnimations(android.R.anim.fade_in, 0, android.R.anim.fade_in, 0)
            .replace(CONTAINER, fragment)
            .addToBackStack(null)
            .commit()
    }

    /** Opens [fragment] on top of the current screen (Back returns to it), e.g. Memory from Setup. */
    fun push(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, 0, android.R.anim.fade_in, 0)
            .replace(CONTAINER, fragment)
            .addToBackStack(null)
            .commit()
    }

    companion object {
        const val EXTRA_CONVERSATION = "conversation"
        const val EXTRA_SETTINGS = "settings"
        const val EXTRA_SPACE = "space"
        const val EXTRA_RECORD = "record"
        const val EXTRA_PAGE = "page"
        const val PAGE_MEMORY = "memory"
        const val PAGE_COMMANDS = "commands"
        private val CONTAINER = R.id.wp_chat_container

        fun intent(context: Context, conversationId: Long = 0L, settings: Boolean = false, space: Boolean = false, record: Boolean = false) =
            Intent(context, ChatActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (conversationId > 0) putExtra(EXTRA_CONVERSATION, conversationId)
                if (settings) putExtra(EXTRA_SETTINGS, true)
                if (space) putExtra(EXTRA_SPACE, true)
                if (record) putExtra(EXTRA_RECORD, true)
            }

        fun open(context: Context, conversationId: Long = 0L, settings: Boolean = false, space: Boolean = false, record: Boolean = false) {
            context.startActivity(intent(context, conversationId, settings, space, record))
        }
    }
}
