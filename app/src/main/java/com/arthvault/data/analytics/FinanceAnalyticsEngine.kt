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
) {
    /**
     * When this charge is next expected — the most useful fact about a subscription,
     * and the one the screen was not showing. Derived rather than stored: it is the
     * last charge plus the established cadence, and nothing else.
     */
    val nextExpectedTimestamp: Long
        get() = lastChargedTimestamp +
            (frequencyDays * FinanceAnalyticsEngine.MILLIS_PER_DAY).toLong()

    /** Days until [nextExpectedTimestamp]; negative when the charge is overdue. */
    fun daysUntilNextCharge(now: Long = System.currentTimeMillis()): Int =
        ((nextExpectedTimestamp - now) / FinanceAnalyticsEngine.MILLIS_PER_DAY)
            .let { if (it < 0) kotlin.math.floor(it) else kotlin.math.ceil(it) }
            .toInt()
}

/**
 * No pre-rendered `reason` string here on purpose.
 *
 * This used to carry `"%.1f× the usual ₹%,.0f for %s"`, which put currency formatting
 * in the data layer using the default-locale grouping — so an anomaly said `₹1,200,000`
 * while every other figure in the app said `₹12,00,000`. The three facts the sentence
 * is built from are all present below; composing it belongs to the UI, which owns the
 * one money formatter.
 */
data class AnomalyItem(
    val transaction: TransactionEntity,
    val categoryMedian: Double,
    val ratioToMedian: Double
)

/**
 * F3.5 — the month-end cash position.
 *
 * It used to project outflows only, while calling itself a cash-position forecast.
 * Income was assumed complete-to-date, so for anyone paid late in the month the card
 * spent three weeks implying they were about to end it deeply in the red.
 * [expectedIncomeRemaining] closes that: income that recurs on a known cadence and
 * has not yet landed is money that is going to arrive.
 */
data class MonthEndForecast(
    val totalSpentSoFar: Double,
    val totalIncomeSoFar: Double,
    val netCashFlowSoFar: Double,
    val projectedSpentMonthEnd: Double,
    val projectedRemainingOutflows: Double,
    val committedRecurringTotal: Double,
    val dailySpendVelocity: Double,
    val daysRemainingInMonth: Int,
    /** Recurring income on an established cadence that has not yet arrived. */
    val expectedIncomeRemaining: Double = 0.0,
    val projectedIncomeMonthEnd: Double = 0.0,
    /** What the month is on course to end at. Negative means spending past income. */
    val projectedNetMonthEnd: Double = 0.0,
    /**
     * Everything not already spent or committed, spread evenly over the days left.
     *
     * This is the number that turns a forecast into a decision: a projection tells
     * you where you are heading, this tells you what to do about it today.
     */
    val safeToSpendPerDay: Double = 0.0,
    val daysElapsedInMonth: Int = 0
) {
    /**
     * Whether there is enough of the month behind us for the projection to mean
     * anything. Two days of spending extrapolated across thirty is arithmetic, not a
     * forecast, and the card should say so rather than present it like the real thing.
     */
    val isProjectionReliable: Boolean
        get() = daysElapsedInMonth >= MIN_DAYS_FOR_RELIABLE_PROJECTION

    companion object {
        const val MIN_DAYS_FOR_RELIABLE_PROJECTION = 7
    }
}

/**
 * Income, spending and net for an arbitrary window.
 *
 * The forecast can only ever describe the current month. This is the same three
 * figures for any period the user picks, which is what lets the header say
 * "last month you earned X and spent Y" instead of only ever discussing today.
 */
data class PeriodSummary(
    val income: Double,
    val spent: Double,
    /** Netted off [spent] rather than counted as income. */
    val refunds: Double,
    val net: Double,
    val spendTransactionIds: List<Long>,
    val incomeTransactionIds: List<Long>
) {
    val transactionCount: Int get() = spendTransactionIds.size + incomeTransactionIds.size

    /** Spending as a fraction of income, or null when there was no income to compare to. */
    val spentFractionOfIncome: Float?
        get() = if (income > 0) (spent / income).toFloat() else null
}

