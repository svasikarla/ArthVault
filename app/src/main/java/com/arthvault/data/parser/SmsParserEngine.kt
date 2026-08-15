package com.arthvault.data.parser

import com.arthvault.data.local.entity.BillNoticeEntity
import com.arthvault.data.local.entity.MerchantRuleEntity
import com.arthvault.data.local.entity.ParserRuleEntity
import com.arthvault.data.local.entity.STATUS_FAILED
import com.arthvault.data.local.entity.STATUS_POSTED
import com.arthvault.data.local.entity.TransactionEntity
import com.arthvault.data.local.entity.TxnType
import com.arthvault.data.local.entity.UnparsedSmsEntity
import java.security.MessageDigest
import java.util.Locale
import java.util.regex.Pattern

/**
 * Three arms, and at most one of them is ever populated.
 *
 * [billNotice] is the newest and is *not* a weaker kind of transaction. A due reminder
 * still yields `parsedTransaction = null`, exactly as it did when the message was
 * dropped outright — money owed is not money moved, and the guard that keeps the two
 * apart is what stopped a card statement being booked as ₹2,783 of spend under one
 * wording and ₹2,783 of income under another.
 */
data class ParseResult(
    val parsedTransaction: TransactionEntity? = null,
    val unparsedSms: UnparsedSmsEntity? = null,
    val billNotice: BillNoticeEntity? = null
)

class SmsParserEngine {

