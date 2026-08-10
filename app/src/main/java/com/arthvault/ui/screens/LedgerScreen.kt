package com.arthvault.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
// Filled is reserved for two things: a selected navigation destination, and an icon
// sitting inside a filled container. The FAB's plus is the second case — it is drawn
// on `primary`, where an outlined glyph reads as unfinished. Everything else on this
// screen is informational and outlined.
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arthvault.BuildConfig
import com.arthvault.data.local.AddCategoryOutcome
import com.arthvault.data.local.CategoryEditor
import com.arthvault.data.local.entity.STATUS_POSTED
import com.arthvault.data.local.entity.TransactionEntity
import com.arthvault.data.local.entity.UnparsedSmsEntity
import com.arthvault.ui.components.BarAction
import com.arthvault.ui.components.EmptyState
import com.arthvault.ui.components.LocalSnackbar
import com.arthvault.ui.components.MerchantAvatar
import com.arthvault.ui.components.VaultRowCard
import com.arthvault.ui.components.VaultScaffold
import com.arthvault.ui.format.dayOf
import com.arthvault.ui.format.formatDayHeader
import com.arthvault.ui.format.formatDirectedMoney
import com.arthvault.ui.format.formatFullTimestamp
import com.arthvault.ui.format.formatMoney
import com.arthvault.ui.format.formatMoneyPrecise
import com.arthvault.ui.format.formatTimeOfDay
import com.arthvault.ui.theme.Spacing
import com.arthvault.ui.theme.VaultTheme
import com.arthvault.ui.theme.moneyMedium
import com.arthvault.ui.theme.numeric
import com.arthvault.ui.theme.payload
import com.arthvault.ui.viewmodel.LedgerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerScreen(
    viewModel: LedgerViewModel
) {
    val transactions by viewModel.filteredTransactions.collectAsStateWithLifecycle()
    val unreviewedSms by viewModel.unreviewedSms.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val selectedDirection by viewModel.selectedDirection.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }
    var showUnparsedSheet by remember { mutableStateOf(false) }
    var selectedTxnForDetail by remember { mutableStateOf<TransactionEntity?>(null) }
    var showCategoryDialogForTxn by remember { mutableStateOf<TransactionEntity?>(null) }
    // Voiding a transaction is a ledger correction, not a UI tidy-up. It used to
    // fire straight from a 30dp icon sitting next to another 30dp icon.
    var pendingVoid by remember { mutableStateOf<TransactionEntity?>(null) }

    // Hoisted out of both slots below, because the app bar's action and the
    // pull-to-refresh gesture start the same scan and have to show the same state.
    var scanning by remember { mutableStateOf(false) }

    VaultScaffold(
        title = "Ledger",
        actions = {
            ScanInboxAction(viewModel = viewModel, onScanningChange = { scanning = it })
            if (BuildConfig.DEBUG) {
                val snackbar = LocalSnackbar.current
                BarAction(
                    label = "Seed",
                    icon = Icons.Outlined.AutoAwesome,
                    onClick = {
                        viewModel.seedSampleData()
                        snackbar.show("Loaded the sample SMS ledger")
                    }
                )
            }
        },
        floatingActionButton = {
            // Extended, not a bare "+". The catalog's rule for a FAB is that it takes
            // a label whenever the action is not self-evident from the icon, and a
            // plus on a screen full of bank-imported rows does not say "record a cash
            // payment the bank never saw".
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("add_manual_txn_fab"),
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add cash") }
            )
        }
    ) { padding ->
        // Rescanning the inbox is the one thing that refills this list, and pulling
        // a feed down to refresh it is the gesture people try first. It stays in the
        // app bar too — the gesture is undiscoverable on its own.
        val startScan = rememberInboxScan(viewModel) { scanning = it }

        PullToRefreshBox(
            isRefreshing = scanning,
            onRefresh = startScan,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TransactionFeed(
                transactions = transactions,
                categories = categories.map { it.name },
                searchQuery = searchQuery,
                selectedCategory = selectedCategory,
                selectedDirection = selectedDirection,
                unreviewedCount = unreviewedSms.size,
                onSearch = viewModel::setSearchQuery,
                onSelectCategory = viewModel::setSelectedCategory,
                onSelectDirection = viewModel::setSelectedDirection,
                onOpenUnparsed = { showUnparsedSheet = true },
                onAddManual = { showAddDialog = true },
                onOpen = { selectedTxnForDetail = it }
            )
        }
    }

    // The review queue used to be the second half of a tab row, which spent 72dp of
    // every viewport advertising a queue that is empty most of the time. It is a work
    // queue rather than a second view of the ledger, so it opens as a sheet from the
    // banner that announces it (T2.3 — nothing here may be silently dropped, and a
    // tab nobody looks at is most of the way to silent).
    if (showUnparsedSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showUnparsedSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            UnparsedFeed(
                items = unreviewedSms,
                onDismiss = viewModel::markUnparsedReviewed
            )
        }
    }

    selectedTxnForDetail?.let { txn ->
        TransactionDetailDialog(
            txn = txn,
            onDismiss = { selectedTxnForDetail = null },
            onChangeCategory = {
                selectedTxnForDetail = null
                showCategoryDialogForTxn = txn
            },
            onVoid = {
                selectedTxnForDetail = null
                pendingVoid = txn
            }
        )
    }

    showCategoryDialogForTxn?.let { txn ->
        RecategorizeDialog(
            transaction = txn,
            categories = categories.map { it.name },
            onDismiss = { showCategoryDialogForTxn = null },
            onPick = { category, applyToAll ->
                viewModel.updateCategory(txn.id, category, txn.merchant, applyToAll)
                showCategoryDialogForTxn = null
            },
            onCreate = { name, applyToAll ->
                // Create then apply, in that order, and only apply if the create
                // actually happened — the dialog's validation is live feedback, but
                // the repository is what decides.
                viewModel.addCategory(name) { outcome ->
                    if (outcome is AddCategoryOutcome.Added) {
                        viewModel.updateCategory(txn.id, outcome.name, txn.merchant, applyToAll)
                    }
                }
                showCategoryDialogForTxn = null
            }
        )
    }

    // A destructive action gets a confirmation and the `error` colour. It had
    // neither, and its trigger was an 30dp icon adjacent to the edit icon.
    pendingVoid?.let { txn ->
        val haptics = LocalHapticFeedback.current
        AlertDialog(
            onDismissRequest = { pendingVoid = null },
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text("Void this transaction?") },
            text = {
                Text(
                    "${txn.merchant} — ${formatMoneyPrecise(txn.amount)}. It stops counting " +
                        "towards your totals. The record itself is kept."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // A ledger correction should be felt as well as seen. This is
                        // the one action on the screen that changes what the totals say.
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.voidTransaction(txn)
                        pendingVoid = null
                    }
                ) {
                    Text("Void", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingVoid = null }) { Text("Keep") }
            }
        )
    }

    if (showAddDialog) {
        AddTransactionDialog(
            defaultCategory = categories.firstOrNull()?.name ?: "Other / Misc",
            onDismiss = { showAddDialog = false },
            onSave = { amount, direction, merchant, category, channel, note ->
                viewModel.addManualTransaction(amount, direction, merchant, category, channel, note)
                showAddDialog = false
            }
        )
    }
}

