package com.arthvault

import com.arthvault.data.analytics.BillAnalyticsEngine
import com.arthvault.data.analytics.BillSettlement
import com.arthvault.data.analytics.BillTrend
import com.arthvault.data.local.entity.BillKind
import com.arthvault.data.local.entity.BillNoticeEntity
import com.arthvault.data.local.entity.STATUS_FAILED
import com.arthvault.data.local.entity.STATUS_POSTED
import com.arthvault.data.local.entity.TransactionEntity
import com.arthvault.data.local.entity.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 9b — what the app is willing to say about a bill.
 *
 * The tests that matter most here are the negative ones. A false "paid" is discovered by
 * the user in the form of a late fee, and a false "overdue" is an accusation about
 * something they may well have done. Both are worse than saying nothing.
 */
class BillAnalyticsEngineTest {

    private val engine = BillAnalyticsEngine()
    private val day = 86_400_000L
    private val now = 1_754_784_000_000L // 10 Aug 2025, fixed so nothing drifts.

    private fun notice(
        billerKey: String = "ICICIBANKCREDITCARD",
        label: String = "ICICI Bank Credit Card",
        kind: String = BillKind.CARD,
        tail: String? = "7009",
        amount: Double? = 2783.0,
        minAmount: Double? = null,
        dueInDays: Long? = 5,
        issuedDaysAgo: Long = 10,
        cycle: String = "cycle-1",
        id: Long = 0
    ) = BillNoticeEntity(
        id = id,
        billerKey = billerKey,
        billerLabel = label,
        kind = kind,
        accountTail = tail,
        amountDue = amount,
        minAmountDue = minAmount,
        dueDate = dueInDays?.let { now + it * day },
        billingPeriodLabel = null,
        issuedAt = now - issuedDaysAgo * day,
        sender = "AD-ICICIB-S",
        rawMessage = "raw",
        noticeHash = "hash-$id",
        cycleKey = cycle
    )

    private fun txn(
        amount: Double,
        merchant: String = "ICICI Bank Credit",
        tail: String? = "7009",
        daysAgo: Long = 2,
        txnType: String = TxnType.CARD_PAYMENT,
        direction: String = "DEBIT",
        status: String = STATUS_POSTED,
        id: Long = 1
    ) = TransactionEntity(
        id = id,
        amount = amount,
        direction = direction,
        timestamp = now - daysAgo * day,
        sender = "AD-ICICIB-S",
        merchant = merchant,
        accountTail = tail,
        channel = "NetBanking",
        category = "Transfers",
        rawMessage = "",
        status = status,
        txnType = txnType,
        hash = "h$id"
    )

    // ---- settlement ------------------------------------------------------

    @Test
    fun `a card payment of the right size settles the bill`() {
        val result = engine.reconcile(listOf(notice()), listOf(txn(2783.0)))

        assertEquals(BillSettlement.PAID, result.single().settlement)
        assertEquals(listOf(1L), result.single().matchedTransactionIds)
    }

    @Test
    fun `a card payment settles the bill even when the tail is the bank's, not the card's`() {
        // The bank leg quotes the account the money left; the card leg quotes the card.
        // Only one of the two can ever match the notice's tail, which is why the
        // transaction type carries the link for card bills.
        val result = engine.reconcile(
            listOf(notice()),
            listOf(txn(2783.0, merchant = "ICICI CREDIT CARD PAYMENT", tail = "635"))
        )

        assertEquals(BillSettlement.PAID, result.single().settlement)
    }

    @Test
    fun `a payment of the minimum due is likely paid, not paid`() {
        val result = engine.reconcile(
            listOf(notice(amount = 2783.0, minAmount = 140.0)),
            listOf(txn(140.0))
        )

        // Something was paid towards this card, but not the sum that clears it. Calling
        // that PAID hides a bill that will attract interest.
        assertEquals(BillSettlement.LIKELY_PAID, result.single().settlement)
    }

