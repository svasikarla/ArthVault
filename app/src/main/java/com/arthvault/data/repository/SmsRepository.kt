package com.arthvault.data.repository

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import com.arthvault.data.analytics.AnalyticsPeriod
import com.arthvault.data.analytics.AnomalyItem
import com.arthvault.data.analytics.BillAnalyticsEngine
import com.arthvault.data.analytics.BillMonth
import com.arthvault.data.analytics.BillObligation
import com.arthvault.data.analytics.BillSettlement
import com.arthvault.data.analytics.BillTrend
import com.arthvault.data.analytics.CategorySlice
import com.arthvault.data.analytics.CategoryTrend
import com.arthvault.data.analytics.DayBucket
import com.arthvault.data.analytics.FinanceAnalyticsEngine
import com.arthvault.data.analytics.InternalTransferSummary
import com.arthvault.data.analytics.MonthEndForecast
import com.arthvault.data.analytics.PeriodResolver
import com.arthvault.data.analytics.PeriodScope
import com.arthvault.data.analytics.PeriodSummary
import com.arthvault.data.analytics.RecurringItem
import com.arthvault.data.backup.BackupCodec
import com.arthvault.data.backup.BackupPayload
import com.arthvault.data.local.AddCategoryOutcome
import com.arthvault.data.local.AppDatabase
import com.arthvault.data.local.CategoryEditor
import com.arthvault.data.local.DefaultSeedData
import com.arthvault.data.local.DeleteCategoryOutcome
import com.arthvault.data.local.entity.AdjustmentEntity
import com.arthvault.data.local.entity.AdjustmentField
import com.arthvault.data.local.entity.AdjustmentSource
import com.arthvault.data.local.entity.AppSettingEntity
import com.arthvault.data.local.entity.BillNoticeEntity
import com.arthvault.data.local.entity.CategoryEntity
import com.arthvault.data.local.entity.MerchantRuleEntity
import com.arthvault.data.local.entity.OwnAccountEntity
import com.arthvault.data.local.entity.ParserRuleEntity
import com.arthvault.data.local.entity.SenderAllowlistEntity
import com.arthvault.data.local.entity.TransactionEntity
import com.arthvault.data.local.entity.UnparsedSmsEntity
import com.arthvault.data.parser.SenderMatcher
import com.arthvault.data.parser.SmsParserEngine
import com.arthvault.data.parser.rules.ParserRuleSeeder
import com.arthvault.data.parser.rules.RuleLoadResult
import com.arthvault.data.query.LedgerQueryEngine
import com.arthvault.data.query.QueryParser
import com.arthvault.data.query.QueryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter

data class ScanResult(
    val newTransactionsCount: Int = 0,
    val unparsedCount: Int = 0,
    /** Bills that are owed, not paid — counted separately so the two are never added. */
    val newBillNoticesCount: Int = 0,
    val totalScanned: Int = 0,
    /** Newest inbox timestamp examined — becomes the next watermark. */
    val newestSeen: Long = 0L
)

data class BackupResult(
    val transactionCount: Int = 0,
    val byteCount: Int = 0,
    val error: String? = null
)

data class RestoreResult(
    val transactionsRestored: Int = 0,
    val duplicatesSkipped: Int = 0,
    val rulesRestored: Int = 0,
    val error: String? = null
)

data class ImportResult(
    val imported: Int = 0,
    val duplicates: Int = 0,
    val skipped: Int = 0,
    val error: String? = null
)

data class AnalyticsResult(
    /** The window every figure below is scoped to. */
    val period: AnalyticsPeriod = PeriodResolver.resolve(PeriodScope.THIS_MONTH),
    /** Income, spending and net for [period] — the three figures the screen leads with. */
    val summary: PeriodSummary = PeriodSummary(0.0, 0.0, 0.0, 0.0, emptyList(), emptyList()),
    /** The same three figures for the like-for-like earlier window. */
    val comparisonSummary: PeriodSummary = PeriodSummary(0.0, 0.0, 0.0, 0.0, emptyList(), emptyList()),
    /** Per-day totals across [period], zero-filled, for the daily chart. */
    val dailyTotals: List<DayBucket> = emptyList(),
    /** Per-day totals across the comparison window, for the pace line. */
    val comparisonDailyTotals: List<DayBucket> = emptyList(),
    val recurring: List<RecurringItem> = emptyList(),
    /** Recurring income on an established cadence — salary, rent received, a pension. */
    val recurringIncome: List<RecurringItem> = emptyList(),
    /** Only populated when [period] is the current month; nothing else can be forecast. */
    val forecast: MonthEndForecast? = null,
    val anomalies: List<AnomalyItem> = emptyList(),
    val duplicates: List<TransactionEntity> = emptyList(),
    val categoryBreakdown: List<CategorySlice> = emptyList(),
    /** F3.6 — [period] against the like-for-like earlier window, largest movement first. */
    val categoryTrends: List<CategoryTrend> = emptyList(),
    val internalTransfers: InternalTransferSummary =
        InternalTransferSummary(0, 0.0, 0.0, emptyList())
)

/**
 * Everything the Bills screen shows, computed in one pass.
 *
 * Deliberately separate from [AnalyticsResult] rather than folded into it. Money owed
 * and money moved are different quantities, and keeping them in different objects is
 * what stops a future change adding one to the other — a card statement's purchases are
 * already in the ledger, so a total combining the two counts the same rupees twice.
 */
data class BillInsights(
    /** Not settled and either already due or due soon. What the screen leads with. */
    val dueSoon: List<BillObligation> = emptyList(),
    /** Everything else with a notice against it, newest first — the audit trail. */
    val settledOrPast: List<BillObligation> = emptyList(),
    /**
     * Charges on an established cadence that no biller texts about — subscriptions,
     * SIPs, autopay. Inferred from the ledger by the existing recurring detector rather
     * than from any notice, which is why they are listed apart from [dueSoon].
     */
    val expectedAutoDebits: List<RecurringItem> = emptyList(),
    val trends: List<BillTrend> = emptyList(),
    val monthlyTotals: List<BillMonth> = emptyList(),
    /** Sum of [dueSoon] amounts. An obligation total, never a spending figure. */
    val outstandingTotal: Double = 0.0,
    val hasAnyNotices: Boolean = false
)

data class BulkRecategorizePreview(
    val merchantPattern: String,
    val targetCategory: String,
    val affectedCount: Int,
    val totalAmount: Double,
    val isGenericUnsafe: Boolean,
    val matchingCriteriaDescription: String,
    val matchingTransactions: List<TransactionEntity>
)

/**
 * What a [SmsRepository.reparseStoredTransactions] pass changed.
 *
 * [skippedUserEdited] is reported rather than hidden: a user who re-parses and sees
 * fewer corrections than they expected needs to know their own edits were the reason.
 */
data class ReparseResult(
    val examined: Int = 0,
    val merchantsCorrected: Int = 0,
    val categoriesCorrected: Int = 0,
    val skippedUserEdited: Int = 0
)

class SmsRepository(private val context: Context) {

    // The vault is opened once, after authentication, by VaultSession. Every
    // screen that can construct a repository already sits behind that gate.
    private val db = AppDatabase.requireDatabase()
    private val transactionDao = db.transactionDao()
    private val unparsedSmsDao = db.unparsedSmsDao()
    private val merchantRuleDao = db.merchantRuleDao()
    private val parserRuleDao = db.parserRuleDao()
    private val categoryDao = db.categoryDao()
    private val senderAllowlistDao = db.senderAllowlistDao()
    private val adjustmentDao = db.adjustmentDao()
    private val appSettingDao = db.appSettingDao()
    private val ownAccountDao = db.ownAccountDao()
    private val billNoticeDao = db.billNoticeDao()

