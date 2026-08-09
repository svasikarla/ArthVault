package com.arthvault

import com.arthvault.data.backup.BackupCodec
import com.arthvault.data.backup.BackupFormatException
import com.arthvault.data.backup.BackupPayload
import com.arthvault.data.local.entity.CategoryEntity
import com.arthvault.data.local.entity.MerchantRuleEntity
import com.arthvault.data.local.entity.STATUS_FAILED
import com.arthvault.data.local.entity.SenderAllowlistEntity
import com.arthvault.data.local.entity.TransactionEntity
import com.arthvault.data.local.entity.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.AEADBadTagException

/**
 * Robolectric only because org.json is an Android stub on a bare JVM; nothing
 * here touches a device.
 */
@RunWith(RobolectricTestRunner::class)
class BackupCodecTest {

    private val payload = BackupPayload(
        transactions = listOf(
            TransactionEntity(
                amount = 420.0,
                direction = "DEBIT",
                timestamp = 1_700_000_000_000L,
                sender = "AD-HDFCBK-S",
                merchant = "SWIGGY",
                accountTail = "8901",
                channel = "UPI",
                category = "Food & Dining",
                rawMessage = "Rs 420.00 debited from A/C XX8901 to SWIGGY",
                balanceAfter = 45210.50,
                hash = "h1",
                txnHash = "t1"
            ),
            TransactionEntity(
                amount = 2750.0,
                direction = "DEBIT",
                timestamp = 1_700_000_100_000L,
                sender = "AD-HDFCBK-S",
                merchant = "MYNTRA",
                accountTail = null,
                channel = null,
                category = "Shopping",
                rawMessage = "declined",
                balanceAfter = null,
                status = STATUS_FAILED,
                txnType = TxnType.PURCHASE,
                hash = "h2",
                txnHash = "t2"
            )
        ),
        merchantRules = listOf(MerchantRuleEntity("SWIGGY", "Food & Dining", 42L)),
        customCategories = listOf(CategoryEntity("Aquarium", "Pets", "#00BCD4", isCustom = true)),
        senderAllowlist = listOf(SenderAllowlistEntity("HDFCBK", "HDFC Bank"))
    )

    private val passphrase = "correct horse battery".toCharArray()

    @Test
    fun `a backup round trips every table it claims to carry`() {
        val restored = BackupCodec.decode(BackupCodec.encode(payload, passphrase), passphrase)

        assertEquals(2, restored.transactions.size)
        assertEquals(1, restored.merchantRules.size)
        assertEquals(1, restored.customCategories.size)
        assertEquals(1, restored.senderAllowlist.size)
    }

    @Test
    fun `nullable and non-default transaction fields survive`() {
        val restored = BackupCodec.decode(BackupCodec.encode(payload, passphrase), passphrase)

        val swiggy = restored.transactions.first { it.merchant == "SWIGGY" }
        assertEquals("8901", swiggy.accountTail)
        assertEquals(45210.50, swiggy.balanceAfter!!, 0.001)
        assertEquals("UPI", swiggy.channel)

        val myntra = restored.transactions.first { it.merchant == "MYNTRA" }
        assertEquals(null, myntra.accountTail)
        assertEquals(null, myntra.balanceAfter)
        assertEquals("a declined charge must not come back as posted", STATUS_FAILED, myntra.status)
    }

    @Test
    fun `hashes survive so a double restore cannot duplicate anything`() {
        val restored = BackupCodec.decode(BackupCodec.encode(payload, passphrase), passphrase)
        assertEquals(listOf("h1", "h2"), restored.transactions.map { it.hash })
    }

    @Test
    fun `restored custom categories stay marked custom`() {
        val restored = BackupCodec.decode(BackupCodec.encode(payload, passphrase), passphrase)
        assertTrue(restored.customCategories.single().isCustom)
    }

    @Test(expected = AEADBadTagException::class)
    fun `the wrong passphrase cannot open a backup`() {
        BackupCodec.decode(BackupCodec.encode(payload, passphrase), "wrong passphrase".toCharArray())
    }

    @Test(expected = AEADBadTagException::class)
    fun `a tampered backup is rejected`() {
        val bytes = BackupCodec.encode(payload, passphrase)
        bytes[bytes.size - 5] = (bytes[bytes.size - 5].toInt() xor 0x7F).toByte()
        BackupCodec.decode(bytes, passphrase)
    }

    @Test(expected = BackupFormatException::class)
    fun `an unrelated file is rejected before any crypto runs`() {
        BackupCodec.decode("just some CSV,I,picked,by,mistake\n".toByteArray(), passphrase)
    }

    @Test(expected = BackupFormatException::class)
    fun `a truncated file is rejected`() {
        BackupCodec.decode(BackupCodec.encode(payload, passphrase).copyOfRange(0, 12), passphrase)
    }

    @Test
    fun `the file is not readable without the passphrase`() {
        val bytes = BackupCodec.encode(payload, passphrase)
        val asText = String(bytes, Charsets.ISO_8859_1)
        assertFalse(asText.contains("SWIGGY"))
        assertFalse(asText.contains("Food & Dining"))
        assertTrue("the magic header stays readable so we can reject wrong files", asText.startsWith("AVLT"))
    }
}
