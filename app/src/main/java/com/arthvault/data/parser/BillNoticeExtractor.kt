package com.arthvault.data.parser

import com.arthvault.data.local.entity.BillKind
import com.arthvault.data.local.entity.BillNoticeEntity
import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale

/**
 * Turns a due-reminder SMS into an obligation record.
 *
 * This runs at exactly the point `SmsParserEngine` used to `return ParseResult()` — the
 * guard that stops a bill that is *owed* being booked as a bill that was *paid*. That
 * guard is still absolutely right about the ledger and stays as it is: nothing here ever
 * produces a `TransactionEntity`, and the parse path's contract that a due reminder
 * yields no transaction is unchanged. What changes is that the message is now kept
 * instead of discarded.
 *
 * The extractor refuses rather than guesses, in the same way the query grammar does. A
 * notice with neither an amount nor a date says nothing a user could act on — "your
 * statement is ready, login to view" is the common case — and inventing a deadline for
 * it would be worse than dropping it, so it is dropped exactly as before.
 */
object BillNoticeExtractor {

    /**
     * The full sum owed. Written as "Total Amount Due of Rs X", "Total Due Rs X" and
     * "Total due Rs X" by different issuers, all of which this has to read.
     */
    private val TOTAL_DUE = Regex(
        """(?i)\b(?:total|net|outstanding)\s+(?:amount\s+|amt\s+)?due\b(?:\s+is)?(?:\s+of)?\s*""" +
            """(?:is\s+)?(?:now\s+)?[:\-]?\s*(?:Rs\.?|INR|₹)\s*([0-9,]+(?:\.[0-9]{1,2})?)"""
    )

    /**
     * Stored because the biller states it, never shown as *the* figure.
     *
     * Reading "Minimum Amount Due of Rs 140.00" as the bill when the total is ₹2,783
     * understates the obligation by twenty times, which is the single worst number this
     * feature could put on screen.
     */
    private val MIN_DUE = Regex(
        """(?i)\b(?:minimum|min)\.?\s+(?:amount\s+|amt\s+)?due\b(?:\s+of)?\s*[:\-]?\s*""" +
            """(?:Rs\.?|INR|₹)\s*([0-9,]+(?:\.[0-9]{1,2})?)"""
    )

    /** Any rupee figure, for the issuers that name a sum without labelling it. */
    private val ANY_AMOUNT = Regex("""(?i)(?:Rs\.?|INR|₹|Re\.?)\s*([0-9,]+(?:\.[0-9]{1,2})?)""")

    /**
     * Phrases that promise a deadline, most specific first.
     *
     * The tiers matter. A bare "by" is a genuine deadline marker in "Rs 140.00 **by**
     * 06-Aug-26", which is how ICICI and Amex both write it, but it is far too weak to
     * trust ahead of an explicit "Due Date". Trying the strong phrases first means the
     * weak one only ever decides messages where nothing better was said.
     */
    private val DUE_PHRASES = listOf(
        Regex("""(?i)\bdue\s*date\b[:\s]*"""),
        Regex(
            """(?i)\b(?:payable\s+by|pay\s+by|due\s+(?:by|on)|""" +
                """last\s+date\s+(?:for|of)\s+payment)\b[:\s]*"""
        ),
        Regex("""(?i)\b(?:by|before|on\s+or\s+before)\b\s*""")
    )

    /**
     * The card the notice is about, with up to three words of issuer in front of it.
     *
     * The prefix is bounded rather than open because the capture starts wherever the
     * engine can reach "Credit Card" from, which on "…payment of Rs 2,783.00 towards
     * American Express Card" means it drags the preposition in with it. Digits and
     * commas break the run, so the damage is limited to a leading word or two, and
     * [LEADING_NOISE] takes those off.
     */
    private val CARD_PHRASE = Regex(
        """(?i)\b((?:[A-Za-z][A-Za-z&'.]{1,15}\s+){0,3}(?:credit\s+card|card))\b"""
    )

