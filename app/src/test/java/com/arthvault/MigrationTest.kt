package com.arthvault

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.arthvault.data.local.AppDatabase
import com.arthvault.data.local.DefaultSeedData
import com.arthvault.data.local.entity.AdjustmentField
import com.arthvault.data.local.entity.AdjustmentSource
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * T3.4 — forward migration verified, not hoped for.
 *
 * Every link in the chain, not just the newest one. A v3→v4 test alone leaves anyone
 * still on v1 or v2 migrating on hope, and those are the installs with the most
 * history to lose.
 *
 * Each version is built from the schema Room itself exported — `app/schemas/N.json` —
 * rather than from SQL retyped here, which would only prove the migration is
 * consistent with whatever the test author believed that version looked like. v1 is
 * the exception and is reconstructed; see [schemaFile].
 *
 * Two things are checked, and the second is the one that bites:
 *
 *  1. the data transformation preserves what it should and discards what it should;
 *  2. the *resulting schema* is exactly what Room expects at the current version. A
 *     migration that produces a subtly different table — a missing index, a
 *     nullability mismatch — succeeds here and then throws on every launch after.
 *
 * Runs under Robolectric with the framework SQLite factory. SQLCipher is not
 * involved: migrations are SQL, and the cipher layer sits underneath them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MigrationTest {

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val SCHEMA_DIR = "schemas/com.arthvault.data.local.AppDatabase"
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        context.getDatabasePath(TEST_DB).let { base ->
            listOf(base, File("${base.path}-wal"), File("${base.path}-shm"), File("${base.path}-journal"))
                .forEach { it.delete() }
        }
    }

    @Test
    fun `v3 to v4 keeps the user's own parser rules`() {
        createSchemaVersion(3).use { db ->
            // A rule the user wrote. It must survive.
            db.execSQL(
                "INSERT INTO parser_rules (id, ruleName, senderPattern, regexPattern, " +
                    "amountGroup, directionGroup, merchantGroup, accountGroup, channelGroup, " +
                    "isDebitKeyword, isActive, isSystemRule) VALUES " +
                    "(1, 'My HDFC rule', 'HDFCBK', 'Rs ([0-9]+) to (.*)', 1, 0, 2, NULL, NULL, " +
                    "'debited', 1, 0)"
            )
            // A seeded rule of the kind Phase 7 found booking payments to 'HDFC Bank'.
            // It must not survive: it is re-seeded from the signed asset instead.
            db.execSQL(
                "INSERT INTO parser_rules (id, ruleName, senderPattern, regexPattern, " +
                    "amountGroup, directionGroup, merchantGroup, accountGroup, channelGroup, " +
                    "isDebitKeyword, isActive, isSystemRule) VALUES " +
                    "(2, 'Universal Debit/Spent SMS', '.*', '(?i)Rs ([0-9,]+)', 1, 2, 3, NULL, " +
                    "NULL, 'debited', 1, 1)"
            )
        }

        val db = migrateToLatest()

        db.query("SELECT ruleId, ruleName, directionGroup, priority, isSystemRule FROM parser_rules")
            .use { cursor ->
                assertEquals("only the user's rule should remain", 1, cursor.count)
                cursor.moveToFirst()
                assertEquals("user.1", cursor.getString(0))
                assertEquals("My HDFC rule", cursor.getString(1))
                // directionGroup 0 was the old "assume DEBIT" sentinel and becomes
                // NULL, meaning "read the direction from the body".
                assertTrue("directionGroup 0 must migrate to NULL", cursor.isNull(2))
                assertEquals("user rules run before system rules", 0, cursor.getInt(3))
                assertEquals(0, cursor.getInt(4))
            }
    }

    @Test
    fun `v3 to v4 leaves the ledger untouched`() {
        createSchemaVersion(3).use { db ->
            db.execSQL(
                "INSERT INTO transactions (id, amount, direction, timestamp, sender, merchant, " +
                    "accountTail, channel, category, rawMessage, balanceAfter, status, txnType, " +
                    "hash, txnHash) VALUES (1, 45425.0, 'DEBIT', 1754700000000, 'AD-ICICIB-S', " +
                    "'Transfer to A/c 066', '635', 'NetBanking', 'Transfers', 'raw', 12000.0, " +
                    "'POSTED', 'TRANSFER', 'h1', 't1')"
            )
        }

        val db = migrateToLatest()

        db.query("SELECT amount, merchant, accountTail FROM transactions").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(45425.0, cursor.getDouble(0), 0.001)
            assertEquals("Transfer to A/c 066", cursor.getString(1))
            assertEquals("635", cursor.getString(2))
        }
    }

    @Test
    fun `the migrated schema is exactly what Room expects`() {
        createSchemaVersion(3).use { /* empty database is enough for a schema check */ }

        // Opening through Room *is* the assertion. Room compares the migrated
        // database's identity hash against the one compiled in from 5.json and
        // throws "Migration didn't properly handle" if they differ — a migration
        // that produces a near-miss schema passes every data check above and then
        // crashes the app on the next launch.
        migrateToLatest()
    }

    // ---- v4 -> v5 --------------------------------------------------------

    @Test
    fun `v4 to v5 adds own_accounts empty, changing nobody's totals on upgrade`() {
        createSchemaVersion(4).use { db ->
            db.execSQL(
                "INSERT INTO transactions (id, amount, direction, timestamp, sender, merchant, " +
                    "accountTail, channel, category, rawMessage, balanceAfter, status, txnType, " +
                    "hash, txnHash) VALUES (1, 20000.0, 'DEBIT', 1754700000000, 'AD-ICICIB-S', " +
                    "'Transfer to A/c 066', '635', 'NetBanking', 'Transfers', 'raw', NULL, " +
                    "'POSTED', 'TRANSFER', 'h1', 't1')"
            )
        }

        val db = migrateToLatest()

        // Empty on arrival is the point. Marking accounts changes what counts as
        // spending, so an upgrade that guessed would silently restate the user's
        // history — indistinguishable, from the outside, from the app being broken.
        db.query("SELECT COUNT(*) FROM own_accounts").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM transactions").use { cursor ->
            cursor.moveToFirst()
            assertEquals("v5 is additive; the ledger is not touched", 1, cursor.getInt(0))
        }
    }

    @Test
    fun `the unique ruleId index survives the migration`() {
        createSchemaVersion(3).use { }
        val db = migrateToLatest()

        db.query("PRAGMA index_list(parser_rules)").use { cursor ->
            val unique = generateSequence({ if (cursor.moveToFirst()) cursor else null }) {
                if (cursor.moveToNext()) cursor else null
            }.any { it.getString(1) == "index_parser_rules_ruleId" && it.getInt(2) == 1 }

            assertTrue("ruleId must be uniquely indexed or upserts become appends", unique)
        }
    }

    // ---- v1 -> v2 --------------------------------------------------------

    @Test
    fun `v1 to v2 defaults the new columns rather than leaving them null`() {
        createSchemaVersion(1).use { db ->
            insertV1Transaction(db, id = 1, hash = "h1")
        }

        val db = migrateToLatest()

        db.query("SELECT status, txnType, txnHash, balanceAfter FROM transactions WHERE id = 1")
            .use { cursor ->
                cursor.moveToFirst()
                // status and txnType are NOT NULL in v3's rebuilt table. If the ALTER
                // had omitted its DEFAULT, every pre-existing row would arrive at the
                // rebuild holding NULL and the INSERT would fail — taking the whole
                // upgrade with it.
                assertEquals("POSTED", cursor.getString(0))
                assertEquals("PURCHASE", cursor.getString(1))
                assertEquals("", cursor.getString(2))
                // balanceAfter is genuinely unknown for a v1 row; null is correct.
                assertTrue(cursor.isNull(3))
            }
    }

    @Test
    fun `v1 to v2 keeps the earliest of a duplicate pair the unique index would reject`() {
        createSchemaVersion(1).use { db ->
            // v1 had no unique index on hash and deduped in application code, so a
            // real database can hold collisions. CREATE UNIQUE INDEX over them fails,
            // which would abort the migration and leave the install unopenable.
            insertV1Transaction(db, id = 5, hash = "dupe", merchant = "first seen")
            insertV1Transaction(db, id = 9, hash = "dupe", merchant = "later copy")
            insertV1Transaction(db, id = 11, hash = "unique", merchant = "untouched")
        }

        val db = migrateToLatest()

        db.query("SELECT id, merchant FROM transactions ORDER BY id").use { cursor ->
            assertEquals("the duplicate must be collapsed, not both kept", 2, cursor.count)
            cursor.moveToFirst()
            assertEquals(5L, cursor.getLong(0))
            assertEquals("first seen", cursor.getString(1))
            cursor.moveToNext()
            assertEquals(11L, cursor.getLong(0))
        }
    }

    @Test
    fun `v1 to v2 seeds the allowlist an upgrading install would otherwise never get`() {
        createSchemaVersion(1).use { }
        val db = migrateToLatest()

        // onCreate fires only for a brand new database. Without this seeding an
        // upgrading install reaches v2 with an empty allowlist, and an empty
        // allowlist reads no SMS at all — the app silently stops ingesting.
        db.query("SELECT COUNT(*) FROM sender_allowlist").use { cursor ->
            cursor.moveToFirst()
            assertEquals(DefaultSeedData.senderAllowlist.size, cursor.getInt(0))
        }
    }

    // ---- the whole chain -------------------------------------------------

    @Test
    fun `a v1 ledger survives every migration intact`() {
        createSchemaVersion(1).use { db ->
            insertV1Transaction(db, id = 1, hash = "h1", merchant = "swiggy@ybl")
            db.execSQL(
                "INSERT INTO parser_rules (id, ruleName, senderPattern, regexPattern, " +
                    "amountGroup, directionGroup, merchantGroup, accountGroup, channelGroup, " +
                    "isDebitKeyword, isActive, isSystemRule) VALUES " +
                    "(1, 'My rule', 'HDFCBK', 'Rs ([0-9]+) to (.*)', 1, 0, 2, NULL, NULL, " +
                    "'debited', 1, 0)"
            )
        }

        // Three migrations run back to back. Each is tested in isolation above; this
        // asserts they compose, which is the only path a real v1 install takes.
        val db = migrateToLatest()

        db.query("SELECT merchant, status FROM transactions WHERE id = 1").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("swiggy@ybl", cursor.getString(0))
            assertEquals("POSTED", cursor.getString(1))
        }
        db.query("SELECT ruleId FROM parser_rules").use { cursor ->
            assertEquals("the user's rule must outlive all three migrations", 1, cursor.count)
            cursor.moveToFirst()
            assertEquals("user.1", cursor.getString(0))
        }
    }

    // ---- v2 -> v3 --------------------------------------------------------

    @Test
    fun `v2 to v3 recovers an in-place edit as an adjustment`() {
        createSchemaVersion(2).use { db ->
            insertV2Transaction(db, id = 1, category = "Groceries", isUserModified = 1)
            insertV2Transaction(db, id = 2, category = "Shopping", isUserModified = 0)
        }

        val db = migrateToLatest()

        db.query(
            "SELECT transactionId, field, oldValue, newValue, source FROM adjustments"
        ).use { cursor ->
            assertEquals("only the edited row should produce an adjustment", 1, cursor.count)
            cursor.moveToFirst()
            assertEquals(1L, cursor.getLong(0))
            assertEquals(AdjustmentField.CATEGORY, cursor.getString(1))
            // The original category was destroyed by the very UPDATE this migration
            // abolishes. Null is the honest answer; a guess would be indistinguishable
            // from a real recovered value.
            assertTrue("there is no old value to recover", cursor.isNull(2))
            assertEquals("Groceries", cursor.getString(3))
            assertEquals(AdjustmentSource.MIGRATION, cursor.getString(4))
        }
    }

    @Test
    fun `v2 to v3 carries every ledger column across the table rebuild`() {
        createSchemaVersion(2).use { db ->
            insertV2Transaction(db, id = 7, category = "Food & Dining", isUserModified = 0)
        }

        val db = migrateToLatest()

        // The rebuild copies fifteen columns by name. A typo in that list loses a
        // field silently — the row is still there, so a row-count check passes.
        db.query(
            "SELECT amount, direction, timestamp, sender, merchant, accountTail, channel, " +
                "category, rawMessage, balanceAfter, status, txnType, hash, txnHash " +
                "FROM transactions WHERE id = 7"
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(420.0, cursor.getDouble(0), 0.001)
            assertEquals("DEBIT", cursor.getString(1))
            assertEquals(1754700000000L, cursor.getLong(2))
            assertEquals("AD-HDFCBK-S", cursor.getString(3))
            assertEquals("swiggy@ybl", cursor.getString(4))
            assertEquals("8901", cursor.getString(5))
            assertEquals("UPI", cursor.getString(6))
            assertEquals("Food & Dining", cursor.getString(7))
            assertEquals("raw body", cursor.getString(8))
            assertEquals(12000.0, cursor.getDouble(9), 0.001)
            assertEquals("POSTED", cursor.getString(10))
            assertEquals("PURCHASE", cursor.getString(11))
            assertEquals("h7", cursor.getString(12))
            assertEquals("t7", cursor.getString(13))
        }
    }

    @Test
    fun `v2 to v3 keeps the settings table the vault passphrase depends on`() {
        createSchemaVersion(2).use { }
        val db = migrateToLatest()

        // app_settings holds the wrapped passphrase and the ingestion watermark.
        // If the migration failed to create it, the app opens and then cannot
        // decrypt itself on the following launch.
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='app_settings'")
            .use { cursor -> assertEquals(1, cursor.count) }
    }

    // ---- helpers ---------------------------------------------------------

    /** A v1 `transactions` row: no balanceAfter, status, txnType or txnHash yet. */
    private fun insertV1Transaction(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        id: Long,
        hash: String,
        merchant: String = "swiggy@ybl"
    ) {
        db.execSQL(
            "INSERT INTO transactions (id, amount, direction, timestamp, sender, merchant, " +
                "accountTail, channel, category, rawMessage, isUserModified, isFlagged, " +
                "flagReason, hash) VALUES ($id, 420.0, 'DEBIT', 1754700000000, 'AD-HDFCBK-S', " +
                "'$merchant', '8901', 'UPI', 'Food & Dining', 'raw body', 0, 0, NULL, '$hash')"
        )
    }

    /** A v2 `transactions` row, including the three mutation columns v3 removes. */
    private fun insertV2Transaction(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        id: Long,
        category: String,
        isUserModified: Int
    ) {
        db.execSQL(
            "INSERT INTO transactions (id, amount, direction, timestamp, sender, merchant, " +
                "accountTail, channel, category, rawMessage, isUserModified, isFlagged, " +
                "flagReason, balanceAfter, status, txnType, hash, txnHash) VALUES " +
                "($id, 420.0, 'DEBIT', 1754700000000, 'AD-HDFCBK-S', 'swiggy@ybl', '8901', " +
                "'UPI', '$category', 'raw body', $isUserModified, 0, NULL, 12000.0, " +
                "'POSTED', 'PURCHASE', 'h$id', 't$id')"
        )
    }

    /**
     * Builds an empty database at [version] from that version's exported schema.
     */
    private fun createSchemaVersion(version: Int): androidx.sqlite.db.SupportSQLiteDatabase {
        val schema = JSONObject(schemaFile(version).readText()).getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DB)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = Unit
                })
                .build()
        )

        val db = helper.writableDatabase
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            db.execSQL(
                entity.getString("createSql")
                    .replace("\${TABLE_NAME}", entity.getString("tableName"))
            )
            val indices = entity.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                db.execSQL(
                    indices.getJSONObject(j).getString("createSql")
                        .replace("\${TABLE_NAME}", entity.getString("tableName"))
                )
            }
        }
        db.version = version
        return db
    }

    /**
     * v2 onwards read the schema Room exported. v1 has no export — schema export was
     * only switched on at v2 — so it is reconstructed, and kept out of `app/schemas/`
     * so that directory stays entirely Room's own output.
     *
     * The reconstruction is 2.json with exactly what [AppDatabase.MIGRATION_1_2] adds
     * taken back out. That is partly circular, and worth being clear about: it cannot
     * detect a v1 column nobody remembers. It does verify the rest — that the migration
     * runs, that it produces a schema v3 can rebuild, and that data survives — which is
     * three of the four ways this chain can break on a real install.
     */
    private fun schemaFile(version: Int): File {
        val moduleDir = File(System.getProperty("user.dir")!!)
        if (version == 1) {
            return File(moduleDir, "src/test/resources/schema-v1-reconstructed.json")
        }
        return File(moduleDir, "$SCHEMA_DIR/$version.json")
    }

    /**
     * Opens the database through Room at the current version, running whichever
     * migrations the starting version requires.
     *
     * All three are registered rather than just the one under test, because that
     * is the set the shipped app registers. Registering only `MIGRATION_3_4` would
     * make a v2 database fail here for a reason that has nothing to do with the
     * migration being tested.
     */
    private fun migrateToLatest(): androidx.sqlite.db.SupportSQLiteDatabase {
        val room = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5
            )
            .build()
        return room.openHelper.writableDatabase
    }
}