/**
 * Starting an inbox scan: the permission check, the scan, and the report of what it
 * found.
 *
 * Returned as a lambda rather than rendered as a button, because two different
 * affordances now start the same scan — the app bar action and pulling the feed down —
 * and they have to agree about whether one is already running.
 *
 * Must be called somewhere beneath a [VaultScaffold]: it reads [LocalSnackbar], which
 * outside that provider silently drops every message it is given.
 *
 * @param onScanningChange fires true when a scan actually begins and false when it
 *   ends. It does *not* fire for a scan that stops at the permission prompt, so a
 *   refresh indicator raised by a pull retracts instead of spinning behind a dialog.
 */
@Composable
private fun rememberInboxScan(
    viewModel: LedgerViewModel,
    onScanningChange: (Boolean) -> Unit = {},
): () -> Unit {
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current

    val runScan = {
        onScanningChange(true)
        viewModel.scanInbox { res ->
            onScanningChange(false)
            snackbar.show(
                when {
                    res.newTransactionsCount > 0 ->
                        "Imported ${res.newTransactionsCount} new transactions from ${res.totalScanned} messages"
                    res.totalScanned > 0 ->
                        "Scanned ${res.totalScanned} messages — everything already up to date"
                    else -> "No messages found in your inbox"
                }
            )
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            runScan()
        } else {
            snackbar.show(
                message = "Arth Vault needs SMS access to read your bank messages",
                actionLabel = "Why?",
                onAction = {
                    snackbar.show("Messages are parsed on this device and never leave it.")
                }
            )
        }
    }

    return {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) runScan() else permissionLauncher.launch(Manifest.permission.READ_SMS)
    }
}

