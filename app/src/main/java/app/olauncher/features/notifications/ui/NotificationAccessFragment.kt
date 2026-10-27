package app.olauncher.features.notifications.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import app.olauncher.R
import app.olauncher.core.ui.ExternalFlow
import app.olauncher.core.ui.PageBuilder
import app.olauncher.core.ui.WpPageFragment
import app.olauncher.core.ui.openPage
import app.olauncher.core.ui.pickApps
import app.olauncher.core.ui.appLabel
import app.olauncher.features.notifications.platform.NotificationAccessStatus
import app.olauncher.features.notifications.platform.NotificationCaptureService

/** Onboarding + status for notification capture: why, what's read, what's stored, how to revoke. */
class NotificationAccessFragment : WpPageFragment() {
    override val pageTitle = R.string.wp_notification_capture

    override fun onResume() {
        super.onResume()
        rebuild() // access may have changed in system settings
        container.capture.updateListenerBinding()
    }

    override fun build(page: PageBuilder) {
        val ctx = requireContext()
        val prefs = container.prefs
        val status = NotificationCaptureService.currentStatus(ctx)
        page.card {
            header(getString(R.string.wp_how_it_works))
            note(getString(R.string.wp_capture_what_read))
            note(getString(R.string.wp_capture_what_stored))
            note(getString(R.string.wp_capture_never))
        }
        page.card {
            header(getString(R.string.wp_access))
            row(
                getString(R.string.wp_access_status),
                value = getString(
                    when (status) {
                        NotificationAccessStatus.NO_ACCESS -> R.string.wp_status_no_access
                        NotificationAccessStatus.CONNECTED -> R.string.wp_status_connected
                        else -> R.string.wp_status_idle
                    }
                ),
                subtitle = getString(if (status == NotificationAccessStatus.NO_ACCESS) R.string.wp_grant_access_hint else R.string.wp_revoke_access_hint),
            ) { ExternalFlow.start(this@NotificationAccessFragment, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            note(getString(R.string.wp_restricted_settings_help))
            row(getString(R.string.wp_open_app_info)) {
                ExternalFlow.start(this@NotificationAccessFragment,
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
            }
        }
        page.card {
            header(getString(R.string.wp_ledger_capture))
            toggle(getString(R.string.wp_save_transactions), prefs.ledgerCaptureEnabled, getString(R.string.wp_save_transactions_hint)) {
                prefs.ledgerCaptureEnabled = it
                container.capture.updateListenerBinding()
            }
            val selected = prefs.capturePackages
            row(
                getString(R.string.wp_source_apps),
                value = selected.size.toString(),
                subtitle = selected.joinToString(", ") { ctx.appLabel(it) }.ifEmpty { getString(R.string.wp_none_selected) },
            ) {
                ctx.pickApps(this@NotificationAccessFragment, selected) {
                    prefs.capturePackages = it
                    container.capture.updateListenerBinding()
                    rebuild()
                }
            }
            note(getString(R.string.wp_known_sources_note))
        }
        page.card {
            row(getString(R.string.wp_open_ledger)) { openPage(R.id.transactionLedgerFragment) }
            row(getString(R.string.wp_privacy_and_data)) { openPage(R.id.privacyDataFragment) }
        }
    }
}
