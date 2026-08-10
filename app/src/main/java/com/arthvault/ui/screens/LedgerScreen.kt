package com.arthvault.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arthvault.BuildConfig
import com.arthvault.data.local.entity.STATUS_POSTED
import com.arthvault.data.local.entity.TransactionEntity
import com.arthvault.data.local.entity.UnparsedSmsEntity
import com.arthvault.ui.components.CardHeading
import com.arthvault.ui.components.EmptyState
import com.arthvault.ui.components.LocalSnackbar
import com.arthvault.ui.components.VaultCard
import com.arthvault.ui.components.VaultRowCard
import com.arthvault.ui.components.VaultScaffold
import com.arthvault.ui.format.formatDateTime
import com.arthvault.ui.format.formatDirectedMoney
import com.arthvault.ui.format.formatFullTimestamp
import com.arthvault.ui.format.formatMoney
import com.arthvault.ui.format.formatMoneyPrecise
import com.arthvault.ui.theme.Spacing
import com.arthvault.ui.theme.VaultTheme
import com.arthvault.ui.theme.moneyMedium
import com.arthvault.ui.theme.moneySmall
import com.arthvault.ui.theme.numeric
import com.arthvault.ui.theme.payload
import com.arthvault.ui.viewmodel.LedgerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerScreen(
    viewModel: LedgerViewModel
) {
    val context = LocalContext.current
    val transactions by viewModel.filteredTransactions.collectAsState()
    val unreviewedSms by viewModel.unreviewedSms.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val selectedDirection by viewModel.selectedDirection.collectAsState()

    var activeTab by remember { mutableIntStateOf(0) } // 0: Ledger, 1: Unparsed Review
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedTxnForDetail by remember { mutableStateOf<TransactionEntity?>(null) }
    var showCategoryDialogForTxn by remember { mutableStateOf<TransactionEntity?>(null) }
    // Voiding a transaction is a ledger correction, not a UI tidy-up. It used to
    // fire straight from a 30dp icon sitting next to another 30dp icon.
    var pendingVoid by remember { mutableStateOf<TransactionEntity?>(null) }

    VaultScaffold(
        title = "Ledger",
        actions = {
            ScanInboxAction(viewModel = viewModel)
            if (BuildConfig.DEBUG) {
                val snackbar = LocalSnackbar.current
                IconButton(onClick = {
                    viewModel.seedSampleData()
                    snackbar.show("Loaded the sample SMS ledger")
                }) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = "Seed sample data")
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("add_manual_txn_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add a cash transaction")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Declined attempts stay visible in the feed as a record, but must
            // never be added up as if the money moved (F1.2).
            LedgerSummary(
                totalDebits = transactions
                    .filter { it.direction == "DEBIT" && it.status == STATUS_POSTED }
                    .sumOf { it.amount },
                totalCredits = transactions
                    .filter { it.direction == "CREDIT" && it.status == STATUS_POSTED }
                    .sumOf { it.amount },
                totalCount = transactions.size
            )

            PrimaryTabRow(
                selectedTabIndex = activeTab,
                containerColor = MaterialTheme.colorScheme.background,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    text = { Text("Feed (${transactions.size})") },
                    icon = { Icon(Icons.Default.ReceiptLong, contentDescription = null) }
                )
                Tab(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    text = { Text("Unparsed (${unreviewedSms.size})") },
                    icon = { Icon(Icons.Default.Message, contentDescription = null) }
                )
            }

            Crossfade(targetState = activeTab, label = "ledger-tab") { tab ->
                if (tab == 0) {
                    TransactionFeed(
                        transactions = transactions,
                        categories = categories.map { it.name },
                        searchQuery = searchQuery,
                        selectedCategory = selectedCategory,
                        selectedDirection = selectedDirection,
                        onSearch = viewModel::setSearchQuery,
                        onSelectCategory = viewModel::setSelectedCategory,
                        onSelectDirection = viewModel::setSelectedDirection,
                        onAddManual = { showAddDialog = true },
                        onOpen = { selectedTxnForDetail = it },
                        onChangeCategory = { showCategoryDialogForTxn = it },
                        onVoid = { pendingVoid = it }
                    )
                } else {
                    UnparsedFeed(
                        items = unreviewedSms,
                        onDismiss = viewModel::markUnparsedReviewed
                    )
                }
            }
        }
    }

    selectedTxnForDetail?.let { txn ->
        TransactionDetailDialog(txn) { selectedTxnForDetail = null }
    }

    showCategoryDialogForTxn?.let { txn ->
        RecategorizeDialog(
            transaction = txn,
            categories = categories.map { it.name },
            onDismiss = { showCategoryDialogForTxn = null },
            onPick = { category, applyToAll ->
                viewModel.updateCategory(txn.id, category, txn.merchant, applyToAll)
                showCategoryDialogForTxn = null
            }
        )
    }

    // A destructive action gets a confirmation and the `error` colour. It had
    // neither, and its trigger was an 30dp icon adjacent to the edit icon.
    pendingVoid?.let { txn ->
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
 * Scanning the inbox, moved into the app bar.
 *
 * It used to be a filled `Button` inside the header card, sitting beside a second
 * filled button and underneath a FAB — three controls all presenting as the primary
 * action on one screen. Adding a transaction is the primary action; scanning is a
 * periodic maintenance task and belongs in the bar.
 */
@Composable
private fun ScanInboxAction(viewModel: LedgerViewModel) {
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current

    val runScan = {
        snackbar.show("Scanning your SMS inbox…")
        viewModel.scanInbox { res ->
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

    IconButton(
        onClick = {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) runScan() else permissionLauncher.launch(Manifest.permission.READ_SMS)
        }
    ) {
        Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan SMS inbox")
    }
}

/**
 * The three totals, as a quiet band rather than a gradient hero.
 *
 * The card this replaces stacked a gradient, an "ON DEVICE" pill, a two-column total
 * panel and two filled buttons above the tab row, the search field and two rows of
 * chips — roughly 40% of the viewport was chrome before any transaction appeared.
 */
@Composable
private fun LedgerSummary(
    totalDebits: Double,
    totalCredits: Double,
    totalCount: Int
) {
    val semantics = VaultTheme.semantics
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.standard, vertical = Spacing.tight)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(Spacing.standard),
        horizontalArrangement = Arrangement.spacedBy(Spacing.snug)
    ) {
        SummaryFigure("TRACKED", totalCount.toString(), null, Modifier.weight(1f))
        SummaryFigure("OUT", formatMoney(totalDebits), semantics.negative, Modifier.weight(1f))
        SummaryFigure("IN", formatMoney(totalCredits), semantics.positive, Modifier.weight(1f))
    }
}