    companion object {
        val GENERIC_MERCHANTS = setOf(
            "UNKNOWN MERCHANT", "UNKNOWN", "UPI", "PAYMENT", "PAY", "TRANSFER",
            "DEBIT", "CREDIT", "BANK", "OTHER", "N/A", "NA", "NULL"
        )

        val KNOWN_BANK_NAMES = setOf(
            "ICICI BANK", "HDFC BANK", "AXIS BANK", "SBI", "STATE BANK OF INDIA",
            "KOTAK BANK", "KOTAK MAHINDRA BANK", "YES BANK", "INDUSIND BANK",
            "PNB", "PUNJAB NATIONAL BANK", "BOB", "BANK OF BARODA", "CANARA BANK",
            "IDFC FIRST BANK", "HSBC", "CITIBANK", "STANDARD CHARTERED", "ICICI", "HDFC",
            "CARD", "CREDIT CARD", "DEBIT CARD", "BANK"
        )

        fun isGenericOrUnsafeMerchant(merchant: String): Boolean {
            val upper = merchant.trim().uppercase(Locale.ROOT)
            return upper.isBlank() || upper.length < 3 || upper in GENERIC_MERCHANTS
        }

        /**
         * True when the string names the issuer rather than the payee.
         *
         * "using ICICI Bank" arrives here because the `amount-verb-payee` rule captures
         * the whole "spent **using ICICI Bank Card XX6013**" clause, and stripping the
         * card reference leaves the preposition attached to the bank.
         */
        fun isBankNameOnly(merchant: String): Boolean {
            val upper = merchant.trim().uppercase(Locale.ROOT)
            val withoutPreposition = upper.removePrefix("USING ").removePrefix("WITH ").trim()
            return upper in KNOWN_BANK_NAMES || withoutPreposition in KNOWN_BANK_NAMES
        }

        /**
         * True when a merchant string must never be generalised into a stored rule or
         * matched against message bodies — it identifies a bank, a payment rail or
         * nothing at all, so any pattern built from it sweeps up unrelated spend.
         */
        fun isUnsafeAsRulePattern(merchant: String): Boolean =
            isGenericOrUnsafeMerchant(merchant) || isBankNameOnly(merchant)


        /** F1.3 — closing/available balance, when the bank quotes one. */
        private val BALANCE = Pattern.compile(
            "(?i)(?:avl|avlbl|avail(?:able)?|clsg|closing|updated|a/c)?\\s*" +
                "bal(?:ance)?(?:\\s+is)?[:\\s.]*\\s*(?:Rs\\.?|INR|₹)?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)"
        )

        /**
         * F1.2 — a declined attempt matches the ordinary amount patterns perfectly,
         * so without this check every failed transaction is stored as real spend and
         * inflates every total.
         */
        private val NEGATED = Regex(
            "(?i)\\b(declined|denied|failed|unsuccessful|rejected|reversed|cancell?ed|" +
                "insufficient|could\\s+not\\s+be|not\\s+(?:processed|completed|successful)|" +
                "has\\s+been\\s+reversed)\\b"
        )

        /**
         * Every "<account> <direction>" leg named in the message, in order.
         *
         * A bank-to-bank transfer alert names both sides — "Acct XX635 debited ... &
         * Acct XX066 credited" — which is why a plain substring search for "credited"
         * gets the sign backwards, and why the other leg is the closest thing the
         * message has to a counterparty.
         */
        private val ACCOUNT_LEG = Pattern.compile(
            "(?i)(?:a/c|acct|account)\\s*(?:no\\.?)?\\s*(?:xx+|\\*+)?([0-9]{3,6})\\s*" +
                "(?:is|has\\s+been|was)?\\s*(credited|debited)"
        )

        private val CREDIT_WORDS = listOf("credited", "received", "refunded", "deposited", "added")
        private val DEBIT_WORDS =
            listOf("debited", "spent", "paid", "withdrawn", "deducted", "sent", "charged")

        private val CURRENCY_AMOUNT =
            Pattern.compile("(?i)(?:Rs\\.?|INR|₹|Re\\.?)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)")

        private val CASH_WORD = Regex("(?i)\\bcash\\b")

        /** A bank named as the counterparty means the money moved between accounts. */
        private val BANK_COUNTERPARTY = Regex("(?i)\\bbank$")

        private val FEE_WORD = Regex("(?i)\\b(?:fee|fees|charges)\\b")

        /**
         * Anchored for the same reason [FEE_WORD] and [CASH_WORD] are: as a bare
         * substring "emi" is inside "reminder", so every payment reminder was typed as
         * a loan instalment. "premium" and "system" hit it too.
         */
        private val EMI_WORD = Regex("(?i)\\b(?:emi|emis|instal?lments?)\\b")

        /**
         * Settling a card bill, from either side of it.
         *
         * The bank leg reads "debited from Acct XX635 for ICICI CREDIT CARD PAYMENT"
         * and the card leg reads "Payment of Rs 2,783.00 received towards ICICI Bank
         * Credit Card XX7009". Both were ordinary transactions: the first a ₹12,400
         * purchase, the second ₹2,783 of income. Since card senders are allowlisted by
         * default the swipes are already in the ledger, so that is the same money
         * counted two and three times over.
         *
         * The "payment ... towards ... credit card" arm has to tolerate the decimal
         * point in "Rs 2,783.00" sitting between the two anchors, which is why the gap
         * is a character class rather than `[^.]`. A purchase *made on* a card is not
         * matched: it names no payment towards the card.
         */
        private val CARD_BILL_PAYMENT = Regex(
            "(?i)(?:\\bcredit\\s+card\\s+(?:bill\\s+)?payment\\b|" +
                "\\bcard\\s+bill(?:\\s+payment)?\\b|" +
                "\\b(?:payment|paid|repayment)\\b[\\w\\s.,₹()/-]{0,60}?" +
                "\\b(?:towards|to)\\b[\\w\\s.,₹()/-]{0,40}?\\bcredit\\s+card\\b)"
        )

        /**
         * A credential, never a transaction and never a bill.
         *
         * "OTP for txn of Rs.5000.00 at AMAZON is 483920" was booked as a ₹5,000
         * purchase. These words are unambiguous — no genuine statement or payment alert
         * describes itself as a one-time password — so this is tested before anything
         * else and its verdict is final.
         */
        private val OTP_MARKER = Regex(
            "(?i)\\b(?:otp|one[\\s-]?time\\s+password|verification\\s+code|passcode)\\b"
        )

        /**
         * Marketing. Also not a transaction — a pre-approved-loan advert was booked as
         * ₹15,00,000 of spend — but unlike [OTP_MARKER] these words are *not* exclusive
         * to marketing, which is why the order of the two checks matters.
         *
         * Utility and telecom billers routinely append a payment link to a genuine due
         * notice: "Your electricity bill of Rs 1,240 is due on 15-Aug. Click here to
         * pay." Tested before the due-reminder check, that message is discarded for
         * containing "click to" and the bill is lost. Tested after, the advert is still
         * discarded — a promotion that quotes no amount due and no deadline does not
         * satisfy the due-reminder pattern, so it never reaches here as a bill.
         */
        private val PROMO_MARKER = Regex(
            "(?i)\\b(?:pre-?approved|apply\\s+now|click\\s+(?:here|to)|offer\\s+valid|" +
                "know\\s+more)\\b"
        )

        /**
         * A bill that is *owed*, not one that has been paid.
         *
         * A card due reminder quotes two rupee amounts and names an account, which is
         * enough for the looser rules to book it: "Total Amount Due of Rs 2,783.00 ...
         * payable by 06-Aug-26" entered the ledger as ₹2,783 of spend, and the common
         * variant that adds "Payments are credited within 2 working days" entered it as
         * ₹2,783 of *income*, filed under Income & Refunds — a reminder to pay a bill
         * counted as money arriving. Nothing has settled when one of these is sent, so
         * like [NON_TRANSACTIONAL] it produces no row at all rather than an unparsed
         * one: there is nothing for the user to review.
         */
        private val DUE_REMINDER = Regex(
            "(?i)\\b(?:(?:total|minimum|min|net)\\s+(?:amount\\s+|amt\\s+)?due|amount\\s+due|" +
                "due\\s+date|payable\\s+by|ignore\\s+if\\s+(?:already\\s+)?paid|credit\\s+bureaus?|" +
                // "is due by 06-Aug-26" — the date is required, so an ordinary alert
                // that merely contains the word "due" is not swept up.
                "due\\s+(?:by|on)\\s+\\d{1,2}[-/]|" +
                "statement\\s+(?:for\\s+\\w+\\s+)?(?:is|has\\s+been)?\\s*(?:generated|ready)|" +
                "last\\s+date\\s+(?:for|of)\\s+payment|" +
                "avoid\\s+late\\s+(?:payment\\s+)?(?:fee|fees|charges))\\b"
        )

        /**
         * The escape hatch for [DUE_REMINDER].
         *
         * A payment confirmation routinely restates the balance it just cleared —
         * "Payment of Rs 2,783.00 received towards Credit Card XX7009. Total Amount Due
         * is now Rs 0.00" — and dropping that would lose a real transaction, which T2.3
         * forbids. So the reminder guard only fires when nothing in the message says the
         * money has actually moved. The tenses matter: "has been credited" is a
         * settlement, "are credited within 2 working days" is a promise.
         */
        private val SETTLED_PAYMENT = Regex(
            "(?i)(?:\\b(?:has|have|had)\\s+been\\s+" +
                "(?:credited|debited|received|paid|processed|reversed)\\b|" +
                "\\b(?:was|were)\\s+(?:credited|debited|received|paid|processed|reversed)\\b|" +
                "\\b(?:has|have|we)\\s+received\\b|" +
                // The window has to tolerate the "." in "Rs 2,783.00", which sits
                // between "payment of" and "received" in every such confirmation.
                "\\bpayment\\s+(?:of\\s+[\\w\\s.,₹]{0,40}?)?received\\b|" +
                "\\bthank\\s+you\\s+for\\s+(?:your\\s+)?payment\\b|" +
                "\\bsuccessfully\\s+(?:paid|processed|credited|debited)\\b)"
        )

        /**
         * A merchant capture that turned out to be nothing but the other account —
         * "transferred from A/c XX3344 to A/c XX9911" names a destination, not a shop.
         * The slash does not survive [cleanMerchantName]'s charset filter, hence the
         * tolerant separator.
         */
        private val ACCOUNT_ONLY_MERCHANT = Regex(
            "(?i)^(?:a\\s*/?\\s*c|ac|acct|account)\\s*(?:no\\.?)?\\s*" +
                "(?:xx+|x|\\*+)?\\s*(\\d{3,6})$"
        )

        const val UNKNOWN_MERCHANT = "Unknown Merchant"
        private const val UNCATEGORISED = "Other / Misc"
    }