    private val parserEngine = SmsParserEngine()

    /**
     * Analytics that know nothing about which accounts are the user's own.
     *
     * Used only where that cannot matter. Anything that produces a spend or income
     * figure builds its own engine from the current own-account set — see
     * [computeAnalytics] — because the set changes while the app is running and a
     * cached engine would keep excluding, or keep counting, an account the user just
     * changed their mind about.
     */
    private val analyticsEngine = FinanceAnalyticsEngine()

    /**
     * Stateless, unlike [FinanceAnalyticsEngine] — a bill's settlement does not depend
     * on which accounts the user has marked as their own, so this one can be held.
     */
    private val billEngine = BillAnalyticsEngine()

    /**
     * T3.3 — the ledger as the user sees it: stored rows folded with their
     * adjustments.
     *
     * The stored rows themselves are never touched. Voided transactions drop out
     * here, which is what keeps them out of every total downstream without any
     * caller needing to know that voiding exists.
     */
    fun getAllTransactions(): Flow<List<TransactionEntity>> =
        transactionDao.getAllTransactions().combine(adjustmentDao.getAll()) { txns, adjustments ->
            AdjustmentFolder.apply(txns, adjustments)
        }

    /** The raw, unadjusted rows — for the audit trail and for export fidelity. */
    fun getStoredTransactions(): Flow<List<TransactionEntity>> = transactionDao.getAllTransactions()

    fun getAdjustmentsByTransaction(): Flow<Map<Long, List<AdjustmentEntity>>> =
        adjustmentDao.getAll().map { list -> list.groupBy { it.transactionId } }

    private val ruleSeeder = ParserRuleSeeder(
        parserRuleDao,
        appSettingDao,
        ParserRuleSeeder.bundledVerifier(context)
    )

    /**
     * T2.2 — installs the bundled signed rule file if it is newer than what is
     * already applied. Called once per unlock.
     */
    suspend fun applyBundledParserRules(): ParserRuleSeeder.Outcome = withContext(Dispatchers.IO) {
        ruleSeeder.seedFromAsset(context)
    }

    /**
     * 5.3 — installs a rule file the user picked. Verified against the same public
     * key as the bundled one, so a sideloaded file is exactly as trustworthy.
     */
    suspend fun applyParserRuleFile(uri: Uri): ParserRuleSeeder.Outcome = withContext(Dispatchers.IO) {
        val json = try {
            context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        } catch (unreadable: Exception) {
            null
        } ?: return@withContext ParserRuleSeeder.Outcome.Rejected(
            RuleLoadResult.Malformed("the file could not be read")
        )

        ruleSeeder.apply(json, allowSameVersion = true)
    }

    /**
     * Reads a rule file without installing it, so the sideload screen can show the
     * user what they are about to accept — and, just as importantly, why it is being
     * refused when it is.
     */
    suspend fun inspectParserRuleFile(uri: Uri): RuleLoadResult = withContext(Dispatchers.IO) {
        val json = try {
            context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        } catch (unreadable: Exception) {
            null
        } ?: return@withContext RuleLoadResult.Malformed("the file could not be read")

        ParserRuleSeeder.bundledVerifier(context).load(json)
    }

    fun getUnreviewedSms(): Flow<List<UnparsedSmsEntity>> = unparsedSmsDao.getUnreviewedSms()
    fun getAllMerchantRules(): Flow<List<MerchantRuleEntity>> = merchantRuleDao.getAllRules()
    fun getAllParserRules(): Flow<List<ParserRuleEntity>> = parserRuleDao.getActiveRules()
    fun getAllCategories(): Flow<List<CategoryEntity>> = categoryDao.getAllCategories()

    /**
     * @param enforceAllowlist F1.1 gating. False for text the user pasted or imported
     *   by hand — they have expressed intent explicitly, so a sender filter would just
     *   throw their input away.
     */
    suspend fun processSingleSms(
        sender: String,
        body: String,
        timestamp: Long,
        enforceAllowlist: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        if (enforceAllowlist) {
            val allowlist = senderAllowlistDao.getEnabledSenderIds().toSet()
            // Not an unparsed-review candidate: T2.3's "never silently dropped" covers
            // covered senders, not every promo and OTP in the inbox.
            if (!SenderMatcher.isAllowed(sender, allowlist)) return@withContext false
        }

        val merchantRules = merchantRuleDao.getAllRulesList()
        val activeParserRules = parserRuleDao.getActiveRulesList()

        val parseResult = parserEngine.parseMessage(
            sender = sender,
            body = body,
            timestamp = timestamp,
            merchantRules = merchantRules,
            parserRules = activeParserRules
        )

        if (parseResult.parsedTransaction != null) {
            val existing = transactionDao.getTransactionByHash(parseResult.parsedTransaction.hash)
            if (existing == null) {
                transactionDao.insertTransaction(parseResult.parsedTransaction)
                return@withContext true
            }
        } else if (parseResult.billNotice != null) {
            // The unique index on noticeHash does the de-duplication, so a re-sent
            // reminder costs one ignored insert rather than a read-then-write race.
            billNoticeDao.insert(parseResult.billNotice)
        } else if (parseResult.unparsedSms != null) {
            unparsedSmsDao.insertUnparsedSms(parseResult.unparsedSms)
        }
        // False, deliberately: the return value is "a transaction was added", and a
        // bill that is owed is not one. Every caller that counts this counts spending.
        return@withContext false
    }

    /**
     * Catches up on everything that arrived while the vault was locked.
     *
     * This is the whole of the deferred-ingestion design. Because the database
     * key is auth-bound (T3.2), nothing can be written while the phone is locked,
     * so the app does not try: the OS SMS inbox *is* the queue, and it costs
     * nothing to maintain. No WorkManager job, no alarm, no wakelock, no
     * foreground service — the app does zero work until the user opens it.
     *
     * The watermark keeps that cheap. A full 500-message re-parse on every
     * unlock would burn CPU proportional to inbox size for a result that only
     * ever changes at the top.
     */
    suspend fun ingestNewMessages(): ScanResult = withContext(Dispatchers.IO) {
        val watermark = appSettingDao.get(AppSettingEntity.KEY_INBOX_WATERMARK)?.toLongOrNull() ?: 0L
        val result = scanDeviceSmsInbox(since = watermark)
        if (result.newestSeen > watermark) {
            appSettingDao.put(
                AppSettingEntity(AppSettingEntity.KEY_INBOX_WATERMARK, result.newestSeen.toString())
            )
        }
        PendingIngestMarker.clear(context)
        result
    }