    @Test
    fun `an unrelated purchase of the same amount does not settle anything`() {
        val result = engine.reconcile(
            listOf(notice()),
            listOf(txn(2783.0, merchant = "CROMA", tail = "4321", txnType = TxnType.PURCHASE))
        )

        // Matching on amount alone would mark this bill settled by a coincidental
        // electronics purchase. Every candidate has to carry an identity link.
        assertEquals(BillSettlement.NO_PAYMENT_SEEN, result.single().settlement)
        assertTrue(result.single().matchedTransactionIds.isEmpty())
    }

    @Test
    fun `a utility bill is settled by a payment naming the biller`() {
        val result = engine.reconcile(
            listOf(
                notice(
                    billerKey = "BESCOM",
                    label = "BESCOM",
                    kind = BillKind.UTILITY,
                    tail = null,
                    amount = 1240.0
                )
            ),
            listOf(
                txn(1240.0, merchant = "BESCOM BENGALURU", tail = null, txnType = TxnType.PURCHASE)
            )
        )

        assertEquals(BillSettlement.PAID, result.single().settlement)
    }

    @Test
    fun `a payment outside the window does not settle the bill`() {
        // 90 days before the notice was even issued.
        val result = engine.reconcile(listOf(notice()), listOf(txn(2783.0, daysAgo = 100)))

        assertEquals(BillSettlement.NO_PAYMENT_SEEN, result.single().settlement)
    }

    @Test
    fun `a late payment inside the grace window still counts`() {
        val overdue = notice(dueInDays = -3)
        val result = engine.reconcile(listOf(overdue), listOf(txn(2783.0, daysAgo = 1)))

        assertEquals(BillSettlement.PAID, result.single().settlement)
        assertFalse("a paid bill is not past due", result.single().isPastDue(now))
    }

    @Test
    fun `a declined payment does not settle a bill`() {
        val result = engine.reconcile(
            listOf(notice()),
            listOf(txn(2783.0, status = STATUS_FAILED))
        )

        assertEquals(BillSettlement.NO_PAYMENT_SEEN, result.single().settlement)
    }

    @Test
    fun `an unpaid bill past its date is past due, and an undated one never is`() {
        val past = engine.reconcile(listOf(notice(dueInDays = -2)), emptyList()).single()
        val undated = engine.reconcile(
            listOf(notice(dueInDays = null, cycle = "cycle-2")),
            emptyList()
        ).single()

        assertTrue(past.isPastDue(now))
        assertNull(undated.daysUntilDue(now))
        assertFalse("no stated deadline means no missed deadline", undated.isPastDue(now))
    }

    @Test
    fun `a bill quoting no amount can only ever be likely paid`() {
        val result = engine.reconcile(
            listOf(notice(amount = null)),
            listOf(txn(999.0))
        )

        // Something settled against this card, but with no stated sum there is nothing
        // to check it against.
        assertEquals(BillSettlement.LIKELY_PAID, result.single().settlement)
    }

    // ---- folding ---------------------------------------------------------

    @Test
    fun `four reminders about one bill fold into one obligation`() {
        val cycle = listOf(
            notice(id = 1, issuedDaysAgo = 12),
            notice(id = 2, issuedDaysAgo = 8),
            notice(id = 3, issuedDaysAgo = 4),
            notice(id = 4, issuedDaysAgo = 1, amount = 1283.0)
        )

        val result = engine.reconcile(cycle, emptyList())

        assertEquals(1, result.size)
        assertEquals(4, result.single().noticeCount)
        // The newest notice is the biller's latest word: a part payment landed and the
        // outstanding total came down.
        assertEquals(1283.0, result.single().amountDue!!, 0.001)
        assertEquals(listOf(1L, 2L, 3L, 4L), result.single().noticeIds)
    }

    @Test
    fun `obligations are ordered by deadline, with undated ones last`() {
        val result = engine.reconcile(
            listOf(
                notice(dueInDays = null, cycle = "c-none", id = 1),
                notice(dueInDays = 20, cycle = "c-late", id = 2),
                notice(dueInDays = 3, cycle = "c-soon", id = 3)
            ),
            emptyList()
        )

        assertEquals(listOf("c-soon", "c-late", "c-none"), result.map { it.cycleKey })
    }

    // ---- trends ----------------------------------------------------------

