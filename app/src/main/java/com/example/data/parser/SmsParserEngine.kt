package com.example.data.parser

import com.example.data.local.entity.MerchantRuleEntity
import com.example.data.local.entity.ParserRuleEntity
import com.example.data.local.entity.STATUS_FAILED
import com.example.data.local.entity.STATUS_POSTED
import com.example.data.local.entity.TransactionEntity
import com.example.data.local.entity.TxnType
import com.example.data.local.entity.UnparsedSmsEntity
import java.security.MessageDigest
import java.util.Locale
import java.util.regex.Pattern

data class ParseResult(
    val parsedTransaction: TransactionEntity? = null,
    val unparsedSms: UnparsedSmsEntity? = null
)

class SmsParserEngine {

    /**
     * A built-in pattern together with the meaning of its capture groups.
     *
     * The group indices are not decoration. The patterns below have genuinely
     * different layouts — Pattern A opens with the amount, Pattern D opens with the
     * account tail — and an earlier version guessed by scanning the groups for the
     * first thing that parsed as a number. On "Acct XX635 debited with Rs 45,425.00"
     * the first number is the *account tail*, so the ledger recorded ₹635. The
     * failure was silent: a plausible amount, no error, no unparsed entry.
     */
    private data class BuiltInPattern(
        val pattern: Pattern,
        val amountGroup: Int,
        val directionGroup: Int? = null,
        val merchantGroup: Int? = null
    )

