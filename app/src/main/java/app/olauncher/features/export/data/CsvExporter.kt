package app.olauncher.features.export.data

import android.content.Context
import android.net.Uri
import app.olauncher.features.export.domain.CsvFormatter
import app.olauncher.features.export.domain.ExportRequest
import app.olauncher.features.transactions.data.LedgerFilter
import app.olauncher.features.transactions.data.TransactionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/** Streams the ledger to CSV. Nothing is uploaded anywhere; destinations are chosen by the user. */
class CsvExporter(
    private val context: Context,
    private val repository: TransactionRepository,
    private val io: CoroutineDispatcher,
) {
    fun filterFor(r: ExportRequest) = LedgerFilter(statuses = r.statuses, fromMillis = r.fromMillis, toMillisExclusive = r.toMillisExclusive)

    suspend fun writeTo(uri: Uri, request: ExportRequest): Int = withContext(io) {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("no output stream")
        out.use { write(it, request) }
    }

    /** Temp copy for the share sheet, in cacheDir/exports (deleted after sharing / next export). */
    suspend fun writeTempFile(request: ExportRequest, fileName: String): File = withContext(io) {
        val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
        val file = File(dir, fileName)
        file.outputStream().use { write(it, request) }
        file
    }

    suspend fun cleanupTemp() = withContext(io) {
        File(context.cacheDir, EXPORT_DIR).listFiles()?.forEach { it.delete() }
        Unit
    }

    private suspend fun write(out: OutputStream, request: ExportRequest): Int {
        val writer = out.bufferedWriter(Charsets.UTF_8)
        var rows = 0
        if (request.excelBom) writer.write(CsvFormatter.BOM)
        writer.write(CsvFormatter.header(request.columns))
        repository.forEachForExport(filterFor(request)) {
            writer.write(CsvFormatter.row(it, request.columns))
            rows++
        }
        writer.flush()
        return rows
    }

    companion object {
        const val EXPORT_DIR = "exports"
    }
}
