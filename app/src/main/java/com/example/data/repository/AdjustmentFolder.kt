package com.example.data.repository

import com.example.data.local.entity.AdjustmentEntity
import com.example.data.local.entity.AdjustmentField
import com.example.data.local.entity.TransactionEntity

/**
 * T3.3 — folds append-only corrections over immutable rows.
 *
 * Kept as a pure function rather than a Room `@DatabaseView` deliberately. The
 * SQL equivalent is a correlated subquery per field picking the newest row, which
 * is both hard to read and awkward to test, and everything downstream — the
 * analytics engine especially — already works on in-memory lists.
 *
 * Rules:
 *  - newest adjustment wins, per field;
 *  - a VOID at any point removes the transaction from the ledger, whatever else
 *    was recorded before or after it;
 *  - an adjustment with an unparseable amount is ignored rather than allowed to
 *    zero out a transaction.
 */
object AdjustmentFolder {

    fun apply(
        transactions: List<TransactionEntity>,
        adjustments: List<AdjustmentEntity>
    ): List<TransactionEntity> {
        if (adjustments.isEmpty()) return transactions

        val byTransaction = adjustments.groupBy { it.transactionId }
        return transactions.mapNotNull { txn ->
            val forThis = byTransaction[txn.id] ?: return@mapNotNull txn
            if (forThis.any { it.field == AdjustmentField.VOID }) return@mapNotNull null

            var result = txn
            // Oldest first so the newest write lands last.
            forThis.sortedBy { it.createdAt }.forEach { adjustment ->
                result = when (adjustment.field) {
                    AdjustmentField.CATEGORY ->
                        adjustment.newValue?.let { result.copy(category = it) } ?: result
                    AdjustmentField.MERCHANT ->
                        adjustment.newValue?.let { result.copy(merchant = it) } ?: result
                    AdjustmentField.AMOUNT ->
                        adjustment.newValue?.toDoubleOrNull()?.let { result.copy(amount = it) } ?: result
                    else -> result
                }
            }
            result
        }
    }

    /** Ids the user has corrected — drives the "edited" marker in the ledger. */
    fun adjustedIds(adjustments: List<AdjustmentEntity>): Set<Long> =
        adjustments.filter { it.field != AdjustmentField.VOID }
            .map { it.transactionId }
            .toSet()

    fun voidedIds(adjustments: List<AdjustmentEntity>): Set<Long> =
        adjustments.filter { it.field == AdjustmentField.VOID }
            .map { it.transactionId }
            .toSet()
}