    companion object {

        /**
         * "on 09-Aug-26" / "on 01/01/2026" — banks routinely slip the value date
         * between the amount and the payee. A `const val` so it can be interpolated
         * into the patterns below regardless of declaration order.
         */
        private const val DATE_CLAUSE = "on\\s+\\d{1,2}[-/][A-Za-z0-9]{2,}(?:[-/]\\d{2,4})?"

        // Built-in Regex fallback patterns for high accuracy (≥95%) across HDFC, ICICI, SBI, Axis, Kotak, PayTM, PhonePe, Cred, etc.
        private val PATTERNS = listOf(
            // Pattern A: "Rs. 450.00 / ₹450 / INR 450.00 debited from A/C XX1234 to SWIGGY on 08-Aug-26"
            // The optional noun slot before the verb matters: "Rs 1,299 refund credited
            // to ..." puts a noun between the amount and the direction word, and
            // without it every pattern here failed and the message was filed unparsed.
            BuiltInPattern(
                Pattern.compile("(?i)(?:Rs\\.?|INR|₹|Re\\.?)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)\\s*(?:has been|is|was)?\\s*(?:refund|cashback|amount|payment|txn|transaction)?\\s*(debited|spent|paid|sent|credited|received|withdrawn|refunded|charged|transferred|deposited|deducted)\\s*(?:from|to|at|via|in|by|for)?\\s*(?:A/C|a/c|acct|account|card)?\\s*(?:xx|[*]+)?([0-9]{3,4})?\\s*(?:to|at|for|vpa|by|from)?\\s*([A-Za-z0-9\\s.@_&'/-]+?)(?=\\s+on|\\s+ref|\\s+Bal|\\s+avail|\\.|\\,|$|\n)", Pattern.CASE_INSENSITIVE),
                amountGroup = 1, directionGroup = 2, merchantGroup = 4
            ),

            // Pattern B: "Paid ₹1,200 / Paid Rs 1200 to Starbucks via UPI on ..."
            BuiltInPattern(
                Pattern.compile("(?i)(Paid|Spent|Received|Sent|Charged|Debited|Credited|Transferred)\\s*(?:Rs\\.?|INR|₹|Re\\.?)?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)\\s*(?:to|at|from|for|on)\\s*([A-Za-z0-9\\s.@_&'/-]+?)(?=\\s+via|\\s+using|\\s+on|\\s+ref|\\s+Bal|\\.|\\,|$|\n)", Pattern.CASE_INSENSITIVE),
                amountGroup = 2, directionGroup = 1, merchantGroup = 3
            ),

            // Pattern C: "Spent Rs.2,499.00 / ₹2499 on HDFC Bank Card ending 5678 at AMAZON INDIA"
            BuiltInPattern(
                Pattern.compile("(?i)(Spent|Charged|Debited|Paid)\\s*(?:Rs\\.?|INR|₹|Re\\.?)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)\\s*(?:on|with)?\\s*([A-Za-z0-9\\s]+)?\\s*(?:ending|card)?\\s*([0-9]{3,4})?\\s*at\\s*([A-Za-z0-9\\s.@_&'/-]+?)(?=\\s+on|\\.|\\,|$|\n)", Pattern.CASE_INSENSITIVE),
                amountGroup = 2, directionGroup = 1, merchantGroup = 5
            ),

            // Pattern D: "A/C 4321 debited with INR 350.00 / ₹350 for SWIGGY"
            //
            // The merchant clause requires its introducing preposition. Without it the
            // lazy group simply started wherever the amount ended, so ICICI's two-leg
            // transfer alert — "...Rs 45,425.00 on 09-Aug-26 & Acct XX066 credited." —
            // yielded a "merchant" of "on 09-Aug-26 & Acct XX066 credited". A message
            // with no merchant named should produce no merchant, not the rest of itself.
            BuiltInPattern(
                Pattern.compile("(?i)(?:A/C|A/c|acct|account|card)\\s*(?:xx+|x|[*]+)?([0-9]{3,4})?\\s*(?:is|has been|was)?\\s*(debited|credited|charged|spent|transferred)\\s*(?:with|for|by)?\\s*(?:Rs\\.?|INR|₹|Re\\.?)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)(?:\\s*$DATE_CLAUSE)?(?:\\s*(?:for|at|to|towards|by)\\s*([A-Za-z0-9\\s.@_&'/-]+?)(?=\\s+on|\\.|\\,|$|\n))?", Pattern.CASE_INSENSITIVE),
                amountGroup = 3, directionGroup = 2, merchantGroup = 4
            ),

            // Pattern E: "Txn of Rs 350.00 / ₹350 done on A/C XX8901 at SWIGGY"
            BuiltInPattern(
                Pattern.compile("(?i)(?:Txn|Transaction|Alert|Spent|Charged)?\\s*(?:of)?\\s*(?:Rs\\.?|INR|₹|Re\\.?)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)\\s*(?:done|spent|debited|credited|paid)?\\s*(?:on|at|from|to|for)\\s*([A-Za-z0-9\\s.@_&'/-]+?)(?=\\s+on|\\s+ref|\\s+Bal|\\.|\\,|$|\n)", Pattern.CASE_INSENSITIVE),
                amountGroup = 1, merchantGroup = 2
            )
        )

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

        const val UNKNOWN_MERCHANT = "Unknown Merchant"
        private const val UNCATEGORISED = "Other / Misc"
    }

