package com.arthvault.data.crypto

import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * The one-time move from a plaintext `vault_ledger.db` to an encrypted one.
 *
 * This is the most dangerous code in the app: it rewrites the user's entire
 * financial history, and a botched attempt destroys it. Three specific hazards
 * are handled explicitly rather than hoped away.
 *
 * **1. `user_version` does not travel.** `sqlcipher_export()` copies tables and
 * data but not the schema version pragma. Room reads `user_version` to decide
 * which migrations to run; left at 0 it would treat a fully populated v2
 * database as brand new. It is read before and written after, by hand.
 *
 * **2. Interruption.** The transcode writes to a temporary file and only then
 * renames. The original is not unlinked until the rename has succeeded, so a
 * process death at any point leaves either the untouched original or the
 * original plus a stale temp file — never a half-written ledger. A marker file
 * records that a transcode was in flight so a leftover temp is discarded rather
 * than trusted.
 *
 * **3. Key form.** See [VaultCrypto.toHexAscii].
 */
object EncryptionMigrator {

    private const val TAG = "EncryptionMigrator"
    private const val TEMP_SUFFIX = ".transcode"
    private const val MARKER_SUFFIX = ".transcoding"

    /** The 16-byte magic string every unencrypted SQLite file opens with. */
    private val HEADER = "SQLite format 3".toByteArray(Charsets.US_ASCII) + 0x00.toByte()

    @Volatile
    private var libraryLoaded = false

    fun loadLibrary() {
        if (!libraryLoaded) {
            System.loadLibrary("sqlcipher")
            libraryLoaded = true
        }
    }

    /**
     * True when a legacy plaintext database is sitting where the encrypted one
     * should be.
     *
     * Sniffing the file header is more honest than inferring from app state, and
     * it doubles as the way to *prove* the database is encrypted afterwards — an
     * encrypted file begins with a random salt, so it cannot match.
     */
    fun isPlaintext(file: File): Boolean {
        if (!file.exists() || file.length() < HEADER.size) return false
        return file.inputStream().use { stream ->
            val head = ByteArray(HEADER.size)
            stream.read(head) == HEADER.size && head.contentEquals(HEADER)
        }
    }

    /**
     * Encrypts [databaseFile] in place if, and only if, it is currently plaintext.
     *
     * @param passphraseHex the hex-ASCII passphrase from [VaultCrypto.toHexAscii].
     * @return true if a transcode happened.
     */
    fun encryptIfNeeded(databaseFile: File, passphraseHex: ByteArray): Boolean {
        loadLibrary()

        val temp = File(databaseFile.parentFile, databaseFile.name + TEMP_SUFFIX)
        val marker = File(databaseFile.parentFile, databaseFile.name + MARKER_SUFFIX)

        // A temp file left by an interrupted run tells us nothing about how far
        // it got. It is never the authority; the original always is.
        if (temp.exists()) {
            Log.w(TAG, "discarding an incomplete transcode from a previous run")
            temp.delete()
        }
        marker.delete()

        if (!isPlaintext(databaseFile)) return false

        Log.i(TAG, "plaintext ledger found; encrypting in place")
        marker.writeBytes(ByteArray(0))

        val key = String(passphraseHex, Charsets.US_ASCII)
        val version: Int

        val plain = SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            "",
            null,
            // CREATE_IF_NECESSARY matters more than it looks: ATTACH inherits the
            // main connection's open flags, so without it SQLite cannot create the
            // destination file and the transcode dies with SQLITE_CANTOPEN.
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
            null
        )
        try {
            version = plain.version
            // 'key' is 64 hex characters, so no quoting hazard exists here.
            plain.rawExecSQL("ATTACH DATABASE '${temp.absolutePath}' AS encrypted KEY '$key'")
            plain.rawExecSQL("SELECT sqlcipher_export('encrypted')")
            plain.rawExecSQL("DETACH DATABASE encrypted")
        } finally {
            plain.close()
        }

        val encrypted = SQLiteDatabase.openDatabase(
            temp.absolutePath,
            key,
            null,
            // CREATE_IF_NECESSARY matters more than it looks: ATTACH inherits the
            // main connection's open flags, so without it SQLite cannot create the
            // destination file and the transcode dies with SQLITE_CANTOPEN.
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
            null
        )
        try {
            encrypted.version = version
        } finally {
            encrypted.close()
        }

        // Journals belong to the old file and are meaningless against the new one.
        File(databaseFile.absolutePath + "-wal").delete()
        File(databaseFile.absolutePath + "-shm").delete()
        File(databaseFile.absolutePath + "-journal").delete()

        if (!databaseFile.delete() || !temp.renameTo(databaseFile)) {
            marker.delete()
            throw IllegalStateException("Could not swap in the encrypted ledger")
        }

        marker.delete()
        Log.i(TAG, "ledger encrypted at schema version $version")
        return true
    }
}
