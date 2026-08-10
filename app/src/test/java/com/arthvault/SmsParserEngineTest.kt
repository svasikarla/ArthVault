package com.arthvault

import com.arthvault.data.local.entity.MerchantRuleEntity
import com.arthvault.data.local.entity.STATUS_FAILED
import com.arthvault.data.local.entity.STATUS_POSTED
import com.arthvault.data.local.entity.TxnType
import com.arthvault.data.parser.SmsParserEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression cover for the parser defects found by running the app against its own
 * sample messages: merchant strings polluted with account prefixes, merchants that
 * collapsed to the literal "Merchant", account tails never extracted, and declined
 * transactions stored as real spend.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SmsParserEngineTest {

    private val engine = SmsParserEngine()
    private val rules = listOf(
        MerchantRuleEntity("SWIGGY", "Food & Dining"),
        MerchantRuleEntity("AMAZON", "Shopping"),
        MerchantRuleEntity("SHELL", "Transport & Fuel"),
        MerchantRuleEntity("STARBUCKS", "Food & Dining")
    )

    private fun parse(body: String) =
        engine.parseMessage("AD-HDFCBK-S", body, 1_700_000_000_000L, rules, BundledParserRules.entities).parsedTransaction

    @Test
    fun `strips the account prefix the capture group sweeps up`() {
        val txn = parse("Rs 420.00 debited from A/C XX8901 to SWIGGY. Ref: UPI/261893. Bal Rs 45,210.")
        assertNotNull(txn)
        assertEquals("SWIGGY", txn!!.merchant)
        assertEquals(420.0, txn.amount, 0.001)
        assertEquals("DEBIT", txn.direction)
    }

    @Test
    fun `card purchase resolves the merchant after at, not the literal Merchant`() {
        val txn = parse("Rs. 2,499.00 spent on Card ending 4321 at AMAZON INDIA. Avail Limit: Rs 1,20,000.")
        assertNotNull(txn)
        assertEquals("AMAZON INDIA", txn!!.merchant)
        assertEquals("Shopping", txn.category)
    }

    @Test
    fun `extracts the account tail even when a DB rule matched first`() {
        val txn = parse("Rs 420.00 debited from A/C XX8901 to SWIGGY. Bal Rs 45,210.")
        assertEquals("8901", txn?.accountTail)
    }

    @Test
    fun `extracts the closing balance`() {
        val txn = parse("Rs 420.00 debited from A/C XX8901 to SWIGGY. Avl Bal: Rs 45,210.50")
        assertEquals(45210.50, txn?.balanceAfter!!, 0.001)
    }

    @Test
    fun `no balance quoted means no balance recorded`() {
        val txn = parse("Paid Rs 850.00 to BLINKIT GROCERY via UPI. Txn ID: 55510001.")
        assertNull(txn?.balanceAfter)
    }

    @Test
    fun `a declined transaction is recorded but marked failed`() {
        val txn = parse("Rs 2,750.00 spent on Card ending 4321 at MYNTRA was declined due to insufficient balance.")
        assertNotNull("declined messages should still be captured for the audit trail", txn)
        assertEquals(STATUS_FAILED, txn!!.status)
        assertEquals("the status clause does not belong in the merchant name", "MYNTRA", txn.merchant)
    }

    @Test
    fun `an ordinary transaction is posted`() {
        assertEquals(STATUS_POSTED, parse("Paid Rs 850.00 to BLINKIT GROCERY via UPI.")?.status)
    }

    @Test
    fun `a refund is typed as a refund, not as income`() {
        val txn = parse("Rs 1,299.00 refund credited to A/C XX8901 from FLIPKART for order cancellation.")
        assertNotNull(txn)
        assertEquals("CREDIT", txn!!.direction)
        assertEquals(TxnType.REFUND, txn.txnType)
        assertEquals("FLIPKART", txn.merchant)
    }

    @Test
    fun `an EMI instalment is typed as EMI`() {
        val txn = parse("Rs 4,999.00 debited from A/C XX8901 towards EMI for LOAN 88213.")
        assertEquals(TxnType.EMI, txn?.txnType)
    }

    @Test
    fun `trailing subscription filler is trimmed from the merchant`() {
        val txn = parse("Rs 199.00 debited from A/C XX8901 for SPOTIFY INDIA recurring subscription.")
        assertEquals("SPOTIFY INDIA", txn?.merchant)
    }

    @Test
    fun `the same payment from two differently worded sources shares a txnHash`() {
        // A push alert for the same payment: shorter, no account quoted.
        val fromSms = parse("Rs 420.00 debited from A/C XX8901 to SWIGGY. Bal Rs 45,210.")
        val fromAlert = parse("Rs 420.00 spent at SWIGGY.")

        assertNotNull(fromSms)
        assertNotNull(fromAlert)
        assertTrue("message hashes must differ", fromSms!!.hash != fromAlert!!.hash)
        assertEquals("transaction hashes must match for F1.6", fromSms.txnHash, fromAlert.txnHash)
    }

    @Test
    fun `a non-financial message is ignored entirely`() {
        val result = engine.parseMessage("AD-HDFCBK-S", "Your OTP is 483920. Do not share.", 1L, rules, BundledParserRules.entities)
        assertNull(result.parsedTransaction)
    }

    // ---- card due reminders ----------------------------------------------
    //
    // A reminder to pay a card bill quotes two rupee amounts and an account, which
    // was enough for the looser rules to book it — as spend in most wordings, and as
    // *income* in the common variant that explains when payments get credited. None
    // of these has settled, so none may produce a row of any kind.

    private fun ingest(body: String) =
        engine.parseMessage("AD-ICICIB-S", body, 1_700_000_000_000L, rules, BundledParserRules.entities)

    @Test
    fun `a card due reminder produces no transaction and no review entry`() {
        val result = ingest(
            "Pay Total Amount Due of Rs 2,783.00 or Minimum Amount Due of Rs 140.00 by " +
                "06-Aug-26 towards ICICI Bank Credit Card XX7009. Delay/Non-payment is " +
                "reported to Credit Bureaus. Ignore if paid."
        )
        assertNull("a bill that is owed is not a transaction", result.parsedTransaction)
        assertNull("there is nothing for the user to review", result.unparsedSms)
    }

    @Test
    fun `a due reminder explaining when payments are credited is not income`() {
        val result = ingest(
            "Total Amount Due of Rs 2,783.00 on ICICI Bank Credit Card XX7009 by 06-Aug-26. " +
                "Payments are credited within 2 working days."
        )
        assertNull(
            "a future-tense 'are credited' is a promise, not money arriving",
            result.parsedTransaction
        )
    }

    @Test
    fun `a due reminder quoting reversed charges is not a refund`() {
        val result = ingest(
            "Total Amount Due Rs 2,783.00 on ICICI Bank Credit Card XX7009 includes " +
                "reversed charges of Rs 500.00."
        )
        assertNull(result.parsedTransaction)
    }

    @Test
    fun `a statement alert is not a transaction`() {
        val result = ingest(
            "Your HDFC Bank Credit Card XX4521 statement is generated. Total Due " +
                "Rs 12,340.00, Min Due Rs 620.00, Due Date 15-Aug-26."
        )
        assertNull(result.parsedTransaction)
    }

    @Test
    fun `a payment confirmation restating the balance still parses`() {
        // T2.3 — the reminder guard must not swallow a settled payment just because
        // the bank helpfully quotes the amount that is now outstanding.
        val txn = ingest(
            "Payment of Rs 2,783.00 received towards ICICI Bank Credit Card XX7009 on " +
                "05-Aug-26. Total Amount Due is now Rs 0.00."
        ).parsedTransaction
        assertNotNull("a real payment must not be dropped as a reminder", txn)
        assertEquals(2783.0, txn!!.amount, 0.001)
    }

    // ---- card bill payments ----------------------------------------------

    @Test
    fun `the bank leg of a card bill payment is not a purchase`() {
        val txn = parse(
            "Rs 12,400.00 debited from Acct XX635 on 02-Aug-26 for ICICI CREDIT CARD " +
                "PAYMENT. Avl Bal Rs 9,200.00"
        )
        assertNotNull(txn)
        assertEquals(TxnType.CARD_PAYMENT, txn!!.txnType)
        assertEquals("Transfers", txn.category)
    }

    @Test
    fun `the card leg of a bill payment is not income`() {
        val txn = ingest(
            "Payment of Rs 2,783.00 received towards ICICI Bank Credit Card XX7009 on " +
                "05-Aug-26. Total Amount Due is now Rs 0.00."
        ).parsedTransaction
        assertNotNull(txn)
        assertEquals(TxnType.CARD_PAYMENT, txn!!.txnType)
        assertEquals("the merchant must not begin mid-'towards'", "ICICI Bank Credit", txn.merchant)
    }

    @Test
    fun `a purchase made on a credit card is still a purchase`() {
        // The card-payment test must not swallow the swipes it exists to protect.
        val txn = parse("Rs 1,899.00 spent on ICICI Bank Card XX4321 at ZOMATO on 03-Aug-26.")
        assertNotNull(txn)
        assertEquals(TxnType.PURCHASE, txn!!.txnType)
        assertEquals("ZOMATO", txn.merchant)
    }

    @Test
    fun `the word reminder is not an EMI`() {
        // "emi" is a substring of "reminder"; the type check has to be anchored.
        val txn = parse("Reminder: Rs 850.00 debited from A/C XX8901 to BLINKIT GROCERY.")
        assertNotNull(txn)
        assertTrue(
            "'Reminder' contains 'emi' but is not an instalment",
            txn!!.txnType != TxnType.EMI
        )
    }
}
