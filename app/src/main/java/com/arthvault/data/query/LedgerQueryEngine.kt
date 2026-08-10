package com.arthvault.data.query

import com.arthvault.data.local.entity.STATUS_POSTED
import com.arthvault.data.local.entity.TransactionEntity
import com.arthvault.data.local.entity.TxnType

/**
 * F4.1 / F4.2 / F4.4 — computes the answer to a parsed question.
 *
 * Every figure here is arithmetic over rows the caller can open. Nothing is
 * estimated, nothing is generated, and the contributing transaction ids travel with
 * the number so the screen can offer tap-through rather than asking to be believed.
 *
 * The filtering deliberately mirrors [com.arthvault.data.analytics.FinanceAnalyticsEngine]:
 * declined attempts are excluded, and refunds are netted off spending rather than
 * counted as income. A query that disagreed with the dashboard about what "spent"
 * means would be worse than no query feature at all.
 */
class LedgerQueryEngine {

    fun run(intent: QueryIntent, transactions: List<TransactionEntity>): QueryResult {
        val matches = transactions
            .filter { it.status == STATUS_POSTED }
            .filter { it.timestamp in intent.period.range }
            .filter { matchesDirection(it, intent.direction) }
            .filter { intent.category == null || it.category.equals(intent.category, true) }
            .filter { intent.merchant == null || it.merchant.contains(intent.merchant, true) }

        val amounts = matches.map { it.amount }
        val value = when (intent.metric) {
            QueryMetric.TOTAL -> amounts.sum()
            QueryMetric.COUNT -> matches.size.toDouble()
            QueryMetric.AVERAGE -> if (amounts.isEmpty()) 0.0 else amounts.average()
            QueryMetric.LARGEST -> amounts.maxOrNull() ?: 0.0
        }

        // For "largest", only the winning row is evidence. Returning every match
        // would send the user to a list in which the answer is one row among many.
        val contributing = if (intent.metric == QueryMetric.LARGEST) {
            listOfNotNull(matches.maxByOrNull { it.amount })
        } else {
            matches
        }

        return QueryResult(
            intent = intent,
            value = value,
            matchCount = matches.size,
            transactionIds = contributing.map { it.id }
        )
    }

    /**
     * A refund is a credit, but it is money coming back from a purchase rather than
     * income — counting it as income made a ₹4,000 refund read like salary. It is
     * excluded from both sides here: it is not income, and it is not spending
     * either.
     */
    private fun matchesDirection(
        transaction: TransactionEntity,
        direction: QueryDirection
    ): Boolean = when (direction) {
        QueryDirection.OUT -> transaction.direction == "DEBIT"
        QueryDirection.IN ->
            transaction.direction == "CREDIT" && transaction.txnType != TxnType.REFUND
    }
}
