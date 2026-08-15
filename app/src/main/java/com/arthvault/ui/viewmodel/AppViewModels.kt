package com.arthvault.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arthvault.data.analytics.BillObligation
import com.arthvault.data.analytics.PeriodScope
import com.arthvault.data.local.entity.AdjustmentEntity
import com.arthvault.data.local.AddCategoryOutcome
import com.arthvault.data.local.DeleteCategoryOutcome
import com.arthvault.data.local.entity.BillNoticeEntity
import com.arthvault.data.local.entity.CategoryEntity
import com.arthvault.data.local.entity.OwnAccountEntity
import com.arthvault.data.local.entity.ParserRuleEntity
import com.arthvault.data.local.entity.SenderAllowlistEntity
import com.arthvault.data.local.entity.TransactionEntity
import com.arthvault.data.local.entity.UnparsedSmsEntity
import com.arthvault.data.parser.rules.ParserRuleSeeder
import com.arthvault.data.parser.rules.RuleLoadResult
import com.arthvault.data.query.QueryResult
import com.arthvault.data.repository.AnalyticsResult
import com.arthvault.data.repository.BackupResult
import com.arthvault.data.repository.BillInsights
import com.arthvault.data.repository.ImportResult
import com.arthvault.data.repository.RestoreResult
import com.arthvault.data.repository.SmsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