/**
 * Scanning the inbox, in the app bar.
 *
 * It used to be a filled `Button` inside the header card, sitting beside a second
 * filled button and underneath a FAB — three controls all presenting as the primary
 * action on one screen. Adding a transaction is the primary action; scanning is a
 * periodic maintenance task and belongs in the bar.
 */
@Composable
internal fun ScanInboxAction(
    viewModel: LedgerViewModel,
    onScanningChange: (Boolean) -> Unit = {},
) {
    val startScan = rememberInboxScan(viewModel, onScanningChange)
    BarAction(
        label = "Scan SMS",
        icon = Icons.Outlined.QrCodeScanner,
        onClick = startScan
    )
}

/**
 * The feed, and everything that filters it.
 *
 * The chrome above the first transaction used to run to roughly 260dp that never
 * scrolled: a three-figure summary band, a two-tab row, a search field and a chip row,
 * all pinned in a `Column` outside the list. Only the chip row is pinned now — it is
 * 40dp and it is the fastest control on the screen. The search field is the first item
 * *inside* the list, so it is there when you scroll to the top and gone when you are
 * reading.
 *
 * The empty state is an item rather than a replacement for the list, which is what
 * keeps the search field on screen when a query matches nothing. Rendering it instead
 * of the list would remove the only control that can undo the filter that emptied it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransactionFeed(
    transactions: List<TransactionEntity>,
    categories: List<String>,
    searchQuery: String,
    selectedCategory: String,
    selectedDirection: String,
    unreviewedCount: Int,
    onSearch: (String) -> Unit,
    onSelectCategory: (String) -> Unit,
    onSelectDirection: (String) -> Unit,
    onOpenUnparsed: () -> Unit,
    onAddManual: () -> Unit,
    onOpen: (TransactionEntity) -> Unit
) {
    // Grouped once per list change, not once per scroll frame. Sorted rather than
    // trusting the query's ORDER BY, so the headers stay in order even if the feed is
    // ever fed from somewhere else.
    val days = remember(transactions) {
        transactions.groupBy { dayOf(it.timestamp) }
            .entries
            .sortedByDescending { it.key }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // One filter row, not two. Direction lives at the head of the same scroller
        // as the categories instead of in a second row below it.
        LazyRow(
            modifier = Modifier.padding(horizontal = Spacing.standard),
            horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item {
                FilterChip(
                    selected = selectedDirection == "DEBIT",
                    onClick = {
                        onSelectDirection(if (selectedDirection == "DEBIT") "ALL" else "DEBIT")
                    },
                    label = { Text("Spend") },
                    leadingIcon = { Icon(Icons.Outlined.ArrowUpward, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    shape = MaterialTheme.shapes.small
                )
            }
            item {
                FilterChip(
                    selected = selectedDirection == "CREDIT",
                    onClick = {
                        onSelectDirection(if (selectedDirection == "CREDIT") "ALL" else "CREDIT")
                    },
                    label = { Text("Income") },
                    leadingIcon = { Icon(Icons.Outlined.ArrowDownward, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    shape = MaterialTheme.shapes.small
                )
            }
            item {
                FilterChip(
                    selected = selectedCategory == "ALL",
                    onClick = { onSelectCategory("ALL") },
                    label = { Text("All categories") },
                    shape = MaterialTheme.shapes.small
                )
            }
            items(categories) { cat ->
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = { onSelectCategory(cat) },
                    label = { Text(cat) },
                    shape = MaterialTheme.shapes.small
                )
            }
        }

        Spacer(Modifier.height(Spacing.tight))

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.standard)
        ) {
            item(key = "search") {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearch,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ledger_search_bar"),
                    label = { Text("Search") },
                    placeholder = { Text("Merchant, amount, or raw message text") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small
                )
                Spacer(Modifier.height(Spacing.snug))
            }

            if (unreviewedCount > 0) {
                item(key = "unparsed-banner") {
                    UnparsedBanner(count = unreviewedCount, onClick = onOpenUnparsed)
                    Spacer(Modifier.height(Spacing.snug))
                }
            }

            if (transactions.isEmpty()) {
                item(key = "empty") {
                    val filtered = searchQuery.isNotBlank() ||
                        selectedCategory != "ALL" ||
                        selectedDirection != "ALL"
                    EmptyState(
                        icon = Icons.Outlined.ReceiptLong,
                        title = if (filtered) "Nothing matches those filters" else "No transactions yet",
                        message = if (filtered) {
                            "Clear the search or filters above to see the whole ledger."
                        } else {
                            "Scan your SMS inbox from the top bar, or record a cash payment yourself."
                        },
                        actionLabel = if (filtered) null else "Add a transaction",
                        onAction = if (filtered) null else onAddManual
                    )
                }
            } else {
                days.forEach { (day, rows) ->
                    stickyHeader(key = "day-$day") {
                        DayHeader(timestamp = rows.first().timestamp)
                    }
                    items(rows, key = { it.id }) { txn ->
                        TransactionCard(
                            transaction = txn,
                            modifier = Modifier
                                .animateItem()
                                .padding(bottom = Spacing.tight),
                            onClick = { onOpen(txn) }
                        )
                    }
                }
            }

            // Clearance for the extended FAB, which floats over the tail of the list.
            item(key = "tail") { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}

/**
 * `Today` / `Yesterday` / `8 August`, pinned while its own day is on screen.
 *
 * The feed printed the full date and time on every row, so orienting in time meant
 * reading the same date down forty rows. With a header the rows carry only the clock,
 * and skimming a month works the way skimming a statement does.
 *
 * Opaque, and the full width of the list: a sticky header drawn on a transparent
 * background has the rows it is meant to be covering sliding through it.
 */
