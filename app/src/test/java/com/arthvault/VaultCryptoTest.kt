package com.arthvault

import com.arthvault.data.crypto.VaultCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

class VaultCryptoTest {

    private val plaintext = "Rs 420.00 debited from A/C XX8901 to SWIGGY".toByteArray()

    @Test
    fun `round trips through AES-GCM`() {
        val key = VaultCrypto.keyFrom(VaultCrypto.randomKey())
        assertArrayEquals(plaintext, VaultCrypto.decrypt(VaultCrypto.encrypt(plaintext, key), key))
    }

    @Test(expected = AEADBadTagException::class)
    fun `the wrong key is rejected, not silently garbled`() {
        val blob = VaultCrypto.encrypt(plaintext, VaultCrypto.keyFrom(VaultCrypto.randomKey()))
        VaultCrypto.decrypt(blob, VaultCrypto.keyFrom(VaultCrypto.randomKey()))
    }

    @Test(expected = AEADBadTagException::class)
    fun `a single flipped bit fails the tag`() {
        val key = VaultCrypto.keyFrom(VaultCrypto.randomKey())
        val blob = VaultCrypto.encrypt(plaintext, key)
        // Past the IV, into the ciphertext proper.
        blob[VaultCrypto.GCM_IV_BYTES + 2] = (blob[VaultCrypto.GCM_IV_BYTES + 2].toInt() xor 0x01).toByte()
        VaultCrypto.decrypt(blob, key)
    }

    @Test
    fun `the IV is never reused across encryptions`() {
        val key = VaultCrypto.keyFrom(VaultCrypto.randomKey())
        val ivs = (1..200).map {
            VaultCrypto.encrypt(plaintext, key).copyOfRange(0, VaultCrypto.GCM_IV_BYTES).toList()
        }
        assertEquals("every IV must be distinct", ivs.size, ivs.toSet().size)
    }

    @Test
    fun `the same passphrase under a different salt yields a different key`() {
        val a = VaultCrypto.deriveKey("correct horse".toCharArray(), VaultCrypto.randomSalt())
        val b = VaultCrypto.deriveKey("correct horse".toCharArray(), VaultCrypto.randomSalt())
        assertFalse(a.encoded.contentEquals(b.encoded))
    }

    @Test
    fun `the same passphrase and salt reproduce the key`() {
        val salt = VaultCrypto.randomSalt()
        val a = VaultCrypto.deriveKey("correct horse".toCharArray(), salt)
        val b = VaultCrypto.deriveKey("correct horse".toCharArray(), salt)
        assertArrayEquals(a.encoded, b.encoded)
    }

    @Test
    fun `derived keys are 256 bits`() {
        assertEquals(32, VaultCrypto.deriveKey("pw".toCharArray(), VaultCrypto.randomSalt()).encoded.size)
    }

    @Test
    fun `the ciphertext does not contain the plaintext`() {
        val key = VaultCrypto.keyFrom(VaultCrypto.randomKey())
        val blob = VaultCrypto.encrypt(plaintext, key)
        assertNotEquals(-1, blob.size)
        assertFalse(
            "the whole point",
            String(blob, Charsets.ISO_8859_1).contains("SWIGGY")
        )
    }

    @Test
    fun `wipe clears the buffer`() {
        val key = VaultCrypto.randomKey()
        VaultCrypto.wipe(key)
        assertTrue(key.all { it == 0.toByte() })
    }
}
