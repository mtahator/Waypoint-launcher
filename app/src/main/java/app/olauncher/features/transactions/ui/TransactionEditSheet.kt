package app.olauncher.features.transactions.ui

import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.olauncher.R
import app.olauncher.core.ui.PageBuilder
import app.olauncher.core.ui.chooseOne
import app.olauncher.core.ui.promptText
import app.olauncher.core.waypoint
import app.olauncher.features.transactions.domain.Money
import app.olauncher.features.transactions.domain.Transaction
import app.olauncher.features.transactions.domain.TxDirection
import app.olauncher.features.transactions.domain.TxStatus
import app.olauncher.helper.OlDialog
import app.olauncher.helper.createDialog
import app.olauncher.helper.showToast
import kotlinx.coroutines.launch
import java.util.Locale

/** Edit merchant/amount/currency/direction/status/note, ignore or delete a record. */
object TransactionEditSheet {

    fun show(fragment: Fragment, original: Transaction, onChanged: () -> Unit) {
        val ctx = fragment.requireContext()
        var t = original
        lateinit var dialog: OlDialog
        lateinit var body: LinearLayout
        val repo = ctx.waypoint.transactions

        fun persist(updated: Transaction) {
            t = updated.copy(updatedAt = System.currentTimeMillis(), editedByUser = true)
            fragment.viewLifecycleOwner.lifecycleScope.launch { repo.update(t); onChanged() }
        }

        fun render() {
            val page = PageBuilder(body)
            page.clear()
            page.row(ctx.getString(R.string.wp_merchant), t.merchant ?: "—") {
                ctx.promptText(R.string.wp_merchant, initial = t.merchant.orEmpty()) { persist(t.copy(merchant = it.trim().ifEmpty { null })); render() }
            }
            page.row(ctx.getString(R.string.wp_amount), t.amountMinor?.let { Money.formatPlain(it, t.currency) } ?: "—") {
                ctx.promptText(R.string.wp_amount, initial = t.amountMinor?.let { Money.formatPlain(it, t.currency) }.orEmpty(), numeric = true) {
                    val minor = Money.parseUserAmount(it, t.currency)
                    if (minor == null) ctx.showToast(R.string.wp_invalid_amount) else { persist(t.copy(amountMinor = minor)); render() }
                }
            }
            page.row(ctx.getString(R.string.wp_currency), t.currency ?: "—") {
                ctx.promptText(R.string.wp_currency, initial = t.currency.orEmpty()) {
                    val code = it.trim().uppercase(Locale.ROOT)
                    if (code.isNotEmpty() && !Money.isKnownCurrency(code)) ctx.showToast(R.string.wp_invalid_currency)
                    else {
                        // Re-scale minor units when the currency's fraction digits change (e.g. GBP -> JPY).
                        val newCode = code.ifEmpty { null }
                        val rescaled = t.amountMinor?.let { m ->
                            val value = java.math.BigDecimal.valueOf(m, Money.fractionDigits(t.currency))
                            Money.toMinor(value.setScale(Money.fractionDigits(newCode), java.math.RoundingMode.HALF_EVEN), newCode)
                        }
                        persist(t.copy(currency = newCode, amountMinor = rescaled)); render()
                    }
                }
            }
            page.row(ctx.getString(R.string.wp_direction), t.direction?.let { dirText(ctx, it) } ?: "—") {
                val dirs = listOf(TxDirection.DEBIT, TxDirection.CREDIT, null)
                ctx.chooseOne(R.string.wp_direction, dirs.map { d -> d?.let { dirText(ctx, it) } ?: "—" }) { persist(t.copy(direction = dirs[it])); render() }
            }
            page.row(ctx.getString(R.string.wp_status), statusText(ctx, t.status)) {
                val all = TxStatus.entries
                ctx.chooseOne(R.string.wp_status, all.map { statusText(ctx, it) }) { persist(t.copy(status = all[it])); render() }
            }
            page.row(ctx.getString(R.string.wp_note), t.userNote ?: "—") {
                ctx.promptText(R.string.wp_note, initial = t.userNote.orEmpty(), multiLine = true) { persist(t.copy(userNote = it.trim().ifEmpty { null })); render() }
            }
            page.row(ctx.getString(R.string.wp_ignore)) { persist(t.copy(status = TxStatus.IGNORED)); dialog.dismiss() }
            page.row(ctx.getString(R.string.delete)) {
                fragment.viewLifecycleOwner.lifecycleScope.launch { repo.delete(t.id); onChanged() }
                dialog.dismiss()
            }
            page.note(ctx.getString(R.string.wp_parsed_by, t.parserId, t.parserVersion))
        }

        dialog = ctx.createDialog(R.string.wp_transaction, R.string.wp_done, content = { parent ->
            LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                body = this
                layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = (12 * resources.displayMetrics.density).toInt() }
            }
        })
        render()
        dialog.showRespectingStatusBar()
    }

    private fun dirText(ctx: android.content.Context, d: TxDirection) =
        ctx.getString(if (d == TxDirection.DEBIT) R.string.wp_debit else R.string.wp_credit)

    private fun statusText(ctx: android.content.Context, s: TxStatus) = ctx.getString(
        when (s) {
            TxStatus.PARSED -> R.string.wp_status_parsed
            TxStatus.NEEDS_REVIEW -> R.string.wp_status_needs_review
            TxStatus.IGNORED -> R.string.wp_status_ignored
        }
    )
}