@Composable
private fun DayHeader(timestamp: Long) {
    Text(
        text = formatDayHeader(timestamp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = Spacing.tight, bottom = Spacing.hairline)
            .semantics { heading() }
    )
}

/**
 * The review queue, announced where the user is already looking.
 *
 * This was the second tab of a two-tab row, which cost 72dp of every viewport to
 * advertise a queue that is empty most of the time — and buried it two taps deep the
 * rest of the time. A banner costs nothing when there is nothing to review and is
 * impossible to miss when there is, which is the behaviour T2.3 actually wants.
 */
@Composable
private fun UnparsedBanner(count: Int, onClick: () -> Unit) {
    val caution = VaultTheme.semantics.caution
    VaultRowCard(
        modifier = Modifier.clickable(onClickLabel = "Review unparsed messages") { onClick() },
        accent = caution
    ) {
        Row(
            modifier = Modifier.heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.MarkEmailUnread,
                contentDescription = null,
                tint = caution,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(Spacing.snug))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (count == 1) {
                        "1 message could not be read"
                    } else {
                        "$count messages could not be read"
                    },
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "They are kept as-is until you look at them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "Review",
                style = MaterialTheme.typography.labelLarge,
                color = caution
            )
        }
    }
}

@Composable
private fun UnparsedFeed(
    items: List<UnparsedSmsEntity>,
    onDismiss: (Long) -> Unit
) {
    if (items.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.Security,
            title = "Review queue is clear",
            message = "Every financial message from your allowed senders parsed cleanly.",
            iconTint = VaultTheme.semantics.positive
        )
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.standard),
            verticalArrangement = Arrangement.spacedBy(Spacing.snug)
        ) {
            item(key = "heading") {
                Text(
                    text = "Messages we could not read",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() }
                )
            }
            items(items, key = { it.id }) { item ->
                UnparsedSmsCard(
                    item = item,
                    modifier = Modifier.animateItem(),
                    onDismiss = { onDismiss(item.id) }
                )
            }
            item { Spacer(modifier = Modifier.height(Spacing.section)) }
        }
    }
}

