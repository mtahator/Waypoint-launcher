package app.olauncher.features.space.platform

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import app.olauncher.core.waypoint
import kotlinx.coroutines.launch

/**
 * Adds new screenshots to Space automatically (opt-in). The launcher is always running, so a MediaStore
 * observer sees each screenshot as it's taken; the picture is copied into Space and read on the phone.
 */
object ScreenshotWatcher {
    private var observer: ContentObserver? = null

    fun permission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(context: Context) = ContextCompat.checkSelfPermission(context, permission()) == PackageManager.PERMISSION_GRANTED

    /** Starts or stops watching according to the setting and permission. */
    fun update(context: Context) {
        val app = context.applicationContext
        val on = app.waypoint.prefs.autoScreenshots && hasPermission(app)
        if (on && observer == null) {
            val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) = check(app)
            }
            runCatching { app.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, o) }
                .onSuccess { observer = o }
            if (app.waypoint.prefs.lastScreenshotSeen == 0L) app.waypoint.prefs.lastScreenshotSeen = System.currentTimeMillis() / 1000
        } else if (!on && observer != null) {
            observer?.let { app.contentResolver.unregisterContentObserver(it) }
            observer = null
        }
    }

    private fun check(context: Context) {
        val c = context.waypoint
        c.scope.launch {
            val since = c.prefs.lastScreenshotSeen
            val found = runCatching { query(context, since) }.getOrDefault(emptyList())
            if (found.isEmpty()) return@launch
            c.prefs.lastScreenshotSeen = found.maxOf { it.second }
            found.forEach { (uri, _) -> c.space.importImage(uri, screenshot = true) }
        }
    }

    /** New images in a Screenshots folder since [since] (seconds), oldest first. */
    private fun query(context: Context, since: Long): List<Pair<Uri, Long>> {
        val proj = buildList {
            add(MediaStore.Images.Media._ID); add(MediaStore.Images.Media.DATE_ADDED)
            if (Build.VERSION.SDK_INT >= 29) add(MediaStore.Images.Media.RELATIVE_PATH) else add(MediaStore.Images.Media.DATA)
        }.toTypedArray()
        val out = ArrayList<Pair<Uri, Long>>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, "${MediaStore.Images.Media.DATE_ADDED} > ?", arrayOf(since.toString()),
            "${MediaStore.Images.Media.DATE_ADDED} ASC",
        )?.use { cur ->
            while (cur.moveToNext()) {
                val path = cur.getString(2).orEmpty()
                if (!path.contains("screenshot", true)) continue
                out += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cur.getLong(0)) to cur.getLong(1)
            }
        }
        return out
    }
}
