package app.olauncher.core.ui

import android.content.Context

/** Installed app label for a package, or the package name when it isn't installed. */
fun Context.appLabel(packageName: String): String = runCatching {
    packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
}.getOrDefault(packageName)
