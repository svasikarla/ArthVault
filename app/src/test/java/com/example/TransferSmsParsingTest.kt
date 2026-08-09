package com.example

import com.example.data.local.DefaultSeedData
import com.example.data.local.entity.TxnType
import com.example.data.parser.SmsParserEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Regression cover for the ICICI transfer alert that was parsed into a completely
 * different transaction from the one it described.
 *
 *   "ICICI Bank Acct XX635 debited with Rs 45,425.00 on 09-Aug-26 & Acct XX066
 *    credited.IMPS:622115186795."
 *
 * booked as **₹635 credited**, from a merchant called "on 09-Aug-26 & Acct XX066
 * credited". Three independent defects stacked up:
 *
 *  1. the amount was taken from whichever capture group parsed as a number first,
 *     which for this pattern is the *account tail*;
 *  2. the direction came from a substring search for "credited" over the whole
 *     body, and a transfer alert names both legs;
 *  3. the merchant group had no anchoring preposition, so it swallowed the rest of
 *     the sentence.
 *
 * The first two are the dangerous ones: both produce a plausible-looking row with
 * no error anywhere, so the ledger is quietly wrong.
 */
class TransferSmsParsingTest {

    private val engine = SmsParserEngine()

    private fun parse(body: String, sender: String = "AD-ICICIB-S") =
        engine.parseMessage(
            sender, body, 1_754_700_000_000L,
            DefaultSeedData.merchantRules, DefaultSeedData.parserRules
        ).parsedTransaction

    private val iciciTransfer =
        "ICICI Bank Acct XX635 debited with Rs 45,425.00 on 09-Aug-26 & Acct XX066 " +
            "credited.IMPS:622115186795. Call 18002662 for dispute or SMS BLOCK 635 to 9215676766"

    @Test
    fun `the amount is the amount, not the account tail`() {
        val txn = parse(iciciTransfer)
        assertNotNull(txn)
        assertEquals(45_425.0, txn!!.amount, 0.001)
    }

    @Test
    fun `a second account credited later in the message does not flip the direction`() {
        assertEquals("DEBIT", parse(iciciTransfer)?.direction)
    }

    @Test
    fun `the debited account is the one recorded`() {
        assertEquals("635", parse(iciciTransfer)?.accountTail)
    }

    @Test
    fun `the destination account stands in for the missing merchant`() {
        assertEquals("Transfer to A/c 066", parse(iciciTransfer)?.merchant)
    }

    @Test
    fun `a two-leg transfer is typed and categorised as a transfer`() {
        val txn = parse(iciciTransfer)
        assertEquals(TxnType.TRANSFER, txn?.txnType)
        assertEquals("Transfers", txn?.category)
    }

    @Test
    fun `the credit leg of the same transfer reads the other way round`() {
        val txn = parse(
            "ICICI Bank Acct XX066 credited with Rs 45,425.00 on 09-Aug-26 & " +
                "Acct XX635 debited.IMPS:622115186795"
        )
        assertNotNull(txn)
        assertEquals(45_425.0, txn!!.amount, 0.001)
        assertEquals("CREDIT", txn.direction)
        assertEquals("066", txn.accountTail)
        assertEquals("Transfer from A/c 635", txn.merchant)
    }

    @Test
    fun `a transfer with no rail named is still a transfer`() {
        // No IMPS/NEFT/RTGS keyword to lean on — only the two opposing legs.
        val txn = parse("Your A/c XX1234 is debited with Rs 2,000.00 and A/c XX9999 credited on 01-Jan-26")
        assertEquals(2_000.0, txn?.amount!!, 0.001)
        assertEquals("DEBIT", txn.direction)
        assertEquals(TxnType.TRANSFER, txn.txnType)
        assertEquals("Transfers", txn.category)
    }

    @Test
    fun `the account-first layout reports the amount, not the leading account number`() {
        val txn = parse("A/C 4321 debited with INR 350.00 for SWIGGY")
        assertEquals(350.0, txn?.amount!!, 0.001)
        assertEquals("4321", txn.accountTail)
        assertEquals("SWIGGY", txn.merchant)
    }

    @Test
    fun `a value date between the amount and the payee does not hide the payee`() {
        val txn = parse("Dear Customer, Acct XX635 debited with Rs 500.00 on 09-Aug-26 for UPI/SWIGGY. Avl Bal Rs 12,000.00")
        assertEquals(500.0, txn?.amount!!, 0.001)
        assertEquals("the UPI rail is not the payee", "SWIGGY", txn.merchant)
        assertEquals(12_000.0, txn.balanceAfter!!, 0.001)
    }

    @Test
    fun `an account named mid-sentence is boilerplate, not the merchant`() {
        val txn = parse(
            "Amt Sent Rs.500.00 From HDFC Bank A/C x1234 To SWIGGY On 09-08 Ref 123456",
            sender = "AD-HDFCBK-S"
        )
        assertNotNull("the slash in A/C used to terminate every merchant capture", txn)
        assertEquals(500.0, txn!!.amount, 0.001)
        assertEquals("SWIGGY", txn.merchant)
        assertEquals("1234", txn.accountTail)
    }

    @Test
    fun `a message naming no payee at all admits it`() {
        val txn = parse("Acct XX635 debited with Rs 45,425.00 on 09-Aug-26")
        assertEquals(45_425.0, txn?.amount!!, 0.001)
        assertEquals(SmsParserEngine.UNKNOWN_MERCHANT, txn.merchant)
    }
}
