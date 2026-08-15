package com.arthvault.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * What kind of obligation a notice describes.
 *
 * Only [CARD] is evidenced by the test corpus. The others are implemented because the
 * patterns are cheap and the alternative is filing a BESCOM bill under "OTHER", but
 * they are unvalidated against real messages — see the Phase 9 note in ROADMAP.md.
 */
object BillKind {
    /** A credit card statement. The one shape the corpus actually covers. */
    const val CARD = "CARD"

    /** Electricity, water, gas, piped utilities. */
    const val UTILITY = "UTILITY"

    /** Postpaid mobile, broadband, DTH. */
    const val TELECOM = "TELECOM"

    const val INSURANCE = "INSURANCE"

    /** A loan instalment falling due — distinct from an EMI that has already been taken. */
    const val LOAN = "LOAN"

    const val OTHER = "OTHER"
}

/**
 * A bill that is *owed* — the message `SmsParserEngine.DUE_REMINDER` used to drop.
 *
 * **This is deliberately not a transaction, and must never become one.** Every
 * aggregate in `FinanceAnalyticsEngine` runs through `postedDebits`/`postedCredits`,
 * both of which mean "money moved". A ₹24,300 statement balance entering there is the
 * exact bug the due-reminder guard was written to kill: the same message was booked as
 * ₹2,783 of spend under one wording and ₹2,783 of *income* under another. Worse, a card
 * statement's underlying purchases are already in the ledger, so counting the statement
 * as well counts the same rupees twice — the reasoning that produced
 * [TxnType.CARD_PAYMENT].
 *
 * A notice is what the biller said, and like a transaction it is never edited. Billers
 * re-send a reminder three or four times a cycle, and the amount can legitimately change
 * between sends when a partial payment lands. Those are separate true statements made at
 * different times, so they are separate rows; [cycleKey] groups them and the newest wins
 * at read time. That is the same split the transactions table already makes between
 * [TransactionEntity.hash] and [TransactionEntity.txnHash].
 */
@Entity(
    tableName = "bill_notices",
    indices = [
        Index(value = ["noticeHash"], unique = true),
        Index(value = ["cycleKey"]),
        Index(value = ["dueDate"])
    ]
)
data class BillNoticeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Normalised biller identity, for grouping across cycles. Uppercase, punctuation-free. */
    val billerKey: String,
    /** What to show on screen, in the biller's own words. */
    val billerLabel: String,
    /** One of [BillKind]. */
    val kind: String,
    val accountTail: String?,
    /**
     * The full amount owed, or null when the notice quotes none — a prepaid validity
     * reminder names a date and no money, and a "your statement is ready" SMS may name
     * neither. Nothing is invented to fill this in.
     */
    val amountDue: Double?,
    /** Stored because it is stated, never shown as *the* figure. Missing it understates
     *  a card bill by roughly twenty times. */
    val minAmountDue: Double?,
    /** Start of the due day in local time, or null when the notice states no date. */
    val dueDate: Long?,
    /** e.g. "Aug 2026" — the period billed, when the message names one. */
    val billingPeriodLabel: String? = null,
    /** When the notice arrived. The SMS envelope timestamp, not a parsed date. */
    val issuedAt: Long,
    val sender: String,
    val rawMessage: String,
    /**
     * Identity of the *message*: sender + body + day. Collapses the identical re-sends
     * a biller makes across a cycle.
     */
    val noticeHash: String,
    /**
     * Identity of the *obligation*: biller + tail + due day. Two notices quoting
     * different outstanding amounts for one cycle share this, which is what lets the
     * read path show the latest rather than both.
     */
    val cycleKey: String
)
