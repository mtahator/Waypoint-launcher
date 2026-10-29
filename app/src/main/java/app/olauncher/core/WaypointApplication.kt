package app.olauncher.core

import android.app.Application
import android.os.StrictMode
import app.olauncher.BuildConfig

/**
 * Does only two things on start: StrictMode in debug and creating the lazy [AppContainer].
 * No disk I/O, no WorkManager, no database here.
 */
class WaypointApplication : Application() {

    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectActivityLeaks().penaltyLog().build()
            )
        }
        // Space: watch for new screenshots only if the user turned it on (off by default: costs nothing)
        android.os.Handler(mainLooper).post {
            if (container.prefs.autoScreenshots) app.olauncher.features.space.platform.ScreenshotWatcher.update(this)
        }
    }
}
