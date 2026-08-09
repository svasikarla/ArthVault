package com.example.data.analytics

import com.example.data.local.entity.STATUS_POSTED
import com.example.data.local.entity.TransactionEntity
import com.example.data.local.entity.TxnType
import java.util.Calendar
import kotlin.math.abs

data class RecurringItem(
    val merchant: String,
    val category: String,
    val currentAmount: Double,
    val previousAmount: Double?,
    val isPriceHike: Boolean,
    val priceHikePercentage: Double = 0.0,
    val frequencyDays: Int,
    val lastChargedTimestamp: Long
)

data class AnomalyItem(
    val transaction: TransactionEntity,
    val categoryMedian: Double,
    val ratioToMedian: Double,
    val reason: String
)

data class MonthEndForecast(
    val totalSpentSoFar: Double,
    val totalIncomeSoFar: Double,
    val netCashFlowSoFar: Double,
    val projectedSpentMonthEnd: Double,
    val projectedRemainingOutflows: Double,
    val committedRecurringTotal: Double,
    val dailySpendVelocity: Double,
    val daysRemainingInMonth: Int
)

/**
 * One slice of the real category distribution.
 *
 * [transactionIds] carries the rows behind the number so an insight can be traced
 * back to its sources (F4.4) without recomputing the grouping.
 */
data class CategorySlice(
    val category: String,
    val total: Double,
    val fraction: Float,
    val transactionIds: List<Long>
)

class FinanceAnalyticsEngine {

    /**
     * Settled debits only.
     *
     * Declined attempts (F1.2) match the amount patterns and used to be stored as
     * real spend; every aggregate must exclude them. Refunds are credits and are
     * netted off separately rather than counted as income.
     */
    private fun postedDebits(transactions: List<TransactionEntity>) =
        transactions.filter { it.direction == "DEBIT" && it.status == STATUS_POSTED }

    // F3.1 & F3.2: Detect Recurring Subscriptions & Silent Price Hikes
    fun detectRecurringAndPriceHikes(transactions: List<TransactionEntity>): List<RecurringItem> {
        val debits = postedDebits(transactions)
            .sortedByDescending { it.timestamp }

        val groupedByMerchant = debits.groupBy { it.merchant.uppercase().trim() }
        val recurringList = mutableListOf<RecurringItem>()

        for ((_, merchantTxns) in groupedByMerchant) {
            if (merchantTxns.size >= 2) {
                val latest = merchantTxns[0]
                val previous = merchantTxns[1]

                // Calculate interval in days between charges
                val intervalMs = abs(latest.timestamp - previous.timestamp)
                val intervalDays = (intervalMs / (1000 * 60 * 60 * 24)).toInt()

                // Consider recurring if charged within roughly 25-35 days (monthly) or 80-95 days (quarterly) or ~360 days (annual)
                val isPeriodic = (intervalDays in 20..38) || (intervalDays in 80..100) || (intervalDays in 350..380)

                if (isPeriodic || merchantTxns.size >= 3) {
                    val isPriceHike = previous.amount > 0 && latest.amount > previous.amount * 1.05 // > 5% increase
                    val hikePct = if (isPriceHike) ((latest.amount - previous.amount) / previous.amount) * 100.0 else 0.0

                    recurringList.add(
                        RecurringItem(
                            merchant = latest.merchant,
                            category = latest.category,
                            currentAmount = latest.amount,
                            previousAmount = previous.amount,
                            isPriceHike = isPriceHike,
                            priceHikePercentage = hikePct,
                            frequencyDays = if (intervalDays in 20..38) 30 else intervalDays,
                            lastChargedTimestamp = latest.timestamp
                        )
                    )
                }
            }
        }
        return recurringList
    }

    // F3.3: Per-Category Anomaly Detection using Median & IQR
    fun detectAnomalies(transactions: List<TransactionEntity>): List<AnomalyItem> {
        val debits = postedDebits(transactions)
        val groupedByCategory = debits.groupBy { it.category }
        val anomalies = mutableListOf<AnomalyItem>()

        for ((_, categoryTxns) in groupedByCategory) {
            if (categoryTxns.size < 4) continue // Need enough data points

            val amounts = categoryTxns.map { it.amount }.sorted()
            val median = calculateMedian(amounts)
            val q1 = calculateMedian(amounts.subList(0, amounts.size / 2))
            val q3 = calculateMedian(amounts.subList((amounts.size + 1) / 2, amounts.size))
            val iqr = (q3 - q1).coerceAtLeast(100.0) // Floor IQR to prevent divide-by-zero or overly strict thresholds

            val upperBound = q3 + 2.5 * iqr

            for (txn in categoryTxns) {
                if (txn.amount > upperBound && txn.amount > median * 2.5) {
                    val ratio = txn.amount / median
                    anomalies.add(
                        AnomalyItem(
                            transaction = txn,
                            categoryMedian = median,
                            ratioToMedian = ratio,
                            reason = "Unusually high spending (%.1fx of category median Rs. %.0f)".format(ratio, median)
                        )
                    )
                }
            }
        }
        return anomalies
    }