/** One calendar day of the window, zero-filled so gaps in spending are visible as gaps. */
data class DayBucket(
    val dayStart: Long,
    /** 0 for the first day of the window, so two windows can be laid over each other. */
    val dayIndex: Int,
    val spent: Double,
    val income: Double
)

/** Running spend total, so two windows can be laid over each other as pace lines. */
fun cumulativeSpend(buckets: List<DayBucket>): List<Double> {
    var running = 0.0
    return buckets.map { running += it.spent; running }
}

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

/**
 * What was left out of the totals because it was the user's own money moving.
 *
 * This exists to be *shown*. Excluding transfers is right, but money vanishing from a
 * total with no explanation is its own kind of wrong number — and if an account has
 * been marked by mistake, this line is how the user finds out.
 */
data class InternalTransferSummary(
    val count: Int,
    /** Sent to the user's own accounts, and so not spending. */
    val outflowTotal: Double,
    /** Arrived from the user's own accounts, and so not income. */
    val inflowTotal: Double,
    val transactionIds: List<Long>
) {
    val isEmpty: Boolean get() = count == 0
}

/**
 * @param ownAccountTails account tails the user has confirmed are theirs. Empty by
 *   default, which reproduces the pre-v5 behaviour exactly: nothing is excluded until
 *   the user says an account is their own.
 */