    @Test
    fun `a trend needs three cycles before it claims anything`() {
        val twoCycles = engine.reconcile(
            listOf(
                notice(cycle = "c1", dueInDays = -40, amount = 799.0, id = 1),
                notice(cycle = "c2", dueInDays = -10, amount = 949.0, id = 2)
            ),
            emptyList()
        )

        val trend = engine.trends(twoCycles).single()

        // The movement is computed and available; what the screen must not do is call
        // one comparison a trend. Metered bills vary by more than this every month.
        assertEquals(150.0, trend.delta!!, 0.001)
        assertFalse(trend.isEstablished)
        assertEquals(BillTrend.MIN_CYCLES_FOR_TREND, 3)
    }

    @Test
    fun `a sustained rise is reported against the previous cycle`() {
        val cycles = engine.reconcile(
            listOf(
                notice(billerKey = "ACTFIBRE", label = "ACT Fibre", kind = BillKind.TELECOM,
                    tail = null, cycle = "c1", dueInDays = -70, amount = 799.0, id = 1),
                notice(billerKey = "ACTFIBRE", label = "ACT Fibre", kind = BillKind.TELECOM,
                    tail = null, cycle = "c2", dueInDays = -40, amount = 949.0, id = 2),
                notice(billerKey = "ACTFIBRE", label = "ACT Fibre", kind = BillKind.TELECOM,
                    tail = null, cycle = "c3", dueInDays = -10, amount = 949.0, id = 3)
            ),
            emptyList()
        )

        val trend = engine.trends(cycles).single()

        assertTrue(trend.isEstablished)
        assertEquals(949.0, trend.latestAmount, 0.001)
        assertEquals(949.0, trend.previousAmount!!, 0.001)
        assertEquals(0.0, trend.delta!!, 0.001)
        assertEquals(3, trend.cycles.size)
        // Oldest first, so a chart drawn straight from this reads left to right.
        assertEquals(799.0, trend.cycles.first().amount, 0.001)
    }

    @Test
    fun `a biller's first bill reports no percentage rather than an infinite one`() {
        val first = engine.reconcile(listOf(notice(cycle = "c1", amount = 2783.0)), emptyList())
        val trend = engine.trends(first).single()

        assertNull(trend.previousAmount)
        assertNull("first bill seen is not up 100%", trend.percentageChange)
        assertNull(trend.delta)
    }

    @Test
    fun `four reminders about one bill do not read as a fourfold rise`() {
        val cycle = engine.reconcile(
            listOf(
                notice(id = 1, issuedDaysAgo = 12),
                notice(id = 2, issuedDaysAgo = 8),
                notice(id = 3, issuedDaysAgo = 4)
            ),
            emptyList()
        )

        val trend = engine.trends(cycle).single()

        // One obligation, so one cycle — not three. Trending on notices rather than
        // obligations would report a rise every time a biller sent a chaser.
        assertEquals(1, trend.cycles.size)
        assertFalse(trend.isEstablished)
    }

    // ---- monthly totals --------------------------------------------------

    @Test
    fun `a bill is billed to the month its money has to leave`() {
        // Issued 28 July, payable 15 August. The outgoing belongs to August.
        val julyIssued = notice(
            issuedDaysAgo = 13, // 28 Jul
            dueInDays = 5,      // 15 Aug
            amount = 12340.0
        )

        val months = engine.monthlyTotals(engine.reconcile(listOf(julyIssued), emptyList()))

        assertEquals(1, months.size)
        assertEquals(java.util.Calendar.AUGUST, months.single().month)
        assertEquals(12340.0, months.single().total, 0.001)
    }

    @Test
    fun `monthly totals sum every biller and count them`() {
        val obligations = engine.reconcile(
            listOf(
                notice(cycle = "card", amount = 12340.0, dueInDays = 5, id = 1),
                notice(billerKey = "BESCOM", label = "BESCOM", kind = BillKind.UTILITY,
                    tail = null, cycle = "power", amount = 1240.0, dueInDays = 3, id = 2)
            ),
            emptyList()
        )

        val month = engine.monthlyTotals(obligations).single()

        assertEquals(13580.0, month.total, 0.001)
        assertEquals(2, month.billerCount)
    }
}
