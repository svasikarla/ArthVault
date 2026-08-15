package com.arthvault

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.arthvault.data.local.AppDatabase
import com.arthvault.data.local.DefaultSeedData
import com.arthvault.data.local.entity.AdjustmentEntity
import com.arthvault.data.local.entity.AdjustmentField
import com.arthvault.data.local.entity.AdjustmentSource
import com.arthvault.data.local.entity.STATUS_POSTED
import com.arthvault.data.local.entity.TransactionEntity
import com.arthvault.data.local.entity.TxnType
import com.arthvault.data.parser.SmsParserEngine
import com.arthvault.data.repository.SmsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two ways a parser fix failed to reach the ledger, and the way a bulk edit
 * reached too far into it.
 *
 * All three were found on one real message: an ICICI card spend whose payee sits
 * after a second "on", which an older ruleset reduced to the issuer's own name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReparseAndRecategorizeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var repo: SmsRepository

    private val iciciSender = "AD-ICICIB"
    private val iciciBody =
        "INR 281.00 spent using ICICI Bank Card XX6013 on 08-Aug-26 on FLIPKART INTERN. " +
            "Avl Limit: INR 2,53,859.67. If not you, call 1800 2662/SMS BLOCK 6013 to 9215676766."

    /** What the older ruleset stored for [iciciBody]: the bank, not the payee. */
    private fun staleRow(id: Long = 0L) = TransactionEntity(
        id = id,
        amount = 281.0,
        direction = "DEBIT",
        timestamp = 1_700_000_000_000L,
        sender = iciciSender,
        merchant = "using ICICI Bank",
        accountTail = "6013",
        channel = "Card",
        category = "Other / Misc",
        rawMessage = iciciBody,
        status = STATUS_POSTED,
        txnType = TxnType.PURCHASE,
        hash = "stale-icici-1",
        txnHash = "stale-icici-txn-1"
    )

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
            .allowMainThreadQueries()
            .build()
        AppDatabase.installForTest(db)

        db.parserRuleDao().insertAllRules(BundledParserRules.entities)
        DefaultSeedData.merchantRules.forEach { db.merchantRuleDao().insertOrUpdateRule(it) }

        repo = SmsRepository(context)
    }

    @After
    fun tearDown() {
        AppDatabase.installForTest(null)
        db.close()
    }

    // ---- resolveMerchantPattern ----

    @Test
    fun `a bank-name merchant is resolved back to the real payee`() = runBlocking {
        // Previously impossible: the re-parse was handed an empty rule list, and since
        // T2.2 the engine has no built-in patterns, so it returned null every time and
        // the issuer name survived as the bulk-edit pattern.
        val resolved = repo.resolveMerchantPattern("ICICI Bank", iciciBody, iciciSender)
        assertEquals("FLIPKART INTERN", resolved)
    }

    @Test
    fun `a merchant that is already a real payee is left alone`() = runBlocking {
        assertEquals("SWIGGY", repo.resolveMerchantPattern("SWIGGY", iciciBody, iciciSender))
    }

    @Test
    fun `an unresolvable bank name is returned unchanged rather than invented`() = runBlocking {
        val balanceOnly = "Your ICICI Bank A/c XX6013 has a balance of Rs.45210.50 as on 09-08-25."
        assertEquals("ICICI Bank", repo.resolveMerchantPattern("ICICI Bank", balanceOnly, iciciSender))
    }

    // ---- matchesTxnPattern ----

    @Test
    fun `a bank name does not match every message body from that bank`() {
        // "using ICICI Bank" is in the body of every ICICI card SMS. Matching the
        // pattern against message text therefore selected the issuer's whole history,
        // so a bulk edit aimed at one merchant re-filed unrelated spend.
        val otherPayee = staleRow().copy(
            merchant = "SWIGGY",
            rawMessage = "INR 450.00 spent using ICICI Bank Card XX6013 on 07-Aug-26 on SWIGGY."
        )
        assertFalse(repo.matchesTxnPattern(otherPayee, "ICICI Bank"))
        assertFalse(repo.matchesTxnPattern(otherPayee, "using ICICI Bank"))
    }

    @Test
    fun `a real payee still matches through the message body`() {
        // The body search is what lets a correction reach a row the parser mis-read,
        // so narrowing it for bank names must not disable it for genuine merchants.
        val misparsed = staleRow()
        assertTrue(repo.matchesTxnPattern(misparsed, "FLIPKART INTERN"))
    }

    // ---- reparseStoredTransactions ----

    @Test
    fun `a row stored by an older ruleset is corrected in place`() = runBlocking {
        db.transactionDao().insertTransaction(staleRow())

        val result = repo.reparseStoredTransactions()

        assertEquals(1, result.merchantsCorrected)
        assertEquals(1, result.categoriesCorrected)
        assertEquals(0, result.skippedUserEdited)

        val effective = repo.getAllTransactions().first().single()
        assertEquals("FLIPKART INTERN", effective.merchant)
        assertEquals("Shopping", effective.category)

        // T3.3 — the stored row is never rewritten; the correction is an overlay.
        val stored = repo.getStoredTransactions().first().single()
        assertEquals("using ICICI Bank", stored.merchant)
        assertEquals("Other / Misc", stored.category)
    }

    @Test
    fun `re-parsing twice records nothing the second time`() = runBlocking {
        db.transactionDao().insertTransaction(staleRow())
        repo.reparseStoredTransactions()

        val second = repo.reparseStoredTransactions()
        assertEquals(0, second.merchantsCorrected)
        assertEquals(0, second.categoriesCorrected)
    }

    @Test
    fun `a category the user set by hand survives a re-parse`() = runBlocking {
        val id = db.transactionDao().insertTransaction(staleRow())
        db.adjustmentDao().insert(
            AdjustmentEntity(
                transactionId = id,
                field = AdjustmentField.CATEGORY,
                oldValue = "Other / Misc",
                newValue = "Grocery",
                source = AdjustmentSource.USER
            )
        )

        val result = repo.reparseStoredTransactions()
        assertEquals(1, result.skippedUserEdited)
        assertEquals(0, result.categoriesCorrected)

        val effective = repo.getAllTransactions().first().single()
        assertEquals("Grocery", effective.category)
        // The merchant was not the user's decision, so it is still corrected.
        assertEquals("FLIPKART INTERN", effective.merchant)
    }

    @Test
    fun `a voided transaction is not re-parsed back into the ledger`() = runBlocking {
        val id = db.transactionDao().insertTransaction(staleRow())
        db.adjustmentDao().insert(
            AdjustmentEntity(
                transactionId = id,
                field = AdjustmentField.VOID,
                source = AdjustmentSource.USER
            )
        )

        val result = repo.reparseStoredTransactions()
        assertEquals(0, result.examined)
        assertTrue(repo.getAllTransactions().first().isEmpty())
    }

    @Test
    fun `a manual entry has no bank message to re-parse`() = runBlocking {
        db.transactionDao().insertTransaction(
            staleRow().copy(
                sender = SmsRepository.MANUAL_SENDER,
                merchant = "Chai stall",
                category = "Food & Dining",
                rawMessage = "Manual entry: Chai stall - Rs. 20.0",
                hash = "manual-1",
                txnHash = "manual-txn-1"
            )
        )

        val result = repo.reparseStoredTransactions()
        assertEquals(0, result.merchantsCorrected)
        assertEquals("Chai stall", repo.getAllTransactions().first().single().merchant)
    }

    // ---- global rule safety ----

    @Test
    fun `a bank name is never stored as a merchant rule`() = runBlocking {
        val id = db.transactionDao().insertTransaction(staleRow())

        repo.updateSelectedTransactionCategories(
            selectedTransactionIds = setOf(id),
            newCategory = "Grocery",
            merchantPattern = "ICICI Bank",
            saveGlobalRule = true
        )

        val rules = db.merchantRuleDao().getAllRulesList()
        assertNull(
            "A rule on the issuer would re-file every future card spend from that bank",
            rules.find { it.merchantPattern.equals("ICICI Bank", ignoreCase = true) }
        )
        // The selected transaction is still re-categorised.
        assertEquals("Grocery", repo.getAllTransactions().first().single().category)
    }

    @Test
    fun `a real payee is stored as a merchant rule`() = runBlocking {
        val id = db.transactionDao().insertTransaction(staleRow())

        repo.updateSelectedTransactionCategories(
            selectedTransactionIds = setOf(id),
            newCategory = "Grocery",
            merchantPattern = "FLIPKART INTERN",
            saveGlobalRule = true
        )

        assertNotNull(
            db.merchantRuleDao().getAllRulesList()
                .find { it.merchantPattern == "FLIPKART INTERN" && it.assignedCategory == "Grocery" }
        )
    }

    // ---- the preview the dialog is built from ----

    @Test
    fun `the preview keys on the recovered payee and flags the bank name as unsafe`() = runBlocking {
        db.transactionDao().insertTransaction(staleRow())

        val preview = repo.previewBulkRecategorization(
            merchant = "ICICI Bank",
            newCategory = "Grocery",
            rawMessage = iciciBody,
            sender = iciciSender
        )

        assertEquals("FLIPKART INTERN", preview.merchantPattern)
        assertFalse(preview.isGenericUnsafe)
        assertEquals(1, preview.affectedCount)
    }

    @Test
    fun `an unresolvable bank name is marked unsafe so no rule is offered`() = runBlocking {
        val balanceOnly = "Your ICICI Bank A/c XX6013 has a balance of Rs.45210.50 as on 09-08-25."
        db.transactionDao().insertTransaction(staleRow())

        val preview = repo.previewBulkRecategorization(
            merchant = "ICICI Bank",
            newCategory = "Grocery",
            rawMessage = balanceOnly,
            sender = iciciSender
        )

        assertEquals("ICICI Bank", preview.merchantPattern)
        assertTrue("The dialog defaults its 'save rule' checkbox off from this", preview.isGenericUnsafe)
    }

    // ---- the predicate the three fixes share ----

    @Test
    fun `isUnsafeAsRulePattern covers bank names, generic tokens and real payees`() {
        assertTrue(SmsParserEngine.isUnsafeAsRulePattern("ICICI Bank"))
        assertTrue(SmsParserEngine.isUnsafeAsRulePattern("using ICICI Bank"))
        assertTrue(SmsParserEngine.isUnsafeAsRulePattern("CARD"))
        assertTrue(SmsParserEngine.isUnsafeAsRulePattern("UPI"))
        assertFalse(SmsParserEngine.isUnsafeAsRulePattern("FLIPKART INTERN"))
        assertFalse(SmsParserEngine.isUnsafeAsRulePattern("SWIGGY"))
    }
}
