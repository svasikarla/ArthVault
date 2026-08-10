package com.arthvault

import com.arthvault.data.analytics.FinanceAnalyticsEngine
import com.arthvault.data.local.entity.STATUS_FAILED
import com.arthvault.data.local.entity.STATUS_POSTED
import com.arthvault.data.local.entity.TransactionEntity
import com.arthvault.data.local.entity.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceAnalyticsEngineTest {

    private val engine = FinanceAnalyticsEngine()
    private val day = 86_400_000L
    private val now = System.currentTimeMillis()

    private fun txn(
        amount: Double,
        merchant: String,
        category: String = "Other / Misc",
        direction: String = "DEBIT",
        daysAgo: Long = 0,
        status: String = STATUS_POSTED,
        txnType: String = TxnType.PURCHASE,
        id: Long = 0
    ) = TransactionEntity(
        id = id,
        amount = amount,
        direction = direction,
        timestamp = now - daysAgo * day,
        sender = "AD-HDFCBK-S",
        merchant = merchant,
        accountTail = "8901",
        channel = "UPI",
        category = category,
        rawMessage = "",
        status = status,
        txnType = txnType,
        hash = "h$id$merchant$amount$daysAgo"
    )

    @Test
    fun `declined transactions are excluded from the category breakdown`() {
        val txns = listOf(
            txn(1000.0, "SWIGGY", "Food & Dining", id = 1),
            txn(9000.0, "MYNTRA", "Shopping", status = STATUS_FAILED, id = 2)
        )
        val slices = engine.computeCategoryBreakdown(txns, now - 30 * day, now + day)

        assertEquals(1, slices.size)
        assertEquals("Food & Dining", slices.first().category)
        assertEquals(1000.0, slices.first().total, 0.001)
    }

    @Test
    fun `category fractions are real and sum to one`() {
        val txns = listOf(
            txn(750.0, "SWIGGY", "Food & Dining", id = 1),
            txn(250.0, "SHELL", "Transport & Fuel", id = 2)
        )
        val slices = engine.computeCategoryBreakdown(txns, now - 30 * day, now + day)

        assertEquals(0.75f, slices.first { it.category == "Food & Dining" }.fraction, 0.001f)
        assertEquals(1.0f, slices.sumOf { it.fraction.toDouble() }.toFloat(), 0.001f)
    }

    @Test
    fun `breakdown carries the source transaction ids for tap-through`() {
        val txns = listOf(
            txn(100.0, "SWIGGY", "Food & Dining", id = 7),
            txn(200.0, "ZOMATO", "Food & Dining", id = 8)
        )
        val slice = engine.computeCategoryBreakdown(txns, now - 30 * day, now + day).first()
        assertEquals(listOf(7L, 8L), slice.transactionIds.sorted())
    }

    @Test
    fun `duplicate charges within a day are flagged, and a declined one is not`() {
        val txns = listOf(
            txn(850.0, "BLINKIT", daysAgo = 5, id = 1),
            txn(850.0, "BLINKIT", daysAgo = 5, id = 2),
            txn(850.0, "BLINKIT", daysAgo = 5, status = STATUS_FAILED, id = 3)
        )
        assertEquals(1, engine.detectDuplicates(txns).size)
    }

    @Test
    fun `a refund reduces spending rather than counting as income`() {
        val withoutRefund = engine.computeMonthEndForecast(listOf(txn(5000.0, "AMAZON", id = 1)))
        val withRefund = engine.computeMonthEndForecast(
            listOf(
                txn(5000.0, "AMAZON", id = 1),
                txn(1000.0, "AMAZON", direction = "CREDIT", txnType = TxnType.REFUND, id = 2)
            )
        )

        assertEquals(0.0, withRefund.totalIncomeSoFar, 0.001)
        assertTrue(
            "refund should lower net spend",
            withRefund.totalSpentSoFar < withoutRefund.totalSpentSoFar
        )
    }

    @Test
    fun `salary still counts as income`() {
        val forecast = engine.computeMonthEndForecast(
            listOf(txn(50000.0, "NEFT Salary", direction = "CREDIT", txnType = TxnType.INCOME, id = 1))
        )
        assertEquals(50000.0, forecast.totalIncomeSoFar, 0.001)
    }

    @Test
    fun `a monthly subscription that rises in price is reported as a hike`() {
        // Two charges at the new price: F3.2 is about sustained increases, so a hike
        // is only reported once it has actually repeated.
        val txns = listOf(
            txn(199.0, "SPOTIFY", daysAgo = 2, id = 1),
            txn(199.0, "SPOTIFY", daysAgo = 32, id = 2),
            txn(149.0, "SPOTIFY", daysAgo = 62, id = 3),
            txn(149.0, "SPOTIFY", daysAgo = 92, id = 4)
        )
        val recurring = engine.detectRecurringAndPriceHikes(txns).single()

        assertTrue(recurring.isPriceHike)
        assertEquals(33.55, recurring.priceHikePercentage, 0.1)
        assertEquals(30, recurring.frequencyDays)
        assertEquals("the level, not the last charge", 199.0, recurring.currentAmount, 0.001)
        assertEquals(149.0, recurring.previousAmount!!, 0.001)
    }

    @Test
    fun `a single dear month is not yet a price hike`() {
        // The same series one month earlier: the new price has been seen once. A
        // one-off is an anomaly (F3.3), not a silent increase.
        val txns = listOf(
            txn(199.0, "SPOTIFY", daysAgo = 2, id = 1),
            txn(149.0, "SPOTIFY", daysAgo = 32, id = 2),
            txn(149.0, "SPOTIFY", daysAgo = 62, id = 3)
        )
        val recurring = engine.detectRecurringAndPriceHikes(txns).single()

        assertEquals(false, recurring.isPriceHike)
        assertEquals(0.0, recurring.priceHikePercentage, 0.001)
    }

    @Test
    fun `a stable subscription is recurring but not a hike`() {
        val txns = listOf(
            txn(649.0, "NETFLIX", daysAgo = 6, id = 1),
            txn(649.0, "NETFLIX", daysAgo = 36, id = 2),
            txn(649.0, "NETFLIX", daysAgo = 66, id = 3)
        )
        val recurring = engine.detectRecurringAndPriceHikes(txns).single()

        assertEquals(false, recurring.isPriceHike)
        assertEquals(649.0, recurring.currentAmount, 0.001)
        assertEquals("no earlier level to compare against", null, recurring.previousAmount)
    }

    @Test
    fun `two charges are not enough to establish a rhythm`() {
        // One gap cannot disagree with itself. This is the case the old detector
        // accepted, and it is why anything billed twice looked like a subscription.
        val txns = listOf(
            txn(649.0, "NETFLIX", daysAgo = 6, id = 1),
            txn(649.0, "NETFLIX", daysAgo = 36, id = 2)
        )
        assertTrue(engine.detectRecurringAndPriceHikes(txns).isEmpty())
    }

    @Test
    fun `three takeaway orders do not make a subscription`() {
        // The exact false positive the old `|| size >= 3` escape hatch produced —
        // and the expensive one, because a bogus recurring charge is then added to
        // the month-end forecast as a committed outflow.
        val txns = listOf(
            txn(420.0, "SWIGGY", daysAgo = 1, id = 1),
            txn(1150.0, "SWIGGY", daysAgo = 3, id = 2),
            txn(680.0, "SWIGGY", daysAgo = 11, id = 3),
            txn(230.0, "SWIGGY", daysAgo = 12, id = 4)
        )
        assertTrue(engine.detectRecurringAndPriceHikes(txns).isEmpty())
    }

    @Test
    fun `a regular schedule with erratic amounts is not recurring`() {
        // Perfectly monthly, but the amounts wander. Periodicity alone is not
        // enough — a monthly grocery run is not a subscription.
        val txns = listOf(
            txn(2400.0, "BIGBASKET", daysAgo = 2, id = 1),
            txn(600.0, "BIGBASKET", daysAgo = 32, id = 2),
            txn(3900.0, "BIGBASKET", daysAgo = 62, id = 3),
            txn(1100.0, "BIGBASKET", daysAgo = 92, id = 4)
        )
        assertTrue(engine.detectRecurringAndPriceHikes(txns).isEmpty())
    }

    @Test
    fun `a recurring item carries its source transactions for tap-through`() {
        val txns = listOf(
            txn(649.0, "NETFLIX", daysAgo = 6, id = 11),
            txn(649.0, "NETFLIX", daysAgo = 36, id = 12),
            txn(649.0, "NETFLIX", daysAgo = 66, id = 13)
        )
        val recurring = engine.detectRecurringAndPriceHikes(txns).single()

        assertEquals(3, recurring.chargeCount)
        assertEquals(listOf(11L, 12L, 13L), recurring.transactionIds.sorted())
    }

    @Test
    fun `a quarterly subscription is recurring at its own cadence`() {
        val txns = listOf(
            txn(1499.0, "ADOBE", daysAgo = 5, id = 1),
            txn(1499.0, "ADOBE", daysAgo = 95, id = 2),
            txn(1499.0, "ADOBE", daysAgo = 185, id = 3)
        )
        assertEquals(90, engine.detectRecurringAndPriceHikes(txns).single().frequencyDays)
    }

    // --- F3.6 category trends ---

    @Test
    fun `category comparison reports totals, delta and percentage change`() {
        val previous = now - 60 * day..now - 31 * day
        val current = now - 30 * day..now

        val txns = listOf(
            txn(1000.0, "SWIGGY", "Food & Dining", daysAgo = 45, id = 1),
            txn(1500.0, "ZOMATO", "Food & Dining", daysAgo = 10, id = 2)
        )
        val trend = engine.compareCategories(txns, previous, current)
            .single { it.category == "Food & Dining" }

        assertEquals(1000.0, trend.previousTotal, 0.001)
        assertEquals(1500.0, trend.currentTotal, 0.001)
        assertEquals(500.0, trend.delta, 0.001)
        assertEquals(50.0, trend.percentageChange!!, 0.001)
        assertEquals(listOf(1L, 2L), trend.transactionIds.sorted())
    }

    @Test
    fun `spending in a category that is new has no percentage change`() {
        val previous = now - 60 * day..now - 31 * day
        val current = now - 30 * day..now

        val txns = listOf(txn(800.0, "CULT FIT", "Health & Medical", daysAgo = 5, id = 1))
        val trend = engine.compareCategories(txns, previous, current).single()

        // Dividing by a zero baseline yields infinity, and rendering that as a
        // number puts a plausible-looking nonsense figure on screen.
        assertEquals(null, trend.percentageChange)
        assertEquals(800.0, trend.delta, 0.001)
    }

    @Test
    fun `a category that stopped is still reported`() {
        val previous = now - 60 * day..now - 31 * day
        val current = now - 30 * day..now

        val txns = listOf(txn(1200.0, "GYM", "Health & Medical", daysAgo = 45, id = 1))
        val trend = engine.compareCategories(txns, previous, current).single()

        assertEquals(0.0, trend.currentTotal, 0.001)
        assertEquals(-1200.0, trend.delta, 0.001)
        assertEquals(-100.0, trend.percentageChange!!, 0.001)
    }
}