    /**
     * Re-runs the current parser over rows already in the ledger.
     *
     * Ingestion de-duplicates on `sha256(sender|body|minute)`, which is deliberately
     * independent of what the parser made of the message. That is right for avoiding
     * duplicate rows and wrong for everything else: a row parsed by an older ruleset
     * keeps its answer forever, because a rescan finds the hash already present and
     * skips the message before the parser is reached. `rescanEntireInbox` does not
     * help — it only rewinds the watermark, and the hash check still short-circuits.
     *
     * Corrections are appended as adjustments rather than written over the rows:
     * `TransactionDao` has no `@Update` by design (T3.3), and the original parse stays
     * recoverable. Anything the user has already decided by hand ([AdjustmentSource.USER])
     * or through a bulk rule ([AdjustmentSource.RULE]) is left alone — a parser
     * improvement is not grounds to overrule them.
     */
    suspend fun reparseStoredTransactions(): ReparseResult = withContext(Dispatchers.IO) {
        val stored = transactionDao.getAllTransactions().first()
        val adjustmentsByTxn = adjustmentDao.getAll().first().groupBy { it.transactionId }
        val merchantRules = merchantRuleDao.getAllRulesList()
        val parserRules = parserRuleDao.getActiveRulesList()
        val now = System.currentTimeMillis()

        val pending = mutableListOf<AdjustmentEntity>()
        var merchantsCorrected = 0
        var categoriesCorrected = 0
        var skippedUserEdited = 0
        var examined = 0

        for (txn in stored) {
            val existing = adjustmentsByTxn[txn.id].orEmpty()
            if (existing.any { it.field == AdjustmentField.VOID }) continue

            val effective = AdjustmentFolder.apply(listOf(txn), existing).firstOrNull() ?: continue
            examined++

            // Manual entries carry a note, not a bank message; there is nothing to re-parse.
            if (txn.sender == MANUAL_SENDER) continue

            val reparsed = parserEngine.parseMessage(
                sender = txn.sender,
                body = txn.rawMessage,
                timestamp = txn.timestamp,
                merchantRules = merchantRules,
                parserRules = parserRules
            ).parsedTransaction ?: continue

            fun decidedByUser(field: String) = existing.any {
                it.field == field &&
                    (it.source == AdjustmentSource.USER || it.source == AdjustmentSource.RULE)
            }

            var touchedUserField = false

            // Never trade a real payee for a placeholder or an issuer name: a rule
            // change that makes one message parse better can make another parse worse,
            // and a re-parse that degrades the ledger is worse than one that skips it.
            val degrades = SmsParserEngine.isUnsafeAsRulePattern(reparsed.merchant) &&
                !SmsParserEngine.isUnsafeAsRulePattern(effective.merchant)

            if (!degrades && reparsed.merchant != effective.merchant) {
                if (decidedByUser(AdjustmentField.MERCHANT)) {
                    touchedUserField = true
                } else {
                    pending += AdjustmentEntity(
                        transactionId = txn.id,
                        field = AdjustmentField.MERCHANT,
                        oldValue = effective.merchant,
                        newValue = reparsed.merchant,
                        reason = "Re-parsed under current rules",
                        createdAt = now,
                        source = AdjustmentSource.REPARSE
                    )
                    merchantsCorrected++
                }
            }

            if (reparsed.category != effective.category) {
                if (decidedByUser(AdjustmentField.CATEGORY)) {
                    touchedUserField = true
                } else {
                    pending += AdjustmentEntity(
                        transactionId = txn.id,
                        field = AdjustmentField.CATEGORY,
                        oldValue = effective.category,
                        newValue = reparsed.category,
                        reason = "Re-parsed under current rules",
                        createdAt = now,
                        source = AdjustmentSource.REPARSE
                    )
                    categoriesCorrected++
                }
            }

            if (touchedUserField) skippedUserEdited++
        }

        if (pending.isNotEmpty()) adjustmentDao.insertAll(pending)

        ReparseResult(
            examined = examined,
            merchantsCorrected = merchantsCorrected,
            categoriesCorrected = categoriesCorrected,
            skippedUserEdited = skippedUserEdited
        )
    }

    /** Forces a re-read of the whole inbox, ignoring the watermark. */
    suspend fun rescanEntireInbox(): ScanResult = withContext(Dispatchers.IO) {
        val result = scanDeviceSmsInbox(since = 0L)
        if (result.newestSeen > 0L) {
            appSettingDao.put(
                AppSettingEntity(AppSettingEntity.KEY_INBOX_WATERMARK, result.newestSeen.toString())
            )
        }
        PendingIngestMarker.clear(context)
        result
    }

