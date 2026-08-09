package com.example

import com.example.data.analytics.FinanceAnalyticsEngine
import com.example.data.local.entity.STATUS_FAILED
import com.example.data.local.entity.STATUS_POSTED
import com.example.data.local.entity.TransactionEntity
import com.example.data.local.entity.TxnType
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
        val txns = listOf(
            txn(199.0, "SPOTIFY", daysAgo = 2, id = 1),
            txn(149.0, "SPOTIFY", daysAgo = 32, id = 2),
            txn(149.0, "SPOTIFY", daysAgo = 62, id = 3)
        )
        val recurring = engine.detectRecurringAndPriceHikes(txns).single()

        assertTrue(recurring.isPriceHike)
        assertEquals(33.55, recurring.priceHikePercentage, 0.1)
        assertEquals(30, recurring.frequencyDays)
    }

    @Test
    fun `a stable subscription is recurring but not a hike`() {
        val txns = listOf(
            txn(649.0, "NETFLIX", daysAgo = 6, id = 1),
            txn(649.0, "NETFLIX", daysAgo = 36, id = 2)
        )
        assertEquals(false, engine.detectRecurringAndPriceHikes(txns).single().isPriceHike)
    }
}
