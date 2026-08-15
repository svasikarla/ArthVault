package com.arthvault.data.analytics

import com.arthvault.data.local.entity.BillKind
import com.arthvault.data.local.entity.BillNoticeEntity
import com.arthvault.data.local.entity.STATUS_POSTED
import com.arthvault.data.local.entity.TransactionEntity
import com.arthvault.data.local.entity.TxnType
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/**
 * What the app is willing to say about whether a bill has been paid.
 *
 * Three states, not two, and the third is the important one. A bill settled by autopay,
 * by netbanking, or from a bank whose alerts are not allowlisted produces no SMS at all,
 * so the ledger genuinely cannot distinguish "not paid" from "paid invisibly". Telling a
 * user a bill is OVERDUE when they cleared it last Tuesday is an accusation rather than
 * an error, and it costs more trust than any wrong total — they can check a total.
 */
enum class BillSettlement {
    /** A payment of the right size, linked to this biller, inside the window. */
    PAID,

    /** Something linked to this biller was paid, but not the sum the notice named. */
    LIKELY_PAID,

    /** Nothing linked to this biller settled in the window. Not the same as unpaid. */
    NO_PAYMENT_SEEN
}

/**
 * One obligation, folded from every notice the biller sent about it.
 *
 * A biller sends the same reminder three or four times a cycle and may restate a smaller
 * total after a part payment. Those are separate rows in `bill_notices`, all sharing a
 * `cycleKey`; this is what the screen shows instead of showing them four times.
 */
data class BillObligation(
    val cycleKey: String,
    val billerKey: String,
    val billerLabel: String,
    val kind: String,
    val accountTail: String?,
    /** From the most recent notice in the cycle — the biller's latest word on the sum. */
    val amountDue: Double?,
    val minAmountDue: Double?,
    val dueDate: Long?,
    val billingPeriodLabel: String?,
    /** When the most recent notice arrived. */
    val issuedAt: Long,
    val noticeCount: Int,
    val settlement: BillSettlement,
    /** F4.4 — the notices behind this row, and whatever payment was matched to it. */
    val noticeIds: List<Long>,
    val matchedTransactionIds: List<Long>
) {
    /** Negative once the date has passed. Null when the biller stated no date. */
    fun daysUntilDue(now: Long): Int? = dueDate?.let {
        val days = (it - now).toDouble() / MILLIS_PER_DAY
        (if (days < 0) kotlin.math.floor(days) else kotlin.math.ceil(days)).toInt()
    }

    /**
     * Past its date with nothing seen against it.
     *
     * Deliberately not called "unpaid": it is a statement about what the ledger has
     * observed, not about what the user did.
     */
    fun isPastDue(now: Long): Boolean =
        dueDate != null && dueDate < now && settlement == BillSettlement.NO_PAYMENT_SEEN
}

/** One biller's bill in one cycle, for the history series. */
data class BillCycleAmount(val at: Long, val amount: Double)

/**
 * How one biller's bill has moved.
 *
 * [percentageChange] is null when there is no earlier bill to compare against, following
 * the same convention as [CategoryTrend]: "first bill seen" is a different statement
 * from "up 100%", and rendering infinity as a number is how plausible-looking nonsense
 * reaches the screen.
 */
data class BillTrend(
    val billerKey: String,
    val billerLabel: String,
    val kind: String,
    /** Oldest first. */
    val cycles: List<BillCycleAmount>,
    val latestAmount: Double,
    val previousAmount: Double?,
    val delta: Double?,
    val percentageChange: Double?,
    val medianAmount: Double
) {
    /**
     * Whether there is enough history for the movement to mean anything.
     *
     * Two bills make one comparison, and one comparison cannot tell a rise from the
     * ordinary variation every metered bill has. This is the same evidence bar
     * [FinanceAnalyticsEngine.MIN_CHARGES_FOR_RECURRING] sets on the spending side, and
     * the screen shows the raw series rather than a trend until it is met.
     */
    val isEstablished: Boolean get() = cycles.size >= MIN_CYCLES_FOR_TREND

    companion object {
        const val MIN_CYCLES_FOR_TREND = 3
    }
}

