package com.arthvault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.arthvault.data.local.entity.AdjustmentEntity
import com.arthvault.data.local.entity.AppSettingEntity
import com.arthvault.data.local.entity.BillNoticeEntity
import com.arthvault.data.local.entity.CategoryEntity
import com.arthvault.data.local.entity.MerchantRuleEntity
import com.arthvault.data.local.entity.OwnAccountEntity
import com.arthvault.data.local.entity.ParserRuleEntity
import com.arthvault.data.local.entity.SenderAllowlistEntity
import com.arthvault.data.local.entity.TransactionEntity
import com.arthvault.data.local.entity.UnparsedSmsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun getAllTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE hash = :hash LIMIT 1")
    suspend fun getTransactionByHash(hash: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE category = :category ORDER BY timestamp DESC")
    fun getTransactionsByCategory(category: String): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE merchant LIKE '%' || :merchant || '%' ORDER BY timestamp DESC")
    suspend fun getTransactionsByMerchant(merchant: String): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp DESC")
    fun getTransactionsInRange(startTime: Long, endTime: Long): Flow<List<TransactionEntity>>

    @Query("SELECT id FROM transactions WHERE merchant LIKE '%' || :merchant || '%'")
    suspend fun findIdsByMerchant(merchant: String): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTransaction(transaction: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllTransactions(transactions: List<TransactionEntity>)

    // T3.3 — no @Update, no @Delete, and no bulk UPDATE. Corrections are inserts
    // into `adjustments`; removal is a VOID adjustment. The absence of these
    // methods is the enforcement: mutation is not reachable from application
    // code, rather than merely discouraged by a comment.

    /** F5.2 only. An explicit, user-initiated erasure of everything. */
    @Query("DELETE FROM transactions")
    suspend fun deleteAllTransactions()
}

@Dao
interface BillNoticeDao {
    @Query("SELECT * FROM bill_notices ORDER BY issuedAt DESC")
    fun getAll(): Flow<List<BillNoticeEntity>>

    @Query("SELECT * FROM bill_notices ORDER BY issuedAt DESC")
    suspend fun getAllList(): List<BillNoticeEntity>

    /**
     * IGNORE rather than REPLACE, and the unique index on `noticeHash` is what makes
     * it fire. A biller re-sending the same reminder must not produce a second row,
     * and REPLACE would churn the primary key on every re-send.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(notice: BillNoticeEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(notices: List<BillNoticeEntity>)

    /** F5.2 — a bill notice names an account and an amount owed. It is user data. */
    @Query("DELETE FROM bill_notices")
    suspend fun deleteAll()
}

@Dao
interface AdjustmentDao {
    @Query("SELECT * FROM adjustments ORDER BY createdAt ASC")
    fun getAll(): Flow<List<AdjustmentEntity>>

    @Query("SELECT * FROM adjustments WHERE transactionId = :transactionId ORDER BY createdAt ASC")
    suspend fun getForTransaction(transactionId: Long): List<AdjustmentEntity>

    @Insert
    suspend fun insert(adjustment: AdjustmentEntity): Long

    @Insert
    suspend fun insertAll(adjustments: List<AdjustmentEntity>)

    @Query("DELETE FROM adjustments")
    suspend fun deleteAll()
}

@Dao
interface AppSettingDao {
    @Query("SELECT value FROM app_settings WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(setting: AppSettingEntity)

    @Query("DELETE FROM app_settings")
    suspend fun deleteAll()
}

@Dao
interface SenderAllowlistDao {
    @Query("SELECT * FROM sender_allowlist ORDER BY senderId")
    fun getAll(): Flow<List<SenderAllowlistEntity>>

    @Query("SELECT senderId FROM sender_allowlist WHERE isEnabled = 1")
    suspend fun getEnabledSenderIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(sender: SenderAllowlistEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDefaults(senders: List<SenderAllowlistEntity>)

    @Query("DELETE FROM sender_allowlist WHERE senderId = :senderId")
    suspend fun delete(senderId: String)
}

@Dao
interface OwnAccountDao {
    @Query("SELECT * FROM own_accounts ORDER BY tail")
    fun getAll(): Flow<List<OwnAccountEntity>>

    @Query("SELECT tail FROM own_accounts")
    suspend fun getTails(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: OwnAccountEntity)

    @Query("DELETE FROM own_accounts WHERE tail = :tail")
    suspend fun delete(tail: String)

    /** F5.2 — part of the full local wipe. */
    @Query("DELETE FROM own_accounts")
    suspend fun deleteAll()

    /**
     * Account tails the parser has actually seen, most-used first — the candidate
     * list the settings screen offers. Derived from the ledger rather than stored,
     * so a newly appearing account shows up without any bookkeeping.
     */
    @Query(
        "SELECT accountTail FROM transactions WHERE accountTail IS NOT NULL " +
            "GROUP BY accountTail ORDER BY COUNT(*) DESC"
    )
    fun getObservedTails(): Flow<List<String>>
}

@Dao
interface UnparsedSmsDao {
    @Query("SELECT * FROM unparsed_sms WHERE isReviewed = 0 ORDER BY timestamp DESC")
    fun getUnreviewedSms(): Flow<List<UnparsedSmsEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUnparsedSms(sms: UnparsedSmsEntity)

    @Query("UPDATE unparsed_sms SET isReviewed = 1 WHERE id = :id")
    suspend fun markAsReviewed(id: Long)

    @Query("DELETE FROM unparsed_sms")
    suspend fun deleteAllUnparsedSms()
}

@Dao
interface MerchantRuleDao {
    @Query("SELECT * FROM merchant_rules")
    fun getAllRules(): Flow<List<MerchantRuleEntity>>

    @Query("SELECT * FROM merchant_rules")
    suspend fun getAllRulesList(): List<MerchantRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateRule(rule: MerchantRuleEntity)

    @Query("DELETE FROM merchant_rules WHERE merchantPattern = :merchantPattern")
    suspend fun deleteRule(merchantPattern: String)

    /** F5.2 — merchant rules record the user's own corrections, so a full wipe must clear them. */
    @Query("DELETE FROM merchant_rules")
    suspend fun deleteAllRules()
}

@Dao
interface ParserRuleDao {
    // T2.2 — evaluation order is `priority` then `ruleId`, never insertion order.
    // Which rule matches first decides what the ledger records, so the order has to
    // be a property of the rules rather than of how they happened to be inserted.
    // `ruleId` breaks ties so the ordering is total and the same on every device.
    @Query("SELECT * FROM parser_rules WHERE isActive = 1 ORDER BY priority ASC, ruleId ASC")
    fun getActiveRules(): Flow<List<ParserRuleEntity>>

    @Query("SELECT * FROM parser_rules WHERE isActive = 1 ORDER BY priority ASC, ruleId ASC")
    suspend fun getActiveRulesList(): List<ParserRuleEntity>

    @Query("SELECT * FROM parser_rules WHERE isSystemRule = 1")
    suspend fun getSystemRules(): List<ParserRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: ParserRuleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllRules(rules: List<ParserRuleEntity>)

    /**
     * Withdraws system rules a newer rule file no longer lists.
     *
     * Scoped to system rules: a rule file has no authority over what the user wrote.
     */
    @Query("DELETE FROM parser_rules WHERE isSystemRule = 1 AND ruleId NOT IN (:keep)")
    suspend fun deleteSystemRulesNotIn(keep: List<String>)

    @Query("DELETE FROM parser_rules WHERE isSystemRule = 0 AND id = :id")
    suspend fun deleteCustomRule(id: Long)

    /** User-authored rules only; system rules are re-seeded, not user data. */
    @Query("DELETE FROM parser_rules WHERE isSystemRule = 0")
    suspend fun deleteAllCustomRules()
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories")
    fun getAllCategories(): Flow<List<CategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(category: CategoryEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDefaultCategories(categories: List<CategoryEntity>)

    /** Only user-created categories; the built-in set is not user data. */
    @Query("DELETE FROM categories WHERE isCustom = 1")
    suspend fun deleteCustomCategories()

    /**
     * Removes one user-created category.
     *
     * `isCustom = 1` is in the WHERE rather than left to the caller: a built-in
     * category is re-seeded on every wipe, so deleting one produces a row that comes
     * back by itself. See [com.arthvault.data.local.CategoryEditor.canDelete] for the
     * checks that run before this is reached.
     */
    @Query("DELETE FROM categories WHERE name = :name AND isCustom = 1")
    suspend fun deleteCustomCategory(name: String)
}
