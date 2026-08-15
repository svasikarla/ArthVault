package com.arthvault

import com.arthvault.data.backup.BackupCodec
import com.arthvault.data.backup.BackupFormatException
import com.arthvault.data.backup.BackupPayload
import com.arthvault.data.local.entity.BillKind
import com.arthvault.data.local.entity.BillNoticeEntity
import com.arthvault.data.local.entity.CategoryEntity
import com.arthvault.data.local.entity.MerchantRuleEntity
import com.arthvault.data.local.entity.OwnAccountEntity
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
        senderAllowlist = listOf(SenderAllowlistEntity("HDFCBK", "HDFC Bank")),
        ownAccounts = listOf(OwnAccountEntity("0662", "HDFC Current", 99L)),
        billNotices = listOf(
            BillNoticeEntity(
                billerKey = "ICICIBANKCREDITCARD",
                billerLabel = "ICICI Bank Credit Card",
                kind = BillKind.CARD,
                accountTail = "7009",
                amountDue = 2783.0,
                minAmountDue = 140.0,
                dueDate = 1_754_438_400_000L,
                billingPeriodLabel = "Aug 2026",
                issuedAt = 1_754_000_000_000L,
                sender = "AD-ICICIB-S",
                rawMessage = "Total Amount Due of Rs 2,783.00 by 06-Aug-26",
                noticeHash = "n1",
                cycleKey = "c1"
            ),
            BillNoticeEntity(
                billerKey = "ONECRD",
                billerLabel = "OneCard",
                kind = BillKind.CARD,
                accountTail = null,
                amountDue = null,
                minAmountDue = null,
                dueDate = null,
                billingPeriodLabel = null,
                issuedAt = 1_754_100_000_000L,
                sender = "AD-ONECRD-S",
                rawMessage = "statement is ready",
                noticeHash = "n2",
                cycleKey = "c2"
            )
        )
    )

    private val passphrase = "correct horse battery".toCharArray()

    @Test
    fun `a backup round trips every table it claims to carry`() {
        val restored = BackupCodec.decode(BackupCodec.encode(payload, passphrase), passphrase)

        assertEquals(2, restored.transactions.size)
        assertEquals(1, restored.merchantRules.size)
        assertEquals(1, restored.customCategories.size)
        assertEquals(1, restored.senderAllowlist.size)
        assertEquals(1, restored.ownAccounts.size)
        assertEquals(2, restored.billNotices.size)
    }

    @Test
    fun `bill notices survive, including the ones that stated nothing`() {
        val restored = BackupCodec.decode(BackupCodec.encode(payload, passphrase), passphrase)

        val card = restored.billNotices.first { it.noticeHash == "n1" }
        assertEquals(2783.0, card.amountDue!!, 0.001)
        assertEquals(140.0, card.minAmountDue!!, 0.001)
        assertEquals(1_754_438_400_000L, card.dueDate)
        assertEquals("7009", card.accountTail)
        assertEquals("Aug 2026", card.billingPeriodLabel)

        // The nullable case is the one worth pinning. A notice that quoted no amount is
        // common — "your statement is ready" — and restoring it as ₹0 would put a bill
        // on screen that looks settled when nothing is known about it at all.
        val bare = restored.billNotices.first { it.noticeHash == "n2" }
        assertEquals(null, bare.amountDue)
        assertEquals(null, bare.minAmountDue)
        assertEquals(null, bare.dueDate)
        assertEquals(null, bare.accountTail)
        assertEquals(null, bare.billingPeriodLabel)
    }

    @Test
    fun `a backup written before v6 restores as no bills`() {
        val legacy = BackupCodec.fromJson(
            BackupCodec.toJson(payload.copy(billNotices = emptyList()))
        )
        assertTrue(legacy.billNotices.isEmpty())
    }

    @Test
    fun `own accounts survive so a restore does not restate the totals`() {
        val restored = BackupCodec.decode(BackupCodec.encode(payload, passphrase), passphrase)

        // Drop these and every own-account transfer counts as spending again, so the
        // restored ledger silently disagrees with the one that was backed up.
        val account = restored.ownAccounts.single()
        assertEquals("0662", account.tail)
        assertEquals("HDFC Current", account.label)
        assertEquals(99L, account.markedAt)
    }

    @Test
    fun `a backup written before v5 restores as no marked accounts`() {
        // Forward compatibility in the direction that actually happens: an older file
        // opened by a newer app. Absent means "none marked", which is exactly the
        // state that install was in.
        val legacy = BackupCodec.fromJson(
            BackupCodec.toJson(payload.copy(ownAccounts = emptyList()))
        )
        assertTrue(legacy.ownAccounts.isEmpty())
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
