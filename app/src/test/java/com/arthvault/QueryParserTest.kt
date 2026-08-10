package com.arthvault

import com.arthvault.data.local.entity.STATUS_FAILED
import com.arthvault.data.local.entity.STATUS_POSTED
import com.arthvault.data.local.entity.TransactionEntity
import com.arthvault.data.local.entity.TxnType
import com.arthvault.data.query.LedgerQueryEngine
import com.arthvault.data.query.QueryDirection
import com.arthvault.data.query.QueryMetric
import com.arthvault.data.query.QueryParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * F4.1 / F4.2 / F4.4 — the query grammar and the arithmetic behind it.
 *
 * Two properties matter more than breadth of vocabulary: the same question always
 * gives the same answer, and every answer names the rows it came from. A grammar
 * that accepts more phrasings but occasionally guesses would be a downgrade.
 */
class QueryParserTest {

    private val categories = listOf(
        "Food & Dining", "Transport & Fuel", "Grocery", "Shopping",
        "Utilities & Bills", "Entertainment & Subs", "Income & Refunds"
    )
    private val parser = QueryParser(categories)
    private val engine = LedgerQueryEngine()

    private val day = 86_400_000L

    /** Fixed instant so period arithmetic is reproducible: 2026-08-10, mid-month. */
    private val now = Calendar.getInstance().apply {
        set(2026, Calendar.AUGUST, 10, 12, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

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
        hash = "h$id"
    )

    // --- grammar ----------------------------------------------------------

    @Test
    fun `spend on fuel last quarter resolves every field`() {
        val intent = parser.parse("spend on fuel last quarter", now)!!

        assertEquals(QueryMetric.TOTAL, intent.metric)
        assertEquals(QueryDirection.OUT, intent.direction)
        assertEquals("Transport & Fuel", intent.category)
        assertEquals("last quarter", intent.period.label)
    }

    @Test
    fun `an alias only resolves to a category the user actually has`() {
        // "medical" maps to Health & Medical, which this database does not contain.
        // Answering "Health & Medical: 0" would be a wrong answer wearing the
        // costume of a right one.
        assertNull(QueryParser(categories).parse("spend on medical", now)?.category)
    }

    @Test
    fun `income questions read the other side of the ledger`() {
        val intent = parser.parse("how much did I earn last month", now)!!
        assertEquals(QueryDirection.IN, intent.direction)
        assertEquals("last month", intent.period.label)
    }

    @Test
    fun `counting and averaging are distinguished from totalling`() {
        assertEquals(QueryMetric.COUNT, parser.parse("how many times did I spend on food", now)!!.metric)
        assertEquals(QueryMetric.AVERAGE, parser.parse("average spend on groceries", now)!!.metric)
        assertEquals(QueryMetric.LARGEST, parser.parse("biggest spend this year", now)!!.metric)
    }

    @Test
    fun `a merchant is taken from after its preposition, without the period`() {
        val intent = parser.parse("how much did I spend at swiggy last month", now)!!
        assertEquals("swiggy", intent.merchant)
        assertEquals("last month", intent.period.label)
    }

    @Test
    fun `a question with no period defaults to this month and says so`() {
        val intent = parser.parse("spend on fuel", now)!!
        assertEquals("this month", intent.period.label)
        assertTrue(
            "the assumed period has to be visible to be correctable",
            intent.interpretation.contains("this month")
        )
    }

    @Test
    fun `a question it cannot read is refused rather than guessed at`() {
        // For a question about money, "I did not understand" beats a confident
        // number answering something else.
        assertNull(parser.parse("how about that", now))
        assertNull(parser.parse("last tuesday", now))
        assertNull(parser.parse("", now))
    }

    @Test
    fun `relative windows are parsed with their unit`() {
        assertEquals("the last 90 days", parser.parse("spend in the last 90 days", now)!!.period.label)
        assertEquals("the last 3 months", parser.parse("spend past 3 months", now)!!.period.label)
    }

    @Test
    fun `the same question always parses the same way`() {
        // F4.2 / T4.5 — determinism is the property that makes the number citable.
        val a = parser.parse("total spend on fuel last quarter", now)!!
        val b = parser.parse("total spend on fuel last quarter", now)!!
        assertEquals(a, b)
    }

    // --- arithmetic -------------------------------------------------------

    @Test
    fun `a total carries the transactions behind it`() {
        val txns = listOf(
            txn(1000.0, "SHELL", "Transport & Fuel", daysAgo = 2, id = 1),
            txn(500.0, "IOCL", "Transport & Fuel", daysAgo = 5, id = 2),
            txn(900.0, "SWIGGY", "Food & Dining", daysAgo = 3, id = 3)
        )
        val result = engine.run(parser.parse("spend on fuel", now)!!, txns)

        assertEquals(1500.0, result.value, 0.001)
        assertEquals(2, result.matchCount)
        assertEquals(listOf(1L, 2L), result.transactionIds.sorted())
    }

    @Test
    fun `declined attempts never reach a query answer`() {
        val txns = listOf(
            txn(1000.0, "SHELL", "Transport & Fuel", daysAgo = 2, id = 1),
            txn(9000.0, "SHELL", "Transport & Fuel", daysAgo = 2, status = STATUS_FAILED, id = 2)
        )
        val result = engine.run(parser.parse("spend on fuel", now)!!, txns)

        assertEquals(1000.0, result.value, 0.001)
        assertEquals(listOf(1L), result.transactionIds)
    }

    @Test
    fun `a refund is not income`() {
        // The same rule the dashboard uses. A query that disagreed with the forecast
        // about what counts as income would undermine both.
        val txns = listOf(
            txn(50000.0, "SALARY", direction = "CREDIT", txnType = TxnType.INCOME, daysAgo = 5, id = 1),
            txn(4000.0, "AMAZON", direction = "CREDIT", txnType = TxnType.REFUND, daysAgo = 3, id = 2)
        )
        val result = engine.run(parser.parse("how much did I earn", now)!!, txns)

        assertEquals(50000.0, result.value, 0.001)
        assertEquals(listOf(1L), result.transactionIds)
    }

    @Test
    fun `a card bill payment is neither income nor spending`() {
        // Both legs, because the query engine is a second definition of income and
        // the analytics fix does not reach it.
        val txns = listOf(
            txn(50000.0, "SALARY", direction = "CREDIT", txnType = TxnType.INCOME, daysAgo = 5, id = 1),
            txn(12400.0, "ICICI Bank Credit", direction = "CREDIT",
                txnType = TxnType.CARD_PAYMENT, daysAgo = 3, id = 2),
            txn(900.0, "SWIGGY", "Food & Dining", daysAgo = 2, id = 3),
            txn(12400.0, "ICICI CREDIT CARD", "Transfers",
                txnType = TxnType.CARD_PAYMENT, daysAgo = 2, id = 4)
        )

        val earned = engine.run(parser.parse("how much did I earn", now)!!, txns)
        assertEquals(50000.0, earned.value, 0.001)
        assertEquals(listOf(1L), earned.transactionIds)

        val spent = engine.run(parser.parse("how much did I spend", now)!!, txns)
        assertEquals(900.0, spent.value, 0.001)
        assertEquals(listOf(3L), spent.transactionIds)
    }

    @Test
    fun `largest returns only the winning row as evidence`() {
        val txns = listOf(
            txn(1000.0, "SHELL", "Transport & Fuel", daysAgo = 2, id = 1),
            txn(2500.0, "IOCL", "Transport & Fuel", daysAgo = 5, id = 2),
            txn(300.0, "HPCL", "Transport & Fuel", daysAgo = 6, id = 3)
        )
        val result = engine.run(parser.parse("biggest spend on fuel", now)!!, txns)

        assertEquals(2500.0, result.value, 0.001)
        assertEquals(listOf(2L), result.transactionIds)
    }

    @Test
    fun `a period actually excludes what falls outside it`() {
        val txns = listOf(
            txn(1000.0, "SHELL", "Transport & Fuel", daysAgo = 2, id = 1),
            txn(7000.0, "SHELL", "Transport & Fuel", daysAgo = 200, id = 2)
        )
        val result = engine.run(parser.parse("spend on fuel this month", now)!!, txns)

        assertEquals(1000.0, result.value, 0.001)
        assertEquals(1, result.matchCount)
    }

    @Test
    fun `a merchant query matches on a substring of the stored name`() {
        val txns = listOf(
            txn(420.0, "SWIGGY INSTAMART", "Grocery", daysAgo = 1, id = 1),
            txn(380.0, "swiggy", "Food & Dining", daysAgo = 2, id = 2),
            txn(900.0, "ZOMATO", "Food & Dining", daysAgo = 2, id = 3)
        )
        val result = engine.run(parser.parse("spend at swiggy", now)!!, txns)

        assertEquals(800.0, result.value, 0.001)
        assertEquals(listOf(1L, 2L), result.transactionIds.sorted())
    }

    @Test
    fun `a question matching nothing answers zero rather than failing`() {
        val result = engine.run(parser.parse("spend on fuel", now)!!, emptyList())

        assertNotNull(result)
        assertEquals(0.0, result.value, 0.001)
        assertEquals(0, result.matchCount)
        assertTrue(result.transactionIds.isEmpty())
    }
}
