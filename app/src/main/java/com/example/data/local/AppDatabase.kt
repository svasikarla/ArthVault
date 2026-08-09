package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.crypto.EncryptionMigrator
import com.example.data.crypto.VaultCrypto
import com.example.data.local.dao.AdjustmentDao
import com.example.data.local.dao.AppSettingDao
import com.example.data.local.dao.CategoryDao
import com.example.data.local.dao.MerchantRuleDao
import com.example.data.local.dao.ParserRuleDao
import com.example.data.local.dao.SenderAllowlistDao
import com.example.data.local.dao.TransactionDao
import com.example.data.local.dao.UnparsedSmsDao
import com.example.data.local.entity.AdjustmentEntity
import com.example.data.local.entity.AdjustmentField
import com.example.data.local.entity.AdjustmentSource
import com.example.data.local.entity.AppSettingEntity
import com.example.data.local.entity.CategoryEntity
import com.example.data.local.entity.MerchantRuleEntity
import com.example.data.local.entity.ParserRuleEntity
import com.example.data.local.entity.SenderAllowlistEntity
import com.example.data.local.entity.TransactionEntity
import com.example.data.local.entity.UnparsedSmsEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

@Database(
    entities = [
        TransactionEntity::class,
        UnparsedSmsEntity::class,
        MerchantRuleEntity::class,
        ParserRuleEntity::class,
        CategoryEntity::class,
        SenderAllowlistEntity::class,
        AdjustmentEntity::class,
        AppSettingEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao
    abstract fun unparsedSmsDao(): UnparsedSmsDao
    abstract fun merchantRuleDao(): MerchantRuleDao
    abstract fun parserRuleDao(): ParserRuleDao
    abstract fun categoryDao(): CategoryDao
    abstract fun senderAllowlistDao(): SenderAllowlistDao
    abstract fun adjustmentDao(): AdjustmentDao
    abstract fun appSettingDao(): AppSettingDao

    companion object {
        const val DATABASE_NAME = "vault_ledger.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * T3.4 — forward migration, not destruction.
         *
         * The original builder used fallbackToDestructiveMigration(), which
         * silently deleted the user's entire financial history on any schema
         * change. For a ledger that is the worst possible failure mode.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN balanceAfter REAL")
                db.execSQL("ALTER TABLE transactions ADD COLUMN status TEXT NOT NULL DEFAULT 'POSTED'")
                db.execSQL("ALTER TABLE transactions ADD COLUMN txnType TEXT NOT NULL DEFAULT 'PURCHASE'")
                db.execSQL("ALTER TABLE transactions ADD COLUMN txnHash TEXT NOT NULL DEFAULT ''")

                // A unique index cannot be created over existing duplicates. v1 had no
                // such constraint and deduped in application code, so collisions are
                // possible; keep the earliest row of each group.
                db.execSQL(
                    "DELETE FROM transactions WHERE id NOT IN " +
                        "(SELECT MIN(id) FROM transactions GROUP BY hash)"
                )

                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_transactions_hash ON transactions (hash)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_txnHash ON transactions (txnHash)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_timestamp ON transactions (timestamp)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS sender_allowlist (" +
                        "senderId TEXT NOT NULL, " +
                        "label TEXT NOT NULL, " +
                        "isEnabled INTEGER NOT NULL, " +
                        "PRIMARY KEY(senderId))"
                )

                // The onCreate callback fires only when the database is first created,
                // so an upgrading install would reach v2 with an empty allowlist and
                // never see the feature. Seed it here for that path.
                DefaultSeedData.senderAllowlist.forEach { sender ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO sender_allowlist (senderId, label, isEnabled) VALUES (?, ?, 1)",
                        arrayOf(sender.senderId, sender.label)
                    )
                }
            }
        }

        /**
         * T3.3 — transactions become immutable and corrections become records.
         *
         * The three columns dropped from `transactions` were all mutation state:
         * `isUserModified` marked a row whose category had been overwritten, and
         * `isFlagged`/`flagReason` were written by an analytics pass that no
         * longer exists. SQLite before 3.35 cannot DROP COLUMN, and Room's
         * expected schema is exact, so the table is rebuilt.
         *
         * Rows that carried `isUserModified = 1` get a reconstructed CATEGORY
         * adjustment. Its `oldValue` is null because the original category was
         * destroyed by the very UPDATE this migration exists to abolish — there
         * is nothing to recover and nothing worth guessing.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS adjustments (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "transactionId INTEGER NOT NULL, " +
                        "field TEXT NOT NULL, " +
                        "oldValue TEXT, " +
                        "newValue TEXT, " +
                        "reason TEXT, " +
                        "createdAt INTEGER NOT NULL, " +
                        "source TEXT NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_adjustments_transactionId ON adjustments (transactionId)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS app_settings (" +
                        "`key` TEXT NOT NULL, " +
                        "value TEXT NOT NULL, " +
                        "PRIMARY KEY(`key`))"
                )

                db.execSQL(
                    "INSERT INTO adjustments (transactionId, field, oldValue, newValue, reason, createdAt, source) " +
                        "SELECT id, '${AdjustmentField.CATEGORY}', NULL, category, " +
                        "'Recovered from a pre-v3 in-place edit', ${System.currentTimeMillis()}, " +
                        "'${AdjustmentSource.MIGRATION}' FROM transactions WHERE isUserModified = 1"
                )

                db.execSQL(
                    "CREATE TABLE transactions_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "amount REAL NOT NULL, " +
                        "direction TEXT NOT NULL, " +
                        "timestamp INTEGER NOT NULL, " +
                        "sender TEXT NOT NULL, " +
                        "merchant TEXT NOT NULL, " +
                        "accountTail TEXT, " +
                        "channel TEXT, " +
                        "category TEXT NOT NULL, " +
                        "rawMessage TEXT NOT NULL, " +
                        "balanceAfter REAL, " +
                        "status TEXT NOT NULL, " +
                        "txnType TEXT NOT NULL, " +
                        "hash TEXT NOT NULL, " +
                        "txnHash TEXT NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO transactions_new (id, amount, direction, timestamp, sender, merchant, " +
                        "accountTail, channel, category, rawMessage, balanceAfter, status, txnType, hash, txnHash) " +
                        "SELECT id, amount, direction, timestamp, sender, merchant, accountTail, channel, " +
                        "category, rawMessage, balanceAfter, status, txnType, hash, txnHash FROM transactions"
                )
                db.execSQL("DROP TABLE transactions")
                db.execSQL("ALTER TABLE transactions_new RENAME TO transactions")

                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_transactions_hash ON transactions (hash)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_txnHash ON transactions (txnHash)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_timestamp ON transactions (timestamp)")
            }
        }

        /** True once [open] has succeeded; false before unlock and after [lock]. */
        val isOpen: Boolean get() = INSTANCE != null

        /**
         * @throws IllegalStateException if called before the vault is unlocked.
         *   That is a programming error, not a runtime condition — every screen
         *   sits behind the unlock gate.
         */
        fun requireDatabase(): AppDatabase =
            INSTANCE ?: error("The vault is locked. AppDatabase.open() must run after authentication.")

        /**
         * T3.1/T3.2 — opens the encrypted ledger.
         *
         * [passphraseRaw] is the 32 bytes unwrapped from the Keystore. It is
         * converted to hex text (see VaultCrypto.toHexAscii) and both copies are
         * zeroed before returning: SQLCipher keeps its own copy of the key
         * material and there is no reason for ours to linger on the heap.
         */
        fun open(context: Context, passphraseRaw: ByteArray): AppDatabase {
            INSTANCE?.let { return it }
            return synchronized(this) {
                INSTANCE?.let { return it }

                EncryptionMigrator.loadLibrary()
                val passphraseHex = VaultCrypto.toHexAscii(passphraseRaw)

                val instance = try {
                    // Existing installs still hold a plaintext file. Transcode it
                    // before Room ever looks at it — Room opening a plaintext file
                    // through the SQLCipher factory would just report corruption.
                    EncryptionMigrator.encryptIfNeeded(
                        context.applicationContext.getDatabasePath(DATABASE_NAME),
                        passphraseHex
                    )

                    Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        DATABASE_NAME
                    )
                        .openHelperFactory(SupportOpenHelperFactory(passphraseHex.copyOf()))
                        .addCallback(DatabaseCallback())
                        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                        .build()
                } finally {
                    VaultCrypto.wipe(passphraseHex)
                }

                INSTANCE = instance
                instance
            }
        }

        /** Closes the ledger. The next screen has to authenticate again. */
        fun lock() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }

        /** F5.2 — used after destroying the vault key, when the file is unopenable. */
        fun deleteDatabaseFiles(context: Context) {
            lock()
            val base = context.applicationContext.getDatabasePath(DATABASE_NAME)
            listOf(base, File("${base.path}-wal"), File("${base.path}-shm"), File("${base.path}-journal"))
                .forEach { it.delete() }
        }

        private class DatabaseCallback : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                INSTANCE?.let { database ->
                    CoroutineScope(Dispatchers.IO).launch {
                        database.categoryDao().insertDefaultCategories(DefaultSeedData.categories)
                        database.parserRuleDao().insertAllRules(DefaultSeedData.parserRules)
                        DefaultSeedData.merchantRules.forEach {
                            database.merchantRuleDao().insertOrUpdateRule(it)
                        }
                        database.senderAllowlistDao().insertDefaults(DefaultSeedData.senderAllowlist)
                    }
                }
            }
        }
    }
}
