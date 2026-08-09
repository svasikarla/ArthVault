package com.arthvault

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.arthvault.data.local.AppDatabase
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
 * `room-testing` has been a dependency since Phase 4 without a single test using it;
 * v1→v2→v3 was only ever checked by hand. Phase 5 makes that untenable: v3→v4
 * rebuilds `parser_rules`, and a rebuild that goes wrong takes the user's own rules
 * with it.
 *
 * The v3 database is built from `app/schemas/.../3.json` — the schema Room itself
 * exported at the time — rather than from SQL retyped here. Retyped SQL only proves
 * the migration is consistent with whatever the test author believed v3 looked like.
 *
 * Two things are checked, and the second is the one that bites:
 *
 *  1. the data transformation preserves what it should and discards what it should;
 *  2. the *resulting schema* is byte-for-byte what Room expects at v4. A migration
 *     that produces a subtly different table — a missing index, a nullability
 *     mismatch — succeeds here and then throws on every launch afterwards.
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

        val db = migrateToV4()

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

        val db = migrateToV4()

        db.query("SELECT amount, merchant, accountTail FROM transactions").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(45425.0, cursor.getDouble(0), 0.001)
            assertEquals("Transfer to A/c 066", cursor.getString(1))
            assertEquals("635", cursor.getString(2))
        }
    }

    @Test
    fun `the migrated schema is exactly what Room expects at v4`() {
        createSchemaVersion(3).use { /* empty database is enough for a schema check */ }

        // Opening through Room *is* the assertion. Room compares the migrated
        // database's identity hash against the one compiled in from 4.json and
        // throws "Migration didn't properly handle" if they differ — a migration
        // that produces a near-miss schema passes every data check above and then
        // crashes the app on the next launch.
        migrateToV4()
    }

    @Test
    fun `the unique ruleId index survives the migration`() {
        createSchemaVersion(3).use { }
        val db = migrateToV4()

        db.query("PRAGMA index_list(parser_rules)").use { cursor ->
            val unique = generateSequence({ if (cursor.moveToFirst()) cursor else null }) {
                if (cursor.moveToNext()) cursor else null
            }.any { it.getString(1) == "index_parser_rules_ruleId" && it.getInt(2) == 1 }

            assertTrue("ruleId must be uniquely indexed or upserts become appends", unique)
        }
    }

    // ---- helpers ---------------------------------------------------------

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

    private fun schemaFile(version: Int): File {
        val moduleDir = File(System.getProperty("user.dir")!!)
        return File(moduleDir, "$SCHEMA_DIR/$version.json")
    }

    /** Opens the database through Room at v4, which runs and validates the migration. */
    private fun migrateToV4(): androidx.sqlite.db.SupportSQLiteDatabase {
        val room = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
            .addMigrations(AppDatabase.MIGRATION_3_4)
            .build()
        return room.openHelper.writableDatabase
    }
}