    private val LEADING_NOISE = Regex(
        """(?i)^(?:dear\s+customer|customer|towards|toward|to|on|for|your|the|a|of|pay|paid|""" +
            """payment|is|by|via|using|with|against)\s+"""
    )

    /**
     * Labels that identify a product category rather than a biller. "Credit Card" is
     * true of every card ever issued and would group an ICICI bill with an Amex one.
     */
    private val GENERIC_LABELS = setOf("CARD", "CREDIT CARD", "BILL", "PAYMENT", "ACCOUNT")

    /** "Card XX7009", "A/c no 4521", "OneCard XX7001" — the substring match is wanted. */
    private val ACCOUNT_TAIL = Regex(
        """(?i)(?:a/c|acct|account|card|ending)\s*(?:no\.?\s*)?(?:xx+|x|\*+)?(\d{3,6})\b"""
    )

    /** A masked tail standing on its own, for messages that name no account word. */
    private val MASKED_TAIL = Regex("""(?i)\bx{2,}(\d{3,6})\b""")

    /** "statement for Aug 2026", "bill of Jul 2025". Never read as a due date — no day. */
    private val BILLING_PERIOD = Regex(
        """(?i)\b(?:for|of)\s+((?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*""" +
            """\.?(?:\s+'?\d{2,4})?)\b"""
    )

    private val CREDIT_CARD_WORD = Regex("""(?i)\bcredit\s+card\b""")
    private val CARD_WORD = Regex("""(?i)\bcards?\b""")
    private val UTILITY_WORD = Regex(
        """(?i)\b(?:electricity|power\s+bill|bescom|msedcl|tneb|kseb|tangedco|adani\s+electricity|""" +
            """torrent\s+power|units\s+consumed|consumer\s+(?:no|number|id)|meter\s+(?:no|reading)|""" +
            """water\s+bill|gas\s+bill|piped\s+gas)\b"""
    )
    private val TELECOM_WORD = Regex(
        """(?i)\b(?:postpaid|broadband|fibre|fiber|landline|dth|data\s+pack|""" +
            """mobile\s+bill|validity|recharge)\b"""
    )
    private val INSURANCE_WORD = Regex(
        """(?i)\b(?:insurance|policy\s*(?:no|number)|premium\s+(?:is|of|due|payable))\b"""
    )
    private val LOAN_WORD = Regex("""(?i)\b(?:loan|emi|instal?ment)\b""")

    /**
     * @param cleanBody the newline-collapsed body the parser matches against.
     * @param rawBody stored verbatim, so the source message survives exactly as received.
     * @return null when the message names neither a sum nor a deadline, which reproduces
     *   the previous behaviour of dropping it.
     */
    fun extract(
        sender: String,
        rawBody: String,
        cleanBody: String,
        timestamp: Long
    ): BillNoticeEntity? {
        val minAmount = MIN_DUE.find(cleanBody)?.let { amountOf(it.groupValues[1]) }
        val totalAmount = TOTAL_DUE.find(cleanBody)?.let { amountOf(it.groupValues[1]) }

        // The unlabelled fallback must not pick up a minimum-due figure as the total.
        // "Pay Total Amount Due of Rs 2,783.00 or Minimum Amount Due of Rs 140.00" names
        // both, and the first rupee figure in a message is not reliably the larger one.
        val amountDue = totalAmount ?: firstUnlabelledAmount(cleanBody, minAmount)

        val dueDate = findDueDate(cleanBody, timestamp)

        // Nothing to act on and nothing to show. "Your statement is ready. Login to
        // view." is a notification about a notification.
        if (amountDue == null && dueDate == null) return null

        val accountTail = ACCOUNT_TAIL.find(cleanBody)?.groupValues?.get(1)
            ?: MASKED_TAIL.find(cleanBody)?.groupValues?.get(1)

        val label = resolveBillerLabel(cleanBody, sender)
        val billerKey = normaliseKey(label)

        return BillNoticeEntity(
            billerKey = billerKey,
            billerLabel = label,
            kind = detectKind(cleanBody),
            accountTail = accountTail,
            amountDue = amountDue,
            minAmountDue = minAmount,
            dueDate = dueDate,
            billingPeriodLabel = BILLING_PERIOD.find(cleanBody)?.groupValues?.get(1)?.trim(),
            issuedAt = timestamp,
            sender = sender,
            rawMessage = rawBody,
            noticeHash = sha256("$sender|$cleanBody|${timestamp / MILLIS_PER_DAY}"),
            cycleKey = cycleKey(billerKey, accountTail, dueDate, timestamp)
        )
    }