class FinanceAnalyticsEngine(
    private val ownAccountTails: Set<String> = emptySet()
) {

    /**
     * True when this is the user's own money moving between their own accounts.
     *
     * The transaction's `accountTail` is the user's side — banks only text you about
     * your own accounts — so the question is entirely about the *other* leg, which the
     * parser writes into the merchant label as "Transfer to A/c 066".
     *
     * Only [TxnType.TRANSFER] qualifies. A card purchase at a shop that happens to have
     * a number in its name is not a transfer, and the type is what the parser already
     * decided from the message wording.
     */
    fun isInternalTransfer(txn: TransactionEntity): Boolean {
        if (ownAccountTails.isEmpty()) return false
        if (txn.txnType != TxnType.TRANSFER) return false
        val counterparty = COUNTERPARTY_TAIL.find(txn.merchant.trim())
            ?.groupValues?.get(1)
            ?: return false
        return counterparty in ownAccountTails
    }

    /**
     * The user's own money moving between their own accounts, by either route.
     *
     * [isInternalTransfer] recognises it by account tail, which works when the message
     * names both sides. A credit card bill payment never does — the bank alert quotes
     * the bank account and the card alert quotes the card — so that leg is recognised
     * by type instead, and needs no confirmed own-account to be excluded: paying a
     * card bill is not spending under any configuration, because the purchases it
     * settles are already in the ledger.
     *
     * This is the predicate every total uses. [isInternalTransfer] stays as it was,
     * meaning specifically "a transfer to a tail the user confirmed is theirs".
     */
    fun isOwnMoneyMovement(txn: TransactionEntity): Boolean =
        txn.txnType == TxnType.CARD_PAYMENT || isInternalTransfer(txn)

    /**
     * F3.x — the money excluded from the totals over [rangeStart]..[rangeEnd].
     *
     * Both directions are counted, because the bug is symmetrical: the outgoing leg
     * inflates spending and the incoming leg inflates income by the same amount.
     *
     * Card bill payments are included in this figure precisely because they are
     * excluded from the totals without the user having confirmed anything. A user
     * whose card alerts are *not* being ingested needs to be able to see what left
     * their spending, and this summary is where the screen says so.
     */
    fun summariseInternalTransfers(
        transactions: List<TransactionEntity>,
        rangeStart: Long,
        rangeEnd: Long
    ): InternalTransferSummary {
        val internal = transactions.filter {
            it.status == STATUS_POSTED &&
                it.timestamp in rangeStart..rangeEnd &&
                isOwnMoneyMovement(it)
        }
        return InternalTransferSummary(
            count = internal.size,
            outflowTotal = internal.filter { it.direction == "DEBIT" }.sumOf { it.amount },
            inflowTotal = internal.filter { it.direction == "CREDIT" }.sumOf { it.amount },
            transactionIds = internal.map { it.id }
        )
    }

    /**
     * Settled debits that represent actual spending.
     *
     * Declined attempts (F1.2) match the amount patterns and used to be stored as
     * real spend; every aggregate must exclude them. Refunds are credits and are
     * netted off separately rather than counted as income. Transfers to the user's
     * own accounts are not spending at all and are excluded here, which is the one
     * chokepoint every debit-side figure passes through.
     */
    private fun postedDebits(transactions: List<TransactionEntity>) =
        transactions.filter {
            it.direction == "DEBIT" && it.status == STATUS_POSTED && !isOwnMoneyMovement(it)
        }

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

    /**
     * F3.3 — per-category anomaly detection using median and IQR.
     *
     * @param reportRange when given, only anomalies falling inside it are returned.
     *   The medians are still computed over the *whole* ledger, which is the point of
     *   separating the two: a category needs all its history to know what normal costs,
     *   but the user does not need to be told about an outlier from fourteen months ago
     *   every time they open the screen. Without this the alert lists only ever grew.
     */
    fun detectAnomalies(
        transactions: List<TransactionEntity>,
        reportRange: LongRange? = null
    ): List<AnomalyItem> {
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
                if (reportRange != null && txn.timestamp !in reportRange) continue
                if (txn.amount > upperBound && txn.amount > median * 2.5) {
                    val ratio = txn.amount / median
                    anomalies.add(
                        AnomalyItem(
                            transaction = txn,
                            categoryMedian = median,
                            ratioToMedian = ratio
                        )
                    )
                }
            }
        }
        return anomalies.sortedByDescending { it.transaction.timestamp }
    }

    /**
     * F3.4 — duplicate charge detection (same merchant and amount within 24 hours).
     *
     * @param reportRange when given, only duplicates inside it are returned. The scan
     *   still runs over the whole ledger so a pair straddling the window boundary is
     *   still found.
     */
    fun detectDuplicates(
        transactions: List<TransactionEntity>,
        reportRange: LongRange? = null
    ): List<TransactionEntity> {
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
        return duplicates
            .filter { reportRange == null || it.timestamp in reportRange }
            .sortedByDescending { it.timestamp }
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

    /**
     * Settled credits that represent actual income.
     *
     * Refunds are money coming back from a purchase, not earnings — counting one as
     * income made a ₹4,000 refund read like salary. Arrivals from the user's own
     * accounts are not income either. Both are excluded here, the mirror of
     * [postedDebits].
     */
    private fun postedCredits(transactions: List<TransactionEntity>) =
        transactions.filter {
            it.direction == "CREDIT" &&
                it.status == STATUS_POSTED &&
                it.txnType != TxnType.REFUND &&
                !isOwnMoneyMovement(it)
        }

    /**
     * Income, spending and net over an arbitrary window.
     *
     * Every figure here passes through the same two chokepoints as the rest of the
     * engine, so a period summary cannot disagree with the donut sitting underneath
     * it. Refunds are netted off spending rather than added to income.
     */
    fun computePeriodSummary(
        transactions: List<TransactionEntity>,
        range: LongRange
    ): PeriodSummary {
        val inWindow = transactions.filter { it.timestamp in range }

        val debits = postedDebits(inWindow)
        val credits = postedCredits(inWindow)
        val refunds = inWindow
            .filter { it.status == STATUS_POSTED && it.direction == "CREDIT" && it.txnType == TxnType.REFUND }
            .sumOf { it.amount }

        val spent = (debits.sumOf { it.amount } - refunds).coerceAtLeast(0.0)
        val income = credits.sumOf { it.amount }

        return PeriodSummary(
            income = income,
            spent = spent,
            refunds = refunds,
            net = income - spent,
            spendTransactionIds = debits.map { it.id },
            incomeTransactionIds = credits.map { it.id }
        )
    }

    /**
     * One bucket per calendar day of [range], zero-filled.
     *
     * The zero-filling is the point: a day with no spending has to occupy width on a
     * chart, or a fortnight of restraint compresses into nothing and the shape of the
     * month is lost. Buckets are walked with [Calendar] rather than by dividing
     * milliseconds so a daylight-saving change cannot drop or duplicate a day.
     */
    fun computeDailyTotals(
        transactions: List<TransactionEntity>,
        range: LongRange
    ): List<DayBucket> {
        val boundaries = dayBoundaries(range)
        if (boundaries.isEmpty()) return emptyList()

        val spentPerDay = DoubleArray(boundaries.size)
        val incomePerDay = DoubleArray(boundaries.size)

        fun bucketOf(timestamp: Long): Int {
            val found = boundaries.binarySearch { it.compareTo(timestamp) }
            // binarySearch returns -(insertionPoint) - 1 for a miss; the bucket is the
            // day that starts at or before the timestamp, i.e. insertionPoint - 1.
            return if (found >= 0) found else -found - 2
        }

        for (txn in postedDebits(transactions)) {
            if (txn.timestamp !in range) continue
            bucketOf(txn.timestamp).takeIf { it in boundaries.indices }
                ?.let { spentPerDay[it] += txn.amount }
        }
        for (txn in postedCredits(transactions)) {
            if (txn.timestamp !in range) continue
            bucketOf(txn.timestamp).takeIf { it in boundaries.indices }
                ?.let { incomePerDay[it] += txn.amount }
        }

        return boundaries.mapIndexed { index, dayStart ->
            DayBucket(
                dayStart = dayStart,
                dayIndex = index,
                spent = spentPerDay[index],
                income = incomePerDay[index]
            )
        }
    }

    private fun dayBoundaries(range: LongRange): List<Long> {
        if (range.isEmpty()) return emptyList()
        val cursor = Calendar.getInstance().apply {
            timeInMillis = range.first
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val boundaries = mutableListOf<Long>()
        // A guard rather than a limit anyone should hit: the widest offered window is
        // 90 days, and an unbounded loop here would be driven by a stored timestamp.
        while (cursor.timeInMillis <= range.last && boundaries.size < MAX_DAY_BUCKETS) {
            boundaries += cursor.timeInMillis
            cursor.add(Calendar.DAY_OF_MONTH, 1)
        }
        return boundaries
    }

    /**
     * Income that arrives on an established cadence — salary, rent received, a pension.
     *
     * The same evidence the spending side demands: at least
     * [MIN_CHARGES_FOR_RECURRING] credits, a consistent gap, and a stable amount. A
     * one-off bonus is not a salary and must not be projected as one.
     */
    fun detectRecurringIncome(transactions: List<TransactionEntity>): List<RecurringItem> =
        postedCredits(transactions)
            .sortedByDescending { it.timestamp }
            .groupBy { it.merchant.uppercase().trim() }
            .values
            .mapNotNull(::asRecurringItem)

    /**
     * F3.5 — the month-end cash position.
     *
     * @param now the clock. Previously read from [Calendar] inside the function, which
     *   made every figure here untestable without freezing system time.
     */
    fun computeMonthEndForecast(
        transactions: List<TransactionEntity>,
        now: Long = System.currentTimeMillis()
    ): MonthEndForecast {
        val month = PeriodResolver.resolve(PeriodScope.THIS_MONTH, now)
        val daysElapsed = month.elapsedDays
        val daysRemaining = month.daysRemaining

        val posted = transactions.filter {
            it.status == STATUS_POSTED && it.timestamp in month.range
        }

        val summary = computePeriodSummary(transactions, month.range)
        val totalSpentSoFar = summary.spent
        val totalIncomeSoFar = summary.income

        val recurring = detectRecurringAndPriceHikes(transactions)
        val recurringMerchants = recurring.map { it.merchant.uppercase().trim() }.toSet()

        // Committed outflows are the recurring charges still expected before month
        // end — i.e. ones that have not already landed this month.
        val chargedThisMonth = posted
            .filter { it.direction == "DEBIT" && !isOwnMoneyMovement(it) }
            .map { it.merchant.uppercase().trim() }
            .toSet()

        val committedRemaining = recurring
            .filter { it.merchant.uppercase().trim() !in chargedThisMonth }
            .sumOf { it.currentAmount }

        // The same reasoning applied to the way in: recurring income that has not yet
        // landed this month is money that is going to arrive, and leaving it out is
        // what made the card imply a deficit for anyone paid after the 1st.
        val receivedThisMonth = posted
            .filter { it.direction == "CREDIT" && it.txnType != TxnType.REFUND && !isOwnMoneyMovement(it) }
            .map { it.merchant.uppercase().trim() }
            .toSet()

        val expectedIncomeRemaining = detectRecurringIncome(transactions)
            .filter { it.merchant.uppercase().trim() !in receivedThisMonth }
            .sumOf { it.currentAmount }

        // Velocity is measured over non-recurring spend only. Including recurring
        // charges here would bake them into the daily rate and then add them again
        // as commitments, double-counting rent-sized items.
        val nonRecurringSpentSoFar = posted
            .filter {
                it.direction == "DEBIT" &&
                    !isOwnMoneyMovement(it) &&
                    it.merchant.uppercase().trim() !in recurringMerchants
            }
            .sumOf { it.amount }

        val dailyVelocity = if (daysElapsed > 0) nonRecurringSpentSoFar / daysElapsed else 0.0

        val projectedTrend = dailyVelocity * daysRemaining
        val projectedRemainingOutflows = projectedTrend + committedRemaining
        val projectedSpentMonthEnd = totalSpentSoFar + projectedRemainingOutflows
        val projectedIncomeMonthEnd = totalIncomeSoFar + expectedIncomeRemaining

        // Deliberately does not subtract the projected trend: the trend is precisely
        // what this number exists to let the user change.
        val headroom = projectedIncomeMonthEnd - totalSpentSoFar - committedRemaining

        return MonthEndForecast(
            totalSpentSoFar = totalSpentSoFar,
            totalIncomeSoFar = totalIncomeSoFar,
            netCashFlowSoFar = totalIncomeSoFar - totalSpentSoFar,
            projectedSpentMonthEnd = projectedSpentMonthEnd,
            projectedRemainingOutflows = projectedRemainingOutflows,
            committedRecurringTotal = committedRemaining,
            dailySpendVelocity = dailyVelocity,
            daysRemainingInMonth = daysRemaining,
            expectedIncomeRemaining = expectedIncomeRemaining,
            projectedIncomeMonthEnd = projectedIncomeMonthEnd,
            projectedNetMonthEnd = projectedIncomeMonthEnd - projectedSpentMonthEnd,
            safeToSpendPerDay = (headroom / daysRemaining.coerceAtLeast(1)).coerceAtLeast(0.0),
            daysElapsedInMonth = daysElapsed
        )
    }

    // `currentMonthRange` and `previousMonthRange` used to live here. `PeriodResolver`
    // now owns every window boundary in the app, and a second implementation of month
    // arithmetic is exactly how the two sides of a comparison drift apart.

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

        /** Guard on day-bucket generation; the widest offered window is 90 days. */
        const val MAX_DAY_BUCKETS = 400

        /**
         * The other account in a transfer, read back out of the merchant label.
         *
         * The label is not free text: `SmsParserEngine.transferLabel` generates it in
         * exactly this shape whenever a transfer names an account rather than a payee,
         * so this parses a value this codebase wrote. Anchored at both ends so a
         * merchant that merely mentions an account cannot be mistaken for one.
         */
        private val COUNTERPARTY_TAIL =
            Regex("""(?i)^transfer\s+(?:to|from)\s+a/c\s+(\d{3,6})$""")
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
