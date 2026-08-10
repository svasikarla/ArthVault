package com.arthvault.data.analytics

import com.arthvault.data.local.entity.STATUS_POSTED
import com.arthvault.data.local.entity.TransactionEntity
import com.arthvault.data.local.entity.TxnType
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A merchant that charges on a schedule (F3.1).
 *
 * [currentAmount] is the *level*, not the last charge: the median of the run of most
 * recent charges at the same price. One odd month does not redefine what a
 * subscription costs.
 *
 * [transactionIds] carries the charges behind the finding so it can be traced back to
 * source rows (F4.4).
 */
data class RecurringItem(
    val merchant: String,
    val category: String,
    val currentAmount: Double,
    /** Median of the charges before the current level, or null if there are none. */
    val previousAmount: Double?,
    val isPriceHike: Boolean,
    val priceHikePercentage: Double = 0.0,
    val frequencyDays: Int,
    val lastChargedTimestamp: Long,
    /** How many charges support this finding. Never below [MIN_CHARGES_FOR_RECURRING]. */
    val chargeCount: Int = 0,
    val transactionIds: List<Long> = emptyList()
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

/**
 * F3.6 — one category's movement between two periods.
 *
 * [percentageChange] is null when the earlier period had no spending in this
 * category: that is "new spending", not an infinite rise, and the two deserve
 * different words on screen.
 */
data class CategoryTrend(
    val category: String,
    val previousTotal: Double,
    val currentTotal: Double,
    /** Current minus previous. Positive means spending went up. */
    val delta: Double,
    val percentageChange: Double?,
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

    /**
     * F3.1 / F3.2 — recurring charges, and price rises on them.
     *
     * The previous version compared the two most recent charges and then let
     * anything through with `|| merchantTxns.size >= 3`, which made *any* merchant
     * billed three times a subscription: order three Swiggy meals and Swiggy became
     * a recurring charge, with its committed cost added to the month-end forecast.
     *
     * Recurrence now needs both halves of what the spec asks for:
     *
     *  - **Periodicity.** At least [MIN_CHARGES_FOR_RECURRING] charges, and the gaps
     *    between them must be consistent — median absolute deviation under
     *    [MAX_GAP_DISPERSION] of the median gap. Two charges can only ever produce
     *    one gap, and one gap is not evidence of a rhythm.
     *  - **Amount clustering.** Charges must sit at a stable price.
     *
     * Those two pull against each other, because a price hike *is* a break in amount
     * clustering — test the amounts naively and F3.2 becomes unreachable, since every
     * item it should flag gets disqualified from being recurring first. So the
     * charges are split into a current level (the newest run of similar amounts) and
     * whatever came before, and each side is required to be internally tight. Random
     * spending fails that: its leading run is a single charge and the tail is
     * scattered. A subscription that stepped up once passes, with both levels
     * visible.
     *
     * A hike must also be *sustained* — at least two charges at the new price. F3.2
     * is about silent increases, which are permanent by nature; one expensive month
     * is an anomaly, and F3.3 already reports those.
     */
    fun detectRecurringAndPriceHikes(transactions: List<TransactionEntity>): List<RecurringItem> {
        val debits = postedDebits(transactions).sortedByDescending { it.timestamp }

        return debits
            .groupBy { it.merchant.uppercase().trim() }
            .values
            .mapNotNull(::asRecurringItem)
    }

    /** @return null when [charges] (newest first) are not a recurring series. */
    private fun asRecurringItem(charges: List<TransactionEntity>): RecurringItem? {
        if (charges.size < MIN_CHARGES_FOR_RECURRING) return null

        val gapDays = charges.zipWithNext { newer, older ->
            (newer.timestamp - older.timestamp).toDouble() / MILLIS_PER_DAY
        }
        val medianGap = calculateMedian(gapDays)
        if (medianGap < MIN_GAP_DAYS) return null

        // Median absolute deviation rather than standard deviation: with three or
        // four points a single irregular gap moves a mean-based spread enough to
        // reject a series that is obviously monthly to a human.
        val gapDispersion = calculateMedian(gapDays.map { abs(it - medianGap) }) / medianGap
        if (gapDispersion >= MAX_GAP_DISPERSION) return null

        val amounts = charges.map { it.amount }
        val currentRun = amounts.takeWhile { withinTolerance(it, amounts.first()) }
        val priorRun = amounts.drop(currentRun.size)

        if (!isTightlyClustered(currentRun)) return null
        if (priorRun.isNotEmpty() && !isTightlyClustered(priorRun)) return null

        val currentLevel = calculateMedian(currentRun)
        val priorLevel = if (priorRun.isEmpty()) null else calculateMedian(priorRun)

        val isSustained = currentRun.size >= MIN_CHARGES_AT_NEW_PRICE
        val isPriceHike = priorLevel != null &&
            priorLevel > 0 &&
            isSustained &&
            currentLevel > priorLevel * (1 + MIN_HIKE_FRACTION)

        val latest = charges.first()
        return RecurringItem(
            merchant = latest.merchant,
            category = latest.category,
            currentAmount = currentLevel,
            previousAmount = priorLevel,
            isPriceHike = isPriceHike,
            priceHikePercentage =
                if (isPriceHike) ((currentLevel - priorLevel!!) / priorLevel) * 100.0 else 0.0,
            frequencyDays = medianGap.roundToInt(),
            lastChargedTimestamp = latest.timestamp,
            chargeCount = charges.size,
            transactionIds = charges.map { it.id }
        )
    }

    /** True when at least [MIN_CLUSTERED_FRACTION] of [amounts] sit near their median. */
    private fun isTightlyClustered(amounts: List<Double>): Boolean {
        if (amounts.size <= 1) return true
        val median = calculateMedian(amounts)
        if (median <= 0) return false
        val near = amounts.count { withinTolerance(it, median) }
        return near.toDouble() / amounts.size >= MIN_CLUSTERED_FRACTION
    }

    private fun withinTolerance(value: Double, reference: Double): Boolean =
        reference > 0 && abs(value - reference) <= reference * AMOUNT_TOLERANCE

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

    /**
     * F3.6 — how each category's spending moved between two windows.
     *
     * Categories present in only one window are still reported, with the absent side
     * at zero: a category you stopped spending in is a trend, and dropping it would
     * hide the most interesting change on the list.
     *
     * [percentageChange] is null rather than infinite when the earlier window is
     * zero. "New spending" is a different statement from "up 100%", and rendering
     * infinity as a number is how a plausible-looking nonsense figure reaches the
     * screen.
     *
     * @param periodA the earlier window, @param periodB the later one. Ordering is
     *   the caller's to state — [delta] is B minus A, so a positive number means
     *   spending rose.
     */
    fun compareCategories(
        transactions: List<TransactionEntity>,
        periodA: LongRange,
        periodB: LongRange
    ): List<CategoryTrend> {
        val debits = postedDebits(transactions)

        fun totalsIn(period: LongRange): Map<String, List<TransactionEntity>> =
            debits.filter { it.timestamp in period }.groupBy { it.category }

        val earlier = totalsIn(periodA)
        val later = totalsIn(periodB)

        return (earlier.keys + later.keys).map { category ->
            val a = earlier[category].orEmpty()
            val b = later[category].orEmpty()
            val totalA = a.sumOf { it.amount }
            val totalB = b.sumOf { it.amount }

            CategoryTrend(
                category = category,
                previousTotal = totalA,
                currentTotal = totalB,
                delta = totalB - totalA,
                percentageChange = if (totalA > 0) ((totalB - totalA) / totalA) * 100.0 else null,
                transactionIds = (a + b).map { it.id }
            )
        }.sortedByDescending { abs(it.delta) }
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

    /** The calendar month before the one containing [now] — F3.6's baseline. */
    fun previousMonthRange(now: Long = System.currentTimeMillis()): LongRange {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val end = cal.timeInMillis
        cal.add(Calendar.MONTH, -1)
        return cal.timeInMillis until end
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

    companion object {
        const val MILLIS_PER_DAY = 86_400_000.0

        /**
         * Two charges yield one gap, and one gap says nothing about rhythm. Three is
         * the smallest number that can disagree with itself.
         */
        const val MIN_CHARGES_FOR_RECURRING = 3

        /** Below this, "monthly" and "twice in one afternoon" are indistinguishable. */
        const val MIN_GAP_DAYS = 5.0

        /** Median absolute deviation of the gaps, as a fraction of the median gap. */
        const val MAX_GAP_DISPERSION = 0.25

        /** How far an amount may sit from its level and still count as that level. */
        const val AMOUNT_TOLERANCE = 0.15

        /** Fraction of a run that must sit within [AMOUNT_TOLERANCE] of its median. */
        const val MIN_CLUSTERED_FRACTION = 0.70

        /** A hike is only a hike once it repeats; one dear month is an anomaly (F3.3). */
        const val MIN_CHARGES_AT_NEW_PRICE = 2

        /** Ignore rounding and small fee changes. */
        const val MIN_HIKE_FRACTION = 0.05
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