    fun parseMessage(
        sender: String,
        body: String,
        timestamp: Long,
        merchantRules: List<MerchantRuleEntity> = emptyList(),
        customParserRules: List<ParserRuleEntity> = emptyList()
    ): ParseResult {
        val cleanBody = body.replace("\n", " ").trim()
        val hash = generateHash(sender, cleanBody, timestamp)
        val status = if (NEGATED.containsMatchIn(cleanBody)) STATUS_FAILED else STATUS_POSTED
        val balanceAfter = extractBalance(cleanBody)

        // Try custom parser rules first
        for (rule in customParserRules) {
            // F1.1 / T2.2 — a rule written for one bank's format must not be applied
            // to every sender; previously senderPattern was stored but never read, so
            // whichever rule matched first won regardless of who sent the message.
            if (!senderMatchesRule(sender, rule.senderPattern)) continue
            try {
                val pattern = Pattern.compile(rule.regexPattern, Pattern.CASE_INSENSITIVE)
                val matcher = pattern.matcher(cleanBody)
                if (matcher.find()) {
                    val rawAmountStr = matcher.group(rule.amountGroup)?.replace(",", "") ?: ""
                    val amount = rawAmountStr.toDoubleOrNull()
                    if (amount != null && amount > 0) {
                        val dirStr = if (rule.directionGroup > 0 && rule.directionGroup <= matcher.groupCount()) {
                            matcher.group(rule.directionGroup)?.lowercase(Locale.ROOT) ?: "debit"
                        } else "debit"

                        val direction = if (dirStr.contains("credit") || dirStr.contains("received") || dirStr.contains("refund")) "CREDIT" else "DEBIT"
                        // F1.3 — fall back to scanning the body. Most seeded rules
                        // leave accountGroup null, so relying on the capture group
                        // alone meant the account tail was never extracted at all
                        // whenever a DB rule matched (which is almost always).
                        val accountTail = rule.accountGroup
                            ?.let { if (it <= matcher.groupCount()) matcher.group(it) else null }
                            ?: extractAccountTail(cleanBody)
                        val channel = rule.channelGroup?.let { if (it <= matcher.groupCount()) matcher.group(it) else null } ?: detectChannel(cleanBody)
                        val named = cleanMerchantName(matcher.groupOrNull(rule.merchantGroup) ?: "")
                            .takeUnless { it == UNKNOWN_MERCHANT }
                        val counterparty =
                            if (named == null) counterpartyMerchant(cleanBody, direction, accountTail)
                            else null
                        val merchant = named ?: counterparty ?: UNKNOWN_MERCHANT
                        val txnType =
                            detectTxnType(cleanBody, direction, channel, counterparty != null)
                        val category = resolveCategory(merchant, merchantRules, txnType)

                        return ParseResult(
                            parsedTransaction = TransactionEntity(
                                amount = amount,
                                direction = direction,
                                timestamp = timestamp,
                                sender = sender,
                                merchant = merchant,
                                accountTail = accountTail,
                                channel = channel,
                                category = category,
                                rawMessage = body,
                                balanceAfter = balanceAfter,
                                status = status,
                                txnType = txnType,
                                hash = hash,
                                txnHash = generateTxnHash(amount, direction, merchant, accountTail, timestamp)
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // Rule evaluation failover
            }
        }

        // Try standard built-in fallback patterns
        for (spec in PATTERNS) {
            val matcher = spec.pattern.matcher(cleanBody)
            if (matcher.find()) {
                val extractedAmount = extractAmount(matcher, spec, cleanBody)
                if (extractedAmount != null && extractedAmount > 0) {
                    // The captured verb sits next to the amount it belongs to, so it
                    // survives a message that names a second account in the other
                    // direction. Only fall back to scanning the body when the pattern
                    // has no direction group at all.
                    val direction = directionOf(matcher.groupOrNull(spec.directionGroup))
                        ?: detectDirection(cleanBody)
                    val accountTail = extractAccountTail(cleanBody)
                    val channel = detectChannel(cleanBody)
                    val named = cleanMerchantName(matcher.groupOrNull(spec.merchantGroup) ?: "")
                        .takeUnless { it == UNKNOWN_MERCHANT }
                    val counterparty =
                        if (named == null) counterpartyMerchant(cleanBody, direction, accountTail)
                        else null
                    val merchant = named ?: counterparty ?: UNKNOWN_MERCHANT
                    val txnType = detectTxnType(cleanBody, direction, channel, counterparty != null)
                    val category = resolveCategory(merchant, merchantRules, txnType)

                    return ParseResult(
                        parsedTransaction = TransactionEntity(
                            amount = extractedAmount,
                            direction = direction,
                            timestamp = timestamp,
                            sender = sender,
                            merchant = merchant,
                            accountTail = accountTail,
                            channel = channel,
                            category = category,
                            rawMessage = body,
                            balanceAfter = balanceAfter,
                            status = status,
                            txnType = txnType,
                            hash = hash,
                            txnHash = generateTxnHash(extractedAmount, direction, merchant, accountTail, timestamp)
                        )
                    )
                }
            }
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

    private fun extractAmount(
        matcher: java.util.regex.Matcher,
        spec: BuiltInPattern,
        text: String
    ): Double? {
        matcher.groupOrNull(spec.amountGroup)?.replace(",", "")?.toDoubleOrNull()
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
            return if (direction == "DEBIT") "Transfer to A/c $tail" else "Transfer from A/c $tail"
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
            lower.contains("upi") || lower.contains("vpa") -> "UPI"
            lower.contains("card") || lower.contains("pos") -> "Card"
            lower.contains("atm") || lower.contains("cash") -> "ATM"
            lower.contains("neft") || lower.contains("rtgs") || lower.contains("imps") || lower.contains("netbanking") -> "NetBanking"
            else -> "Digital"
        }
    }

    private fun cleanMerchantName(raw: String): String {
        var clean = raw.trim()

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
        Regex(
            "(?i)^.*\\b(?:a/c|ac|acct|account|card)\\s*(?:no\\.?)?\\s*" +
                "(?:xx+|x|\\*+)?\\d{3,6}\\s+(?:to|at|for|towards|via)\\s+(.+)$"
        ).find(clean)?.let { clean = it.groupValues[1].trim() }

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

        // A leading preposition the capture group carried in, e.g. "from FLIPKART".
        clean = clean.replace(Regex("(?i)^\\s*(?:from|to|at|for|by|via)\\s+"), "")

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

        clean = clean.replace(Regex("[^A-Za-z0-9\\s.&'-]"), " ")
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
        for (rule in rules) {
            if (upperMerchant.contains(rule.merchantPattern.uppercase(Locale.ROOT))) {
                return rule.assignedCategory
            }
        }
        // Heuristic fallback matching
        return when {
            upperMerchant.contains("SWIGGY") || upperMerchant.contains("ZOMATO") || upperMerchant.contains("REST") || upperMerchant.contains("CAF") || upperMerchant.contains("FOOD") -> "Food & Dining"
            upperMerchant.contains("AMAZON") || upperMerchant.contains("FLIPKART") || upperMerchant.contains("MYNTRA") || upperMerchant.contains("STORE") -> "Shopping"
            upperMerchant.contains("BLINKIT") || upperMerchant.contains("ZEPTO") || upperMerchant.contains("GROCER") || upperMerchant.contains("MART") -> "Grocery"
            upperMerchant.contains("UBER") || upperMerchant.contains("OLA") || upperMerchant.contains("PETROL") || upperMerchant.contains("FUEL") || upperMerchant.contains("SHELL") -> "Transport & Fuel"
            upperMerchant.contains("NETFLIX") || upperMerchant.contains("SPOTIFY") || upperMerchant.contains("APPLE") || upperMerchant.contains("PRIME") -> "Entertainment & Subs"
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
            TxnType.TRANSFER -> "Transfers"
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
        isAccountTransfer: Boolean = false
    ): String {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            lower.contains("emi") || lower.contains("instalment") || lower.contains("installment") -> TxnType.EMI
            lower.contains("refund") || lower.contains("reversed") || lower.contains("cashback") -> TxnType.REFUND
            channel == "ATM" || lower.contains("withdrawn") -> TxnType.ATM
            lower.contains("charge") && lower.contains("fee") -> TxnType.FEE
            lower.contains("salary") || lower.contains("interest credited") -> TxnType.INCOME
            // A message naming two of the user's own accounts, one debited and one
            // credited, is a transfer whether or not it spells out the rail.
            isAccountTransfer ||
                lower.contains("neft") || lower.contains("imps") || lower.contains("rtgs") ||
                lower.contains("transferred") -> TxnType.TRANSFER
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
