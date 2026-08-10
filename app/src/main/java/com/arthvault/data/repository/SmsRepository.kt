package com.arthvault.data.repository

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import com.arthvault.data.analytics.AnomalyItem
import com.arthvault.data.analytics.CategorySlice
import com.arthvault.data.analytics.CategoryTrend
import com.arthvault.data.analytics.FinanceAnalyticsEngine
import com.arthvault.data.analytics.MonthEndForecast
import com.arthvault.data.analytics.RecurringItem
import com.arthvault.data.backup.BackupCodec
import com.arthvault.data.backup.BackupPayload
import com.arthvault.data.local.AppDatabase
import com.arthvault.data.local.DefaultSeedData
import com.arthvault.data.local.entity.AdjustmentEntity
import com.arthvault.data.local.entity.AdjustmentField
import com.arthvault.data.local.entity.AdjustmentSource
import com.arthvault.data.local.entity.AppSettingEntity
import com.arthvault.data.local.entity.CategoryEntity
import com.arthvault.data.local.entity.MerchantRuleEntity
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
    val recurring: List<RecurringItem> = emptyList(),
    val forecast: MonthEndForecast? = null,
    val anomalies: List<AnomalyItem> = emptyList(),
    val duplicates: List<TransactionEntity> = emptyList(),
    val categoryBreakdown: List<CategorySlice> = emptyList(),
    /** F3.6 — this month against last, largest movement first. */
    val categoryTrends: List<CategoryTrend> = emptyList()
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

    private val parserEngine = SmsParserEngine()
    private val analyticsEngine = FinanceAnalyticsEngine()

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
        } else if (parseResult.unparsedSms != null) {
            unparsedSmsDao.insertUnparsedSms(parseResult.unparsedSms)
        }
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
    suspend fun updateTransactionCategory(
        id: Long,
        newCategory: String,
        merchant: String,
        updateAllForMerchant: Boolean
    ) = withContext(Dispatchers.IO) {
        val effective = getAllTransactions().first()
        val now = System.currentTimeMillis()

        val targets = if (updateAllForMerchant) {
            effective.filter { it.merchant.contains(merchant, ignoreCase = true) }
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

        if (updateAllForMerchant) {
            merchantRuleDao.insertOrUpdateRule(
                MerchantRuleEntity(
                    merchantPattern = merchant,
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
                sender = "MANUAL_ENTRY",
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
    suspend fun computeAnalytics(): AnalyticsResult = withContext(Dispatchers.IO) {
        // The folded ledger, not the stored rows. Reading `transactionDao` directly —
        // as this used to — meant every correction and every voided transaction was
        // invisible to analytics: a transaction the user had removed still counted
        // towards the spend total, the donut and the forecast, while the ledger
        // screen right next to it showed it gone.
        val txns = getAllTransactions().first()
        val monthRange = analyticsEngine.currentMonthRange()

        return@withContext AnalyticsResult(
            recurring = analyticsEngine.detectRecurringAndPriceHikes(txns),
            forecast = analyticsEngine.computeMonthEndForecast(txns),
            anomalies = analyticsEngine.detectAnomalies(txns),
            duplicates = analyticsEngine.detectDuplicates(txns),
            categoryBreakdown = analyticsEngine.computeCategoryBreakdown(
                transactions = txns,
                rangeStart = monthRange.first,
                rangeEnd = monthRange.last
            ),
            // F3.6 — this month against last, largest movement first.
            categoryTrends = analyticsEngine.compareCategories(
                transactions = txns,
                periodA = analyticsEngine.previousMonthRange(),
                periodB = monthRange
            )
        )
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
                    senderAllowlist = senderAllowlistDao.getAll().first()
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
        PendingIngestMarker.clear(context)

        File(context.cacheDir, "exports").listFiles()?.forEach { it.delete() }

        // Restore the system baseline. Merchant rules are wiped wholesale above
        // because user overrides live in the same table; without this the app comes
        // back with no categorisation and everything lands in "Other / Misc".
        categoryDao.insertDefaultCategories(DefaultSeedData.categories)
        DefaultSeedData.merchantRules.forEach { merchantRuleDao.insertOrUpdateRule(it) }
    }

    suspend fun addCustomCategory(name: String, colorHex: String, iconName: String) = withContext(Dispatchers.IO) {
        categoryDao.insertCategory(
            CategoryEntity(
                name = name,
                iconName = iconName,
                colorHex = colorHex,
                isCustom = true
            )
        )
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
}
