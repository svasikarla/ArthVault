package com.arthvault

import com.arthvault.data.analytics.FinanceAnalyticsEngine
import com.arthvault.data.analytics.cumulativeSpend
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

    // --- own-account transfers (v5) --------------------------------------

    /** The outgoing leg of "move ₹20,000 from savings (8901) to current (0662)". */
    private fun transferOut(id: Long, tail: String = "0662", daysAgo: Long = 1) = txn(
        amount = 20000.0,
        merchant = "Transfer to A/c $tail",
        category = "Transfers",
        txnType = TxnType.TRANSFER,
        daysAgo = daysAgo,
        id = id
    )

    /** The matching arrival. */
    private fun transferIn(id: Long, tail: String = "8901", daysAgo: Long = 1) = txn(
        amount = 20000.0,
        merchant = "Transfer from A/c $tail",
        category = "Transfers",
        direction = "CREDIT",
        txnType = TxnType.TRANSFER,
        daysAgo = daysAgo,
        id = id
    )

    @Test
    fun `an unmarked transfer is still spending`() {
        // Upgrading to v5 must not restate anybody's history. Until the user says an
        // account is theirs, the app behaves exactly as it did before.
        val slices = engine.computeCategoryBreakdown(
            listOf(transferOut(id = 1)), now - 30 * day, now + day
        )
        assertEquals(20000.0, slices.single().total, 0.001)
    }

    @Test
    fun `a transfer to an account the user marked as their own is not spending`() {
        val mine = FinanceAnalyticsEngine(setOf("0662"))
        val slices = mine.computeCategoryBreakdown(
            listOf(transferOut(id = 1), txn(500.0, "SWIGGY", "Food & Dining", id = 2)),
            now - 30 * day, now + day
        )

        assertEquals(1, slices.size)
        assertEquals("Food & Dining", slices.single().category)
    }

    @Test
    fun `a transfer to somebody else's account is spending`() {
        // Paying a person by account number is a real outflow. Only accounts the user
        // has actually marked are excluded — this is the case a blanket
        // "ignore all TRANSFER" rule would get wrong.
        val mine = FinanceAnalyticsEngine(setOf("0662"))
        val slices = mine.computeCategoryBreakdown(
            listOf(transferOut(id = 1, tail = "9999")), now - 30 * day, now + day
        )
        assertEquals(20000.0, slices.single().total, 0.001)
    }

    @Test
    fun `a purchase is never treated as an internal transfer`() {
        val mine = FinanceAnalyticsEngine(setOf("0662"))
        // Same merchant text, but the parser typed it a purchase. Type is what decides.
        val purchase = txn(
            300.0, "Transfer to A/c 0662", "Shopping", txnType = TxnType.PURCHASE, id = 1
        )
        assertTrue(!mine.isInternalTransfer(purchase))
    }

    @Test
    fun `an internal transfer inflates neither spending nor income`() {
        val mine = FinanceAnalyticsEngine(setOf("0662", "8901"))
        val txns = listOf(
            transferOut(id = 1),
            transferIn(id = 2),
            txn(500.0, "SWIGGY", "Food & Dining", id = 3),
            txn(60000.0, "ACME PAYROLL", "Income", direction = "CREDIT",
                txnType = TxnType.INCOME, id = 4)
        )

        val forecast = mine.computeMonthEndForecast(txns)

        // Before this, net cash flow was the only figure that came out right — the
        // ₹20,000 was added to both sides and cancelled — while the two numbers
        // either side of it were each overstated by ₹20,000.
        assertEquals(500.0, forecast.totalSpentSoFar, 0.001)
        assertEquals(60000.0, forecast.totalIncomeSoFar, 0.001)
        assertEquals(59500.0, forecast.netCashFlowSoFar, 0.001)
    }

    @Test
    fun `what was excluded is reported rather than silently netted away`() {
        val mine = FinanceAnalyticsEngine(setOf("0662", "8901"))
        val txns = listOf(
            transferOut(id = 1),
            transferIn(id = 2),
            txn(500.0, "SWIGGY", "Food & Dining", id = 3)
        )

        val summary = mine.summariseInternalTransfers(txns, now - 30 * day, now + day)

        assertEquals(2, summary.count)
        assertEquals(20000.0, summary.outflowTotal, 0.001)
        assertEquals(20000.0, summary.inflowTotal, 0.001)
        // F4.4 — the user can open the rows and check the exclusion was right.
        assertEquals(listOf(1L, 2L), summary.transactionIds)
    }

    // --- period summary, day buckets and the cash position ---------------

    @Test
    fun `a period summary reports income, spending and net for its window`() {
        val txns = listOf(
            txn(60000.0, "ACME PAYROLL", "Income", direction = "CREDIT",
                txnType = TxnType.INCOME, daysAgo = 5, id = 1),
            txn(2000.0, "SWIGGY", "Food & Dining", daysAgo = 4, id = 2),
            txn(500.0, "AMAZON", "Shopping", direction = "CREDIT",
                txnType = TxnType.REFUND, daysAgo = 3, id = 3),
            // Outside the window: must not appear in any of the three figures.
            txn(9999.0, "MYNTRA", "Shopping", daysAgo = 40, id = 4)
        )

        val summary = engine.computePeriodSummary(txns, now - 10 * day..now)

        assertEquals(60000.0, summary.income, 0.001)
        assertEquals("the refund is netted off spending", 1500.0, summary.spent, 0.001)
        assertEquals(58500.0, summary.net, 0.001)
        assertEquals(500.0, summary.refunds, 0.001)
        assertEquals(listOf(1L), summary.incomeTransactionIds)
        assertEquals(listOf(2L), summary.spendTransactionIds)
    }

    @Test
    fun `spending cannot be reported as a fraction of income that does not exist`() {
        // The card this replaces drew a hardcoded half-full bar when income was zero,
        // which looked like a real measurement of something.
        val summary = engine.computePeriodSummary(
            listOf(txn(1000.0, "SWIGGY", "Food & Dining", id = 1)),
            now - 10 * day..now
        )
        assertEquals(null, summary.spentFractionOfIncome)
    }

    @Test
    fun `daily totals zero-fill the days with no spending`() {
        val txns = listOf(
            txn(300.0, "SWIGGY", daysAgo = 2, id = 1),
            txn(200.0, "ZOMATO", daysAgo = 2, id = 2)
        )
        val buckets = engine.computeDailyTotals(txns, now - 4 * day..now)

        assertEquals("five calendar days, none skipped", 5, buckets.size)
        assertEquals(listOf(0, 1, 2, 3, 4), buckets.map { it.dayIndex })
        assertEquals(500.0, buckets.single { it.spent > 0 }.spent, 0.001)
        assertEquals(4, buckets.count { it.spent == 0.0 })
    }

    @Test
    fun `cumulative spend never decreases and ends at the window total`() {
        val txns = listOf(
            txn(300.0, "SWIGGY", daysAgo = 3, id = 1),
            txn(700.0, "ZOMATO", daysAgo = 1, id = 2)
        )
        val running = cumulativeSpend(engine.computeDailyTotals(txns, now - 4 * day..now))

        assertEquals(1000.0, running.last(), 0.001)
        assertTrue("a running total cannot fall", running.zipWithNext().all { it.first <= it.second })
    }

    @Test
    fun `recurring income is detected but a one-off bonus is not`() {
        val salary = listOf(
            txn(80000.0, "ACME PAYROLL", "Income", direction = "CREDIT",
                txnType = TxnType.INCOME, daysAgo = 2, id = 1),
            txn(80000.0, "ACME PAYROLL", "Income", direction = "CREDIT",
                txnType = TxnType.INCOME, daysAgo = 32, id = 2),
            txn(80000.0, "ACME PAYROLL", "Income", direction = "CREDIT",
                txnType = TxnType.INCOME, daysAgo = 62, id = 3),
            txn(25000.0, "DIWALI BONUS", "Income", direction = "CREDIT",
                txnType = TxnType.INCOME, daysAgo = 10, id = 4)
        )
        val streams = engine.detectRecurringIncome(salary)

        assertEquals(1, streams.size)
        assertEquals("ACME PAYROLL", streams.single().merchant)
        assertEquals(80000.0, streams.single().currentAmount, 0.001)
    }

    @Test
    fun `a subscription knows when it is next due`() {
        val txns = listOf(
            txn(649.0, "NETFLIX", daysAgo = 6, id = 1),
            txn(649.0, "NETFLIX", daysAgo = 36, id = 2),
            txn(649.0, "NETFLIX", daysAgo = 66, id = 3)
        )
        val item = engine.detectRecurringAndPriceHikes(txns).single()

        // Last charged six days ago on a thirty-day cadence: due in about 24 days.
        assertEquals(24.0, item.daysUntilNextCharge(now).toDouble(), 1.0)
    }

    @Test
    fun `an alert outside the reported window is not surfaced`() {
        // These lists used to run over the whole ledger, so an outlier from over a
        // year ago sat at the top of the screen forever.
        // Enough ordinary charges that the outlier does not skew the quartiles it is
        // being judged against — with only a handful of points it inflates its own
        // upper bound past itself and stops looking unusual.
        val history = (1..8).map {
            txn(500.0, "SWIGGY", "Food & Dining", daysAgo = 340L + it * 5, id = it.toLong())
        }
        val txns = history + txn(30000.0, "SWIGGY", "Food & Dining", daysAgo = 350, id = 99)

        assertEquals("found over all history", 1, engine.detectAnomalies(txns).size)
        assertTrue(
            "but not reported for this month",
            engine.detectAnomalies(txns, reportRange = now - 30 * day..now).isEmpty()
        )
    }

    @Test
    fun `the medians behind an anomaly still use the whole ledger`() {
        // The window scopes what is *reported*, not what is *measured*. A category
        // needs all its history to know what normal costs.
        val history = (1..8).map {
            txn(500.0, "SWIGGY", "Food & Dining", daysAgo = 40L + it * 5, id = it.toLong())
        }
        val spike = txn(20000.0, "SWIGGY", "Food & Dining", daysAgo = 2, id = 99)

        val anomaly = engine
            .detectAnomalies(history + spike, reportRange = now - 30 * day..now)
            .single()

        assertEquals(99L, anomaly.transaction.id)
        assertEquals(500.0, anomaly.categoryMedian, 0.001)
    }

    @Test
    fun `recurring income not yet received is counted towards the month-end position`() {
        // Anyone paid late in the month used to spend three weeks looking at a card
        // that implied they were about to end it deeply in the red.
        val paidOnThe28th = listOf(
            txn(90000.0, "ACME PAYROLL", "Income", direction = "CREDIT",
                txnType = TxnType.INCOME, daysAgo = 33, id = 1),
            txn(90000.0, "ACME PAYROLL", "Income", direction = "CREDIT",
                txnType = TxnType.INCOME, daysAgo = 63, id = 2),
            txn(90000.0, "ACME PAYROLL", "Income", direction = "CREDIT",
                txnType = TxnType.INCOME, daysAgo = 93, id = 3)
        )
        val forecast = engine.computeMonthEndForecast(paidOnThe28th, now)

        assertEquals(90000.0, forecast.expectedIncomeRemaining, 0.001)
        assertEquals(90000.0, forecast.projectedIncomeMonthEnd, 0.001)
        assertTrue("the month should not read as a deficit", forecast.projectedNetMonthEnd > 0)
    }

    @Test
    fun `income already received this month is not expected a second time`() {
        val salary = listOf(
            txn(90000.0, "ACME PAYROLL", "Income", direction = "CREDIT",
                txnType = TxnType.INCOME, daysAgo = 0, id = 1),
            txn(90000.0, "ACME PAYROLL", "Income", direction = "CREDIT",
                txnType = TxnType.INCOME, daysAgo = 30, id = 2),
            txn(90000.0, "ACME PAYROLL", "Income", direction = "CREDIT",
                txnType = TxnType.INCOME, daysAgo = 60, id = 3)
        )
        val forecast = engine.computeMonthEndForecast(salary, now)

        assertEquals(0.0, forecast.expectedIncomeRemaining, 0.001)
        assertEquals(90000.0, forecast.projectedIncomeMonthEnd, 0.001)
    }

    @Test
    fun `safe to spend is what is left after commitments, spread over the days left`() {
        val txns = listOf(
            txn(100000.0, "ACME PAYROLL", "Income", direction = "CREDIT",
                txnType = TxnType.INCOME, daysAgo = 0, id = 1),
            txn(20000.0, "SWIGGY", "Food & Dining", daysAgo = 0, id = 2)
        )
        val forecast = engine.computeMonthEndForecast(txns, now)

        val expected = (100000.0 - 20000.0) / forecast.daysRemainingInMonth.coerceAtLeast(1)
        assertEquals(expected, forecast.safeToSpendPerDay, 0.01)
    }

    @Test
    fun `safe to spend never goes negative`() {
        val overspent = listOf(
            txn(5000.0, "ACME PAYROLL", "Income", direction = "CREDIT",
                txnType = TxnType.INCOME, daysAgo = 0, id = 1),
            txn(50000.0, "MYNTRA", "Shopping", daysAgo = 0, id = 2)
        )
        // A negative daily allowance is not an instruction anybody can follow; the
        // deficit is stated by the net figure instead.
        assertEquals(0.0, engine.computeMonthEndForecast(overspent, now).safeToSpendPerDay, 0.001)
    }

    @Test
    fun `internal transfers do not become recurring commitments`() {
        // A monthly savings sweep is textbook periodicity: same amount, same day each
        // month. Left in, it becomes a "subscription" whose cost is then added to the
        // month-end forecast as a committed outflow.
        val mine = FinanceAnalyticsEngine(setOf("0662"))
        val sweeps = listOf(
            transferOut(id = 1, daysAgo = 2),
            transferOut(id = 2, daysAgo = 32),
            transferOut(id = 3, daysAgo = 62),
            transferOut(id = 4, daysAgo = 92)
        )

        assertTrue(FinanceAnalyticsEngine().detectRecurringAndPriceHikes(sweeps).isNotEmpty())
        assertTrue(mine.detectRecurringAndPriceHikes(sweeps).isEmpty())
    }
}
