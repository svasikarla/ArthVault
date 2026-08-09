package com.arthvault.data.crypto

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * T3.2 — the database passphrase, sealed by a key the user must authenticate to use.
 *
 * Two levels, on purpose:
 *
 *  1. A random 256-bit **passphrase** is what SQLCipher actually needs. It is
 *     never stored in the clear.
 *  2. A **wrapping key** lives in the AndroidKeyStore under [KEY_ALIAS]. It is
 *     hardware-backed where the device has a TEE or StrongBox, cannot be
 *     exported by any means including a root shell, and — because it is created
 *     with `setUserAuthenticationRequired(true)` — the *hardware* refuses to
 *     perform a decryption unless the user authenticated recently. This is what
 *     makes T3.2 a cryptographic property rather than a screen the UI draws.
 *
 * The wrapped passphrase sits in [KEY_FILE] as `iv || ciphertext || tag`. Pulling
 * that file off the device yields nothing.
 */
class DatabaseKeyManager(private val context: Context) {

    companion object {
        private const val KEY_ALIAS = "arth_vault_db_key"
        private const val KEY_FILE = "vault.key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /**
         * How long an authentication stays good for.
         *
         * The alternative is 0 (every single use needs its own BiometricPrompt
         * CryptoObject). That is marginally stronger and considerably worse here:
         * the key is used exactly once per session, immediately after the unlock
         * prompt, to unwrap one passphrase. A short window covers that one call
         * and keeps the API-26..29 path — which has no CryptoObject-per-use
         * equivalent that also accepts a device PIN — identical to the modern one.
         */
        private const val AUTH_VALIDITY_SECONDS = 30
    }

    private val keyFile: File get() = File(context.filesDir, KEY_FILE)

    /** True once a vault key exists, i.e. this is not a first run. */
    fun isInitialised(): Boolean = keyFile.exists() && loadKeystoreKey() != null

    /**
     * An auth-bound key cannot exist on a device with no screen lock — there is
     * nothing for the hardware to check. The app has to say so rather than
     * silently downgrade to an unprotected database.
     */
    fun isDeviceSecure(): Boolean =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure

    /**
     * Returns the raw SQLCipher passphrase, creating the vault on first run.
     *
     * Must be called within [AUTH_VALIDITY_SECONDS] of a successful
     * authentication; otherwise the Keystore throws [UserNotAuthenticatedException]
     * and the caller should re-prompt.
     *
     * The caller owns the returned array and should [VaultCrypto.wipe] it once
     * SQLCipher has taken a copy.
     *
     * @throws UserNotAuthenticatedException authentication is stale — prompt again.
     * @throws VaultKeyInvalidatedException the Keystore key is gone (see below).
     */
    fun unlockPassphrase(): ByteArray {
        val key = loadKeystoreKey() ?: return createVault()
        if (!keyFile.exists()) {
            // Key without a wrapped passphrase: a half-finished first run. The
            // key protects nothing yet, so starting over loses nothing.
            deleteKeystoreKey()
            return createVault()
        }
        return try {
            VaultCrypto.decrypt(keyFile.readBytes(), key)
        } catch (e: UserNotAuthenticatedException) {
            throw e
        } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
            throw VaultKeyInvalidatedException(e)
        }
    }

    private fun createVault(): ByteArray {
        val passphrase = VaultCrypto.randomKey()
        val key = generateKeystoreKey()
        keyFile.writeBytes(VaultCrypto.encryptWithKeystoreKey(passphrase, key))
        return passphrase
    }

    private fun loadKeystoreKey(): SecretKey? {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return store.getKey(KEY_ALIAS, null) as? SecretKey
    }

    private fun generateKeystoreKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(VaultCrypto.KEY_BITS)
            .setUserAuthenticationRequired(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(
                        AUTH_VALIDITY_SECONDS,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
                }
                // Deliberately false.
                //
                // True is the textbook answer: enrolling a new fingerprint would
                // destroy the key. It would also destroy every transaction the
                // user has ever recorded, permanently, for an action people take
                // routinely and with no warning that it is destructive. The
                // threat it defends against — an attacker who can both enrol a
                // biometric and has the device — already implies a compromised
                // lock screen. Losing a decade of financial history to a new
                // thumb is the worse outcome, so the key survives enrolment and
                // a device credential remains a valid authenticator.
                setInvalidatedByBiometricEnrollment(false)
            }
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun deleteKeystoreKey() {
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    /**
     * Destroys the key and the wrapped passphrase, which makes the encrypted
     * database permanently unreadable. Used by F5.2's wipe and as the only exit
     * from [VaultKeyInvalidatedException].
     */
    fun destroyVaultKey() {
        deleteKeystoreKey()
        keyFile.delete()
    }
}

/**
 * The Keystore key is gone — the user removed the screen lock, or the secure
 * hardware was reset. The database can never be decrypted again; the only paths
 * forward are a restore from an F5.3 backup or starting over.
 */
class VaultKeyInvalidatedException(cause: Throwable) : Exception(
    "The vault key is no longer valid. The encrypted ledger cannot be opened.",
    cause
)