@Composable
private fun SummaryFigure(
    label: String,
    value: String,
    tint: androidx.compose.ui.graphics.Color?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.semantics(mergeDescendants = true) {}) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.moneySmall,
            color = tint ?: MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun TransactionFeed(
    transactions: List<TransactionEntity>,
    categories: List<String>,
    searchQuery: String,
    selectedCategory: String,
    selectedDirection: String,
    onSearch: (String) -> Unit,
    onSelectCategory: (String) -> Unit,
    onSelectDirection: (String) -> Unit,
    onAddManual: () -> Unit,
    onOpen: (TransactionEntity) -> Unit,
    onChangeCategory: (TransactionEntity) -> Unit,
    onVoid: (TransactionEntity) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.standard),
            verticalArrangement = Arrangement.spacedBy(Spacing.tight)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearch,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ledger_search_bar"),
                label = { Text("Search") },
                placeholder = { Text("Merchant, amount, or raw message text") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = MaterialTheme.shapes.small
            )

            // One filter row, not two. Direction now lives at the head of the same
            // scroller as the categories instead of in a second row below it.
            LazyRow(
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
                        leadingIcon = { Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(18.dp)) },
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
                        leadingIcon = { Icon(Icons.Default.ArrowDownward, contentDescription = null, modifier = Modifier.size(18.dp)) },
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
        }

        Spacer(Modifier.height(Spacing.snug))

        if (transactions.isEmpty()) {
            val filtered = searchQuery.isNotBlank() ||
                selectedCategory != "ALL" ||
                selectedDirection != "ALL"
            EmptyState(
                icon = Icons.Default.ReceiptLong,
                title = if (filtered) "Nothing matches those filters" else "No transactions yet",
                message = if (filtered) {
                    "Clear the search or filters above to see the whole ledger."
                } else {
                    "Scan your SMS inbox from the top bar, or record a cash payment yourself."
                },
                actionLabel = if (filtered) null else "Add a transaction",
                onAction = if (filtered) null else onAddManual
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Spacing.standard),
                verticalArrangement = Arrangement.spacedBy(Spacing.tight)
            ) {
                items(transactions, key = { it.id }) { txn ->
                    TransactionCard(
                        transaction = txn,
                        modifier = Modifier.animateItem(),
                        onClick = { onOpen(txn) },
                        onChangeCategory = { onChangeCategory(txn) },
                        onVoid = { onVoid(txn) }
                    )
                }
                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
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
            icon = Icons.Default.Security,
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

@Composable
fun TransactionCard(
    transaction: TransactionEntity,
    onClick: () -> Unit,
    onChangeCategory: () -> Unit,
    onVoid: () -> Unit,
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
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isCredit) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(20.dp)
                )
            }

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
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Text(
                            text = transaction.category,
                            modifier = Modifier.padding(horizontal = Spacing.tight, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    transaction.channel?.let { channel ->
                        Spacer(modifier = Modifier.width(Spacing.tight))
                        Text(
                            text = channel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // A declined attempt is kept in the record but must never read as
                    // money that moved.
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
                Spacer(modifier = Modifier.height(Spacing.hairline))
                Text(
                    text = buildString {
                        append(formatDateTime(transaction.timestamp))
                        transaction.balanceAfter?.let { append("  •  Bal ${formatMoney(it)}") }
                    },
                    style = MaterialTheme.typography.numeric,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(Spacing.tight))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatDirectedMoney(transaction.amount, isCredit),
                    style = MaterialTheme.typography.moneyMedium,
                    color = tint
                )
                Row {
                    // 48dp, not 30dp. These were 18dp under the minimum target and
                    // sat directly beside each other, so the destructive one was
                    // easy to hit by accident.
                    IconButton(onClick = onChangeCategory, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Change category",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onVoid, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Void transaction",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
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

@Composable
private fun TransactionDetailDialog(
    txn: TransactionEntity,
    onDismiss: () -> Unit
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

@Composable
private fun RecategorizeDialog(
    transaction: TransactionEntity,
    categories: List<String>,
    onDismiss: () -> Unit,
    onPick: (String, Boolean) -> Unit
) {
    var applyToAll by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text("Recategorize ${transaction.merchant}") },
        text = {
            Column {
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
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
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