/** Total billed in one calendar month, across every biller. */
data class BillMonth(
    val year: Int,
    /** 0-based, as [Calendar.MONTH] gives it. */
    val month: Int,
    val monthStart: Long,
    val total: Double,
    val billerCount: Int
)

private const val MILLIS_PER_DAY = 86_400_000L

/**
 * Turns captured bill notices into obligations, and obligations into history.
 *
 * Nothing here ever touches a spending total. A statement balance is money *owed*, and
 * a card statement's underlying purchases are already in the ledger — adding the two
 * counts the same rupees twice. The Bills screen reports obligations; the Analytics
 * screen reports money that moved; the two are never summed.
 */
class BillAnalyticsEngine {

    /**
     * Folds notices into obligations and decides what can be said about each.
     *
     * @param transactions the adjustment-folded ledger. Voided rows are already gone,
     *   which is what stops a payment the user removed from marking a bill settled.
     */
    fun reconcile(
        notices: List<BillNoticeEntity>,
        transactions: List<TransactionEntity>
    ): List<BillObligation> {
        val settled = transactions.filter { it.status == STATUS_POSTED && it.direction == "DEBIT" }

        return notices
            .groupBy { it.cycleKey }
            .map { (cycleKey, cycle) -> obligationOf(cycleKey, cycle, settled) }
            .sortedWith(
                // Undated obligations sort last: they are real, but a list about
                // deadlines is ordered by deadline and they have none.
                compareBy(nullsLast()) { it.dueDate }
            )
    }

    private fun obligationOf(
        cycleKey: String,
        cycle: List<BillNoticeEntity>,
        settledDebits: List<TransactionEntity>
    ): BillObligation {
        val newest = cycle.maxByOrNull { it.issuedAt }!!
        val earliestIssue = cycle.minOf { it.issuedAt }

        val matches = matchingPayments(newest, earliestIssue, settledDebits)
        val settlement = when {
            matches.isEmpty() -> BillSettlement.NO_PAYMENT_SEEN
            newest.amountDue == null -> BillSettlement.LIKELY_PAID
            matches.any { withinTolerance(it.amount, newest.amountDue) } -> BillSettlement.PAID
            else -> BillSettlement.LIKELY_PAID
        }

        return BillObligation(
            cycleKey = cycleKey,
            billerKey = newest.billerKey,
            billerLabel = newest.billerLabel,
            kind = newest.kind,
            accountTail = newest.accountTail,
            amountDue = newest.amountDue,
            minAmountDue = newest.minAmountDue,
            dueDate = newest.dueDate,
            billingPeriodLabel = newest.billingPeriodLabel,
            issuedAt = newest.issuedAt,
            noticeCount = cycle.size,
            settlement = settlement,
            noticeIds = cycle.sortedBy { it.issuedAt }.map { it.id },
            matchedTransactionIds = matches.map { it.id }
        )
    }

    /**
     * Payments that could plausibly be this bill.
     *
     * Every candidate must carry an **identity link** to the biller — the transaction
     * type, the account tail, or the payee name. Matching on amount alone was rejected:
     * an unrelated ₹2,783 purchase in the same fortnight would mark a card bill settled,
     * and a false "paid" is the one error this feature cannot afford, because the user
     * finds out about it from a late fee.
     */
    private fun matchingPayments(
        notice: BillNoticeEntity,
        earliestIssue: Long,
        settledDebits: List<TransactionEntity>
    ): List<TransactionEntity> {
        val windowStart = earliestIssue - ISSUE_LEAD_DAYS * MILLIS_PER_DAY
        val windowEnd = (notice.dueDate ?: (notice.issuedAt + UNDATED_WINDOW_DAYS * MILLIS_PER_DAY)) +
            GRACE_DAYS * MILLIS_PER_DAY

        return settledDebits.filter { txn ->
            txn.timestamp in windowStart..windowEnd && hasIdentityLink(notice, txn)
        }
    }