    /**
     * @param parserRules every rule to try, already in evaluation order — user rules
     *   ahead of system ones (see `ParserRuleDao.getActiveRulesList`). There is no
     *   hidden built-in set behind these: the bundled patterns arrive here the same
     *   way a sideloaded rule does, having been seeded from the signed asset, so a
     *   rule fix can ship without an APK (T2.2). An empty list parses nothing, which
     *   is why `ParserRuleSeeder` keeps the previous rules when a file is rejected.
     */
    fun parseMessage(
        sender: String,
        body: String,
        timestamp: Long,
        merchantRules: List<MerchantRuleEntity> = emptyList(),
        parserRules: List<ParserRuleEntity> = emptyList()
    ): ParseResult {
        val cleanBody = body.replace("\n", " ").trim()

        // Before anything else: an OTP quotes an amount but records no event, and
        // nothing else is worded like one. Returning an empty result rather than an
        // unparsed entry is deliberate — T2.3 exists so genuine transactions are never
        // dropped, and a credential SMS in the review queue is noise that trains the
        // user to ignore it.
        if (OTP_MARKER.containsMatchIn(cleanBody)) return ParseResult()

        // A bill that is owed is not a bill that was paid. Checked before the rules so
        // the amount never reaches them, and after the settlement test so a payment
        // confirmation that restates the outstanding balance still parses.
        //
        // It also sits ahead of the marketing check, because a biller that attaches
        // "click here to pay" to a real due notice would otherwise have that notice
        // thrown away for the sake of its own payment link.
        if (DUE_REMINDER.containsMatchIn(cleanBody) &&
            !SETTLED_PAYMENT.containsMatchIn(cleanBody)
        ) {
            return ParseResult(
                billNotice = BillNoticeExtractor.extract(sender, body, cleanBody, timestamp)
            )
        }

        // An advert quoting a large round number was being booked as spend.
        if (PROMO_MARKER.containsMatchIn(cleanBody)) return ParseResult()

        val hash = generateHash(sender, cleanBody, timestamp)
        val status = if (NEGATED.containsMatchIn(cleanBody)) STATUS_FAILED else STATUS_POSTED
        val balanceAfter = extractBalance(cleanBody)

        // One evaluation path for every rule, bundled or user-authored.
        //
        // There used to be two: database rules were tried with one set of semantics
        // and hardcoded patterns with another, better set. Because database rules ran
        // first, the weaker path decided almost every message — three seeded
        // catch-all rules shadowed the patterns entirely and scored 0.483 on merchant
        // extraction. Two implementations of "apply a regex to an SMS" is one too
        // many, and the weaker one wins by construction.
        for (rule in parserRules) {
            // F1.1 / T2.2 — a rule written for one bank's format must not be applied
            // to every sender; senderPattern was once stored but never read, so
            // whichever rule matched first won regardless of who sent the message.
            if (!senderMatchesRule(sender, rule.senderPattern)) continue

            val matcher = try {
                val matcher = compiled(rule.regexPattern).matcher(cleanBody)
                if (!matcher.find()) continue
                matcher
            } catch (invalidPattern: Exception) {
                // A user-authored rule with a broken regex must not block ingestion.
                continue
            }

            val amount = extractAmount(matcher, rule.amountGroup, cleanBody)
            if (amount == null || amount <= 0) continue

            // The captured verb sits next to the amount it belongs to, so it survives
            // a message that names a second account in the other direction. Scanning
            // the body is the fallback only when the rule captures no direction —
            // which beats the old "assume DEBIT", the reason credits matched by such
            // a rule were recorded with the sign inverted.
            val direction = directionOf(matcher.groupOrNull(rule.directionGroup))
                ?: detectDirection(cleanBody)

            // F1.3 — the body scan is the fallback, and for the bundled rules it is
            // also the primary: they all leave accountGroup null because scanning
            // outperformed every per-rule capture group on the corpus.
            val accountTail = matcher.groupOrNull(rule.accountGroup)
                ?: extractAccountTail(cleanBody)
            val channel = matcher.groupOrNull(rule.channelGroup) ?: detectChannel(cleanBody)

            val payee = resolvePayee(
                matcher.groupOrNull(rule.merchantGroup), cleanBody, direction, accountTail
            )
            val merchant = payee.merchant
            val txnType =
                detectTxnType(cleanBody, direction, channel, payee.isAccountTransfer, merchant)

            return ParseResult(
                parsedTransaction = TransactionEntity(
                    amount = amount,
                    direction = direction,
                    timestamp = timestamp,
                    sender = sender,
                    merchant = merchant,
                    accountTail = accountTail,
                    channel = channel,
                    category = resolveCategory(merchant, merchantRules, txnType),
                    rawMessage = body,
                    balanceAfter = balanceAfter,
                    status = status,
                    txnType = txnType,
                    hash = hash,
                    txnHash = generateTxnHash(amount, direction, merchant, accountTail, timestamp)
                )
            )
        }

        // Check if message is financial but unparsed (contains Rs, INR, ₹, debited, credited, paid, transferred, A/C, etc.)
        val isFinancial = cleanBody.contains("Rs", ignoreCase = true) ||
                cleanBody.contains("INR", ignoreCase = true) ||
                cleanBody.contains("₹") ||
                cleanBody.contains("debited", ignoreCase = true) ||
                cleanBody.contains("credited", ignoreCase = true) ||
                cleanBody.contains("paid", ignoreCase = true) ||
                cleanBody.contains("spent", ignoreCase = true) ||
                cleanBody.contains("transferred", ignoreCase = true) ||
                cleanBody.contains("A/C", ignoreCase = true) ||
                cleanBody.contains("A/c", ignoreCase = true) ||
                cleanBody.contains("UPI", ignoreCase = true) ||
                cleanBody.contains("withdrawn", ignoreCase = true)

        if (isFinancial) {
            return ParseResult(
                unparsedSms = UnparsedSmsEntity(
                    sender = sender,
                    rawMessage = body,
                    timestamp = timestamp,
                    failureReason = "Pattern missing or amount unextractable"
                )
            )
        }

        return ParseResult() // Non-financial SMS ignored
    }

