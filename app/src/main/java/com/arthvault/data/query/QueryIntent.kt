package com.arthvault.data.query

/** What the question is asking for. */
enum class QueryMetric {
    /** Sum of the matching amounts. The default when nothing else is asked for. */
    TOTAL,
    AVERAGE,
    COUNT,
    LARGEST
}

/** Which side of the ledger. */
enum class QueryDirection { OUT, IN }

/**
 * A resolved period, with the words that produced it.
 *
 * [label] is echoed back to the user so a misread question is obvious: asking for
 * "last quarter" and being shown "this month" is only detectable if the app says
 * which one it used.
 */
data class QueryPeriod(val range: LongRange, val label: String)

/**
 * What the app understood a question to mean (F4.1).
 *
 * Deliberately small and closed. F4.2 forbids an LLM anywhere near the numbers, and
 * a grammar that tries to accept everything ends up guessing — which, for a question
 * about money, is worse than saying "I did not understand that".
 */
data class QueryIntent(
    val metric: QueryMetric,
    val direction: QueryDirection,
    val category: String?,
    val merchant: String?,
    val period: QueryPeriod,
    /** Plain-language restatement of every field above, for confirmation on screen. */
    val interpretation: String
)

/**
 * An answer, with the rows it came from (F4.4).
 *
 * [transactionIds] is the whole point: every figure the app shows must be traceable
 * to the transactions behind it, so a number that looks wrong can be opened and
 * checked rather than argued with.
 */
data class QueryResult(
    val intent: QueryIntent,
    val value: Double,
    val matchCount: Int,
    val transactionIds: List<Long>
)
