package com.arthvault.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Which aspect of a transaction an adjustment restates. */
object AdjustmentField {
    const val CATEGORY = "CATEGORY"
    const val MERCHANT = "MERCHANT"
    const val AMOUNT = "AMOUNT"

    /** Removes the transaction from the ledger without removing it from the record. */
    const val VOID = "VOID"
}

object AdjustmentSource {
    /** The user changed it by hand. */
    const val USER = "USER"

    /** A merchant rule the user created was applied in bulk. */
    const val RULE = "RULE"

    /** Written by a schema migration reconstructing history it could not fully know. */
    const val MIGRATION = "MIGRATION"
}

/**
 * T3.3 — corrections as append-only adjustments.
 *
 * Before this table existed, recategorising a transaction ran an UPDATE over the
 * row: the parser's original answer was overwritten and gone. For a ledger that
 * is the wrong shape entirely — you can no longer tell what the bank said from
 * what you decided it meant, and a mistaken bulk edit is unrecoverable.
 *
 * Nothing here ever changes a transaction. The effective state of a transaction
 * is its stored row folded with its adjustments, newest wins per field, computed
 * at read time (see SmsRepository.getAllTransactions).
 *
 * Deleting is [AdjustmentField.VOID]: the transaction leaves every total and
 * every list, and its raw SMS survives for the audit trail. A genuine erase is
 * F5.2's wipe, which is a different and explicitly destructive act.
 */
@Entity(
    tableName = "adjustments",
    indices = [Index(value = ["transactionId"])]
)
data class AdjustmentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val transactionId: Long,
    /** One of [AdjustmentField]. */
    val field: String,
    /**
     * What the value was immediately before this adjustment. Null only where it
     * is genuinely unknown — a migration reconstructing a pre-v3 edit cannot
     * invent the value that edit overwrote, and saying so is better than
     * fabricating one.
     */
    val oldValue: String? = null,
    val newValue: String? = null,
    val reason: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** One of [AdjustmentSource]. */
    val source: String = AdjustmentSource.USER
)

/**
 * Small key/value store for app state that has to survive a restart but is not
 * user data — currently just the SMS inbox scan watermark.
 *
 * It lives inside the encrypted database rather than in SharedPreferences so
 * that "when did you last open this app and how far had your bank SMS got"
 * isn't sitting in a world-readable-by-root plaintext XML file next to a
 * carefully encrypted ledger.
 */
@Entity(tableName = "app_settings")
data class AppSettingEntity(
    @PrimaryKey
    val key: String,
    val value: String
) {
    companion object {
        /** Timestamp of the newest inbox message already considered. */
        const val KEY_INBOX_WATERMARK = "inbox_watermark"
    }
}