    /** Bounds-checked group access; regex optional groups are routinely absent. */
    private fun java.util.regex.Matcher.groupOrNull(index: Int?): String? =
        index?.takeIf { it in 1..groupCount() }?.let { group(it)?.trim()?.ifBlank { null } }

    /**
     * Compiled patterns, keyed by their source text.
     *
     * Every rule is now data, so the regexes are recompiled on every message unless
     * they are cached — and a full inbox scan runs thousands of messages past a
     * dozen rules. Keyed by pattern text rather than rule id so a rule edited in
     * place does not serve a stale compilation.
     */
    private val compiledPatterns = HashMap<String, Pattern>()

    private fun compiled(regex: String): Pattern =
        compiledPatterns.getOrPut(regex) { Pattern.compile(regex, Pattern.CASE_INSENSITIVE) }

    private fun extractAmount(
        matcher: java.util.regex.Matcher,
        amountGroup: Int,
        text: String
    ): Double? {
        matcher.groupOrNull(amountGroup)?.replace(",", "")?.toDoubleOrNull()
            ?.let { return it }

        // Only reached if the declared group was optional and did not participate.
        val m = CURRENCY_AMOUNT.matcher(text)
        return if (m.find()) m.group(1)?.replace(",", "")?.toDoubleOrNull() else null
    }