    suspend fun scanDeviceSmsInbox(since: Long = 0L): ScanResult = withContext(Dispatchers.IO) {
        var newTxns = 0
        var unparsed = 0
        var newBills = 0
        var totalScanned = 0
        var newestSeen = since
        try {
            val uri = Telephony.Sms.Inbox.CONTENT_URI
            val projection = arrayOf(
                Telephony.Sms.Inbox.ADDRESS,
                Telephony.Sms.Inbox.BODY,
                Telephony.Sms.Inbox.DATE
            )

            val cursor = context.contentResolver.query(
                uri,
                projection,
                if (since > 0L) "${Telephony.Sms.Inbox.DATE} > ?" else null,
                if (since > 0L) arrayOf(since.toString()) else null,
                "${Telephony.Sms.Inbox.DATE} DESC LIMIT 500"
            )

            cursor?.use {
                val addressIdx = it.getColumnIndex(Telephony.Sms.Inbox.ADDRESS)
                val bodyIdx = it.getColumnIndex(Telephony.Sms.Inbox.BODY)
                val dateIdx = it.getColumnIndex(Telephony.Sms.Inbox.DATE)

                val merchantRules = merchantRuleDao.getAllRulesList()
                val activeParserRules = parserRuleDao.getActiveRulesList()
                val allowlist = senderAllowlistDao.getEnabledSenderIds().toSet()

                while (it.moveToNext()) {
                    val address = if (addressIdx != -1) it.getString(addressIdx) else "BankSMS"
                    val body = if (bodyIdx != -1) it.getString(bodyIdx) else ""
                    val date = if (dateIdx != -1) it.getLong(dateIdx) else System.currentTimeMillis()

                    // Advance past everything examined, allowlisted or not, so a
                    // spam-heavy inbox isn't re-walked on every unlock.
                    if (date > newestSeen) newestSeen = date

                    // F1.1 — read bank senders only. Everything else in the inbox is
                    // skipped outright rather than parsed and queued for review.
                    if (address == null || !SenderMatcher.isAllowed(address, allowlist)) continue

                    if (body.isNotBlank()) {
                        totalScanned++
                        val parseResult = parserEngine.parseMessage(
                            sender = address,
                            body = body,
                            timestamp = date,
                            merchantRules = merchantRules,
                            parserRules = activeParserRules
                        )

                        if (parseResult.parsedTransaction != null) {
                            val existing = transactionDao.getTransactionByHash(parseResult.parsedTransaction.hash)
                            if (existing == null) {
                                transactionDao.insertTransaction(parseResult.parsedTransaction)
                                newTxns++
                            }
                        } else if (parseResult.billNotice != null) {
                            if (billNoticeDao.insert(parseResult.billNotice) != -1L) newBills++
                        } else if (parseResult.unparsedSms != null) {
                            val existingUnparsed = unparsedSmsDao.getUnreviewedSms().first().find { u -> u.rawMessage == body }
                            if (existingUnparsed == null) {
                                unparsedSmsDao.insertUnparsedSms(parseResult.unparsedSms)
                                unparsed++
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext ScanResult(
            newTransactionsCount = newTxns,
            unparsedCount = unparsed,
            newBillNoticesCount = newBills,
            totalScanned = totalScanned,
            newestSeen = newestSeen
        )
    }

    /** Text the user pasted in by hand — bypasses the sender allowlist by design. */
    suspend fun processBatchRawText(rawTextList: List<String>): Int = withContext(Dispatchers.IO) {
        var count = 0
        val now = System.currentTimeMillis()
        val step = 86400000L / 2 // Spaced out over days

        rawTextList.forEachIndexed { index, text ->
            val time = now - (index * step)
            if (processSingleSms("BANK-SMS", text, time, enforceAllowlist = false)) {
                count++
            }
        }
        return@withContext count
    }

    fun getSenderAllowlist(): Flow<List<SenderAllowlistEntity>> = senderAllowlistDao.getAll()

    suspend fun addAllowedSender(senderId: String, label: String) = withContext(Dispatchers.IO) {
        senderAllowlistDao.upsert(
            SenderAllowlistEntity(
                senderId = SenderMatcher.normalize(senderId),
                label = label.ifBlank { senderId }
            )
        )
    }

    suspend fun removeAllowedSender(senderId: String) = withContext(Dispatchers.IO) {
        senderAllowlistDao.delete(senderId)
    }

    /**
     * F1.5 — import a CSV in exactly the shape exportDataAsCsv() writes, so the two
     * round-trip. A malformed header rejects the whole file rather than importing a
     * partial ledger, which would be worse than importing nothing.
     */
    suspend fun importCsv(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val lines = try {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.readLines()
                ?: return@withContext ImportResult(error = "Could not open the file.")
        } catch (e: Exception) {
            return@withContext ImportResult(error = "Could not read the file: ${e.message}")
        }

        if (lines.isEmpty()) return@withContext ImportResult(error = "The file is empty.")

        val header = parseCsvLine(lines.first()).map { it.trim() }
        val expected = listOf(
            "ID", "Date", "Sender", "Merchant", "Direction",
            "Amount", "Category", "Channel", "AccountTail", "RawSMS"
        )
        if (header != expected) {
            return@withContext ImportResult(
                error = "Unexpected columns. Expected an Arth Vault export:\n${expected.joinToString(",")}"
            )
        }

        var imported = 0
        var duplicates = 0
        var skipped = 0

        lines.drop(1).filter { it.isNotBlank() }.forEach { line ->
            val cells = parseCsvLine(line)
            if (cells.size < expected.size) {
                skipped++
                return@forEach
            }
            val amount = cells[5].toDoubleOrNull()
            val timestamp = cells[1].toLongOrNull()
            if (amount == null || timestamp == null || amount <= 0.0) {
                skipped++
                return@forEach
            }

            val merchant = cells[3].ifBlank { "Unknown Merchant" }
            val direction = if (cells[4].equals("CREDIT", ignoreCase = true)) "CREDIT" else "DEBIT"
            val hash = "import_${timestamp}_${merchant}_$amount"

            if (transactionDao.getTransactionByHash(hash) != null) {
                duplicates++
                return@forEach
            }

            transactionDao.insertTransaction(
                TransactionEntity(
                    amount = amount,
                    direction = direction,
                    timestamp = timestamp,
                    sender = cells[2].ifBlank { "CSV_IMPORT" },
                    merchant = merchant,
                    accountTail = cells[8].ifBlank { null },
                    channel = cells[7].ifBlank { null },
                    category = cells[6].ifBlank { "Other / Misc" },
                    rawMessage = cells[9],
                    hash = hash
                )
            )
            imported++
        }

        return@withContext ImportResult(imported = imported, duplicates = duplicates, skipped = skipped)
    }

    /** Minimal RFC4180 reader: handles quoted fields and doubled quotes. */
    private fun parseCsvLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"'); i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    cells.add(current.toString()); current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        cells.add(current.toString())
        return cells
    }

    /**
     * F2.2/F2.4 — a re-categorisation, recorded rather than applied.
     *
     * This used to run an UPDATE over the row (and, in the bulk case, over every
     * row matching the merchant), destroying whatever the parser had originally
     * decided. Now each change is one append to `adjustments` carrying the value
     * it replaced, so the original classification is always recoverable and a
     * bulk edit made by mistake is legible after the fact.
     */
    /**
     * The merchant string a bulk re-categorisation should actually key on.
     *
     * A row whose merchant is a bank name ("using ICICI Bank") or a generic token is
     * useless as a pattern — it identifies the issuer, not the payee — so the raw
     * message is re-parsed to recover the real one.
     *
     * [parserRules] must be the live rules. Since T2.2 the engine carries no built-in
     * patterns: every rule arrives as data, and the loop that builds a transaction is
     * `for (rule in parserRules)`. This used to pass `emptyList()`, so the re-parse
     * returned null every single time and the recovery path could never fire. Worse
     * than a no-op — the bank name survived as the pattern, and [matchesTxnPattern]
     * then matched it against every message body from that bank.
     */
    suspend fun resolveMerchantPattern(
        merchant: String,
        rawMessage: String?,
        sender: String = UNKNOWN_SENDER
    ): String = resolveMerchantPattern(
        merchant, rawMessage, sender, parserRuleDao.getActiveRulesList()
    )

    fun resolveMerchantPattern(
        merchant: String,
        rawMessage: String?,
        sender: String,
        parserRules: List<ParserRuleEntity>
    ): String {
        val cleanMerchant = merchant.trim()
        if (SmsParserEngine.isBankNameOnly(cleanMerchant) || SmsParserEngine.isGenericOrUnsafeMerchant(cleanMerchant)) {
            if (!rawMessage.isNullOrBlank()) {
                // The real sender, not a placeholder: a user-authored rule carries a
                // senderPattern, and senderMatchesRule would skip it for a stand-in.
                val parsed = parserEngine.parseMessage(
                    sender.ifBlank { UNKNOWN_SENDER },
                    rawMessage,
                    System.currentTimeMillis(),
                    emptyList(),
                    parserRules
                )
                parsed.parsedTransaction?.merchant?.let { extracted ->
                    if (extracted != SmsParserEngine.UNKNOWN_MERCHANT &&
                        !SmsParserEngine.isBankNameOnly(extracted) &&
                        !SmsParserEngine.isGenericOrUnsafeMerchant(extracted)
                    ) {
                        return extracted
                    }
                }
            }
        }
        return cleanMerchant
    }

    fun matchesMerchantPattern(txnMerchant: String, targetMerchant: String): Boolean {
        val cleanTxn = txnMerchant.trim().uppercase(java.util.Locale.ROOT)
        val cleanTarget = targetMerchant.trim().uppercase(java.util.Locale.ROOT)
        if (cleanTarget.isBlank()) return false
        if (SmsParserEngine.isGenericOrUnsafeMerchant(cleanTarget)) {
            return cleanTxn == cleanTarget
        }
        if (cleanTxn == cleanTarget) return true
        return try {
            val regex = Regex("\\b" + Regex.escape(cleanTarget) + "\\b", RegexOption.IGNORE_CASE)
            regex.containsMatchIn(cleanTxn)
        } catch (_: Exception) {
            cleanTxn.contains(cleanTarget, ignoreCase = true)
        }
    }

    /**
     * Whether [txn] belongs to the bulk edit keyed on [targetMerchant].
     *
     * The message body is searched as well as the merchant name, because a payee the
     * parser failed to lift out of a message is still named *in* it — that is the only
     * way a badly-parsed row can be swept up by a correction aimed at the right name.
     *
     * But not for a bank name. "using ICICI Bank" appears verbatim in every ICICI card
     * SMS, so body matching on it selected the issuer's entire history regardless of
     * payee — a bulk edit aimed at one merchant silently re-filed everything from that
     * bank. A pattern that names the issuer rather than the payee is matched against
     * the merchant field alone, where it can only hit rows that really carry it.
     */
    fun matchesTxnPattern(txn: TransactionEntity, targetMerchant: String): Boolean {
        val cleanTarget = targetMerchant.trim().uppercase(java.util.Locale.ROOT)
        if (cleanTarget.isBlank()) return false

        val cleanTxnMerchant = txn.merchant.trim().uppercase(java.util.Locale.ROOT)
        val cleanRawMessage = txn.rawMessage.trim().uppercase(java.util.Locale.ROOT)

        if (SmsParserEngine.isGenericOrUnsafeMerchant(cleanTarget)) {
            return cleanTxnMerchant == cleanTarget
        }
        if (cleanTxnMerchant == cleanTarget || cleanTxnMerchant.contains(cleanTarget)) return true

        val searchBody = !SmsParserEngine.isBankNameOnly(cleanTarget)

        return try {
            val regex = Regex("\\b" + Regex.escape(cleanTarget) + "\\b", RegexOption.IGNORE_CASE)
            regex.containsMatchIn(cleanTxnMerchant) ||
                (searchBody && regex.containsMatchIn(cleanRawMessage))
        } catch (_: Exception) {
            cleanTxnMerchant.contains(cleanTarget, ignoreCase = true) ||
                (searchBody && cleanRawMessage.contains(cleanTarget, ignoreCase = true))
        }
    }

    suspend fun previewBulkRecategorization(
        merchant: String,
        newCategory: String,
        rawMessage: String? = null,
        sender: String = UNKNOWN_SENDER
    ): BulkRecategorizePreview = withContext(Dispatchers.IO) {
        val effectivePattern = resolveMerchantPattern(merchant, rawMessage, sender)
        val effective = getAllTransactions().first()
        val cleanMerchant = effectivePattern.trim()

        // A bank name is as unsafe to generalise from as a generic token: it names the
        // issuer, so a rule built on it would re-file every future card spend from that
        // bank. Folding it in here is what keeps the "save rule" checkbox off by
        // default and the warning visible.
        val isBankName = SmsParserEngine.isBankNameOnly(cleanMerchant)
        val isUnsafe = SmsParserEngine.isUnsafeAsRulePattern(effectivePattern)

        val matching = effective.filter { txn ->
            matchesTxnPattern(txn, effectivePattern) && txn.category != newCategory
        }

        val criteriaDesc = when {
            isBankName ->
                "'$cleanMerchant' names the bank, not the payee — matched against merchant " +
                    "names only, and no global rule will be created."
            isUnsafe -> "Broad term '$cleanMerchant' — exact match only (no global rule created)."
            cleanMerchant.length <= 4 -> "Exact merchant name match for '$cleanMerchant'."
            else -> "Word boundary pattern match '\\b$cleanMerchant\\b' across merchant names or SMS text."
        }

        BulkRecategorizePreview(
            merchantPattern = effectivePattern,
            targetCategory = newCategory,
            affectedCount = matching.size,
            totalAmount = matching.sumOf { it.amount },
            isGenericUnsafe = isUnsafe,
            matchingCriteriaDescription = criteriaDesc,
            matchingTransactions = matching
        )
    }

    /**
     * Selective bulk re-categorisation allowing users to accept a subset of transaction IDs.
     */
    suspend fun updateSelectedTransactionCategories(
        selectedTransactionIds: Set<Long>,
        newCategory: String,
        merchantPattern: String,
        saveGlobalRule: Boolean
    ) = withContext(Dispatchers.IO) {
        if (selectedTransactionIds.isEmpty()) return@withContext
        val effective = getAllTransactions().first()
        val now = System.currentTimeMillis()

        val targets = effective.filter { it.id in selectedTransactionIds }

        adjustmentDao.insertAll(
            targets.filter { it.category != newCategory }.map { txn ->
                AdjustmentEntity(
                    transactionId = txn.id,
                    field = AdjustmentField.CATEGORY,
                    oldValue = txn.category,
                    newValue = newCategory,
                    reason = "Bulk re-categorisation of $merchantPattern",
                    createdAt = now,
                    source = AdjustmentSource.RULE
                )
            }
        )

        if (saveGlobalRule && !SmsParserEngine.isUnsafeAsRulePattern(merchantPattern)) {
            merchantRuleDao.insertOrUpdateRule(
                MerchantRuleEntity(
                    merchantPattern = merchantPattern.trim(),
                    assignedCategory = newCategory
                )
            )
        }
    }

    /**
     * F2.2/F2.4 — a re-categorisation, recorded rather than applied.
     *
     * This used to run an UPDATE over the row (and, in the bulk case, over every
     * row matching the merchant), destroying whatever the parser had originally
     * decided. Now each change is one append to `adjustments` carrying the value
     * it replaced, so the original classification is always recoverable and a
     * bulk edit made by mistake is legible after the fact.
     */
    suspend fun updateTransactionCategory(
        id: Long,
        newCategory: String,
        merchant: String,
        updateAllForMerchant: Boolean
    ) = withContext(Dispatchers.IO) {
        val effective = getAllTransactions().first()
        val now = System.currentTimeMillis()

        val targets = if (updateAllForMerchant) {
            effective.filter { matchesMerchantPattern(it.merchant, merchant) }
        } else {
            effective.filter { it.id == id }
        }

        adjustmentDao.insertAll(
            targets.filter { it.category != newCategory }.map { txn ->
                AdjustmentEntity(
                    transactionId = txn.id,
                    field = AdjustmentField.CATEGORY,
                    oldValue = txn.category,
                    newValue = newCategory,
                    reason = if (updateAllForMerchant) "Bulk re-categorisation of $merchant" else null,
                    createdAt = now,
                    source = if (updateAllForMerchant) AdjustmentSource.RULE else AdjustmentSource.USER
                )
            }
        )

        if (updateAllForMerchant && !SmsParserEngine.isUnsafeAsRulePattern(merchant)) {
            merchantRuleDao.insertOrUpdateRule(
                MerchantRuleEntity(
                    merchantPattern = merchant.trim(),
                    assignedCategory = newCategory
                )
            )
        }
    }

    /** The correction history behind one transaction, for the tap-through view. */
    suspend fun getAdjustmentsFor(transactionId: Long): List<AdjustmentEntity> =
        withContext(Dispatchers.IO) { adjustmentDao.getForTransaction(transactionId) }

    suspend fun addManualTransaction(
        amount: Double,
        direction: String,
        merchant: String,
        category: String,
        channel: String,
        rawNote: String
    ) = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        val hash = "manual_${timestamp}_${merchant}_$amount"
        transactionDao.insertTransaction(
            TransactionEntity(
                amount = amount,
                direction = direction,
                timestamp = timestamp,
                sender = MANUAL_SENDER,
                merchant = merchant,
                accountTail = "Cash",
                channel = channel,
                category = category,
                rawMessage = rawNote.ifBlank { "Manual entry: $merchant - Rs. $amount" },
                hash = hash
            )
        )
    }

    /**
     * T3.3 — removal without erasure.
     *
     * The transaction leaves the ledger and every total. The row and its original
     * SMS stay, so a mistaken swipe is recoverable and the record still reflects
     * what the bank actually sent. F5.2's wipe is the destructive operation, and
     * it is the only one.
     */
    suspend fun voidTransaction(transaction: TransactionEntity, reason: String? = null) =
        withContext(Dispatchers.IO) {
            adjustmentDao.insert(
                AdjustmentEntity(
                    transactionId = transaction.id,
                    field = AdjustmentField.VOID,
                    oldValue = transaction.amount.toString(),
                    newValue = null,
                    reason = reason ?: "Removed from the ledger by the user"
                )
            )
        }

    suspend fun markUnparsedReviewed(id: Long) = withContext(Dispatchers.IO) {
        unparsedSmsDao.markAsReviewed(id)
    }

    /**
     * Runs all four detectors. Anomalies and duplicates were implemented but never
     * called, so F3.3/F3.4 shipped invisible; they are part of the result now.
     *
     * Results are computed on demand rather than written back onto the transaction
     * rows — transactions stay immutable (T3.3), and there is no flag column to
     * fall out of sync with the data.
     */
    suspend fun computeAnalytics(
        scope: PeriodScope = PeriodScope.THIS_MONTH,
        now: Long = System.currentTimeMillis()
    ): AnalyticsResult = withContext(Dispatchers.IO) {
        // The folded ledger, not the stored rows. Reading `transactionDao` directly —
        // as this used to — meant every correction and every voided transaction was
        // invisible to analytics: a transaction the user had removed still counted
        // towards the spend total, the donut and the forecast, while the ledger
        // screen right next to it showed it gone.
        val txns = getAllTransactions().first()

        // Built per call from the current set. Moving money between your own accounts
        // is not spending, and the receiving leg is not income; the engine can only
        // know that if it is told which accounts are yours.
        val engine = FinanceAnalyticsEngine(ownAccountDao.getTails().toSet())
        val period = PeriodResolver.resolve(scope, now)

        return@withContext AnalyticsResult(
            period = period,
            summary = engine.computePeriodSummary(txns, period.range),
            comparisonSummary = engine.computePeriodSummary(txns, period.comparison),
            dailyTotals = engine.computeDailyTotals(txns, period.range),
            comparisonDailyTotals = engine.computeDailyTotals(txns, period.comparison),
            recurring = engine.detectRecurringAndPriceHikes(txns),
            recurringIncome = engine.detectRecurringIncome(txns),
            // A forecast only means something for a window that is still filling.
            // Offering one for a month that has already ended is a projection of the
            // past, which is a contradiction the screen should not have to explain.
            forecast = if (scope == PeriodScope.THIS_MONTH) {
                engine.computeMonthEndForecast(txns, now)
            } else {
                null
            },
            // Detection still runs over the whole ledger — a category needs all its
            // history to know what normal costs — but only findings inside the window
            // are reported. Otherwise these lists only ever grew.
            anomalies = engine.detectAnomalies(txns, reportRange = period.range),
            duplicates = engine.detectDuplicates(txns, reportRange = period.range),
            categoryBreakdown = engine.computeCategoryBreakdown(
                transactions = txns,
                rangeStart = period.range.first,
                rangeEnd = period.range.last
            ),
            // F3.6 — against the *like-for-like* earlier window. This used to compare
            // a month-to-date total against a whole previous month, so on the 3rd of
            // the month it reported that spending had collapsed in every category.
            categoryTrends = engine.compareCategories(
                transactions = txns,
                periodA = period.comparison,
                periodB = period.range
            ),
            // Shown on screen rather than silently netted away: see
            // InternalTransferSummary.
            internalTransfers = engine.summariseInternalTransfers(
                transactions = txns,
                rangeStart = period.range.first,
                rangeEnd = period.range.last
            )
        )
    }

    // --- bills (v6) ---

    fun getBillNotices(): Flow<List<BillNoticeEntity>> = billNoticeDao.getAll()

    suspend fun getBillNoticesByIds(ids: List<Long>): List<BillNoticeEntity> =
        withContext(Dispatchers.IO) {
            val wanted = ids.toSet()
            billNoticeDao.getAllList().filter { it.id in wanted }
        }

    /**
     * Phase 9 — obligations, their settlement state, and how they have moved.
     *
     * The ledger is read through [getAllTransactions], the adjustment-folded view, for
     * the same reason `computeAnalytics` does: a payment the user has voided must not
     * go on marking a bill settled.
     *
     * @param dueSoonHorizonDays how far ahead counts as "due soon". Bills already past
     *   their date are always included regardless — a missed deadline does not stop
     *   being the most important thing on the screen because it is now in the past.
     */
    suspend fun computeBillInsights(
        now: Long = System.currentTimeMillis(),
        dueSoonHorizonDays: Int = DUE_SOON_HORIZON_DAYS
    ): BillInsights = withContext(Dispatchers.IO) {
        val notices = billNoticeDao.getAllList()
        val txns = getAllTransactions().first()

        val obligations = billEngine.reconcile(notices, txns)

        val (dueSoon, rest) = obligations.partition { obligation ->
            // Only a full match retires a bill. LIKELY_PAID means something linked to
            // this biller settled but not the sum that clears it — the commonest case
            // being a minimum-due payment on a card, which leaves the balance owed and
            // accruing interest. Filing that as settled would hide the bill that most
            // needs looking at.
            if (obligation.settlement == BillSettlement.PAID) return@partition false
            // An undated obligation with nothing seen against it is still outstanding.
            // Dropping it because the biller forgot to name a date would hide a real
            // bill; it sorts last, which is where an unknown deadline belongs.
            val days = obligation.daysUntilDue(now) ?: return@partition true
            days <= dueSoonHorizonDays
        }

        // Detection needs the whole ledger to establish a cadence; the own-account set
        // matters because a standing transfer to the user's own savings is not a bill.
        val autoDebits = FinanceAnalyticsEngine(ownAccountDao.getTails().toSet())
            .detectRecurringAndPriceHikes(txns)
            .sortedBy { it.daysUntilNextCharge(now) }

        BillInsights(
            dueSoon = dueSoon,
            settledOrPast = rest.sortedByDescending { it.dueDate ?: it.issuedAt },
            expectedAutoDebits = autoDebits,
            trends = billEngine.trends(obligations),
            monthlyTotals = billEngine.monthlyTotals(obligations),
            outstandingTotal = dueSoon.sumOf { it.amountDue ?: 0.0 },
            hasAnyNotices = notices.isNotEmpty()
        )
    }

    // --- own accounts (v5) ---

    fun getOwnAccounts(): Flow<List<OwnAccountEntity>> = ownAccountDao.getAll()

    /** Account tails the parser has actually seen — the candidates worth offering. */
    fun getObservedAccountTails(): Flow<List<String>> = ownAccountDao.getObservedTails()

    suspend fun markAccountAsOwn(tail: String, label: String) = withContext(Dispatchers.IO) {
        ownAccountDao.upsert(OwnAccountEntity(tail = tail.trim(), label = label.trim()))
    }

    suspend fun unmarkAccount(tail: String) = withContext(Dispatchers.IO) {
        ownAccountDao.delete(tail)
    }

    /**
     * F4.1 — answers a typed question about the ledger.
     *
     * @return null when the grammar could not read the question. That is a real
     *   answer: for a question about money, "I did not understand" beats a confident
     *   number answering something else (F4.2 — nothing here is generated).
     */
    suspend fun answerQuestion(question: String): QueryResult? = withContext(Dispatchers.IO) {
        val categories = categoryDao.getAllCategories().first().map { it.name }
        val intent = QueryParser(categories).parse(question) ?: return@withContext null
        LedgerQueryEngine().run(intent, getAllTransactions().first())
    }

    /** F4.4 — the rows behind a figure, for tap-through from any insight. */
    suspend fun getTransactionsByIds(ids: List<Long>): List<TransactionEntity> =
        withContext(Dispatchers.IO) {
            val wanted = ids.toSet()
            getAllTransactions().first().filter { it.id in wanted }
        }

    /**
     * F5.1 — exports the ledger as the user sees it: corrections applied, voided
     * entries gone. A CSV that re-listed a removed transaction as live would be
     * wrong in the one place people actually check their numbers. The full
     * audit trail, including voids and the values they replaced, travels in the
     * F5.3 backup instead.
     */
    suspend fun exportDataAsCsv(): File = withContext(Dispatchers.IO) {
        val txns = getAllTransactions().first()

        // Must match the <cache-path> in res/xml/file_paths.xml, or the share
        // sheet cannot resolve a URI for the file.
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }

        // Exports are copies of the whole ledger. Don't accumulate them in the
        // cache — keep only the newest.
        exportDir.listFiles()?.forEach { it.delete() }

        val file = File(exportDir, "vault_ledger_export_${System.currentTimeMillis()}.csv")
        FileWriter(file).use { writer ->
            writer.append("ID,Date,Sender,Merchant,Direction,Amount,Category,Channel,AccountTail,RawSMS\n")
            txns.forEach { t ->
                val cleanRaw = t.rawMessage.replace("\"", "\"\"")
                writer.append("${t.id},${t.timestamp},${t.sender},\"${t.merchant}\",${t.direction},${t.amount},\"${t.category}\",${t.channel ?: ""},${t.accountTail ?: ""},\"$cleanRaw\"\n")
            }
        }
        return@withContext file
    }

    suspend fun exportDataAsJson(): String = withContext(Dispatchers.IO) {
        val txns = getAllTransactions().first()
        val jsonArray = JSONArray()
        txns.forEach { t ->
            val obj = JSONObject().apply {
                put("id", t.id)
                put("timestamp", t.timestamp)
                put("sender", t.sender)
                put("merchant", t.merchant)
                put("direction", t.direction)
                put("amount", t.amount)
                put("category", t.category)
                put("channel", t.channel)
                put("accountTail", t.accountTail)
                put("rawMessage", t.rawMessage)
            }
            jsonArray.put(obj)
        }
        return@withContext jsonArray.toString(2)
    }

    /**
     * F5.2 — every trace of the user, not just the transaction table.
     *
     * Merchant rules are the user's own categorisation corrections, which is
     * behavioural data; custom categories and custom parser rules are theirs too.
     * Built-in categories and system parser rules survive so the app still works
     * afterwards. Exported CSVs sitting in the cache go as well.
     */
    /**
     * F5.3 — writes an encrypted backup to a location the user chose.
     *
     * Carries the *stored* rows plus the adjustment history rather than the
     * folded view: a backup that flattened corrections would quietly discard the
     * T3.3 audit trail it is supposed to preserve.
     */
    suspend fun writeEncryptedBackup(uri: Uri, passphrase: CharArray): BackupResult =
        withContext(Dispatchers.IO) {
            try {
                val payload = BackupPayload(
                    transactions = transactionDao.getAllTransactions().first(),
                    merchantRules = merchantRuleDao.getAllRulesList(),
                    customCategories = categoryDao.getAllCategories().first().filter { it.isCustom },
                    customParserRules = parserRuleDao.getActiveRulesList().filter { !it.isSystemRule },
                    senderAllowlist = senderAllowlistDao.getAll().first(),
                    ownAccounts = ownAccountDao.getAll().first(),
                    billNotices = billNoticeDao.getAllList()
                )
                val bytes = BackupCodec.encode(payload, passphrase)
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                    ?: return@withContext BackupResult(error = "Could not write to the chosen location.")
                BackupResult(transactionCount = payload.transactions.size, byteCount = bytes.size)
            } catch (e: Exception) {
                BackupResult(error = e.message ?: "The backup could not be written.")
            } finally {
                passphrase.fill('\u0000')
            }
        }

    /**
     * F5.3 — restores a backup by merging, not replacing.
     *
     * The unique index on `hash` plus IGNORE means restoring the same file twice
     * is a no-op, and restoring an older backup over a newer ledger adds what is
     * missing instead of destroying what is there.
     */
    suspend fun restoreEncryptedBackup(uri: Uri, passphrase: CharArray): RestoreResult =
        withContext(Dispatchers.IO) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@withContext RestoreResult(error = "Could not open the file.")

                val payload = BackupCodec.decode(bytes, passphrase)

                val existing = transactionDao.getAllTransactions().first().map { it.hash }.toSet()
                val fresh = payload.transactions.filter { it.hash !in existing }
                // Ids are reassigned on insert, so adjustments cannot be carried
                // across as-is; they travel with the file for a future restore
                // that can map them, and the folded categories are already in the
                // exported rows.
                transactionDao.insertAllTransactions(fresh.map { it.copy(id = 0) })

                payload.merchantRules.forEach { merchantRuleDao.insertOrUpdateRule(it) }
                payload.customCategories.forEach { categoryDao.insertCategory(it) }
                payload.customParserRules.forEach { parserRuleDao.insertRule(it) }
                senderAllowlistDao.insertDefaults(payload.senderAllowlist)
                // Without these the restored ledger would count every own-account
                // transfer as spending again, and disagree with the ledger the
                // backup was taken from.
                payload.ownAccounts.forEach { ownAccountDao.upsert(it) }
                // Ids reassigned on insert, as with transactions; the unique noticeHash
                // is what makes restoring the same file twice a no-op.
                billNoticeDao.insertAll(payload.billNotices.map { it.copy(id = 0) })

                RestoreResult(
                    transactionsRestored = fresh.size,
                    duplicatesSkipped = payload.transactions.size - fresh.size,
                    rulesRestored = payload.merchantRules.size
                )
            } catch (e: javax.crypto.AEADBadTagException) {
                RestoreResult(error = "Wrong passphrase, or the file has been altered.")
            } catch (e: Exception) {
                RestoreResult(error = e.message ?: "The backup could not be restored.")
            } finally {
                passphrase.fill('\u0000')
            }
        }

    suspend fun fullLocalWipe() = withContext(Dispatchers.IO) {
        transactionDao.deleteAllTransactions()
        adjustmentDao.deleteAll()
        appSettingDao.deleteAll()
        unparsedSmsDao.deleteAllUnparsedSms()
        merchantRuleDao.deleteAllRules()
        parserRuleDao.deleteAllCustomRules()
        categoryDao.deleteCustomCategories()
        // F5.2 — these are account numbers the user told us about. A wipe that left
        // them behind would leave a record of which accounts they hold.
        ownAccountDao.deleteAll()
        // A notice names an account, a biller and a sum owed. Leaving them would leave
        // a record of which cards and utilities the user holds.
        billNoticeDao.deleteAll()
        PendingIngestMarker.clear(context)

        File(context.cacheDir, "exports").listFiles()?.forEach { it.delete() }

        // Restore the system baseline. Merchant rules are wiped wholesale above
        // because user overrides live in the same table; without this the app comes
        // back with no categorisation and everything lands in "Other / Misc".
        categoryDao.insertDefaultCategories(DefaultSeedData.categories)
        DefaultSeedData.merchantRules.forEach { merchantRuleDao.insertOrUpdateRule(it) }
    }

    /**
     * Creates one of the user's own categories, or explains why it could not.
     *
     * `iconName` and `colorHex` are not asked for. Both columns are vestigial: the
     * seeded hexes were tuned against a palette this app no longer has, nothing in
     * `ui/` reads either field, and a category's colour is derived at render time from
     * the theme-aware ramp (see `MerchantAvatar`) so that it is legible on both
     * grounds. Offering the user a colour picker whose result is never drawn would be
     * a control that does nothing. The neutral defaults written here are the same ones
     * `BackupCodec` substitutes when restoring a backup that predates the columns, so
     * a created category and a restored one are indistinguishable.
     */
    suspend fun addCustomCategory(name: String): AddCategoryOutcome = withContext(Dispatchers.IO) {
        val existing = categoryDao.getAllCategories().first().map { it.name }
        val outcome = CategoryEditor.validateNew(name, existing)
        if (outcome is AddCategoryOutcome.Added) {
            categoryDao.insertCategory(
                CategoryEntity(
                    name = outcome.name,
                    iconName = "Category",
                    colorHex = "#607D8B",
                    isCustom = true
                )
            )
        }
        outcome
    }

    /**
     * Removes one of the user's own categories, if nothing is relying on it.
     *
     * The usage count comes from [getAllTransactions] rather than the raw table,
     * because a transaction the user has already recategorised carries its new
     * category only in the adjustments overlay. Counting the stored rows would report
     * a freshly-created category as unused at the exact moment it is being used.
     */
    suspend fun deleteCustomCategory(name: String): DeleteCategoryOutcome = withContext(Dispatchers.IO) {
        val categories = categoryDao.getAllCategories().first()
        val transactionsUsing = getAllTransactions().first().count { it.category == name }
        val rulesUsing = merchantRuleDao.getAllRulesList().count { it.assignedCategory == name }

        val outcome = CategoryEditor.canDelete(name, categories, transactionsUsing, rulesUsing)
        if (outcome is DeleteCategoryOutcome.Deleted) categoryDao.deleteCustomCategory(name)
        outcome
    }

    suspend fun addCustomParserRule(
        ruleName: String,
        senderPattern: String,
        regexPattern: String,
        amountGroup: Int,
        merchantGroup: Int
    ) = withContext(Dispatchers.IO) {
        parserRuleDao.insertRule(
            ParserRuleEntity(
                // Unique per rule, and marked as the user's so a rule-file update
                // can never overwrite or withdraw it.
                ruleId = "user." + System.currentTimeMillis(),
                ruleName = ruleName,
                senderPattern = senderPattern,
                regexPattern = regexPattern,
                amountGroup = amountGroup,
                // The form captures no direction group, so the direction is read from
                // the message body rather than assumed to be a debit.
                directionGroup = null,
                merchantGroup = merchantGroup,
                accountGroup = null,
                channelGroup = null,
                // Ahead of the bundled rules: F2.2 says a user override persists,
                // which it cannot do if a system rule matches first.
                priority = 0,
                isSystemRule = false
            )
        )
    }

    /**
     * Debug-only demo data (see BuildConfig.DEBUG gate on the calling button).
     *
     * Each sample carries an explicit age in days. The previous version spaced every
     * message 12 hours apart regardless of the dates in the text, which collapsed the
     * whole set into a few days — so the recurring detector's 20–38 day window could
     * never match and the headline feature demoed as "0 detected".
     *
     * The set deliberately exercises: a monthly subscription that rises in price
     * (SPOTIFY 149 -> 199), a second monthly subscription (NETFLIX), an outlier
     * (STARBUCKS), and a same-day double charge (BLINKIT).
     */
    suspend fun seedSampleTransactions() = withContext(Dispatchers.IO) {
        val hour = 3_600_000L
        val now = System.currentTimeMillis()

        // (hours ago, message)
        val samples = listOf(
            24L to "Rs 420.00 debited from A/C XX8901 to SWIGGY. Ref: UPI/261893. Bal Rs 45,210.",
            48L to "Paid Rs 1,450 to ZOMATO MEDIA via UPI. Txn ID: 90218390.",
            72L to "Rs. 2,499.00 spent on Card ending 4321 at AMAZON INDIA. Avail Limit: Rs 1,20,000.",
            96L to "Rs 3,200.00 spent on Card ending 4321 at SHELL PETROL PUMP.",

            // Same merchant, same amount, hours apart, different refs — a real
            // double charge rather than a re-delivered copy of one message (F3.4).
            120L to "Paid Rs 850.00 to BLINKIT GROCERY via UPI. Txn ID: 55510001.",
            123L to "Paid Rs 850.00 to BLINKIT GROCERY via UPI. Txn ID: 55510002.",

            144L to "Paid Rs 4,500.00 to STARBUCKS COFFEE.", // outlier for its category (F3.3)
            168L to "Rs 1,200.00 debited for BESCOM ELECTRICITY BILL.",
            192L to "Rs 5,000.00 withdrawn from ATM A/C XX8901.",
            216L to "INR 12,500.00 credited to A/C XX8901 via NEFT Salary.",

            // Must NOT count as spend (F1.2) — it matches the amount patterns exactly.
            30L to "Rs 2,750.00 spent on Card ending 4321 at MYNTRA was declined due to insufficient balance.",
            // Refund: a credit that is not income (F1.2 / F3.5).
            54L to "Rs 1,299.00 refund credited to A/C XX8901 from FLIPKART for order cancellation.",

            // Monthly subscriptions across three cycles — a real price hike (F3.2)
            48L to "Rs 199.00 debited from A/C XX8901 for SPOTIFY INDIA recurring subscription.",
            768L to "Rs 149.00 debited from A/C XX8901 for SPOTIFY INDIA recurring subscription.",
            1488L to "Rs 149.00 debited from A/C XX8901 for SPOTIFY INDIA recurring subscription.",
            144L to "Rs 649.00 debited from A/C XX8901 for NETFLIX RECURRING.",
            864L to "Rs 649.00 debited from A/C XX8901 for NETFLIX RECURRING.",
            1584L to "Rs 649.00 debited from A/C XX8901 for NETFLIX RECURRING."
        )

        // A realistic DLT header, so seeding also exercises the F1.1 allowlist path
        // rather than bypassing it.
        samples.forEach { (hoursAgo, text) ->
            processSingleSms("AD-HDFCBK-S", text, now - (hoursAgo * hour))
        }
    }

    companion object {
        /**
         * Stand-in when a caller re-parses a message it holds no sender for. Bundled
         * rules are all `.*`-scoped so it costs nothing there; a user-authored rule
         * with a real senderPattern would not match, which is why every caller that
         * has the sender passes it.
         */
        const val UNKNOWN_SENDER = "UNKNOWN"

        /** Rows the user typed in themselves — there is no bank message behind them. */
        const val MANUAL_SENDER = "MANUAL_ENTRY"

        /**
         * How far ahead a bill counts as "due soon".
         *
         * Long enough to be actionable on a monthly billing cycle, short enough that
         * the list is a to-do rather than an inventory. Anything already past its date
         * is included whatever this is set to.
         */
        const val DUE_SOON_HORIZON_DAYS = 21
    }
}
