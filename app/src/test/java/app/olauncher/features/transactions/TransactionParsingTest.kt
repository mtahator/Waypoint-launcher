package app.olauncher.features.transactions

import app.olauncher.features.transactions.domain.CapturedNotification
import app.olauncher.features.transactions.domain.Confidence
import app.olauncher.features.transactions.domain.DedupKey
import app.olauncher.features.transactions.domain.GenericAmountParser
import app.olauncher.features.transactions.domain.Money
import app.olauncher.features.transactions.domain.TxDirection
import app.olauncher.features.transactions.domain.TxStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Synthetic fixtures only. Real, anonymized samples go under resources/fixtures/notifications/<source>/vN/
 * once captured on a device (TODO(verify-on-device)); no source-specific format is guessed here.
 */
class TransactionParsingTest {
    private val parser = GenericAmountParser()
    private fun n(title: String, text: String, redacted: Boolean = false) =
        CapturedNotification("com.example.bank", "Bank", "key", 1_700_000_000_000, title, text, redacted)

    @Test
    fun clearDebitIsHighConfidence() {
        val r = parser.parse(n("Bank", "Paid £4.50 at Coffee Shop"))
        assertEquals(450L, r.amountMinor)
        assertEquals("GBP", r.currency)
        assertEquals(TxDirection.DEBIT, r.direction)
        assertEquals("Coffee Shop", r.merchant)
        assertEquals(Confidence.HIGH, r.confidence)
        assertEquals(TxStatus.PARSED, r.status)
    }

    @Test
    fun refundsAndCreditsAreCredit() {
        assertEquals(TxDirection.CREDIT, parser.parse(n("Bank", "Refund of €12.00 from Shop")).direction)
        assertEquals(TxDirection.CREDIT, parser.parse(n("Bank", "You received 25.00 EUR from Alex")).direction)
        assertEquals(TxDirection.CREDIT, parser.parse(n("Bank", "+£10.00 top-up")).direction)
    }

    @Test
    fun missingOrAmbiguousCurrencyNeedsReview() {
        val dollars = parser.parse(n("Bank", "Paid $5.00 at Store")) // $ = USD/CAD/AUD/...: never guessed
        assertNull(dollars.currency)
        assertEquals(500L, dollars.amountMinor)
        assertEquals(TxStatus.NEEDS_REVIEW, dollars.status)
        val none = parser.parse(n("Bank", "Your card was used"))
        assertNull(none.amountMinor)
        assertEquals(TxStatus.NEEDS_REVIEW, none.status)
    }

    @Test
    fun multipleAmountsNeedReview() {
        val r = parser.parse(n("Bank", "Paid £4.50 at Cafe. Balance £120.30"))
        assertNull(r.amountMinor)
        assertEquals(TxStatus.NEEDS_REVIEW, r.status)
        // The same amount mentioned twice is still one amount
        assertEquals(TxStatus.PARSED, parser.parse(n("Paid £4.50", "Paid £4.50 at Cafe")).status)
    }

    @Test
    fun unknownDirectionNeedsReview() {
        assertEquals(TxStatus.NEEDS_REVIEW, parser.parse(n("Bank", "£4.50 Coffee Shop")).status)
    }

    @Test
    fun redactedContentNeedsReview() {
        val r = parser.parse(n("Bank", "Sensitive notification content hidden", redacted = true))
        assertEquals(TxStatus.NEEDS_REVIEW, r.status)
        assertNull(r.amountMinor)
    }

    @Test
    fun foreignCurrenciesAndSeparators() {
        assertEquals(1_234_56L, parser.parse(n("Bank", "Paid USD 1,234.56 at Hotel")).amountMinor)
        assertEquals(1_234_56L, parser.parse(n("Bank", "Paid 1.234,56 EUR at Hotel")).amountMinor)
        assertEquals(1_234_56L, parser.parse(n("Bank", "Paid 1 234,56 EUR")).amountMinor)
        assertEquals(450L, parser.parse(n("Bank", "Paid 4,50 € at Bäckerei")).amountMinor)
        assertEquals(1_500L, parser.parse(n("Bank", "Paid JPY 1,500 at Konbini")).amountMinor) // JPY has 0 decimals
        assertEquals(1_234_567L, parser.parse(n("Bank", "Paid KWD 1,234.567")).amountMinor) // KWD has 3 decimals
        assertEquals(150_000L, parser.parse(n("Bank", "Paid 1,500 GBP")).amountMinor) // thousands, not decimals
    }

    @Test
    fun wordsThatLookLikeCodesAreNotMoney() {
        assertTrue(parser.findAmounts("You have 2 NEW messages").isEmpty())
        assertTrue(parser.findAmounts("Flight AB 1234 boards").isEmpty())
    }

    @Test
    fun rtlTextIsHandled() {
        val r = parser.parse(n("بانک", "پرداخت شد 4.50 GBP"))
        assertEquals(450L, r.amountMinor)
        assertEquals("GBP", r.currency)
    }

    @Test
    fun moneyMinorUnits() {
        assertEquals(0, Money.fractionDigits("JPY"))
        assertEquals(2, Money.fractionDigits("GBP"))
        assertEquals(3, Money.fractionDigits("KWD"))
        assertEquals(2, Money.fractionDigits(null))
        assertEquals(450L, Money.toMinor(BigDecimal("4.5"), "GBP"))
        assertNull(Money.toMinor(BigDecimal("4.505"), "GBP"))
        assertEquals("4.50", Money.formatPlain(450, "GBP"))
        assertEquals("1500", Money.formatPlain(1500, "JPY"))
        assertEquals("1.234", Money.formatPlain(1234, "KWD"))
        assertEquals(450L, Money.parseUserAmount("4,50", "EUR"))
        assertNull(Money.parseUserAmount("abc", "EUR"))
        assertNull(Money.parseUserAmount("-3", "EUR"))
        assertFalse(Money.isKnownCurrency("XYZ1"))
    }

    @Test
    fun dedupKeyIsExactAndDistinguishesPurchases() {
        val a = DedupKey.of("pkg", "key1", 450, "GBP", "Cafe", 1_700_000_000_123)
        assertEquals(a, DedupKey.of("pkg", "key1", 450, "GBP", "Cafe", 1_700_000_000_999)) // same second
        // Two genuine identical-amount purchases arrive as different notifications: different keys.
        assertNotEquals(a, DedupKey.of("pkg", "key2", 450, "GBP", "Cafe", 1_700_000_000_123))
        assertNotEquals(a, DedupKey.of("pkg", "key1", 450, "GBP", "Cafe", 1_700_000_001_000))
        assertEquals(64, a.length)
    }

    @Test
    fun noFinancialDataInToString() {
        val captured = n("Bank", "Paid £4.50 at Coffee Shop")
        assertFalse(captured.toString().contains("4.50"))
        assertFalse(captured.toString().contains("Coffee"))
        assertFalse(parser.parse(captured).toString().contains("450"))
    }
}
