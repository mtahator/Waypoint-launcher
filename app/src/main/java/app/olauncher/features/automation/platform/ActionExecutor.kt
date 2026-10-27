package app.olauncher.features.automation.platform

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import app.olauncher.R
import app.olauncher.core.WaypointPrefs
import app.olauncher.features.appearance.platform.SystemGrayscaleController
import app.olauncher.features.automation.domain.ActionContext
import app.olauncher.features.automation.domain.ActionResult
import app.olauncher.features.automation.domain.ActionRunner
import app.olauncher.features.automation.domain.ActionSpec
import app.olauncher.features.automation.domain.AppEventType
import app.olauncher.features.automation.domain.AutomationEvent
import app.olauncher.features.automation.domain.ReasonCode
import app.olauncher.features.automation.domain.ToggleMode
import app.olauncher.features.transactions.data.TransactionRepository
import app.olauncher.features.transactions.domain.CapturedNotification
import app.olauncher.features.transactions.domain.ParserRegistry

/** Executes the whitelisted actions. Nothing here runs user-provided code. */
class ActionExecutor(
    private val context: Context,
    private val prefs: WaypointPrefs,
    private val notifier: AutomationNotifier,
    private val grayscale: SystemGrayscaleController,
    private val transactions: () -> TransactionRepository,
    private val parsers: ParserRegistry,
    private val emit: (AutomationEvent) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) : ActionRunner {

    private val main = Handler(Looper.getMainLooper())

    override suspend fun run(action: ActionSpec, ctx: ActionContext): ActionResult = when (action) {
        is ActionSpec.ShowNotification -> when {
            !notifier.canPost() -> ActionResult.Skipped(ReasonCode.PERMISSION_MISSING)
            ctx.dryRun -> ActionResult.Success
            notifier.post(action.title, action.text) -> ActionResult.Success
            else -> ActionResult.Failed(ReasonCode.PERMISSION_MISSING)
        }

        is ActionSpec.SetLauncherMonochrome -> {
            val current = prefs.launcherMonochrome
            val target = resolve(action.mode, current)
            if (!ctx.dryRun && target != current) {
                prefs.launcherMonochrome = target
                emit(
                    AutomationEvent.App(
                        AppEventType.MONOCHROME_CHANGED, clock(),
                        causedByRuleId = ctx.rule.id, chainDepth = ctx.event.chainDepth + 1,
                    )
                )
            }
            ActionResult.Success
        }

        is ActionSpec.SetSystemGrayscale -> when {
            !grayscale.isAvailable() -> ActionResult.Skipped(ReasonCode.PERMISSION_MISSING)
            ctx.dryRun -> ActionResult.Success
            grayscale.set(resolve(action.mode, grayscale.isOn())) -> ActionResult.Success
            else -> ActionResult.Failed(ReasonCode.NOT_AVAILABLE)
        }

        is ActionSpec.OpenApp -> openApp(action, ctx.dryRun)

        ActionSpec.SaveTransaction -> {
            val e = ctx.event
            if (e !is AutomationEvent.NotificationReceived) ActionResult.Skipped(ReasonCode.NOT_APPLICABLE)
            else if (ctx.dryRun) ActionResult.Success
            else {
                val captured = CapturedNotification(e.packageName, labelFor(e.packageName), e.notificationKey, e.postTime, e.title, e.text, e.redacted)
                transactions().saveCaptured(captured, parsers.parse(captured), clock())
                ActionResult.Success
            }
        }
    }

    private fun resolve(mode: ToggleMode, current: Boolean) = when (mode) {
        ToggleMode.ON -> true
        ToggleMode.OFF -> false
        ToggleMode.TOGGLE -> !current
    }

    private fun openApp(action: ActionSpec.OpenApp, dryRun: Boolean): ActionResult {
        val user = userFor(action.userHandleId) ?: return ActionResult.Failed(ReasonCode.APP_NOT_INSTALLED)
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val activity = try {
            launcherApps.getActivityList(action.packageName, user).firstOrNull()
        } catch (_: SecurityException) {
            null
        } ?: return ActionResult.Failed(ReasonCode.APP_NOT_INSTALLED)
        if (dryRun) return ActionResult.Success

        if (HomeVisibility.isResumed) {
            main.post {
                runCatching { launcherApps.startMainActivity(activity.componentName, user, null, null) }
            }
            return ActionResult.Success
        }
        // Background activity starts are restricted: hand the user a tap-to-open notification instead.
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(activity.componentName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context, action.packageName.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val label = activity.label?.toString() ?: action.label
        return if (notifier.post(context.getString(R.string.wp_tap_to_open, label), "", pending)) ActionResult.Success
        else ActionResult.Skipped(ReasonCode.PERMISSION_MISSING)
    }

    private fun userFor(serial: Long): UserHandle? =
        context.getSystemService(UserManager::class.java).getUserForSerialNumber(serial)

    private fun labelFor(packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Exception) {
        packageName
    }
}