// --- LEDGER VIEW MODEL ---
class LedgerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SmsRepository(application)

    val allTransactions: StateFlow<List<TransactionEntity>> = repository.getAllTransactions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unreviewedSms: StateFlow<List<UnparsedSmsEntity>> = repository.getUnreviewedSms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val categories: StateFlow<List<CategoryEntity>> = repository.getAllCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedCategory = MutableStateFlow("ALL")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    private val _selectedDirection = MutableStateFlow("ALL") // "ALL", "DEBIT", "CREDIT"
    val selectedDirection: StateFlow<String> = _selectedDirection.asStateFlow()

    val filteredTransactions: StateFlow<List<TransactionEntity>> = combine(
        allTransactions,
        _searchQuery,
        _selectedCategory,
        _selectedDirection
    ) { list, query, cat, dir ->
        list.filter { txn ->
            val matchesQuery = query.isEmpty() ||
                    txn.merchant.contains(query, ignoreCase = true) ||
                    txn.rawMessage.contains(query, ignoreCase = true) ||
                    txn.category.contains(query, ignoreCase = true) ||
                    txn.amount.toString().contains(query)

            val matchesCat = cat == "ALL" || txn.category.equals(cat, ignoreCase = true)
            val matchesDir = dir == "ALL" || txn.direction.equals(dir, ignoreCase = true)

            matchesQuery && matchesCat && matchesDir
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchQuery(query: String) { _searchQuery.value = query }
    fun setSelectedCategory(category: String) { _selectedCategory.value = category }
    fun setSelectedDirection(direction: String) { _selectedDirection.value = direction }

    fun updateCategory(id: Long, newCategory: String, merchant: String, updateAllForMerchant: Boolean) {
        viewModelScope.launch {
            repository.updateTransactionCategory(id, newCategory, merchant, updateAllForMerchant)
        }
    }

    fun previewBulkRecategorization(
        merchant: String,
        newCategory: String,
        rawMessage: String? = null,
        sender: String = com.arthvault.data.repository.SmsRepository.UNKNOWN_SENDER,
        onResult: (com.arthvault.data.repository.BulkRecategorizePreview) -> Unit
    ) {
        viewModelScope.launch {
            val preview =
                repository.previewBulkRecategorization(merchant, newCategory, rawMessage, sender)
            onResult(preview)
        }
    }

    fun updateSelectedTransactionCategories(
        selectedTransactionIds: Set<Long>,
        newCategory: String,
        merchantPattern: String,
        saveGlobalRule: Boolean
    ) {
        viewModelScope.launch {
            repository.updateSelectedTransactionCategories(
                selectedTransactionIds,
                newCategory,
                merchantPattern,
                saveGlobalRule
            )
        }
    }

    /**
     * Creates one of the user's own categories.
     *
     * These live on the Ledger ViewModel rather than the Vault one because both screens
     * that offer the action already hold it, and because [categories] — the list the new
     * one has to appear in — is here. `VaultViewModel` had an `addCustomCategory` that
     * nothing ever called.
     *
     * The outcome comes back through a callback rather than a StateFlow: the caller
     * shows it as a snackbar and there is nothing to retain afterwards.
     */
    fun addCategory(name: String, onResult: (AddCategoryOutcome) -> Unit) {
        viewModelScope.launch { onResult(repository.addCustomCategory(name)) }
    }

    fun deleteCategory(name: String, onResult: (DeleteCategoryOutcome) -> Unit) {
        viewModelScope.launch { onResult(repository.deleteCustomCategory(name)) }
    }

    fun addManualTransaction(amount: Double, direction: String, merchant: String, category: String, channel: String, note: String) {
        viewModelScope.launch {
            repository.addManualTransaction(amount, direction, merchant, category, channel, note)
        }
    }

    /**
     * Manual "scan now" — re-reads the whole inbox, not just past the watermark, and
     * then re-parses what is already stored.
     *
     * Both halves are needed and neither substitutes for the other. Ingestion
     * de-duplicates on a hash of sender, body and minute, which is independent of the
     * parser, so a rescan alone finds every known message already present and leaves
     * rows parsed by an older ruleset exactly as they were.
     */
    fun scanInbox(
        onResult: ((
            com.arthvault.data.repository.ScanResult,
            com.arthvault.data.repository.ReparseResult
        ) -> Unit)? = null
    ) {
        viewModelScope.launch {
            val result = repository.rescanEntireInbox()
            val reparse = repository.reparseStoredTransactions()
            onResult?.invoke(result, reparse)
        }
    }

    fun seedSampleData() {
        viewModelScope.launch {
            repository.seedSampleTransactions()
        }
    }

    fun importRawSmsBatch(smsList: List<String>) {
        viewModelScope.launch {
            repository.processBatchRawText(smsList)
        }
    }

    /** T3.3 — removes it from the ledger by recording the removal, not by deleting. */
    fun voidTransaction(transaction: TransactionEntity) {
        viewModelScope.launch {
            repository.voidTransaction(transaction)
        }
    }

    /** Transaction ids the user has corrected, for the "edited" marker. */
    val adjustments: StateFlow<Map<Long, List<AdjustmentEntity>>> =
        repository.getAdjustmentsByTransaction()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun markUnparsedReviewed(id: Long) {
        viewModelScope.launch {
            repository.markUnparsedReviewed(id)
        }
    }

    // --- F1.1 sender allowlist ---

    val allowedSenders: StateFlow<List<SenderAllowlistEntity>> = repository.getSenderAllowlist()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addAllowedSender(senderId: String, label: String) {
        viewModelScope.launch { repository.addAllowedSender(senderId, label) }
    }

    fun removeAllowedSender(senderId: String) {
        viewModelScope.launch { repository.removeAllowedSender(senderId) }
    }

    // --- own accounts (v5) ---

    val ownAccounts: StateFlow<List<OwnAccountEntity>> = repository.getOwnAccounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Account tails seen in the ledger, so the user picks from what exists rather
     * than typing digits from memory and mistyping one.
     */
    val observedAccountTails: StateFlow<List<String>> = repository.getObservedAccountTails()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun markAccountAsOwn(tail: String, label: String) {
        viewModelScope.launch { repository.markAccountAsOwn(tail, label) }
    }

    fun unmarkAccount(tail: String) {
        viewModelScope.launch { repository.unmarkAccount(tail) }
    }

    // --- F1.5 CSV import ---

    private val _importResult = MutableStateFlow<ImportResult?>(null)
    val importResult: StateFlow<ImportResult?> = _importResult.asStateFlow()

    fun importCsv(uri: Uri) {
        viewModelScope.launch { _importResult.value = repository.importCsv(uri) }
    }

    fun clearImportResult() { _importResult.value = null }
}

// --- ANALYTICS VIEW MODEL ---
class AnalyticsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SmsRepository(application)

    private val _analytics = MutableStateFlow(AnalyticsResult())
    val analytics: StateFlow<AnalyticsResult> = _analytics.asStateFlow()

    /**
     * The window the screen is scoped to.
     *
     * Everything used to be pinned to the current calendar month, so there was no way
     * to ask what last month came to, or how this week compares to the last one.
     */
    private val _scope = MutableStateFlow(PeriodScope.THIS_MONTH)
    val scope: StateFlow<PeriodScope> = _scope.asStateFlow()

    /** True while a recomputation is in flight, so the screen can avoid flashing zeros. */
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // No `init { refreshAnalytics() }`. This ViewModel is constructed the moment the
    // vault unlocks, whichever screen the user is on, so computing here ran six passes
    // over the whole ledger before anyone had asked for a figure — and then AnalyticsScreen
    // ran them again on its first composition. The screen owns the trigger now.

    fun setScope(scope: PeriodScope) {
        if (_scope.value == scope) return
        _scope.value = scope
        refreshAnalytics()
    }

    fun refreshAnalytics() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _analytics.value = repository.computeAnalytics(_scope.value)
            } finally {
                _isLoading.value = false
            }
        }
    }

    // --- F4.1 deterministic query ---

    /**
     * The answer to the last question, or [QueryAnswer.NotUnderstood].
     *
     * Distinguishing "I could not read that" from "the answer is zero" matters:
     * they look identical if both render as ₹0, and one of them is the app being
     * wrong rather than the ledger being empty.
     */
    private val _queryAnswer = MutableStateFlow<QueryAnswer?>(null)
    val queryAnswer: StateFlow<QueryAnswer?> = _queryAnswer.asStateFlow()

    sealed interface QueryAnswer {
        data class Answered(val result: QueryResult) : QueryAnswer
        data class NotUnderstood(val question: String) : QueryAnswer
    }

    fun ask(question: String) {
        viewModelScope.launch {
            val result = repository.answerQuestion(question)
            _queryAnswer.value = result
                ?.let { QueryAnswer.Answered(it) }
                ?: QueryAnswer.NotUnderstood(question)
        }
    }

    fun clearQueryAnswer() {
        _queryAnswer.value = null
    }

    // --- F4.4 tap-through ---

    private val _tappedThrough = MutableStateFlow<List<TransactionEntity>>(emptyList())
    val tappedThrough: StateFlow<List<TransactionEntity>> = _tappedThrough.asStateFlow()

    /** Opens the rows behind any insight — a slice, a recurring item, an answer. */
    fun showSourceTransactions(ids: List<Long>) {
        viewModelScope.launch {
            _tappedThrough.value = repository.getTransactionsByIds(ids)
        }
    }

    fun clearSourceTransactions() {
        _tappedThrough.value = emptyList()
    }
}

