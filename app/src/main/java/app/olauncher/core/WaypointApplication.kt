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
    }
}
