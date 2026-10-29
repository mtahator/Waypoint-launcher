package app.olauncher.features.privacy

import android.content.Intent
import android.provider.Settings
import app.olauncher.R
import app.olauncher.core.ui.ExternalFlow
import app.olauncher.core.ui.PageBuilder
import app.olauncher.core.ui.WpPageFragment
import app.olauncher.core.ui.chooseOne
import app.olauncher.features.transactions.data.LedgerFilter
import app.olauncher.helper.createDialog
import app.olauncher.helper.showToast
import java.time.LocalDate
import java.time.ZoneId

/** What is stored, and one-tap deletion of all of it. */
class PrivacyDataFragment : WpPageFragment() {
    override val pageTitle = R.string.wp_privacy_and_data
    override val pageSubtitle = R.string.wp_privacy_subtitle

    private var txCount = -1

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        launchUi {
            txCount = container.transactions.count(LedgerFilter())
            rebuild()
        }
    }

    override fun build(page: PageBuilder) {
        page.card {
            header(getString(R.string.wp_data_summary))
            row(getString(R.string.wp_transactions), if (txCount >= 0) txCount.toString() else "…")
            note(getString(R.string.wp_privacy_promise))
        }
        page.card {
            header(getString(R.string.wp_ledger))
            row(getString(R.string.wp_delete_range)) { chooseRangeToDelete() }
            row(getString(R.string.wp_delete_ledger)) {
                confirm(R.string.wp_delete_ledger, R.string.wp_delete_ledger_message) { container.transactions.deleteAll() }
            }
            note(getString(R.string.wp_ledger_not_backed_up))
        }
        page.card {
            header(getString(R.string.wp_chats))
            row(getString(R.string.wp_delete_chats)) {
                confirm(R.string.wp_delete_chats, R.string.wp_delete_chats_message) { container.chats.deleteAll() }
            }
            row(getString(R.string.wp_api_key_forget)) { container.apiKeys.set(null); rebuild() }
            note(getString(R.string.wp_assistant_privacy_note))
        }
        page.card {
            header(getString(R.string.wp_rates_title))
            toggle(getString(R.string.wp_rates_toggle), container.prefs.onlineRates, getString(R.string.wp_rates_toggle_hint)) {
                container.prefs.onlineRates = it
            }
            row(getString(R.string.wp_rates_clear)) { launchUi { container.exchangeRates.clear() } }
        }
        page.card {
            header(getString(R.string.wp_notification_access))
            row(getString(R.string.wp_revoke_access)) {
                ExternalFlow.start(this@PrivacyDataFragment, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
    }

    private fun chooseRangeToDelete() {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        fun start(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
        val ranges = listOf(
            R.string.wp_range_older_30 to (0L to start(today.minusDays(29))),
            R.string.wp_range_older_90 to (0L to start(today.minusDays(89))),
            R.string.wp_range_older_year to (0L to start(today.minusYears(1))),
        )
        requireContext().chooseOne(R.string.wp_delete_range, ranges.map { getString(it.first) }) { i ->
            val (from, to) = ranges[i].second
            confirm(R.string.wp_delete_range, R.string.wp_delete_range_message) { container.transactions.deleteRange(from, to) }
        }
    }

    private fun confirm(title: Int, message: Int, block: suspend () -> Unit) {
        requireContext().createDialog(title, R.string.delete, message = message, onAction = {
            launchUi {
                block()
                requireContext().showToast(R.string.wp_deleted)
                refresh()
            }
        }).showRespectingStatusBar()
    }
}
