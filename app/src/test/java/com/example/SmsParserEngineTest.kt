package com.example

import com.example.data.local.entity.MerchantRuleEntity
import com.example.data.local.entity.STATUS_FAILED
import com.example.data.local.entity.STATUS_POSTED
import com.example.data.local.entity.TxnType
import com.example.data.parser.SmsParserEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the parser defects found by running the app against its own
 * sample messages: merchant strings polluted with account prefixes, merchants that
 * collapsed to the literal "Merchant", account tails never extracted, and declined
 * transactions stored as real spend.
 */
class SmsParserEngineTest {

    private val engine = SmsParserEngine()
    private val rules = listOf(
        MerchantRuleEntity("SWIGGY", "Food & Dining"),
        MerchantRuleEntity("AMAZON", "Shopping"),
        MerchantRuleEntity("SHELL", "Transport & Fuel"),
        MerchantRuleEntity("STARBUCKS", "Food & Dining")
    )

    private fun parse(body: String) =
        engine.parseMessage("AD-HDFCBK-S", body, 1_700_000_000_000L, rules).parsedTransaction

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
        val result = engine.parseMessage("AD-HDFCBK-S", "Your OTP is 483920. Do not share.", 1L, rules)
        assertNull(result.parsedTransaction)
    }
}
