package com.arthvault.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A transaction that actually settled. */
const val STATUS_POSTED = "POSTED"

/** A declined / failed / reversed attempt. Kept for the audit trail, excluded from every total. */
const val STATUS_FAILED = "FAILED"

object TxnType {
    const val PURCHASE = "PURCHASE"
    const val EMI = "EMI"
    const val REFUND = "REFUND"
    const val TRANSFER = "TRANSFER"
    const val ATM = "ATM"
    const val FEE = "FEE"
    const val INCOME = "INCOME"
}

/**
 * T3.3 — a transaction is what the bank said, and it is never edited.
 *
 * There is deliberately no `isUserModified`, `isFlagged` or `flagReason` here.
 * The first was a mutation marker for edits that overwrote the parser's answer;
 * the other two were written by a flagging pass that no longer exists, since
 * anomalies and duplicates are computed on demand. Corrections now live in
 * [AdjustmentEntity], and the DAO exposes no UPDATE or DELETE for this table.
 */
@Entity(
    tableName = "transactions",
    indices = [
        // F1.6 — with a unique index the IGNORE conflict strategy actually fires,
        // which closes the read-then-insert race between the SMS receiver and a
        // concurrent inbox scan.
        Index(value = ["hash"], unique = true),
        Index(value = ["txnHash"]),
        Index(value = ["timestamp"])
    ]
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val amount: Double,
    val direction: String, // "DEBIT" or "CREDIT"
    val timestamp: Long,
    val sender: String,
    val merchant: String,
    val accountTail: String?,
    val channel: String?, // e.g. "UPI", "Card", "ATM", "NetBanking", "Cash"
    val category: String,
    val rawMessage: String,
    /** Closing/available balance quoted in the message, when present (F1.3). */
    val balanceAfter: Double? = null,
    /** [STATUS_POSTED] or [STATUS_FAILED] — declined attempts must not count as spend. */
    val status: String = STATUS_POSTED,
    /** One of [TxnType]. Drives refund/EMI handling in the forecast. */
    val txnType: String = TxnType.PURCHASE,
    /** Identity of the *message*: sender + body + minute. Exact-duplicate suppression. */
    val hash: String,
    /**
     * Identity of the *transaction*: amount + direction + merchant + tail + day.
     * Two sources describing one payment share this even though their text differs,
     * which is what F1.6 needs for cross-source dedup.
     */
    val txnHash: String = ""
)

@Entity(tableName = "unparsed_sms")
data class UnparsedSmsEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sender: String,
    val rawMessage: String,
    val timestamp: Long,
    val failureReason: String = "No pattern matched",
    val isReviewed: Boolean = false
)

/**
 * F1.1 — the set of senders whose messages are read at all.
 *
 * [senderId] is the normalised entity code (see SenderMatcher), e.g. "HDFCBK" for
 * a DLT header like "AD-HDFCBK-S".
 */
@Entity(tableName = "sender_allowlist")
data class SenderAllowlistEntity(
    @PrimaryKey
    val senderId: String,
    val label: String,
    val isEnabled: Boolean = true
)
