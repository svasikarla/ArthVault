package com.example.data.backup

import com.example.data.crypto.VaultCrypto
import com.example.data.local.entity.CategoryEntity
import com.example.data.local.entity.MerchantRuleEntity
import com.example.data.local.entity.ParserRuleEntity
import com.example.data.local.entity.SenderAllowlistEntity
import com.example.data.local.entity.TransactionEntity
import org.json.JSONArray
import org.json.JSONObject

class BackupFormatException(message: String) : Exception(message)

/**
 * Everything a restore needs to rebuild the user's vault.
 *
 * Deliberately more than the CSV export carries. A "backup" that restores the
 * transactions but loses every categorisation correction the user ever made is
 * not a backup, and F5.3 would be met only on paper.
 */
data class BackupPayload(
    val transactions: List<TransactionEntity> = emptyList(),
    val merchantRules: List<MerchantRuleEntity> = emptyList(),
    val customCategories: List<CategoryEntity> = emptyList(),
    val customParserRules: List<ParserRuleEntity> = emptyList(),
    val senderAllowlist: List<SenderAllowlistEntity> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * F5.3 — encrypted local backup, written to a location the user picks.
 *
 * File layout:
 *
 *     "AVLT" | version:u8 | salt:16 | AES-256-GCM(iv:12 || ciphertext || tag:16)
 *
 * The key is derived from a passphrase the user types, *not* from the Keystore
 * key that protects the live database. Keystore keys are non-exportable and are
 * destroyed with the app install — a backup sealed with one could only be opened
 * on the device that no longer has it, which is the exact situation a backup
 * exists to survive. The passphrase is the price of restorability.
 */
object BackupCodec {

    private val MAGIC = byteArrayOf('A'.code.toByte(), 'V'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte())
    private const val FORMAT_VERSION = 1
    private const val HEADER_BYTES = 4 + 1 + VaultCrypto.SALT_BYTES

    const val FILE_EXTENSION = "avault"
    const val MIME_TYPE = "application/octet-stream"

    /** Short enough not to be theatre, long enough that PBKDF2 buys something. */
    const val MIN_PASSPHRASE_LENGTH = 8

    fun encode(payload: BackupPayload, passphrase: CharArray): ByteArray {
        val salt = VaultCrypto.randomSalt()
        val key = VaultCrypto.deriveKey(passphrase, salt)
        val sealed = VaultCrypto.encrypt(toJson(payload).toByteArray(Charsets.UTF_8), key)
        return MAGIC + byteArrayOf(FORMAT_VERSION.toByte()) + salt + sealed
    }

    /**
     * @throws BackupFormatException if this is not an Arth Vault backup at all.
     * @throws javax.crypto.AEADBadTagException if the passphrase is wrong or the
     *   file has been altered. GCM cannot distinguish those two cases and the
     *   caller should not pretend otherwise.
     */
    fun decode(bytes: ByteArray, passphrase: CharArray): BackupPayload {
        if (bytes.size <= HEADER_BYTES) {
            throw BackupFormatException("File is too small to be a backup.")
        }
        if (!bytes.copyOfRange(0, 4).contentEquals(MAGIC)) {
            throw BackupFormatException("This is not an Arth Vault backup file.")
        }
        val version = bytes[4].toInt()
        if (version != FORMAT_VERSION) {
            throw BackupFormatException("Backup format v$version was written by a newer version of the app.")
        }
        val salt = bytes.copyOfRange(5, HEADER_BYTES)
        val key = VaultCrypto.deriveKey(passphrase, salt)
        val json = VaultCrypto.decrypt(bytes.copyOfRange(HEADER_BYTES, bytes.size), key)
        return fromJson(String(json, Charsets.UTF_8))
    }

    fun toJson(payload: BackupPayload): String {
        val root = JSONObject()
        root.put("formatVersion", FORMAT_VERSION)
        root.put("createdAt", payload.createdAt)

        root.put("transactions", JSONArray().apply {
            payload.transactions.forEach { t ->
                put(JSONObject().apply {
                    put("amount", t.amount)
                    put("direction", t.direction)
                    put("timestamp", t.timestamp)
                    put("sender", t.sender)
                    put("merchant", t.merchant)
                    put("accountTail", t.accountTail ?: JSONObject.NULL)
                    put("channel", t.channel ?: JSONObject.NULL)
                    put("category", t.category)
                    put("rawMessage", t.rawMessage)
                    put("balanceAfter", t.balanceAfter ?: JSONObject.NULL)
                    put("status", t.status)
                    put("txnType", t.txnType)
                    put("hash", t.hash)
                    put("txnHash", t.txnHash)
                })
            }
        })

        root.put("merchantRules", JSONArray().apply {
            payload.merchantRules.forEach { r ->
                put(JSONObject().apply {
                    put("merchantPattern", r.merchantPattern)
                    put("assignedCategory", r.assignedCategory)
                    put("updatedTimestamp", r.updatedTimestamp)
                })
            }
        })

        root.put("customCategories", JSONArray().apply {
            payload.customCategories.forEach { c ->
                put(JSONObject().apply {
                    put("name", c.name)
                    put("iconName", c.iconName)
                    put("colorHex", c.colorHex)
                })
            }
        })

        root.put("customParserRules", JSONArray().apply {
            payload.customParserRules.forEach { p ->
                put(JSONObject().apply {
                    put("ruleName", p.ruleName)
                    put("senderPattern", p.senderPattern)
                    put("regexPattern", p.regexPattern)
                    put("amountGroup", p.amountGroup)
                    put("directionGroup", p.directionGroup)
                    put("merchantGroup", p.merchantGroup)
                    put("accountGroup", p.accountGroup ?: JSONObject.NULL)
                    put("channelGroup", p.channelGroup ?: JSONObject.NULL)
                    put("isDebitKeyword", p.isDebitKeyword)
                    put("isActive", p.isActive)
                })
            }
        })

        root.put("senderAllowlist", JSONArray().apply {
            payload.senderAllowlist.forEach { s ->
                put(JSONObject().apply {
                    put("senderId", s.senderId)
                    put("label", s.label)
                    put("isEnabled", s.isEnabled)
                })
            }
        })

        return root.toString()
    }

    fun fromJson(json: String): BackupPayload {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw BackupFormatException("Backup contents are not readable.")
        }

        return BackupPayload(
            createdAt = root.optLong("createdAt", 0L),
            transactions = root.optJSONArray("transactions").map { o ->
                TransactionEntity(
                    amount = o.getDouble("amount"),
                    direction = o.getString("direction"),
                    timestamp = o.getLong("timestamp"),
                    sender = o.getString("sender"),
                    merchant = o.getString("merchant"),
                    accountTail = o.optNullableString("accountTail"),
                    channel = o.optNullableString("channel"),
                    category = o.getString("category"),
                    rawMessage = o.optString("rawMessage", ""),
                    balanceAfter = if (o.isNull("balanceAfter")) null else o.getDouble("balanceAfter"),
                    status = o.optString("status", com.example.data.local.entity.STATUS_POSTED),
                    txnType = o.optString("txnType", com.example.data.local.entity.TxnType.PURCHASE),
                    hash = o.getString("hash"),
                    txnHash = o.optString("txnHash", "")
                )
            },
            merchantRules = root.optJSONArray("merchantRules").map { o ->
                MerchantRuleEntity(
                    merchantPattern = o.getString("merchantPattern"),
                    assignedCategory = o.getString("assignedCategory"),
                    updatedTimestamp = o.optLong("updatedTimestamp", System.currentTimeMillis())
                )
            },
            customCategories = root.optJSONArray("customCategories").map { o ->
                CategoryEntity(
                    name = o.getString("name"),
                    iconName = o.optString("iconName", "Category"),
                    colorHex = o.optString("colorHex", "#607D8B"),
                    isCustom = true
                )
            },
            customParserRules = root.optJSONArray("customParserRules").map { o ->
                ParserRuleEntity(
                    ruleName = o.getString("ruleName"),
                    senderPattern = o.optString("senderPattern", ""),
                    regexPattern = o.getString("regexPattern"),
                    amountGroup = o.getInt("amountGroup"),
                    directionGroup = o.getInt("directionGroup"),
                    merchantGroup = o.getInt("merchantGroup"),
                    accountGroup = if (o.isNull("accountGroup")) null else o.getInt("accountGroup"),
                    channelGroup = if (o.isNull("channelGroup")) null else o.getInt("channelGroup"),
                    isDebitKeyword = o.optString("isDebitKeyword", "debited|spent|paid"),
                    isActive = o.optBoolean("isActive", true),
                    isSystemRule = false
                )
            },
            senderAllowlist = root.optJSONArray("senderAllowlist").map { o ->
                SenderAllowlistEntity(
                    senderId = o.getString("senderId"),
                    label = o.optString("label", o.getString("senderId")),
                    isEnabled = o.optBoolean("isEnabled", true)
                )
            }
        )
    }

    private fun <T> JSONArray?.map(transform: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).map { transform(getJSONObject(it)) }
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key, "").ifEmpty { null }
}