    private fun directionOf(word: String?): String? {
        val w = word?.lowercase(Locale.ROOT)?.ifBlank { null } ?: return null
        return when {
            w.startsWith("credit") || w.startsWith("receiv") || w.startsWith("refund") ||
                w.startsWith("deposit") || w.startsWith("add") -> "CREDIT"
            else -> "DEBIT"
        }
    }

    /** Who the money went to, and whether that turned out to be another account. */
    private data class Payee(val merchant: String, val isAccountTransfer: Boolean)

    /**
     * Resolves the captured merchant text into a payee.
     *
     * Three outcomes, in order of preference: a real name; the other account, when
     * the capture was only ever an account number ("transferred from A/c XX3344 to
     * A/c XX9911" — the destination is the closest thing to a payee); or an honest
     * admission that the message named nobody.
     */
    private fun resolvePayee(
        captured: String?,
        body: String,
        direction: String,
        accountTail: String?
    ): Payee {
        val trimmed = captured?.trim().orEmpty()

        // Tested before cleaning, not after: cleanMerchantName strips a leading
        // account reference as boilerplate, which reduces a capture of "A/c XX7788"
        // to the empty string. That is right when the account prefixes a real payee
        // and wrong when the account *is* the payee, so the account-only case has to
        // be recognised while the text is still intact.
        ACCOUNT_ONLY_MERCHANT.find(trimmed)?.let { match ->
            return Payee(transferLabel(direction, match.groupValues[1]), isAccountTransfer = true)
        }

        val cleaned = cleanMerchantName(trimmed).takeUnless { it == UNKNOWN_MERCHANT }
        if (cleaned != null && !isBankNameOnly(cleaned)) return Payee(cleaned, isAccountTransfer = false)

        val extracted = extractPayeeFromBody(body)
        if (extracted != null) return Payee(extracted, isAccountTransfer = false)
        if (cleaned != null) return Payee(cleaned, isAccountTransfer = false)

        val counterparty = counterpartyMerchant(body, direction, accountTail)
        return Payee(counterparty ?: UNKNOWN_MERCHANT, isAccountTransfer = counterparty != null)
    }

    private fun extractPayeeFromBody(body: String): String? {
        val regex = Regex(
            "(?i)\\b(?:a/c|ac|acct|account|card)\\s*(?:no\\.?)?\\s*(?:xx+|x|\\*+)?\\d{3,6}\\s*(?:on\\s+\\d{1,2}[-/][A-Za-z0-9]{2,}(?:[-/]\\d{2,4})?)?\\s+(?:to|at|for|towards|via|from|on)\\s+([A-Za-z0-9.@_&'/-][A-Za-z0-9\\s.@_&'/-]*?)(?=\\s+on\\b|\\s+ref\\b|\\s+bal\\b|\\s+avl\\b|\\s+limit\\b|\\s+avail\\b|\\s+via\\b|\\s+using\\b|[.,]|$|\\n)"
        )
        regex.find(body)?.let { match ->
            val candidate = cleanMerchantName(match.groupValues[1])
            if (candidate != UNKNOWN_MERCHANT && !isBankNameOnly(candidate)) {
                return candidate
            }
        }
        return null
    }

    private fun transferLabel(direction: String, tail: String): String =
        if (direction == "DEBIT") "Transfer to A/c $tail" else "Transfer from A/c $tail"

    /**
     * The other leg of a transfer, for messages that name no merchant at all.
     *
     * "Acct XX635 debited ... & Acct XX066 credited" has a payee, it just isn't a
     * shop: it is the destination account. Naming it beats "Unknown Merchant",
     * and it groups consistently in the analytics because the label is derived
     * from the account number rather than from the wording.
     */
    private fun counterpartyMerchant(
        text: String,
        direction: String,
        ownAccountTail: String?
    ): String? {
        val matcher = ACCOUNT_LEG.matcher(text)
        while (matcher.find()) {
            val tail = matcher.group(1) ?: continue
            if (tail == ownAccountTail) continue
            val legDirection = directionOf(matcher.group(2)) ?: continue
            if (legDirection == direction) continue
            return transferLabel(direction, tail)
        }
        return null
    }

    private fun extractAccountTail(text: String): String? {
        // Single "x" as well as "xx": HDFC writes "A/C x1234" where ICICI writes "XX635".
        val pattern = Pattern.compile("(?i)(?:A/C|acct|card|ending)\\s*(?:xx+|x|[*]+)?([0-9]{3,4})")
        val m = pattern.matcher(text)
        return if (m.find()) m.group(1) else null
    }

