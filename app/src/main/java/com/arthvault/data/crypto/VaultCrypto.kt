package com.arthvault.data.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The one place symmetric crypto happens (T3.1, T3.2, F5.3).
 *
 * Everything here is platform JCE — no third-party crypto library. That is a
 * deliberate T1.1 decision as much as a security one: androidx.security-crypto
 * would pull in Tink, and transitive AAR manifests are exactly how this project
 * acquired android.permission.INTERNET the first time round.
 *
 * Two independent key hierarchies use these primitives, and conflating them
 * would be a serious design error:
 *
 *  - The **database** passphrase is wrapped by a hardware-backed, non-exportable
 *    AndroidKeystore key (see DatabaseKeyManager). It never leaves the device and
 *    cannot be restored anywhere else.
 *  - A **backup** file is encrypted with a key derived from a passphrase the user
 *    types (see BackupCodec). It must survive a factory reset and a new phone,
 *    which a Keystore key by definition cannot.
 */
object VaultCrypto {

    const val GCM_IV_BYTES = 12
    const val GCM_TAG_BITS = 128
    const val SALT_BYTES = 16
    const val KEY_BITS = 256

    /**
     * OWASP's 2023 floor for PBKDF2-HMAC-SHA256. Costs roughly 100-200 ms on a
     * mid-range phone, which is acceptable for an action the user takes by hand
     * (writing or restoring a backup) and nowhere near a hot path.
     */
    const val PBKDF2_ITERATIONS = 210_000

    private val secureRandom = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { secureRandom.nextBytes(it) }

    fun randomIv(): ByteArray = randomBytes(GCM_IV_BYTES)

    fun randomSalt(): ByteArray = randomBytes(SALT_BYTES)

    /** A fresh 256-bit key. Used for the raw SQLCipher passphrase. */
    fun randomKey(): ByteArray = randomBytes(KEY_BITS / 8)

    fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BITS)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    fun keyFrom(raw: ByteArray): SecretKey = SecretKeySpec(raw, "AES")

    /**
     * Encrypts with AES-256/GCM and returns iv || ciphertext || tag as one blob.
     *
     * The IV is generated here rather than accepted as a parameter so that a
     * caller cannot reuse one, which for GCM is catastrophic rather than merely
     * sloppy.
     */
    fun encrypt(plaintext: ByteArray, key: SecretKey): ByteArray {
        val iv = randomIv()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return iv + cipher.doFinal(plaintext)
    }

    /**
     * [encrypt] for AndroidKeyStore keys, which will not accept one.
     *
     * Keystore keys are created with `setRandomizedEncryptionRequired(true)` by
     * default, and supplying a GCMParameterSpec on init fails outright with
     * "Caller-provided IV not permitted" — the platform insists on generating the
     * nonce itself precisely so that application code cannot reuse one. The IV it
     * chose is read back off the cipher afterwards and prepended, so the blob has
     * the same shape [decrypt] expects.
     */
    fun encryptWithKeystoreKey(plaintext: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val body = cipher.doFinal(plaintext)
        val iv = cipher.iv
        require(iv.size == GCM_IV_BYTES) { "unexpected Keystore IV length: ${iv.size}" }
        return iv + body
    }

    /**
     * Reverses [encrypt] and [encryptWithKeystoreKey].
     *
     * Throws [javax.crypto.AEADBadTagException] on a wrong key *or* on modified
     * bytes — GCM cannot tell the two apart, and neither should the caller. This
     * is the integrity check for backup files; there is no separate checksum
     * because a checksum an attacker can recompute is not one.
     */
    fun decrypt(blob: ByteArray, key: SecretKey): ByteArray {
        require(blob.size > GCM_IV_BYTES) { "ciphertext too short to contain an IV" }
        val iv = blob.copyOfRange(0, GCM_IV_BYTES)
        val body = blob.copyOfRange(GCM_IV_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return cipher.doFinal(body)
    }

    /**
     * The 32 random bytes rendered as 64 lowercase hex characters.
     *
     * SQLCipher takes the passphrase both through a factory (as bytes) and
     * through `ATTACH ... KEY '<literal>'` during the one-time transcode. Raw
     * bytes cannot be written into a SQL string literal safely, and mixing the
     * literal form `x'..'` (a *raw key*, no KDF) with the byte form (a
     * *passphrase*, KDF applied) would silently produce two different keys and
     * an unopenable database. Hex text means both paths say the same thing.
     */
    fun toHexAscii(raw: ByteArray): ByteArray {
        val hex = ByteArray(raw.size * 2)
        for (i in raw.indices) {
            val v = raw[i].toInt() and 0xFF
            hex[i * 2] = HEX_DIGITS[v ushr 4]
            hex[i * 2 + 1] = HEX_DIGITS[v and 0x0F]
        }
        return hex
    }

    private val HEX_DIGITS = "0123456789abcdef".toByteArray(Charsets.US_ASCII)

    /** Best-effort scrub of a key buffer once it has been handed to SQLCipher. */
    fun wipe(bytes: ByteArray?) {
        bytes?.fill(0)
    }

    const val TRANSFORMATION = "AES/GCM/NoPadding"
}
