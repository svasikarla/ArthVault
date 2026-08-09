package com.example

import com.example.data.local.entity.AdjustmentEntity
import com.example.data.local.entity.AdjustmentField
import com.example.data.local.entity.TransactionEntity
import com.example.data.repository.AdjustmentFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T3.3 — the read side of immutability. The stored rows below are never modified
 * by any of these cases; only the folded result changes.
 */
class AdjustmentFolderTest {

    private fun txn(id: Long, merchant: String, category: String, amount: Double = 100.0) =
        TransactionEntity(
            id = id,
            amount = amount,
            direction = "DEBIT",
            timestamp = 1_700_000_000_000L,
            sender = "AD-HDFCBK-S",
            merchant = merchant,
            accountTail = "8901",
            channel = "UPI",
            category = category,
            rawMessage = "",
            hash = "h$id"
        )

    private fun adjustment(
        txnId: Long,
        field: String,
        newValue: String?,
        at: Long,
        oldValue: String? = null
    ) = AdjustmentEntity(
        transactionId = txnId,
        field = field,
        oldValue = oldValue,
        newValue = newValue,
        createdAt = at
    )

    @Test
    fun `with no adjustments the ledger is the stored rows`() {
        val txns = listOf(txn(1, "SWIGGY", "Food & Dining"))
        assertEquals(txns, AdjustmentFolder.apply(txns, emptyList()))
    }

    @Test
    fun `a category adjustment changes the read value and not the row`() {
        val stored = txn(1, "SWIGGY", "Other / Misc")
        val folded = AdjustmentFolder.apply(
            listOf(stored),
            listOf(adjustment(1, AdjustmentField.CATEGORY, "Food & Dining", at = 10))
        )

        assertEquals("Food & Dining", folded.single().category)
        assertEquals("the stored row must be untouched", "Other / Misc", stored.category)
    }

    @Test
    fun `the newest adjustment for a field wins`() {
        val folded = AdjustmentFolder.apply(
            listOf(txn(1, "SWIGGY", "Other / Misc")),
            listOf(
                adjustment(1, AdjustmentField.CATEGORY, "Groceries", at = 10),
                adjustment(1, AdjustmentField.CATEGORY, "Food & Dining", at = 20)
            )
        )
        assertEquals("Food & Dining", folded.single().category)
    }

    @Test
    fun `adjustments arriving out of order still resolve by timestamp`() {
        val folded = AdjustmentFolder.apply(
            listOf(txn(1, "SWIGGY", "Other / Misc")),
            listOf(
                adjustment(1, AdjustmentField.CATEGORY, "Food & Dining", at = 20),
                adjustment(1, AdjustmentField.CATEGORY, "Groceries", at = 10)
            )
        )
        assertEquals("Food & Dining", folded.single().category)
    }

    @Test
    fun `different fields do not overwrite each other`() {
        val folded = AdjustmentFolder.apply(
            listOf(txn(1, "SWGY*IN", "Other / Misc", amount = 100.0)),
            listOf(
                adjustment(1, AdjustmentField.CATEGORY, "Food & Dining", at = 10),
                adjustment(1, AdjustmentField.MERCHANT, "SWIGGY", at = 11),
                adjustment(1, AdjustmentField.AMOUNT, "420.5", at = 12)
            )
        ).single()

        assertEquals("Food & Dining", folded.category)
        assertEquals("SWIGGY", folded.merchant)
        assertEquals(420.5, folded.amount, 0.001)
    }

    @Test
    fun `a voided transaction leaves the ledger entirely`() {
        val folded = AdjustmentFolder.apply(
            listOf(txn(1, "SWIGGY", "Food & Dining"), txn(2, "AMAZON", "Shopping")),
            listOf(adjustment(1, AdjustmentField.VOID, null, at = 10))
        )
        assertEquals(listOf("AMAZON"), folded.map { it.merchant })
    }

    @Test
    fun `a void wins even when a later edit exists`() {
        val folded = AdjustmentFolder.apply(
            listOf(txn(1, "SWIGGY", "Food & Dining")),
            listOf(
                adjustment(1, AdjustmentField.VOID, null, at = 10),
                adjustment(1, AdjustmentField.CATEGORY, "Groceries", at = 99)
            )
        )
        assertTrue(folded.isEmpty())
    }

    @Test
    fun `an unparseable amount is ignored rather than zeroing the transaction`() {
        val folded = AdjustmentFolder.apply(
            listOf(txn(1, "SWIGGY", "Food & Dining", amount = 420.0)),
            listOf(adjustment(1, AdjustmentField.AMOUNT, "four hundred", at = 10))
        )
        assertEquals(420.0, folded.single().amount, 0.001)
    }

    @Test
    fun `adjustments for one transaction do not touch another`() {
        val folded = AdjustmentFolder.apply(
            listOf(txn(1, "SWIGGY", "Other / Misc"), txn(2, "ZOMATO", "Other / Misc")),
            listOf(adjustment(1, AdjustmentField.CATEGORY, "Food & Dining", at = 10))
        )
        assertEquals("Other / Misc", folded.first { it.id == 2L }.category)
    }

    @Test
    fun `voided ids are reported separately for the audit view`() {
        val adjustments = listOf(
            adjustment(1, AdjustmentField.VOID, null, at = 10),
            adjustment(2, AdjustmentField.CATEGORY, "Shopping", at = 11)
        )
        assertEquals(setOf(1L), AdjustmentFolder.voidedIds(adjustments))
        assertEquals(setOf(2L), AdjustmentFolder.adjustedIds(adjustments))
    }
}