    /**
     * Last resort, for patterns that capture no direction verb.
     *
     * Whichever direction word appears *first* wins. The previous version asked
     * "does the body contain 'credited'?", which is the wrong question for a
     * transfer alert naming both legs — "Acct XX635 debited ... Acct XX066
     * credited" was booked as money arriving when in fact it left.
     */
    private fun detectDirection(text: String): String {
        val lower = text.lowercase(Locale.ROOT)
        fun firstIndexOf(words: List<String>) =
            words.mapNotNull { w -> lower.indexOf(w).takeIf { it >= 0 } }.minOrNull()

        val credit = firstIndexOf(CREDIT_WORDS)
        val debit = firstIndexOf(DEBIT_WORDS)
        return when {
            credit == null -> "DEBIT"
            debit == null -> "CREDIT"
            credit < debit -> "CREDIT"
            else -> "DEBIT"
        }
    }

    private fun detectChannel(text: String): String {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            // Google Pay and PhonePe are UPI rails; their alerts often never say
            // "UPI" at all, and calling those payments "Digital" hid the rail on
            // every message from two of the most-used apps in the country.
            lower.contains("upi") || lower.contains("vpa") ||
                lower.contains("google pay") || lower.contains("gpay") ||
                lower.contains("phonepe") -> "UPI"
            lower.contains("card") || lower.contains("pos") -> "Card"
            // "cash" anchored: unanchored it matched "cashback", so every cashback
            // credit was filed as an ATM transaction.
            lower.contains("atm") || CASH_WORD.containsMatchIn(lower) -> "ATM"
            lower.contains("neft") || lower.contains("rtgs") || lower.contains("imps") || lower.contains("netbanking") -> "NetBanking"
            else -> "Digital"
        }
    }

    private fun cleanMerchantName(raw: String): String {
        var clean = raw.trim()

        // The payee ends where the *funding* account is named: "paid to zepto@ybl
        // from A/c XX4567 via UPI". Without this the capture ran on past the account
        // and the rule below then read the rail as the payee, so a string of SBI UPI
        // payments were all booked to a merchant called "UPI".
        clean = clean.replace(
            Regex("(?i)\\s+(?:from|in)\\s+(?:a\\s*/?\\s*c|ac|acct|account|card)\\b.*$"),
            ""
        )

        // "spent on Card ending 4321 at AMAZON INDIA" — when an " at " separator is
        // present the merchant is what follows it, and everything before is card or
        // account boilerplate.
        Regex("(?i)^.*\\bat\\s+(.+)$").find(clean)?.let { clean = it.groupValues[1].trim() }

        // Strip a leading account/card reference that the capture group swept up,
        // e.g. "A/C XX8901 to SWIGGY" or "Card ending 4321 at AMAZON" -> the merchant.
        clean = clean.replace(
            Regex(
                "(?i)^\\s*(?:a/c|ac|acct|account|card)\\s*(?:no\\.?)?\\s*" +
                    "(?:xx+|\\*+)?\\d{3,6}\\s*(?:to|at|for|towards|via|by)?\\s*"
            ),
            ""
        )
        clean = clean.replace(Regex("(?i)^\\s*(?:card\\s+ending|ending)\\s*\\d{3,6}\\s*(?:at|to|for)?\\s*"), "")

        // The same account reference, but mid-string and followed by a preposition:
        // "HDFC Bank A/C x1234 To SWIGGY". Whatever comes after the preposition is
        // the payee; everything before it is the sending account's own description.
        //
        // "from" belongs in this list: a refund reads "credited to HDFC Bank A/c
        // xx8901 from FLIPKART", and without it the whole clause survived as the
        // merchant name.
        Regex(
            "(?i)^.*\\b(?:a/c|ac|acct|account|card)\\s*(?:no\\.?)?\\s*" +
                "(?:xx+|x|\\*+)?\\d{3,6}\\s+(?:to|at|for|towards|via|from)\\s+(.+)$"
        ).find(clean)?.let { clean = it.groupValues[1].trim() }

        // "towards EMI for LOAN 88213" — the instalment wrapper is the transaction
        // *type*, which detectTxnType already records. What is left is the payee.
        clean = clean.replace(
            Regex("(?i)^\\s*(?:emi|instal?lment)\\s+(?:for|of|towards|on)\\s+"),
            ""
        )

        // A leading channel token, e.g. "UPI/SWIGGY" or "IMPS-ACME LTD". These name
        // the rail the money travelled on, not who received it. ATM is deliberately
        // absent: for a withdrawal the rail genuinely is the counterparty.
        clean = clean.replace(Regex("(?i)^\\s*(?:upi|imps|neft|rtgs|pos|ach|inb|mmt)[\\s/:-]+"), "")

        // A trailing account reference, e.g. "SWIGGY from A/C XX8901".
        clean = clean.replace(
            Regex(
                "(?i)\\s+(?:from|to|via|by|on)?\\s*(?:a/c|ac|acct|account|card)\\s*(?:no\\.?)?\\s*" +
                    "(?:xx+|\\*+)?\\d{3,6}\\s*$"
            ),
            ""
        )

        // A leading preposition the capture group carried in, e.g. "from FLIPKART" or "using FLIPKART".
        clean = clean.replace(Regex("(?i)^\\s*(?:from|to|at|for|by|via|using|with|on)\\s+"), "")

        // A trailing reason clause, e.g. "FLIPKART for order cancellation".
        clean = clean.replace(
            Regex("(?i)\\s+for\\s+(?:order|txn|transaction|ref|reference|purchase|booking)\\b.*$"),
            ""
        )

        // A trailing status clause, e.g. "MYNTRA was declined due to insufficient balance".
        // The status itself is captured separately; it does not belong in the name.
        clean = clean.replace(
            Regex(
                "(?i)\\s+(?:was|is|has\\s+been|could\\s+not)\\s+" +
                    "(?:declined|denied|failed|unsuccessful|rejected|reversed|cancell?ed|processed)\\b.*$"
            ),
            ""
        )

        // Drop trailing boilerplate. Anchored on word boundaries — the previous
        // version matched these as bare substrings, so "spent on Card ... at AMAZON
        // INDIA" was truncated at the "on" and collapsed to nothing.
        clean = clean.replace(
            Regex("(?i)\\b(via|ref|refno|vpa|txnid|txn|uzpi|uzpi/|bal|balance|avail|available|info)\\b.*"),
            ""
        ).trim()

        // A trailing " on <date>" only; not every "on".
        clean = clean.replace(Regex("(?i)\\s+on\\s+\\d{1,2}[-/][A-Za-z0-9]{2,}[-/]?\\d{0,4}.*$"), "").trim()

        // "@" survives: a UPI payee *is* a VPA. Stripping it turned "instamart@ybl"
        // into "instamart ybl", which then failed to group with itself across months.
        clean = clean.replace(Regex("[^A-Za-z0-9\\s.@&'-]"), " ")
        clean = clean.replace(Regex("\\s{2,}"), " ").trim().trimEnd('.', '-', '&', '\'')

        // Trailing filler left by the capture, e.g. "SPOTIFY INDIA recurring subscription".
        clean = clean.replace(
            Regex("(?i)(\\s+(recurring|subscription|payment|purchase|transaction))+$"),
            ""
        ).trim()

        if (clean.length > 40) clean = clean.substring(0, 40).trim()
        return if (clean.isBlank()) UNKNOWN_MERCHANT else clean
    }

    fun determineCategory(merchant: String, rules: List<MerchantRuleEntity>): String {
        val upperMerchant = merchant.uppercase(Locale.ROOT)
        // Sort rules by pattern length descending so specific rules (e.g. "AMAZON PAY")
        // take precedence over generic rules (e.g. "AMAZON")
        val sortedRules = rules.sortedByDescending { it.merchantPattern.length }
        for (rule in sortedRules) {
            val patternUpper = rule.merchantPattern.uppercase(Locale.ROOT)
            if (patternUpper.isBlank()) continue

            val isMatch = if (patternUpper.length <= 4 || isGenericOrUnsafeMerchant(patternUpper)) {
                upperMerchant == patternUpper
            } else {
                try {
                    val regex = Regex("\\b" + Regex.escape(patternUpper) + "\\b", RegexOption.IGNORE_CASE)
                    regex.containsMatchIn(upperMerchant)
                } catch (_: Exception) {
                    upperMerchant.contains(patternUpper)
                }
            }
            if (isMatch) {
                return rule.assignedCategory
            }
        }
        // Heuristic fallback matching
        return when {
            upperMerchant.contains("SWIGGY") || upperMerchant.contains("ZOMATO") || upperMerchant.contains("REST") || upperMerchant.contains("CAF") || upperMerchant.contains("FOOD") -> "Food & Dining"
            upperMerchant.contains("AMAZON") || upperMerchant.contains("FLIPKART") || upperMerchant.contains("MYNTRA") || upperMerchant.contains("STORE") -> "Shopping"
            upperMerchant.contains("BLINKIT") || upperMerchant.contains("ZEPTO") || upperMerchant.contains("GROCER") || upperMerchant.contains("MART") -> "Grocery"
            upperMerchant.contains("UBER") || upperMerchant.contains("OLA") || upperMerchant.contains("PETROL") || upperMerchant.contains("FUEL") || upperMerchant.contains("SHELL") -> "Transport & Fuel"
            upperMerchant.contains("NETFLIX") || upperMerchant.contains("SPOTIFY") || upperMerchant.contains("APPLE") || upperMerchant.contains("PRIME") || upperMerchant.contains("HOTSTAR") -> "Entertainment & Subs"
            upperMerchant.contains("ATM") || upperMerchant.contains("CASH") -> "ATM Cash"
            upperMerchant.contains("ELECTRIC") || upperMerchant.contains("BESCOM") || upperMerchant.contains("AIRTEL") || upperMerchant.contains("JIO") || upperMerchant.contains("BILL") -> "Utilities & Bills"
            else -> UNCATEGORISED
        }
    }

    /**
     * The merchant name is the primary signal, but it is not the only one. A
     * self-transfer or an ATM withdrawal names no merchant a rule could match, and
     * dropping them into "Other / Misc" left the seeded Transfers and ATM Cash
     * categories permanently empty.
     */
    private fun resolveCategory(
        merchant: String,
        rules: List<MerchantRuleEntity>,
        txnType: String
    ): String {
        val byMerchant = determineCategory(merchant, rules)
        if (byMerchant != UNCATEGORISED) return byMerchant
        return when (txnType) {
            TxnType.TRANSFER, TxnType.CARD_PAYMENT -> "Transfers"
            TxnType.ATM -> "ATM Cash"
            TxnType.REFUND, TxnType.INCOME -> "Income & Refunds"
            else -> UNCATEGORISED
        }
    }

    private fun senderMatchesRule(sender: String, senderPattern: String): Boolean {
        if (senderPattern.isBlank() || senderPattern == ".*") return true
        return try {
            Regex(senderPattern, RegexOption.IGNORE_CASE).containsMatchIn(sender)
        } catch (invalidPattern: Exception) {
            // A user-authored rule with a broken regex should not block ingestion.
            true
        }
    }

    private fun extractBalance(text: String): Double? {
        val m = BALANCE.matcher(text)
        return if (m.find()) m.group(1)?.replace(",", "")?.toDoubleOrNull() else null
    }

    /** F1.2 — EMI and refund are called out by the spec and behave differently downstream. */
    private fun detectTxnType(
        text: String,
        direction: String,
        channel: String?,
        isAccountTransfer: Boolean = false,
        merchant: String? = null
    ): String {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            EMI_WORD.containsMatchIn(lower) -> TxnType.EMI
            // Ahead of the refund and direction tests: the card's acknowledgement is a
            // credit, and every rule below would otherwise read it as money arriving.
            CARD_BILL_PAYMENT.containsMatchIn(lower) -> TxnType.CARD_PAYMENT
            lower.contains("refund") || lower.contains("reversed") || lower.contains("cashback") -> TxnType.REFUND
            channel == "ATM" || lower.contains("withdrawn") -> TxnType.ATM
            // Previously this required "charge" *and* "fee", so an "annual card fee"
            // was filed as an ordinary purchase. The word boundary matters: bare
            // "fee" also matches "coffee", and "charged" is a common debit verb
            // rather than a fee, so only the plural noun counts.
            FEE_WORD.containsMatchIn(lower) -> TxnType.FEE
            lower.contains("salary") || lower.contains("interest credited") -> TxnType.INCOME
            // A message naming two of the user's own accounts, one debited and one
            // credited, is a transfer whether or not it spells out the rail.
            isAccountTransfer -> TxnType.TRANSFER
            // A rail keyword on its own does not make a credit a transfer: an
            // employer pays salary by NEFT and a pension arrives by NEFT, and both
            // were being typed TRANSFER and so dropped out of income.
            direction == "DEBIT" && (
                lower.contains("neft") || lower.contains("imps") ||
                    lower.contains("rtgs") || lower.contains("transferred")
                ) -> TxnType.TRANSFER
            // Money arriving from a bank rather than from a payer is a transfer in —
            // a wallet top-up funded from the user's own account, typically.
            merchant != null && BANK_COUNTERPARTY.containsMatchIn(merchant) -> TxnType.TRANSFER
            direction == "CREDIT" -> TxnType.INCOME
            else -> TxnType.PURCHASE
        }
    }

    private fun generateHash(sender: String, body: String, timestamp: Long): String {
        val input = "$sender|$body|${timestamp / 60000}" // Group by minute for deduplication
        return sha256(input)
    }

    /**
     * F1.6 — identity of the underlying payment rather than of the message.
     *
     * Bucketed to the day so the same transaction reported by SMS and by a push
     * notification, minutes apart and worded differently, collapses to one value.
     *
     * The account tail is deliberately *not* part of this. Push alerts routinely omit
     * it, and including it meant the two sources could never agree — which is exactly
     * the case this hash exists to cover. Collisions between two genuinely distinct
     * same-day, same-amount charges are fine: this index is a dedup *candidate*
     * signal, and telling those apart is F3.4's job, not this hash's.
     */
    private fun generateTxnHash(
        amount: Double,
        direction: String,
        merchant: String,
        accountTail: String?,
        timestamp: Long
    ): String {
        val day = timestamp / 86_400_000L
        val normalizedMerchant = merchant.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")
        return sha256("$amount|$direction|$normalizedMerchant|$day")
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