    private fun amountOf(raw: String): Double? =
        raw.replace(",", "").toDoubleOrNull()?.takeIf { it > 0 }

    /** The first rupee figure that is not the one [MIN_DUE] already claimed. */
    private fun firstUnlabelledAmount(text: String, minAmount: Double?): Double? =
        ANY_AMOUNT.findAll(text)
            .mapNotNull { amountOf(it.groupValues[1]) }
            .firstOrNull { minAmount == null || it != minAmount }

    private fun findDueDate(text: String, reference: Long): Long? {
        for (phrase in DUE_PHRASES) {
            for (match in phrase.findAll(text)) {
                BillDateParser.findNear(text, match.range.last + 1, reference)?.let { return it }
            }
        }

        // A message that carries exactly one day-precise date, in a body already known
        // to be a due reminder, has said when. More than one and there is nothing to
        // choose between them, so nothing is chosen.
        return BillDateParser.findAll(text, reference).singleOrNull()?.millis
    }

    private fun resolveBillerLabel(text: String, sender: String): String {
        val captured = CARD_PHRASE.find(text)?.groupValues?.get(1)?.trim()
        if (captured != null) {
            // Looped because the bounded prefix can carry more than one noise word,
            // as in "Dear Customer, payment of … towards American Express Card".
            var cleaned: String = captured
            var stripped = LEADING_NOISE.replace(cleaned, "")
            while (stripped != cleaned) {
                cleaned = stripped
                stripped = LEADING_NOISE.replace(cleaned, "")
            }
            cleaned = cleaned.trim()
            if (cleaned.isNotBlank() && cleaned.uppercase(Locale.ROOT) !in GENERIC_LABELS) {
                return cleaned
            }
        }

        // An aggregator like CRED tells you a card bill is due without saying whose
        // card it is. Naming the messenger is honest; naming a guessed issuer is not.
        return SenderMatcher.normalize(sender)
    }

    private fun detectKind(text: String): String = when {
        CREDIT_CARD_WORD.containsMatchIn(text) -> BillKind.CARD
        UTILITY_WORD.containsMatchIn(text) -> BillKind.UTILITY
        TELECOM_WORD.containsMatchIn(text) -> BillKind.TELECOM
        INSURANCE_WORD.containsMatchIn(text) -> BillKind.INSURANCE
        LOAN_WORD.containsMatchIn(text) -> BillKind.LOAN
        CARD_WORD.containsMatchIn(text) -> BillKind.CARD
        else -> BillKind.OTHER
    }

    private fun normaliseKey(label: String): String =
        label.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")

    /**
     * Groups the several reminders a biller sends for one cycle into one obligation.
     *
     * Keyed on the due day rather than the amount on purpose: a reminder re-sent after a
     * partial payment quotes a smaller total, and that is the same bill in a new state,
     * not a second bill.
     */
    private fun cycleKey(
        billerKey: String,
        accountTail: String?,
        dueDate: Long?,
        issuedAt: Long
    ): String {
        val period = if (dueDate != null) {
            "due${dueDate / MILLIS_PER_DAY}"
        } else {
            // With no stated deadline the issue month is the best available proxy, and
            // it still collapses a re-send. It cannot collapse two undated notices that
            // straddle a month boundary, which is the honest limit of knowing nothing.
            val calendar = Calendar.getInstance().apply { timeInMillis = issuedAt }
            "issued${calendar.get(Calendar.YEAR)}-${calendar.get(Calendar.MONTH)}"
        }
        return "$billerKey|${accountTail ?: "-"}|$period"
    }

    private const val MILLIS_PER_DAY = 86_400_000L

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