/**
 * Phase 9 — bills that are owed, kept apart from money that moved.
 *
 * A separate ViewModel rather than more state on [AnalyticsViewModel], mirroring the
 * separation in the repository: the two screens answer different questions and nothing
 * should make it easy to add an obligation total to a spending total.
 */
class BillsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SmsRepository(application)

    private val _insights = MutableStateFlow(BillInsights())
    val insights: StateFlow<BillInsights> = _insights.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // No init-block refresh, for the reason AnalyticsViewModel records: this is
    // constructed the moment the vault unlocks, whichever screen the user is on.

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _insights.value = repository.computeBillInsights()
            } finally {
                _isLoading.value = false
            }
        }
    }

    // --- F4.4 tap-through ---
    //
    // A bill row opens two different things: the reminders the biller sent, and
    // whatever payment was matched against them. Keeping them in separate state means
    // the sheet can show both and say which is which, rather than merging an SMS the
    // bank sent with a transaction the app inferred.

    private val _tappedNotices = MutableStateFlow<List<BillNoticeEntity>>(emptyList())
    val tappedNotices: StateFlow<List<BillNoticeEntity>> = _tappedNotices.asStateFlow()

    private val _tappedTransactions = MutableStateFlow<List<TransactionEntity>>(emptyList())
    val tappedTransactions: StateFlow<List<TransactionEntity>> = _tappedTransactions.asStateFlow()

    fun showSources(obligation: BillObligation) {
        viewModelScope.launch {
            _tappedNotices.value = repository.getBillNoticesByIds(obligation.noticeIds)
            _tappedTransactions.value =
                repository.getTransactionsByIds(obligation.matchedTransactionIds)
        }
    }

    fun showSourceTransactions(ids: List<Long>) {
        viewModelScope.launch {
            _tappedNotices.value = emptyList()
            _tappedTransactions.value = repository.getTransactionsByIds(ids)
        }
    }

    fun clearSources() {
        _tappedNotices.value = emptyList()
        _tappedTransactions.value = emptyList()
    }
}

