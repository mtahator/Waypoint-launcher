package app.olauncher.features.automation.platform

import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserManager
import app.olauncher.features.appearance.platform.SystemGrayscaleController
import app.olauncher.features.automation.domain.ValidationEnvironment
import app.olauncher.features.worldclock.domain.DayOffset

class AndroidValidationEnvironment(
    private val context: Context,
    private val grayscale: SystemGrayscaleController,
) : ValidationEnvironment {
    override fun isAppInstalled(packageName: String, userHandleId: Long): Boolean {
        val user = context.getSystemService(UserManager::class.java).getUserForSerialNumber(userHandleId) ?: return false
        return runCatching {
            context.getSystemService(LauncherApps::class.java).getActivityList(packageName, user).isNotEmpty()
        }.getOrDefault(false)
    }

    override fun isSystemGrayscaleAvailable() = grayscale.isAvailable()
    override fun isValidZone(zoneId: String) = DayOffset.isValid(zoneId)
}
