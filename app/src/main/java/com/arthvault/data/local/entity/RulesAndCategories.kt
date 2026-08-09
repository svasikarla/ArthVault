package com.arthvault.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "merchant_rules")
data class MerchantRuleEntity(
    @PrimaryKey
    val merchantPattern: String, // Normalized upper-case string or keyword e.g. "SWIGGY"
    val assignedCategory: String,
    val updatedTimestamp: Long = System.currentTimeMillis()
)

/**
 * A parser rule as stored. See `data/parser/rules/ParserRuleDocument.kt` for the
 * signed wire format this is populated from (T2.2).
 */
@Entity(
    tableName = "parser_rules",
    // T2.2 — system rules are upserted by [ruleId] when a newer signed rule file
    // arrives. Without uniqueness the "upsert" would append a second copy of every
    // rule on each update, and both copies would then match.
    indices = [Index(value = ["ruleId"], unique = true)]
)
data class ParserRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /**
     * Stable identity across rule-file versions, e.g. `builtin.card-purchase`.
     *
     * The row id cannot serve: it is assigned by SQLite and means nothing to a rule
     * file, so without this an updated rule set could only be applied by deleting
     * every system rule and reinserting — which loses the distinction between a rule
     * that changed and a rule that was withdrawn.
     */
    val ruleId: String,
    val ruleName: String,
    val senderPattern: String, // e.g. "HDFCBK|ICICIB|SBIBNK|AXISBK|KOTAKB|PAYTM"
    val regexPattern: String,
    val amountGroup: Int,
    /**
     * null means "read the direction from the message body".
     *
     * This used to be a non-null Int where 0 meant "assume DEBIT", which quietly
     * mis-signed every credit matched by a rule with no direction capture. Scanning
     * the body is what the built-in patterns always did, and it is right far more
     * often than assuming.
     */
    val directionGroup: Int?,
    val merchantGroup: Int?,
    val accountGroup: Int?,
    val channelGroup: Int?,
    /**
     * Evaluation order, lowest first. User-authored rules default to 0 so they run
     * ahead of the bundled ones — F2.2 says a user override persists, which it
     * cannot do if a system rule matches first.
     */
    val priority: Int = 0,
    val isActive: Boolean = true,
    val isSystemRule: Boolean = true
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey
    val name: String,
    val iconName: String, // e.g. "ShoppingBag", "Fastfood", "DirectionsCar"
    val colorHex: String,
    val isCustom: Boolean = false
)
