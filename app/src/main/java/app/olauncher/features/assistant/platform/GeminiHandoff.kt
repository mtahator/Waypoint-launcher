package app.olauncher.features.assistant.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Hands a question to Gemini. Waypoint itself makes no network request: it starts the Gemini app with
 * the text (share intent), and only if that app isn't installed opens Gemini on the web in the browser.
 * The question is never logged or stored.
 */
object GeminiHandoff {

    const val GEMINI_PACKAGE = "com.google.android.apps.bard"
    private const val WEB_URL = "https://gemini.google.com/app?q="

    /** @return false if nothing could handle it. */
    fun ask(context: Context, question: String): Boolean {
        val app = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, question)
            setPackage(GEMINI_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (start(context, app)) return true
        val web = Intent(Intent.ACTION_VIEW, Uri.parse(WEB_URL + Uri.encode(question)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(context, web)
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