    private fun hasIdentityLink(notice: BillNoticeEntity, txn: TransactionEntity): Boolean {
        // A card bill is settled by a transfer the parser already types CARD_PAYMENT.
        // The tail cannot be relied on here: the bank leg quotes the bank account and
        // the card leg quotes the card, so only one of the two ever matches the notice.
        if (notice.kind == BillKind.CARD && txn.txnType == TxnType.CARD_PAYMENT) return true

        if (notice.accountTail != null && notice.accountTail == txn.accountTail) return true

        val payee = normalise(txn.merchant)
        if (payee.isBlank() || notice.billerKey.isBlank()) return false

        // Substring either way: "AIRTEL" as a biller against "AIRTEL PREPAID" as a
        // payee, and "BESCOMBENGALURU" against "BESCOM".
        return payee.contains(notice.billerKey) || notice.billerKey.contains(payee)
    }

    private fun normalise(value: String): String =
        value.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")

    private fun withinTolerance(value: Double, reference: Double): Boolean =
        reference > 0 && abs(value - reference) <= reference * AMOUNT_TOLERANCE

    /**
     * How each biller's bill has moved, largest absolute change first.
     *
     * One cycle per obligation, not per notice: four reminders about one ₹12,340
     * statement are one bill, and counting them as four would report a fourfold rise
     * every month.
     */
    fun trends(obligations: List<BillObligation>): List<BillTrend> =
        obligations
            .filter { it.amountDue != null }
            .groupBy { it.billerKey }
            .mapNotNull { (billerKey, cycles) ->
                val ordered = cycles.sortedBy { it.dueDate ?: it.issuedAt }
                val amounts = ordered.map { it.amountDue!! }
                val latest = amounts.last()
                val previous = amounts.dropLast(1).lastOrNull()
                val newest = ordered.last()

                BillTrend(
                    billerKey = billerKey,
                    billerLabel = newest.billerLabel,
                    kind = newest.kind,
                    cycles = ordered.map {
                        BillCycleAmount(it.dueDate ?: it.issuedAt, it.amountDue!!)
                    },
                    latestAmount = latest,
                    previousAmount = previous,
                    delta = previous?.let { latest - it },
                    percentageChange =
                        if (previous != null && previous > 0) ((latest - previous) / previous) * 100.0
                        else null,
                    medianAmount = median(amounts)
                )
            }
            .sortedByDescending { abs(it.delta ?: 0.0) }

    /**
     * Total billed per calendar month, oldest first.
     *
     * Bucketed by due date where there is one, because that is the month the money has
     * to leave — a statement issued on 28 July and payable on 15 August belongs to
     * August's outgoings, not July's.
     */
    fun monthlyTotals(obligations: List<BillObligation>): List<BillMonth> =
        obligations
            .filter { it.amountDue != null }
            .groupBy { monthStartOf(it.dueDate ?: it.issuedAt) }
            .map { (monthStart, inMonth) ->
                val calendar = Calendar.getInstance().apply { timeInMillis = monthStart }
                BillMonth(
                    year = calendar.get(Calendar.YEAR),
                    month = calendar.get(Calendar.MONTH),
                    monthStart = monthStart,
                    total = inMonth.sumOf { it.amountDue!! },
                    billerCount = inMonth.map { it.billerKey }.distinct().size
                )
            }
            .sortedBy { it.monthStart }

    private fun monthStartOf(timestamp: Long): Long =
        Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    companion object {
        /**
         * A payment can land a day or two before the reminder that mentions it — the
         * biller's send queue is not synchronised with the user's netbanking.
         */
        const val ISSUE_LEAD_DAYS = 2L

        /** Payments clear after the due date; a late payment is still this bill's. */
        const val GRACE_DAYS = 5L

        /** How long an obligation with no stated deadline stays open for matching. */
        const val UNDATED_WINDOW_DAYS = 45L

        /**
         * Tighter than the spending side's 15%. A bill names an exact figure, so a
         * payment that is meant to clear it matches it closely; a looser band would
         * start calling a minimum-due payment a settled bill.
         */
        const val AMOUNT_TOLERANCE = 0.02
    }
}