    // F3.4: Duplicate Charge Detection (Same merchant + same amount within 24 hours)
    fun detectDuplicates(transactions: List<TransactionEntity>): List<TransactionEntity> {
        val duplicates = mutableSetOf<TransactionEntity>()
        val debits = postedDebits(transactions).sortedBy { it.timestamp }

        for (i in 0 until debits.size) {
            val current = debits[i]
            for (j in i + 1 until debits.size) {
                val next = debits[j]
                val timeDiffHours = (next.timestamp - current.timestamp) / (1000 * 60 * 60)
                if (timeDiffHours > 24) break // Beyond 24 hour window

                if (current.merchant.equals(next.merchant, ignoreCase = true) && abs(current.amount - next.amount) < 0.01) {
                    duplicates.add(next) // Flag the second occurrence
                }
            }
        }
        return duplicates.toList()
    }

    /**
     * F3.6 groundwork / real category distribution.
     *
     * Returns the actual per-category debit totals for the given window, largest
     * first, with everything past [topN] folded into a genuine "Other" slice.
     */
    fun computeCategoryBreakdown(
        transactions: List<TransactionEntity>,
        rangeStart: Long,
        rangeEnd: Long,
        topN: Int = 5
    ): List<CategorySlice> {
        val debits = postedDebits(transactions).filter { it.timestamp in rangeStart..rangeEnd }
        val total = debits.sumOf { it.amount }
        if (total <= 0.0) return emptyList()

        val byCategory = debits
            .groupBy { it.category }
            .map { (category, txns) ->
                CategorySlice(
                    category = category,
                    total = txns.sumOf { it.amount },
                    fraction = (txns.sumOf { it.amount } / total).toFloat(),
                    transactionIds = txns.map { it.id }
                )
            }
            .sortedByDescending { it.total }

        if (byCategory.size <= topN) return byCategory

        val head = byCategory.take(topN)
        val tail = byCategory.drop(topN)
        val otherTotal = tail.sumOf { it.total }

        return head + CategorySlice(
            category = "Other",
            total = otherTotal,
            fraction = (otherTotal / total).toFloat(),
            transactionIds = tail.flatMap { it.transactionIds }
        )
    }

    // F3.5: Month-End Cash-Position Forecast from committed outflows + trend
    fun computeMonthEndForecast(transactions: List<TransactionEntity>): MonthEndForecast {
        val now = Calendar.getInstance()
        val currentMonth = now.get(Calendar.MONTH)
        val currentYear = now.get(Calendar.YEAR)
        val currentDay = now.get(Calendar.DAY_OF_MONTH)
        val daysInMonth = now.getActualMaximum(Calendar.DAY_OF_MONTH)
        val daysRemaining = (daysInMonth - currentDay).coerceAtLeast(1)

        val monthTxns = transactions.filter {
            val cal = Calendar.getInstance().apply { timeInMillis = it.timestamp }
            cal.get(Calendar.MONTH) == currentMonth && cal.get(Calendar.YEAR) == currentYear
        }

        val posted = monthTxns.filter { it.status == STATUS_POSTED }

        // A refund is a credit, but it is money coming back from a purchase rather
        // than income — counting it as income made a ₹4,000 refund read like salary.
        // It is netted off spending instead.
        val refundsThisMonth = posted
            .filter { it.direction == "CREDIT" && it.txnType == TxnType.REFUND }
            .sumOf { it.amount }

        val grossSpent = posted.filter { it.direction == "DEBIT" }.sumOf { it.amount }
        val totalSpentSoFar = (grossSpent - refundsThisMonth).coerceAtLeast(0.0)
        val totalIncomeSoFar = posted
            .filter { it.direction == "CREDIT" && it.txnType != TxnType.REFUND }
            .sumOf { it.amount }
        val netCashFlow = totalIncomeSoFar - totalSpentSoFar

        val recurring = detectRecurringAndPriceHikes(transactions)
        val recurringMerchants = recurring.map { it.merchant.uppercase().trim() }.toSet()

        // Committed outflows are the recurring charges still expected before month
        // end — i.e. ones that have not already landed this month.
        val chargedThisMonth = posted
            .filter { it.direction == "DEBIT" }
            .map { it.merchant.uppercase().trim() }
            .toSet()

        val committedRemaining = recurring
            .filter { it.merchant.uppercase().trim() !in chargedThisMonth }
            .sumOf { it.currentAmount }

        // Velocity is measured over non-recurring spend only. Including recurring
        // charges here would bake them into the daily rate and then add them again
        // as commitments, double-counting rent-sized items.
        val nonRecurringSpentSoFar = posted
            .filter { it.direction == "DEBIT" && it.merchant.uppercase().trim() !in recurringMerchants }
            .sumOf { it.amount }

        val dailyVelocity = if (currentDay > 0) nonRecurringSpentSoFar / currentDay else 0.0

        val projectedTrend = dailyVelocity * daysRemaining
        val projectedRemainingOutflows = projectedTrend + committedRemaining
        val projectedSpentMonthEnd = totalSpentSoFar + projectedRemainingOutflows

        return MonthEndForecast(
            totalSpentSoFar = totalSpentSoFar,
            totalIncomeSoFar = totalIncomeSoFar,
            netCashFlowSoFar = netCashFlow,
            projectedSpentMonthEnd = projectedSpentMonthEnd,
            projectedRemainingOutflows = projectedRemainingOutflows,
            committedRecurringTotal = committedRemaining,
            dailySpendVelocity = dailyVelocity,
            daysRemainingInMonth = daysRemaining
        )
    }

    /** Start-of-month and end-of-month timestamps for the month containing [now]. */
    fun currentMonthRange(now: Long = System.currentTimeMillis()): LongRange {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        return start until cal.timeInMillis
    }

    private fun calculateMedian(list: List<Double>): Double {
        if (list.isEmpty()) return 0.0
        val sorted = list.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }
}
