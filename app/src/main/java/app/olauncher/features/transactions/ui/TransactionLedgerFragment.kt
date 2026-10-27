package app.olauncher.features.transactions.ui

import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import app.olauncher.R
import app.olauncher.core.ui.PageBuilder
import app.olauncher.core.ui.WpPageFragment
import app.olauncher.core.ui.chooseOne
import app.olauncher.core.ui.openPage
import app.olauncher.features.transactions.data.LedgerFilter
import app.olauncher.features.transactions.domain.Money
import app.olauncher.features.transactions.domain.Transaction
import app.olauncher.features.transactions.domain.TxDirection
import app.olauncher.features.transactions.domain.TxStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Local transaction ledger grouped by day, with status/source filters and per-row editing. */
class TransactionLedgerFragment : WpPageFragment() {
    override val pageTitle = R.string.wp_ledger
    override val pageSubtitle = R.string.wp_ledger_subtitle

    private var filter = LedgerFilter()
    private var items: List<Transaction> = emptyList()
    private var total = 0
    private var limit = PAGE

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    fun load() = launchUi {
        val repo = container.transactions
        total = repo.count(filter)
        items = repo.list(filter, limit, 0)
        rebuild()
    }

    override fun build(page: PageBuilder) {
        val ctx = requireContext()
        page.card {
            row(getString(R.string.wp_filter_status), statusFilterLabel()) { chooseStatusFilter() }
            row(getString(R.string.wp_export_csv)) { openPage(R.id.exportPreviewFragment) }
        }
        if (items.isEmpty()) {
            page.card { note(getString(R.string.wp_ledger_empty)) }
            return
        }
        val dayFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
        val zone = ZoneId.systemDefault()
        items.groupBy { Instant.ofEpochMilli(it.receivedAt).atZone(zone).toLocalDate() }.forEach { (day, txs) ->
            page.card {
                header(dayFormat.format(day))
                txs.forEach { t ->
                    val time = DateUtils.formatDateTime(ctx, t.receivedAt, DateUtils.FORMAT_SHOW_TIME)
                    row(
                        t.merchant ?: t.sourceLabel,
                        value = amountText(t),
                        subtitle = listOfNotNull(t.sourceLabel, time, statusText(t.status), t.userNote).joinToString(" · "),
                    ) {
                        TransactionEditSheet.show(this@TransactionLedgerFragment, t) { load() }
                    }
                }
            }
        }
        if (items.size < total) page.card {
            row(getString(R.string.wp_load_more), value = "${items.size}/$total") { limit += PAGE; load() }
        }
    }

    private fun amountText(t: Transaction): String {
        val amount = t.amountMinor ?: return "—"
        val sign = when (t.direction) {
            TxDirection.DEBIT -> "−"
            TxDirection.CREDIT -> "+"
            null -> ""
        }
        return sign + Money.formatPlain(amount, t.currency) + (t.currency?.let { " $it" } ?: "")
    }

    private fun statusText(s: TxStatus) = getString(
        when (s) {
            TxStatus.PARSED -> R.string.wp_status_parsed
            TxStatus.NEEDS_REVIEW -> R.string.wp_status_needs_review
            TxStatus.IGNORED -> R.string.wp_status_ignored
        }
    )

    private fun statusFilterLabel(): String =
        if (filter.statuses.size == TxStatus.entries.size) getString(R.string.wp_all) else filter.statuses.joinToString(", ") { statusText(it) }

    private fun chooseStatusFilter() {
        val options = listOf<Set<TxStatus>>(
            TxStatus.entries.toSet(), setOf(TxStatus.PARSED), setOf(TxStatus.NEEDS_REVIEW), setOf(TxStatus.IGNORED),
        )
        val labels = listOf(getString(R.string.wp_all)) + listOf(TxStatus.PARSED, TxStatus.NEEDS_REVIEW, TxStatus.IGNORED).map { statusText(it) }
        requireContext().chooseOne(R.string.wp_filter_status, labels) {
            filter = filter.copy(statuses = options[it])
            limit = PAGE
            load()
        }
    }

    companion object {
        const val PAGE = 100
    }
}
