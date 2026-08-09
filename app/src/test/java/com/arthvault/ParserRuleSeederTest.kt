package com.arthvault

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.arthvault.data.local.AppDatabase
import com.arthvault.data.local.entity.ParserRuleEntity
import com.arthvault.data.parser.rules.ParserRuleJson
import com.arthvault.data.parser.rules.ParserRuleSeeder
import com.arthvault.data.parser.rules.RuleAssetVerifier
import com.arthvault.data.parser.rules.RuleLoadResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * T2.2 / 5.2 — applying a rule file to the database.
 *
 * The rules themselves are covered by `ParserRuleAssetTest` and their accuracy by
 * `ParserAccuracyTest`. What matters here is what happens *around* them: that a
 * rejected file cannot take the working rules down with it, that a user's own rules
 * are never collateral damage, and that re-running on an unchanged file is a no-op
 * rather than a table rewrite on every unlock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ParserRuleSeederTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var seeder: ParserRuleSeeder

    private val assetJson: String by lazy {
        File(System.getProperty("user.dir")!!, "src/main/assets/parser_rules_v1.json").readText()
    }
    private val publicKey: ByteArray by lazy {
        File(System.getProperty("user.dir")!!, "src/main/res/raw/parser_rules_public_key.der")
            .readBytes()
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
            .allowMainThreadQueries()
            .build()
        seeder = ParserRuleSeeder(db.parserRuleDao(), db.appSettingDao(), RuleAssetVerifier(publicKey))
    }

    @After
    fun tearDown() = db.close()

    private val userRule = ParserRuleEntity(
        ruleId = "user.mine",
        ruleName = "My rule",
        senderPattern = "HDFCBK",
        regexPattern = "Rs ([0-9]+) to (.*)",
        amountGroup = 1,
        directionGroup = null,
        merchantGroup = 2,
        accountGroup = null,
        channelGroup = null,
        priority = 0,
        isSystemRule = false
    )

    @Test
    fun `a signed file installs its rules`() = runBlocking {
        val outcome = seeder.apply(assetJson, allowSameVersion = false)

        val expected = ParserRuleJson.parse(assetJson)
        assertTrue("expected Applied but got $outcome", outcome is ParserRuleSeeder.Outcome.Applied)
        assertEquals(
            expected.rules.size,
            (outcome as ParserRuleSeeder.Outcome.Applied).ruleCount
        )
        assertEquals(expected.rules.size, db.parserRuleDao().getActiveRulesList().size)
    }

    @Test
    fun `re-applying the same version does nothing`() = runBlocking {
        seeder.apply(assetJson, allowSameVersion = false)
        val second = seeder.apply(assetJson, allowSameVersion = false)

        // Unattended work on every unlock has to be cheap, or the rule file becomes
        // a reason the app is slow to open.
        assertTrue("expected AlreadyCurrent but got $second", second is ParserRuleSeeder.Outcome.AlreadyCurrent)
    }

    @Test
    fun `a user picking the same version explicitly gets it installed`() = runBlocking {
        seeder.apply(assetJson, allowSameVersion = false)
        val sideloaded = seeder.apply(assetJson, allowSameVersion = true)

        // Choosing a file by hand is a statement of intent; the version check exists
        // to keep the unattended path cheap, not to argue with the user.
        assertTrue("expected Applied but got $sideloaded", sideloaded is ParserRuleSeeder.Outcome.Applied)
    }

    @Test
    fun `a tampered file is rejected and leaves the installed rules alone`() = runBlocking {
        seeder.apply(assetJson, allowSameVersion = false)
        val before = db.parserRuleDao().getActiveRulesList()

        // Bump the version so it would be applied if it verified, then corrupt a
        // regex. Both halves matter: a rejection that only happened because the
        // version was stale would prove nothing.
        val tampered = assetJson
            .replace("\"rulesVersion\": 1", "\"rulesVersion\": 99")
            .replace("builtin.card-purchase", "builtin.card-purchase-EVIL")

        val outcome = seeder.apply(tampered, allowSameVersion = false)

        assertTrue("expected Rejected but got $outcome", outcome is ParserRuleSeeder.Outcome.Rejected)
        assertEquals(
            RuleLoadResult.BadSignature,
            (outcome as ParserRuleSeeder.Outcome.Rejected).reason
        )
        assertEquals(
            "a rejected file must not disturb the working rules",
            before.map { it.ruleId },
            db.parserRuleDao().getActiveRulesList().map { it.ruleId }
        )
    }

    @Test
    fun `installing system rules never touches the user's own`() = runBlocking {
        db.parserRuleDao().insertRule(userRule)
        seeder.apply(assetJson, allowSameVersion = false)

        val rules = db.parserRuleDao().getActiveRulesList()
        assertTrue(
            "the user's rule must survive a system rule install",
            rules.any { it.ruleId == "user.mine" && !it.isSystemRule }
        )
    }

    @Test
    fun `withdrawing a system rule does not withdraw a user rule`() = runBlocking {
        db.parserRuleDao().insertRule(userRule)
        seeder.apply(assetJson, allowSameVersion = false)

        // A later rule file that lists only one rule: every other *system* rule is
        // withdrawn, the user's is not.
        db.parserRuleDao().deleteSystemRulesNotIn(listOf("builtin.card-purchase"))

        val remaining = db.parserRuleDao().getActiveRulesList().map { it.ruleId }.toSet()
        assertEquals(setOf("builtin.card-purchase", "user.mine"), remaining)
    }

    @Test
    fun `user rules are evaluated before system rules`() = runBlocking {
        db.parserRuleDao().insertRule(userRule)
        seeder.apply(assetJson, allowSameVersion = false)

        // F2.2 — an override that runs after the rule it overrides is not an override.
        assertEquals("user.mine", db.parserRuleDao().getActiveRulesList().first().ruleId)
    }

    @Test
    fun `the bundled asset installs from assets`() = runBlocking {
        // Exercises the real path the app uses at unlock, including reading from the
        // packaged assets rather than from the source tree.
        val outcome = seeder.seedFromAsset(context)
        assertTrue("expected Applied but got $outcome", outcome is ParserRuleSeeder.Outcome.Applied)
    }
}
