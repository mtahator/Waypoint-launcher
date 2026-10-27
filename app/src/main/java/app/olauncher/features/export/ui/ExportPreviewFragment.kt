package app.olauncher.features.export.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import app.olauncher.R
import app.olauncher.core.ui.ExternalFlow
import app.olauncher.core.ui.PageBuilder
import app.olauncher.core.ui.WpPageFragment
import app.olauncher.core.ui.chooseOne
import app.olauncher.features.export.data.CsvExporter
import app.olauncher.features.export.domain.CsvColumn
import app.olauncher.features.export.domain.CsvFormatter
import app.olauncher.features.export.domain.ExportRequest
import app.olauncher.features.transactions.domain.Transaction
import app.olauncher.features.transactions.domain.TxStatus
import app.olauncher.helper.showToast
import java.time.LocalDate
import java.time.ZoneId

/** Review before export: range, statuses, columns, preview of the first rows, then SAF save or share. */
class ExportPreviewFragment : WpPageFragment() {
    override val pageTitle = R.string.wp_export_csv
    override val pageSubtitle = R.string.wp_export_subtitle

    private val exporter by lazy { CsvExporter(requireContext().applicationContext, container.transactions, container.dispatchers.io) }

    private var rangeIndex = 1
    private var statuses = setOf(TxStatus.PARSED, TxStatus.NEEDS_REVIEW)
    private val columns = CsvColumn.entries.toMutableList()
    private var excelBom = false
    private var masked = true
    private var count = 0
    private var preview: List<Transaction> = emptyList()

    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        ExternalFlow.active = false
        if (uri != null) launchUi {
            val rows = runCatching { exporter.writeTo(uri, request()) }
            requireContext().showToast(
                if (rows.isSuccess) getString(R.string.wp_export_saved, rows.getOrDefault(0)) else getString(R.string.wp_export_failed)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        ExternalFlow.active = false
        launchUi {
            exporter.cleanupTemp() // the share sheet has returned (or this is a fresh export)
            refreshPreview()
        }
    }

    private suspend fun refreshPreview() {
        val repo = container.transactions
        val filter = exporter.filterFor(request())
        count = repo.count(filter)
        preview = repo.list(filter, 5, 0)
        rebuild()
    }

    private fun ranges(): List<Pair<Int, Pair<Long, Long>>> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        fun start(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrow = start(today.plusDays(1))
        return listOf(
            R.string.wp_range_7_days to (start(today.minusDays(6)) to tomorrow),
            R.string.wp_range_30_days to (start(today.minusDays(29)) to tomorrow),
            R.string.wp_range_this_month to (start(today.withDayOfMonth(1)) to tomorrow),
            R.string.wp_range_last_month to (start(today.withDayOfMonth(1).minusMonths(1)) to start(today.withDayOfMonth(1))),
            R.string.wp_range_this_year to (start(today.withDayOfYear(1)) to tomorrow),
            R.string.wp_range_all to (0L to Long.MAX_VALUE),
        )
    }

    private fun request(): ExportRequest {
        val (from, to) = ranges()[rangeIndex].second
        return ExportRequest(from, to, statuses, CsvColumn.entries.filter { it in columns }, excelBom)
    }

    override fun build(page: PageBuilder) {
        page.card {
            row(getString(R.string.wp_date_range), getString(ranges()[rangeIndex].first)) {
                requireContext().chooseOne(R.string.wp_date_range, ranges().map { getString(it.first) }) { rangeIndex = it; launchUi { refreshPreview() } }
            }
            TxStatus.entries.forEach { s ->
                toggle(statusLabel(s), s in statuses) { on ->
                    statuses = if (on) statuses + s else statuses - s
                    launchUi { refreshPreview() }
                }
            }
        }
        page.card {
            header(getString(R.string.wp_columns))
            CsvColumn.entries.forEach { c ->
                toggle(c.header, c in columns, if (c.sensitive) getString(R.string.wp_sensitive_column) else null) { on ->
                    if (on) columns += c else columns -= c
                    rebuild()
                }
            }
            toggle(getString(R.string.wp_excel_bom), excelBom, getString(R.string.wp_excel_bom_hint)) { excelBom = it }
        }
        page.card {
            header(getString(R.string.wp_preview))
            row(getString(R.string.wp_rows), count.toString())
            toggle(getString(R.string.wp_mask_preview), masked) { masked = it; rebuild() }
            val cols = CsvColumn.entries.filter { it in columns }
            if (cols.isEmpty()) note(getString(R.string.wp_no_columns))
            else note(CsvFormatter.header(cols).trimEnd() + "\n" + preview.joinToString("\n") { previewRow(it, cols) })
            if (columns.any { it.sensitive }) note(getString(R.string.wp_export_warning))
        }
        page.card {
            val canExport = count > 0 && columns.isNotEmpty()
            row(getString(R.string.wp_save_to_file), subtitle = CsvFormatter.suggestedFileName(System.currentTimeMillis())) {
                if (!canExport) return@row
                ExternalFlow.active = true
                createDocument.launch(CsvFormatter.suggestedFileName(System.currentTimeMillis()))
            }.setEnabled(canExport)
            row(getString(R.string.wp_share), subtitle = getString(R.string.wp_share_hint)) {
                if (canExport) share()
            }.setEnabled(canExport)
        }
    }

    private fun previewRow(t: Transaction, cols: List<CsvColumn>): String = cols.joinToString(",") { c ->
        val cell = CsvFormatter.row(t, listOf(c)).trimEnd()
        if (masked && c.sensitive && cell.isNotEmpty()) "•••" else cell
    }

    private fun share() = launchUi {
        val ctx = requireContext()
        val file = exporter.writeTempFile(request(), CsvFormatter.suggestedFileName(System.currentTimeMillis()))
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".exports", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ExternalFlow.start(this@ExportPreviewFragment, Intent.createChooser(send, getString(R.string.wp_share)))
    }

    private fun statusLabel(s: TxStatus) = getString(
        when (s) {
            TxStatus.PARSED -> R.string.wp_status_parsed
            TxStatus.NEEDS_REVIEW -> R.string.wp_status_needs_review
            TxStatus.IGNORED -> R.string.wp_status_ignored
        }
    )
}
