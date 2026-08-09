package com.example.data.local

import com.example.data.local.entity.CategoryEntity
import com.example.data.local.entity.MerchantRuleEntity
import com.example.data.local.entity.ParserRuleEntity
import com.example.data.local.entity.SenderAllowlistEntity

/**
 * The built-in categories, merchant rules and parser rules.
 *
 * These are *system* data, not user data. Two paths need them: first-run database
 * creation, and restoring the baseline after a full local wipe (F5.2) — the wipe
 * clears the user's own rules, and without a re-seed the app would come back with
 * no categorisation at all.
 */
object DefaultSeedData {

    val categories: List<CategoryEntity> = listOf(
        CategoryEntity("Food & Dining", "Fastfood", "#FF6B6B"),
        CategoryEntity("Shopping", "ShoppingBag", "#4ECDC4"),
        CategoryEntity("Grocery", "ShoppingCart", "#45B7D1"),
        CategoryEntity("Utilities & Bills", "Receipt", "#FFA07A"),
        CategoryEntity("Transport & Fuel", "LocalGasStation", "#96CEB4"),
        CategoryEntity("Entertainment & Subs", "Movie", "#D4A5A5"),
        CategoryEntity("Health & Medical", "LocalHospital", "#9B59B6"),
        CategoryEntity("Income & Refunds", "TrendingUp", "#2ECC71"),
        CategoryEntity("Transfers", "SwapHoriz", "#34495E"),
        CategoryEntity("ATM Cash", "Atm", "#E67E22"),
        CategoryEntity("Other / Misc", "Category", "#95A5A6")
    )

    val merchantRules: List<MerchantRuleEntity> = listOf(
        MerchantRuleEntity("SWIGGY", "Food & Dining"),
        MerchantRuleEntity("ZOMATO", "Food & Dining"),
        MerchantRuleEntity("UBER EATS", "Food & Dining"),
        MerchantRuleEntity("STARBUCKS", "Food & Dining"),
        MerchantRuleEntity("MCDONALDS", "Food & Dining"),
        MerchantRuleEntity("AMAZON", "Shopping"),
        MerchantRuleEntity("FLIPKART", "Shopping"),
        MerchantRuleEntity("MYNTRA", "Shopping"),
        MerchantRuleEntity("BLINKIT", "Grocery"),
        MerchantRuleEntity("ZEPTO", "Grocery"),
        MerchantRuleEntity("BIGBASKET", "Grocery"),
        MerchantRuleEntity("INSTAMART", "Grocery"),
        MerchantRuleEntity("UBER", "Transport & Fuel"),
        MerchantRuleEntity("OLA", "Transport & Fuel"),
        MerchantRuleEntity("SHELL", "Transport & Fuel"),
        MerchantRuleEntity("PETROL", "Transport & Fuel"),
        MerchantRuleEntity("IOCL", "Transport & Fuel"),
        MerchantRuleEntity("HPCL", "Transport & Fuel"),
        MerchantRuleEntity("BPCL", "Transport & Fuel"),
        MerchantRuleEntity("NETFLIX", "Entertainment & Subs"),
        MerchantRuleEntity("SPOTIFY", "Entertainment & Subs"),
        MerchantRuleEntity("APPLE", "Entertainment & Subs"),
        MerchantRuleEntity("PRIME", "Entertainment & Subs"),
        MerchantRuleEntity("HOTSTAR", "Entertainment & Subs"),
        MerchantRuleEntity("BESCOM", "Utilities & Bills"),
        MerchantRuleEntity("AIRTEL", "Utilities & Bills"),
        MerchantRuleEntity("JIO", "Utilities & Bills"),
        MerchantRuleEntity("ATM", "ATM Cash"),
        MerchantRuleEntity("NEFT SALARY", "Income & Refunds"),
        MerchantRuleEntity("SALARY", "Income & Refunds")
    )

