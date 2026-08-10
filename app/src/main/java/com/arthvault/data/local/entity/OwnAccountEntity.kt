package com.arthvault.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * An account the user has confirmed is their own.
 *
 * Moving ₹20,000 from your savings account to your current account is not spending,
 * but every bank describes it in exactly the language of a payment — "Rs 20000 debited
 * from A/c XX635" — so the parser books it as an outflow and the analytics count it.
 * The mirror is worse: the receiving leg arrives as a credit and counts as *income*.
 * A user who moves money between their own accounts twice a month sees both their
 * spending and their earnings inflated by the same amount, and neither figure can be
 * reconciled against anything.
 *
 * This is deliberately **confirmed rather than inferred**. Every [tail] the parser has
 * ever recorded in `TransactionEntity.accountTail` is, strictly, already an account of
 * the user's — banks do not text you about somebody else's account — so the set could
 * be derived with no user involvement at all. It is not, for two reasons:
 *
 *  - `accountTail` is extracted by regex, and on a message naming two accounts it takes
 *    the first. Auto-marking would let one bad extraction silently delete real spending
 *    from the totals.
 *  - Excluding a transaction from your spending is a decision about your money. The
 *    screen suggests, the user confirms, and the analytics say how much was excluded.
 *
 * @param tail the last 3–6 digits as the bank quotes them, matching `accountTail`.
 */
@Entity(tableName = "own_accounts")
data class OwnAccountEntity(
    @PrimaryKey
    val tail: String,
    /** What the user calls it — "HDFC Savings". Display only; nothing matches on it. */
    val label: String,
    val markedAt: Long = System.currentTimeMillis()
)
