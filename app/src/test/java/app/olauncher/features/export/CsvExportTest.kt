package app.olauncher.features.export

import app.olauncher.features.export.domain.CsvColumn
import app.olauncher.features.export.domain.CsvFormatter
import app.olauncher.features.export.domain.CsvSanitizer
import app.olauncher.features.transactions.domain.Transaction
import app.olauncher.features.transactions.domain.TxDirection
import app.olauncher.features.transactions.domain.TxStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Locale

class CsvExportTest {
    private fun tx(merchant: String?, amount: Long?, currency: String? = "GBP", note: String? = null) = Transaction(
        id = "1", receivedAt = Instant.parse("2026-10-03T12:15:04Z").toEpochMilli(),
        transactionAt = Instant.parse("2026-10-03T12:15:00Z").toEpochMilli(),
        sourcePackage = "pkg", sourceLabel = "Revolut", merchant = merchant, amountMinor = amount, currency = currency,
        direction = TxDirection.DEBIT, status = TxStatus.PARSED, dedupKey = "d", notificationKey = "k",
        parserId = "generic", parserVersion = 1, userNote = note, editedByUser = false, updatedAt = 0,
    )

    @Test
    fun matchesTheDocumentedFormat() {
        val csv = CsvFormatter.format(listOf(tx("Coffee Shop", 450)), CsvColumn.entries)
        assertEquals(
            "transaction_date,received_at,source,merchant,amount,currency,direction,status,note\r\n" +
                "2026-10-03T12:15:00Z,2026-10-03T12:15:04Z,Revolut,Coffee Shop,4.50,GBP,debit,parsed,\r\n",
            csv,
        )
    }

    @Test
    fun escapingRfc4180() {
        assertEquals("\"a,b\"", CsvSanitizer.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", CsvSanitizer.escape("say \"hi\""))
        assertEquals("\"line1\nline2\"", CsvSanitizer.escape("line1\nline2"))
        assertEquals("plain", CsvSanitizer.escape("plain"))
    }

    @Test
    fun formulaInjectionIsNeutralized() {
        listOf("=SUM(A1)", "+1", "-1", "@cmd", "\tx", "\rx", "|x").forEach {
            assertTrue(it, CsvSanitizer.textField(it).trimStart('"').startsWith("'"))
        }
        assertEquals("Shop", CsvSanitizer.textField("Shop"))
        val row = CsvFormatter.row(tx("=HYPERLINK(\"x\")", 450), listOf(CsvColumn.MERCHANT, CsvColumn.AMOUNT))
        assertEquals("\"'=HYPERLINK(\"\"x\"\")\",4.50\r\n", row)
    }

    @Test
    fun unicodeSurvives() {
        val row = CsvFormatter.row(tx("کافه ☕", 450, note = "یادداشت 😀"), listOf(CsvColumn.MERCHANT, CsvColumn.NOTE))
        assertEquals("کافه ☕,یادداشت 😀\r\n", row)
        assertTrue(CsvFormatter.format(emptyList(), listOf(CsvColumn.SOURCE), bom = true).startsWith("﻿"))
    }

    @Test
    fun localeIndependent() {
        val saved = Locale.getDefault()
        try {
            for (l in listOf(Locale("fa"), Locale.GERMANY, Locale("ar"))) {
                Locale.setDefault(l)
                val row = CsvFormatter.row(tx("x", 123456), listOf(CsvColumn.AMOUNT, CsvColumn.RECEIVED_AT))
                assertEquals("1234.56,2026-10-03T12:15:04Z\r\n", row)
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun zeroAndThreeDecimalCurrencies() {
        assertEquals("1500\r\n", CsvFormatter.row(tx("x", 1500, "JPY"), listOf(CsvColumn.AMOUNT)))
        assertEquals("1.234\r\n", CsvFormatter.row(tx("x", 1234, "KWD"), listOf(CsvColumn.AMOUNT)))
        assertEquals("\r\n", CsvFormatter.row(tx("x", null), listOf(CsvColumn.AMOUNT)))
    }

    @Test
    fun fileNameHasNoPersonalData() {
        assertEquals("ledger-export-20261027.csv", CsvFormatter.suggestedFileName(Instant.parse("2026-10-27T10:00:00Z").toEpochMilli()))
    }
}