    /**
     * F1.1 — DLT entity codes for the major Indian banks and payment apps.
     *
     * These are normalised codes, not full sender headers: "AD-HDFCBK-S" reduces to
     * "HDFCBK". Users add their own on the Rules & Parse screen; an empty list is
     * treated as "allow everything" so a misconfigured allowlist cannot silently
     * hide the entire inbox.
     */
    val senderAllowlist: List<SenderAllowlistEntity> = listOf(
        SenderAllowlistEntity("HDFCBK", "HDFC Bank"),
        SenderAllowlistEntity("ICICIB", "ICICI Bank"),
        SenderAllowlistEntity("ICICIT", "ICICI Bank"),
        SenderAllowlistEntity("SBIINB", "State Bank of India"),
        SenderAllowlistEntity("SBIUPI", "SBI UPI"),
        SenderAllowlistEntity("ATMSBI", "SBI ATM"),
        SenderAllowlistEntity("AXISBK", "Axis Bank"),
        SenderAllowlistEntity("KOTAKB", "Kotak Mahindra Bank"),
        SenderAllowlistEntity("PNBSMS", "Punjab National Bank"),
        SenderAllowlistEntity("CANBNK", "Canara Bank"),
        SenderAllowlistEntity("BOBSMS", "Bank of Baroda"),
        SenderAllowlistEntity("IDFCFB", "IDFC First Bank"),
        SenderAllowlistEntity("INDUSB", "IndusInd Bank"),
        SenderAllowlistEntity("YESBNK", "Yes Bank"),
        SenderAllowlistEntity("AMEXIN", "American Express"),
        SenderAllowlistEntity("ONECRD", "OneCard"),
        SenderAllowlistEntity("SCISMS", "Standard Chartered"),
        SenderAllowlistEntity("PAYTMB", "Paytm Payments Bank"),
        SenderAllowlistEntity("PHONPE", "PhonePe"),
        SenderAllowlistEntity("GPAYIN", "Google Pay"),
        SenderAllowlistEntity("CRED", "CRED"),
        SenderAllowlistEntity("BANK-SMS", "Imported / pasted messages")
    )

    val parserRules: List<ParserRuleEntity> = listOf(
        // Rule 1: Universal Debit/Credit SMS (HDFC, ICICI, SBI, Axis, Kotak, PayTM, PhonePe)
        ParserRuleEntity(
            ruleName = "Universal Debit/Spent SMS",
            senderPattern = ".*",
            regexPattern = "(?i)(?:Rs\\.?|INR)\\s*([0-9,]+(?:\\.[0-9]{2})?)\\s*(?:has been|is)?\\s*(debited|spent|paid|sent|credited|received|withdrawn)\\s*(?:from|to|at|for|via)?\\s*([A-Za-z0-9\\s.&'/-]+?)(?=\\s+on|\\s+ref|\\s+avail|\\s+Bal|\\.|\\,|$)",
            amountGroup = 1,
            directionGroup = 2,
            merchantGroup = 3,
            accountGroup = null,
            channelGroup = null,
            isSystemRule = true
        ),
        // Rule 2: UPI Transaction format
        ParserRuleEntity(
            ruleName = "UPI Transaction Standard",
            senderPattern = ".*",
            regexPattern = "(?i)Paid\\s*(?:Rs\\.?|INR)?\\s*([0-9,]+(?:\\.[0-9]{2})?)\\s*to\\s*([A-Za-z0-9\\s.@_-]+)\\s*via\\s*UPI",
            amountGroup = 1,
            directionGroup = 0, // Default to DEBIT
            merchantGroup = 2,
            accountGroup = null,
            channelGroup = null,
            isSystemRule = true
        ),
        // Rule 3: Card Transaction format
        ParserRuleEntity(
            ruleName = "Credit/Debit Card Purchase",
            senderPattern = ".*",
            regexPattern = "(?i)Spent\\s*(?:Rs\\.?|INR)?\\s*([0-9,]+(?:\\.[0-9]{2})?)\\s*on\\s*Card\\s*(?:xx|[*]+)?([0-9]{4})?\\s*at\\s*([A-Za-z0-9\\s.&'/-]+?)\\s*on",
            amountGroup = 1,
            directionGroup = 0, // DEBIT
            merchantGroup = 3,
            accountGroup = 2,
            channelGroup = null,
            isSystemRule = true
        )
    )
}