// --- VAULT & PRIVACY VIEW MODEL ---
class VaultViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SmsRepository(application)

    val activeParserRules: StateFlow<List<ParserRuleEntity>> = repository.getAllParserRules()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- T2.2 / 5.3 sideloaded parser rules ---

    /**
     * What a picked rule file turned out to be, before anything is installed.
     *
     * The user sees the version, the rule count and the signature verdict and then
     * decides. A file is never applied straight from the picker: "I chose this file"
     * and "I accept these rules" are different statements, and the second one is the
     * one that changes how the ledger reads money.
     */
    private val _pendingRuleFile = MutableStateFlow<PendingRuleFile?>(null)
    val pendingRuleFile: StateFlow<PendingRuleFile?> = _pendingRuleFile.asStateFlow()

    private val _ruleFileOutcome = MutableStateFlow<ParserRuleSeeder.Outcome?>(null)
    val ruleFileOutcome: StateFlow<ParserRuleSeeder.Outcome?> = _ruleFileOutcome.asStateFlow()

    data class PendingRuleFile(val uri: Uri, val inspection: RuleLoadResult)

    fun inspectRuleFile(uri: Uri) {
        viewModelScope.launch {
            _pendingRuleFile.value = PendingRuleFile(uri, repository.inspectParserRuleFile(uri))
        }
    }

    fun confirmRuleFile() {
        val pending = _pendingRuleFile.value ?: return
        viewModelScope.launch {
            _ruleFileOutcome.value = repository.applyParserRuleFile(pending.uri)
            _pendingRuleFile.value = null
        }
    }

    fun dismissRuleFile() {
        _pendingRuleFile.value = null
    }

    fun clearRuleFileOutcome() {
        _ruleFileOutcome.value = null
    }

    private val _exportedFile = MutableStateFlow<File?>(null)
    val exportedFile: StateFlow<File?> = _exportedFile.asStateFlow()

    private val _exportedJson = MutableStateFlow<String?>(null)
    val exportedJson: StateFlow<String?> = _exportedJson.asStateFlow()

    fun exportCsv() {
        viewModelScope.launch {
            _exportedFile.value = repository.exportDataAsCsv()
        }
    }

    fun exportJson() {
        viewModelScope.launch {
            _exportedJson.value = repository.exportDataAsJson()
        }
    }

    fun clearExport() {
        _exportedFile.value = null
        _exportedJson.value = null
    }

    fun fullWipe() {
        viewModelScope.launch {
            repository.fullLocalWipe()
        }
    }

    // --- F5.3 encrypted backup ---

    private val _backupResult = MutableStateFlow<BackupResult?>(null)
    val backupResult: StateFlow<BackupResult?> = _backupResult.asStateFlow()

    private val _restoreResult = MutableStateFlow<RestoreResult?>(null)
    val restoreResult: StateFlow<RestoreResult?> = _restoreResult.asStateFlow()

    fun writeBackup(uri: Uri, passphrase: String) {
        viewModelScope.launch {
            _backupResult.value = repository.writeEncryptedBackup(uri, passphrase.toCharArray())
        }
    }

    fun restoreBackup(uri: Uri, passphrase: String) {
        viewModelScope.launch {
            _restoreResult.value = repository.restoreEncryptedBackup(uri, passphrase.toCharArray())
        }
    }

    fun clearBackupResults() {
        _backupResult.value = null
        _restoreResult.value = null
    }

    fun addCustomParserRule(ruleName: String, senderPattern: String, regexPattern: String, amountGroup: Int, merchantGroup: Int) {
        viewModelScope.launch {
            repository.addCustomParserRule(ruleName, senderPattern, regexPattern, amountGroup, merchantGroup)
        }
    }

}