/**
 * One row of the feed: who, what kind, when, how much.
 *
 * The two icon buttons that used to sit under the amount are gone. They were 96dp of
 * every row spent on actions taken maybe twice a session, sitting exactly where the
 * eye lands to read amounts — and one of them was destructive, repeated down a
 * scrolling list. Both now live in the detail dialog that a tap already opened.
 *
 * The row also no longer repeats the date. It is under a day header (see [DayHeader]),
 * so the clock is the only part still doing work.
 */
@Composable
fun TransactionCard(
    transaction: TransactionEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isCredit = transaction.direction == "CREDIT"
    val semantics = VaultTheme.semantics
    val tint = semantics.forDirection(isCredit)

    VaultRowCard(
        modifier = modifier.clickable(onClickLabel = "Open transaction details") { onClick() }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MerchantAvatar(
                merchant = transaction.merchant,
                category = transaction.category
            )

            Spacer(modifier = Modifier.width(Spacing.snug))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = transaction.merchant,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(Spacing.hairline))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = transaction.category,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "  ·  ${formatTimeOfDay(transaction.timestamp)}",
                        style = MaterialTheme.typography.numeric,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // A declined attempt is kept in the record but must never read as
                    // money that moved. This is the one pill worth keeping on a row:
                    // it changes what the amount means.
                    if (transaction.status != STATUS_POSTED) {
                        Spacer(modifier = Modifier.width(Spacing.tight))
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = semantics.negative.copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = "DECLINED",
                                modifier = Modifier.padding(horizontal = Spacing.tight, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = semantics.negative
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(Spacing.tight))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatDirectedMoney(transaction.amount, isCredit),
                    style = MaterialTheme.typography.moneyMedium,
                    color = tint
                )
                transaction.balanceAfter?.let { balance ->
                    Text(
                        text = "Bal ${formatMoney(balance)}",
                        style = MaterialTheme.typography.numeric,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun UnparsedSmsCard(
    item: UnparsedSmsEntity,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    VaultRowCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item.sender,
                style = MaterialTheme.typography.titleSmall
            )
            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = VaultTheme.semantics.caution.copy(alpha = 0.12f)
            ) {
                Text(
                    text = item.failureReason,
                    modifier = Modifier.padding(horizontal = Spacing.tight, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultTheme.semantics.caution
                )
            }
        }
        Spacer(modifier = Modifier.height(Spacing.tight))
        Surface(
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = item.rawMessage,
                modifier = Modifier.padding(Spacing.snug),
                style = MaterialTheme.typography.payload
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Mark reviewed") }
        }
    }
}

// --- dialogs ---------------------------------------------------------------

/**
 * Everything the app knows about one transaction, and the two things it can do to it.
 *
 * Recategorise and void arrived here from the feed rows, where they were repeated on
 * every card. This is where they belong: the dialog already opens on a tap, it names
 * the transaction it is acting on, and a destructive action reached deliberately is a
 * different risk from one sitting under a scrolling thumb.
 */
