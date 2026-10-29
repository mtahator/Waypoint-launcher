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

/**
 * "Chat": a small app of its own (shown in the app list) for conversations with the configured API.
 * Chats are stored on the phone; replies keep streaming if you leave the screen.
 */
class ChatActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        waypoint.appearance.apply(this)
        setContentView(FrameLayout(this).apply { id = CONTAINER })
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(CONTAINER, ChatListFragment()).commit()
            open(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        open(intent)
    }

    private fun open(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_CONVERSATION, 0L) ?: 0L
        if (id > 0) showConversation(id)
        if (intent?.getBooleanExtra(EXTRA_SETTINGS, false) == true) show(AssistantSettingsFragment())
    }

    fun showConversation(id: Long) = show(ChatFragment.newInstance(id))

    fun show(fragment: Fragment) {
        supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out, android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(CONTAINER, fragment)
            .addToBackStack(null)
            .commit()
    }

    companion object {
        const val EXTRA_CONVERSATION = "conversation"
        const val EXTRA_SETTINGS = "settings"
        private val CONTAINER = R.id.wp_chat_container

        fun open(context: Context, conversationId: Long = 0L, settings: Boolean = false) {
            context.startActivity(Intent(context, ChatActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (conversationId > 0) putExtra(EXTRA_CONVERSATION, conversationId)
                if (settings) putExtra(EXTRA_SETTINGS, true)
            })
        }
    }
}
