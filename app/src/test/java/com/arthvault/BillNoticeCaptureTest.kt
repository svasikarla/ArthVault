package com.arthvault

import com.arthvault.data.local.entity.BillKind
import com.arthvault.data.parser.BillDateParser
import com.arthvault.data.parser.SmsParserEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/**
 * Phase 9a — the due-reminder messages the parser used to drop are now kept.
 *
 * Every body here is copied verbatim from `sms_corpus.jsonl`, where each one is marked
 * `parsed:false`. That marking is still correct and is asserted alongside the capture:
 * the whole point of the change is that a bill which is *owed* stops being discarded
 * without ever becoming a transaction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BillNoticeCaptureTest {

    private val engine = SmsParserEngine()

    /** 5 Aug 2026, the date most of the corpus's card cycle sits around. */
    private val received = dateAt(2026, Calendar.AUGUST, 5)

    private fun parse(sender: String, body: String) =
        engine.parseMessage(sender, body, received, emptyList(), BundledParserRules.entities)

    private fun notice(sender: String, body: String) = parse(sender, body).billNotice

    private fun dateAt(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            set(year, month, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    // ---- the guard that must not regress ---------------------------------

    @Test
    fun `a due reminder still produces no transaction`() {
        val result = parse(
            "AD-ICICIB-S",
            "Pay Total Amount Due of Rs 2,783.00 or Minimum Amount Due of Rs 140.00 by " +
                "06-Aug-26 towards ICICI Bank Credit Card XX7009. Delay/Non-payment is " +
                "reported to Credit Bureaus. Ignore if paid."
        )

        // This is the defect the due-reminder guard exists for: the same message was
        // booked as ₹2,783 of spend under one wording and ₹2,783 of income under
        // another. Capturing it as an obligation must not reopen that.
        assertNull("a bill that is owed is not a transaction", result.parsedTransaction)
        assertNull("and it is not a review-queue entry either", result.unparsedSms)
        assertNotNull(result.billNotice)
    }

    @Test
    fun `a payment confirmation is still a transaction, not a notice`() {
        val result = parse(
            "AD-ICICIB-S",
            "Payment of Rs 2,783.00 received towards ICICI Bank Credit Card XX7009 on " +
                "05-Aug-26. Total Amount Due is now Rs 0.00."
        )

        // The settlement escape hatch runs first. A confirmation routinely restates the
        // balance it just cleared, and reading that as a fresh obligation would leave
        // every paid bill sitting on the Bills tab forever.
        assertNotNull(result.parsedTransaction)
        assertNull(result.billNotice)
    }

    // ---- amounts ---------------------------------------------------------

    @Test
    fun `total amount due wins over minimum amount due`() {
        val bill = notice(
            "AD-ICICIB-S",
            "Pay Total Amount Due of Rs 2,783.00 or Minimum Amount Due of Rs 140.00 by " +
                "06-Aug-26 towards ICICI Bank Credit Card XX7009. Ignore if paid."
        )!!

        // Reading the minimum as the bill understates this obligation twentyfold.
        assertEquals(2783.0, bill.amountDue!!, 0.001)
        assertEquals(140.0, bill.minAmountDue!!, 0.001)
    }

    @Test
    fun `total due is read when the words are in the terser order`() {
        val bill = notice(
            "AD-HDFCBK-S",
            "Your HDFC Bank Credit Card XX4521 statement is generated. Total Due Rs " +
                "12,340.00, Min Due Rs 620.00, Due Date 15-Aug-26."
        )!!

        assertEquals(12340.0, bill.amountDue!!, 0.001)
        assertEquals(620.0, bill.minAmountDue!!, 0.001)
    }

    @Test
    fun `an unlabelled sum is taken when the issuer labels nothing`() {
        val bill = notice(
            "AD-CRED-S",
            "CRED: your credit card bill of Rs 24,300.00 is due on 18-08-25."
        )!!

        assertEquals(24300.0, bill.amountDue!!, 0.001)
        assertNull(bill.minAmountDue)
    }

    @Test
    fun `a trailing figure in another clause does not displace the total`() {
        val bill = notice(
            "AD-ICICIB-S",
            "Total Amount Due Rs 2,783.00 on ICICI Bank Credit Card XX7009 includes " +
                "reversed charges of Rs 500.00."
        )!!

        assertEquals(2783.0, bill.amountDue!!, 0.001)
    }

    // ---- dates -----------------------------------------------------------

    @Test
    fun `reads an alphabetic due date`() {
        val bill = notice(
            "AD-AMEXIN-S",
            "Dear Customer, payment of Rs 2,783.00 towards American Express Card XX1005 " +
                "is due by 06-Aug-26."
        )!!

        assertEquals(dateAt(2026, Calendar.AUGUST, 6), bill.dueDate)
    }

    @Test
    fun `reads a numeric due date as day-month, not month-day`() {
        val bill = notice(
            "AD-CRED-S",
            "CRED: your credit card bill of Rs 24,300.00 is due on 18-08-25."
        )!!

        // The American reading would make this 8 June. There is no way to tell from the
        // digits, so the convention is fixed by region.
        assertEquals(dateAt(2025, Calendar.AUGUST, 18), bill.dueDate)
    }

    @Test
    fun `an explicit Due Date label beats a bare by`() {
        val bill = notice(
            "AD-HDFCBK-S",
            "Your HDFC Bank Credit Card XX4521 statement is generated. Total Due Rs " +
                "12,340.00, Min Due Rs 620.00, Due Date 15-Aug-26."
        )!!

        assertEquals(dateAt(2026, Calendar.AUGUST, 15), bill.dueDate)
    }

    @Test
    fun `a billing period without a day is not read as a deadline`() {
        val bill = notice(
            "AD-ICICIT-S",
            "Your ICICI Bank Credit Card XX4321 statement for Aug 2026 is ready. " +
                "Total due Rs 24,300.00 by 20-Aug-26."
        )!!

        // "Aug 2026" names the period billed. Reading it as a date would invent a
        // deadline of 1 August for a bill payable on the 20th.
        assertEquals(dateAt(2026, Calendar.AUGUST, 20), bill.dueDate)
        assertEquals("Aug 2026", bill.billingPeriodLabel)
    }

    @Test
    fun `a missing year is resolved forward from the message, not from today`() {
        val december = dateAt(2025, Calendar.DECEMBER, 20)
        val dates = BillDateParser.findAll("payable by 05-Jan", december)

        // A bill received on 20 December and payable on 5 January means next January.
        assertEquals(1, dates.size)
        assertEquals(dateAt(2026, Calendar.JANUARY, 5), dates.single().millis)
    }

    @Test
    fun `an impossible date is rejected rather than rolled forward`() {
        // Lenient Calendar arithmetic would turn this into 3 March and report a
        // confident deadline that the biller never wrote.
        assertTrue(BillDateParser.findAll("due date 31-Feb-26", received).isEmpty())
    }

    @Test
    fun `a notice with no stated deadline still captures the amount`() {
        val bill = notice(
            "AD-ONECRD-S",
            "Your OneCard XX7001 statement for Aug is ready. Total due Rs 18,900.00."
        )!!

        assertEquals(18900.0, bill.amountDue!!, 0.001)
        assertNull("no date was stated, so none is invented", bill.dueDate)
    }

    @Test
    fun `a statement notice naming neither sum nor date is still dropped`() {
        val result = parse(
            "AD-CANBNK-S",
            "Canara Bank: Your statement for Jul 2025 is ready. Login to view."
        )

        // A notification about a notification. Nothing here a user could act on.
        assertNull(result.billNotice)
        assertNull(result.parsedTransaction)
    }

    // ---- biller identity -------------------------------------------------

    @Test
    fun `the issuer is named without the preposition that precedes it`() {
        val bill = notice(
            "AD-ICICIB-S",
            "Pay Total Amount Due of Rs 2,783.00 by 06-Aug-26 towards ICICI Bank " +
                "Credit Card XX7009. Ignore if paid."
        )!!

        assertEquals("ICICI Bank Credit Card", bill.billerLabel)
        assertEquals("7009", bill.accountTail)
        assertEquals(BillKind.CARD, bill.kind)
    }

    @Test
    fun `an aggregator is named as itself rather than a guessed issuer`() {
        val bill = notice(
            "AD-CRED-S",
            "CRED: your credit card bill of Rs 24,300.00 is due on 18-08-25."
        )!!

        // CRED says a card bill is due without saying whose card. "Credit Card" as a
        // label would group an ICICI bill with an Amex one.
        assertEquals("CRED", bill.billerLabel)
        assertNull(bill.accountTail)
    }

    @Test
    fun `a tail embedded in a single-word product name is still found`() {
        val bill = notice(
            "AD-ONECRD-S",
            "Your OneCard XX7001 statement for Aug is ready. Total due Rs 18,900.00."
        )!!

        assertEquals("7001", bill.accountTail)
    }

    // ---- identity and de-duplication -------------------------------------

    @Test
    fun `the same reminder read twice yields the same notice hash`() {
        val body = "Total Amount Due of Rs 2,783.00 on ICICI Bank Credit Card XX7009 by " +
            "06-Aug-26. Payments are credited within 2 working days."

        // A full inbox rescan re-reads every message. Identical input must produce an
        // identical hash or the unique index cannot collapse it.
        assertEquals(
            notice("AD-ICICIB-S", body)!!.noticeHash,
            notice("AD-ICICIB-S", body)!!.noticeHash
        )
    }

    @Test
    fun `two reminders for one cycle share a cycle key but not a notice hash`() {
        val first = notice(
            "AD-ICICIB-S",
            "Pay Total Amount Due of Rs 2,783.00 or Minimum Amount Due of Rs 140.00 by " +
                "06-Aug-26 towards ICICI Bank Credit Card XX7009. Ignore if paid."
        )!!
        val second = notice(
            "AD-ICICIB-S",
            "Reminder: Rs 2,783.00 to be paid towards ICICI Bank Credit Card XX7009 by " +
                "06-Aug-26. Avoid late payment charges."
        )!!

        // Different wording, different rows — each is a true statement made at its own
        // time. The cycle key is what folds them into one obligation on screen.
        assertEquals(first.cycleKey, second.cycleKey)
        assertNotEquals(first.noticeHash, second.noticeHash)
    }

    @Test
    fun `a partial payment restating a smaller total stays the same cycle`() {
        val full = notice(
            "AD-ICICIB-S",
            "Total Amount Due of Rs 2,783.00 on ICICI Bank Credit Card XX7009 by 06-Aug-26."
        )!!
        val reduced = notice(
            "AD-ICICIB-S",
            "Total Amount Due of Rs 1,283.00 on ICICI Bank Credit Card XX7009 by 06-Aug-26."
        )!!

        // Keyed on the due day rather than the amount precisely so this holds: a
        // reminder re-sent after a part payment is the same bill in a new state.
        assertEquals(full.cycleKey, reduced.cycleKey)
    }

    // ---- the marketing-ordering fix --------------------------------------

    @Test
    fun `a due notice carrying a payment link is captured, not discarded as an advert`() {
        val bill = notice(
            "AD-BESCOM-S",
            "Your electricity bill of Rs 1,240.00 is due on 15-Aug-26. Click here to pay."
        )

        // Utility billers routinely append a payment link to a genuine notice. Tested
        // before the due-reminder check, "click to" threw the whole bill away.
        assertNotNull(bill)
        assertEquals(1240.0, bill!!.amountDue!!, 0.001)
        assertEquals(dateAt(2026, Calendar.AUGUST, 15), bill.dueDate)
        assertEquals(BillKind.UTILITY, bill.kind)
    }

    @Test
    fun `an advert quoting no sum owed is still discarded`() {
        val result = parse(
            "AD-PHONPE-S",
            "Get up to Rs 500 cashback on your next PhonePe recharge. Offer valid till 31-08-25."
        )

        // Reordering the promo check must not turn marketing into a bill. This one
        // names no amount due and no deadline, so it never satisfies the reminder
        // pattern in the first place.
        assertNull(result.billNotice)
        assertNull(result.parsedTransaction)
    }

    @Test
    fun `an OTP quoting an amount is discarded ahead of everything else`() {
        val result = parse(
            "AD-HDFCBK-S",
            "OTP for txn of Rs.5000.00 at AMAZON is 483920. Amount due 06-Aug-26."
        )

        // OTP wording is exclusive to credentials, so its verdict stays final even
        // when the body also trips the reminder pattern.
        assertNull(result.billNotice)
        assertNull(result.parsedTransaction)
    }
}