@Composable
private fun TransactionDetailDialog(
    txn: TransactionEntity,
    onDismiss: () -> Unit,
    onChangeCategory: () -> Unit,
    onVoid: () -> Unit
) {
    val isCredit = txn.direction == "CREDIT"
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(txn.merchant) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.tight)) {
                Text(
                    text = formatDirectedMoney(txn.amount, isCredit),
                    style = MaterialTheme.typography.moneyMedium,
                    color = VaultTheme.semantics.forDirection(isCredit)
                )
                DetailLine("Category", txn.category)
                DetailLine("Channel", txn.channel ?: "Unknown")
                DetailLine("Sender", txn.sender)
                DetailLine("Date", formatFullTimestamp(txn.timestamp))
                Spacer(Modifier.height(Spacing.hairline))
                Text(
                    "RAW ON-DEVICE SMS SIGNAL",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Text(
                        text = txn.rawMessage,
                        modifier = Modifier.padding(Spacing.snug),
                        style = MaterialTheme.typography.payload
                    )
                }
                Spacer(Modifier.height(Spacing.hairline))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.tight)) {
                    TextButton(onClick = onChangeCategory) { Text("Recategorize") }
                    TextButton(onClick = onVoid) {
                        Text("Void", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp)
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Picking a category for one transaction — or inventing one.
 *
 * "None of these fit" is the moment a person actually wants a new category, and until
 * now the only answer was to leave the ledger, find the Rules screen and come back.
 * Creating one here applies it to the transaction that prompted it in the same step,
 * which is the only reason the user opened this dialog.
 *
 * The name is validated as it is typed, against the same [CategoryEditor] the
 * repository uses, so the reason a name is refused appears under the field rather than
 * after the dialog has closed. The repository re-checks regardless — this is feedback,
 * not the guard.
 */
@Composable
private fun RecategorizeDialog(
    transaction: TransactionEntity,
    categories: List<String>,
    onDismiss: () -> Unit,
    onPick: (String, Boolean) -> Unit,
    onCreate: (String, Boolean) -> Unit
) {
    var applyToAll by remember { mutableStateOf(true) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    val validation = remember(newName, categories) {
        if (newName.isBlank()) null else CategoryEditor.validateNew(newName, categories)
    }
    val problem = when (validation) {
        is AddCategoryOutcome.AlreadyExists -> "\"${validation.existing}\" already exists"
        is AddCategoryOutcome.TooLong -> "Keep it under ${validation.limit} characters"
        else -> null
    }
    val newNameValid = validation is AddCategoryOutcome.Added

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = {
            Text(if (creating) "New category" else "Recategorize ${transaction.merchant}")
        },
        text = {
            Column {
                if (creating) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Category name") },
                        placeholder = { Text("Pet Care") },
                        singleLine = true,
                        isError = problem != null,
                        supportingText = problem?.let { { Text(it) } },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("new_category_field")
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                        items(categories) { cat ->
                            Text(
                                text = cat,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.extraSmall)
                                    .clickable { onPick(cat, applyToAll) }
                                    .heightIn(min = 48.dp)
                                    .padding(vertical = Spacing.snug)
                            )
                        }
                        item {
                            TextButton(
                                onClick = { creating = true },
                                modifier = Modifier.testTag("new_category_button")
                            ) {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(Spacing.hairline))
                                Text("New category")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.tight))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.extraSmall)
                        .clickable { applyToAll = !applyToAll }
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = applyToAll,
                        onCheckedChange = { applyToAll = it }
                    )
                    Text(
                        "Apply to every ${transaction.merchant} transaction",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            if (creating) {
                Button(
                    onClick = { onCreate(newName, applyToAll) },
                    enabled = newNameValid
                ) { Text("Create and apply") }
            }
        },
        dismissButton = {
            TextButton(
                onClick = { if (creating) creating = false else onDismiss() }
            ) { Text(if (creating) "Back" else "Cancel") }
        }
    )
}

@Composable
private fun AddTransactionDialog(
    defaultCategory: String,
    onDismiss: () -> Unit,
    onSave: (Double, String, String, String, String, String) -> Unit
) {
    var amountStr by remember { mutableStateOf("") }
    var merchant by remember { mutableStateOf("") }
    var direction by remember { mutableStateOf("DEBIT") }
    var note by remember { mutableStateOf("") }

    val amount = amountStr.toDoubleOrNull()
    val canSave = amount != null && amount > 0 && merchant.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text("Add a cash transaction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.snug)) {
                OutlinedTextField(
                    value = amountStr,
                    onValueChange = { amountStr = it },
                    label = { Text("Amount") },
                    prefix = { Text("₹") },
                    // Validated on submit, not per keystroke: "0" is not an error
                    // while it is still being typed into "015".
                    isError = amountStr.isNotBlank() && (amount == null || amount <= 0),
                    supportingText = if (amountStr.isNotBlank() && (amount == null || amount <= 0)) {
                        { Text("Enter an amount greater than zero") }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text("Merchant or store") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.tight)) {
                    FilterChip(
                        selected = direction == "DEBIT",
                        onClick = { direction = "DEBIT" },
                        label = { Text("Spend") },
                        shape = MaterialTheme.shapes.small
                    )
                    FilterChip(
                        selected = direction == "CREDIT",
                        onClick = { direction = "CREDIT" },
                        label = { Text("Income") },
                        shape = MaterialTheme.shapes.small
                    )
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(amount ?: 0.0, direction, merchant, defaultCategory, "Cash", note) },
                enabled = canSave
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
