package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.data.local.entity.STATUS_POSTED
import com.example.data.local.entity.TransactionEntity
import com.example.data.local.entity.UnparsedSmsEntity
import com.example.ui.theme.ArthCrimson
import com.example.ui.theme.ArthEmerald
import com.example.ui.theme.ArthEmeraldDark
import com.example.ui.theme.ArthGold
import com.example.ui.theme.ArthIndigo
import com.example.ui.viewmodel.LedgerViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(context, "Scanning SMS inbox...", Toast.LENGTH_SHORT).show()
            viewModel.scanInbox { res ->
                val msg = if (res.newTransactionsCount > 0) {
                    "Scanned ${res.totalScanned} SMS: Imported ${res.newTransactionsCount} new transactions!"
                } else if (res.totalScanned > 0) {
                    "Scanned ${res.totalScanned} SMS: All bank transactions up to date."
                } else {
                    "No SMS messages found in inbox."
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(context, "SMS permission is required to scan your inbox for bank transactions.", Toast.LENGTH_LONG).show()
        }
    }

    val triggerScanWithPermission = {
        val permissionCheck = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
        if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(context, "Scanning SMS inbox...", Toast.LENGTH_SHORT).show()
            viewModel.scanInbox { res ->
                val msg = if (res.newTransactionsCount > 0) {
                    "Scanned ${res.totalScanned} SMS: Imported ${res.newTransactionsCount} new transactions!"
                } else if (res.totalScanned > 0) {
                    "Scanned ${res.totalScanned} SMS: All bank transactions up to date."
                } else {
                    "No SMS messages found in inbox."
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        } else {
            smsPermissionLauncher.launch(Manifest.permission.READ_SMS)
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("add_manual_txn_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Transaction")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Header Stats Banner
            LedgerHeaderBanner(
                // Declined attempts stay visible in the feed as a record, but must
                // never be added up as if the money moved (F1.2).
                totalDebits = transactions
                    .filter { it.direction == "DEBIT" && it.status == STATUS_POSTED }
                    .sumOf { it.amount },
                totalCredits = transactions
                    .filter { it.direction == "CREDIT" && it.status == STATUS_POSTED }
                    .sumOf { it.amount },
                totalCount = transactions.size,
                onScanInbox = triggerScanWithPermission,
                onSeedSample = {
                    viewModel.seedSampleData()
                    Toast.makeText(context, "Loaded sample SMS transaction ledger!", Toast.LENGTH_SHORT).show()
                }
            )

            // Primary Tabs: Ledger vs Unparsed Review
            PrimaryTabRow(
                selectedTabIndex = activeTab,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    text = { Text("Transaction Feed (${transactions.size})") },
                    icon = { Icon(Icons.Default.ReceiptLong, contentDescription = null) }
                )
                Tab(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    text = { Text("Unparsed SMS (${unreviewedSms.size})") },
                    icon = { Icon(Icons.Default.Message, contentDescription = null) }
                )
            }

            if (activeTab == 0) {
                // Search Bar & Filters
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { viewModel.setSearchQuery(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ledger_search_bar"),
                        placeholder = { Text("Search by merchant, amount, or raw text...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Category Filter Chips
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        item {
                            FilterChip(
                                selected = selectedCategory == "ALL",
                                onClick = { viewModel.setSelectedCategory("ALL") },
                                label = { Text("All Categories") }
                            )
                        }
                        items(categories) { cat ->
                            FilterChip(
                                selected = selectedCategory == cat.name,
                                onClick = { viewModel.setSelectedCategory(cat.name) },
                                label = { Text(cat.name) }
                            )
                        }
                    }

                    // Direction Filter Chips
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        AssistChip(
                            onClick = { viewModel.setSelectedDirection(if (selectedDirection == "DEBIT") "ALL" else "DEBIT") },
                            label = { Text("Debits Only") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.ArrowUpward,
                                    contentDescription = null,
                                    tint = if (selectedDirection == "DEBIT") ArthCrimson else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        )
                        AssistChip(
                            onClick = { viewModel.setSelectedDirection(if (selectedDirection == "CREDIT") "ALL" else "CREDIT") },
                            label = { Text("Income / Credits") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.ArrowDownward,
                                    contentDescription = null,
                                    tint = if (selectedDirection == "CREDIT") ArthEmerald else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        )
                    }
                }

                // Transactions Feed
                if (transactions.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.ReceiptLong,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "No transactions found",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Tap 'Seed Sample SMS' above or scan inbox to load financial transactions.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(transactions, key = { it.id }) { txn ->
                            TransactionCard(
                                transaction = txn,
                                onClick = { selectedTxnForDetail = txn },
                                onChangeCategory = { showCategoryDialogForTxn = txn },
                                onDelete = { viewModel.voidTransaction(txn) }
                            )
                        }
                        item { Spacer(modifier = Modifier.height(80.dp)) }
                    }
                }
            } else {
                // Unparsed SMS Review List
                if (unreviewedSms.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Security,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = ArthEmerald
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "100% Parsing Accuracy Queue Clear!",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No unparsed financial SMS messages queued for review.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(unreviewedSms) { item ->
                            UnparsedSmsCard(
                                item = item,
                                onDismiss = { viewModel.markUnparsedReviewed(item.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    // Modal: Transaction Detail & Source Raw SMS
    selectedTxnForDetail?.let { txn ->
        AlertDialog(
            onDismissRequest = { selectedTxnForDetail = null },
            title = { Text(text = txn.merchant, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        text = "₹%.2f (%s)".format(txn.amount, txn.direction),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (txn.direction == "CREDIT") ArthEmerald else ArthCrimson,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Category: ${txn.category}", style = MaterialTheme.typography.bodyMedium)
                    Text("Channel: ${txn.channel ?: "Unknown"}", style = MaterialTheme.typography.bodyMedium)
                    Text("Sender: ${txn.sender}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Date: " + SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(txn.timestamp)),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Raw On-Device SMS Signal:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = txn.rawMessage,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedTxnForDetail = null }) {
                    Text("Close")
                }
            }
        )
    }

    // Modal: Change Category & Bulk Override
    showCategoryDialogForTxn?.let { txn ->
        var bulkRuleCheck by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { showCategoryDialogForTxn = null },
            title = { Text("Recategorize ${txn.merchant}") },
            text = {
                Column {
                    Text("Select a new category for this transaction:")
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.height(200.dp)) {
                        items(categories) { cat ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.updateCategory(
                                            txn.id,
                                            cat.name,
                                            txn.merchant,
                                            bulkRuleCheck
                                        )
                                        showCategoryDialogForTxn = null
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(cat.name, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(
                            checked = bulkRuleCheck,
                            onCheckedChange = { bulkRuleCheck = it }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Apply to all future & past ${txn.merchant} txns", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCategoryDialogForTxn = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Modal: Add Manual Cash / Gap Transaction
    if (showAddDialog) {
        var amountStr by remember { mutableStateOf("") }
        var merchant by remember { mutableStateOf("") }
        var direction by remember { mutableStateOf("DEBIT") }
        var category by remember { mutableStateOf(categories.firstOrNull()?.name ?: "Other / Misc") }
        var channel by remember { mutableStateOf("Cash") }
        var note by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Manual / Cash Transaction") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = amountStr,
                        onValueChange = { amountStr = it },
                        label = { Text("Amount (Rs.)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = merchant,
                        onValueChange = { merchant = it },
                        label = { Text("Merchant / Store Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = direction == "DEBIT",
                            onClick = { direction = "DEBIT" },
                            label = { Text("Expense (Debit)") }
                        )
                        FilterChip(
                            selected = direction == "CREDIT",
                            onClick = { direction = "CREDIT" },
                            label = { Text("Income (Credit)") }
                        )
                    }
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Note / Memory") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amount = amountStr.toDoubleOrNull() ?: 0.0
                        if (amount > 0 && merchant.isNotBlank()) {
                            viewModel.addManualTransaction(amount, direction, merchant, category, channel, note)
                            showAddDialog = false
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun LedgerHeaderBanner(
    totalDebits: Double,
    totalCredits: Double,
    totalCount: Int,
    onScanInbox: () -> Unit,
    onSeedSample: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            ArthEmerald.copy(alpha = 0.08f),
                            ArthGold.copy(alpha = 0.03f),
                            Color.Transparent
                        )
                    )
                )
                .padding(18.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "ON-DEVICE LEDGER SUMMARY",
                            style = MaterialTheme.typography.labelSmall,
                            color = ArthGold,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "$totalCount Signals Tracked",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = ArthEmerald.copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, ArthEmerald.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(ArthEmerald)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "ON DEVICE",
                                style = MaterialTheme.typography.labelSmall,
                                color = ArthEmerald,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(14.dp)
                        )
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "TOTAL OUTFLOWS",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "₹%.2f".format(totalDebits),
                            style = MaterialTheme.typography.titleMedium,
                            color = ArthCrimson,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(36.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    )
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "TOTAL INFLOWS",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "₹%.2f".format(totalCredits),
                            style = MaterialTheme.typography.titleMedium,
                            color = ArthEmerald,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onScanInbox,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Scan SMS Inbox", fontWeight = FontWeight.SemiBold)
                    }
                    // Debug builds only. Seeded rows are indistinguishable from real
                    // ones once stored, and they skew every total, the forecast and
                    // the recurring detector — not something to ship in a ledger.
                    if (BuildConfig.DEBUG) {
                        Button(
                            onClick = onSeedSample,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = ArthGold, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Seed Sample SMS", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TransactionCard(
    transaction: TransactionEntity,
    onClick: () -> Unit,
    onChangeCategory: () -> Unit,
    onDelete: () -> Unit
) {
    val isCredit = transaction.direction == "CREDIT"
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Direction Badge
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(if (isCredit) ArthEmerald.copy(alpha = 0.12f) else ArthCrimson.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isCredit) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                    contentDescription = null,
                    tint = if (isCredit) ArthEmerald else ArthCrimson,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = transaction.merchant,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = transaction.category,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    transaction.channel?.let { channel ->
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "• $channel",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // A declined attempt is kept in the record but must never read as
                    // money that moved.
                    if (transaction.status != STATUS_POSTED) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = ArthCrimson.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "DECLINED",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = ArthCrimson,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(transaction.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    transaction.balanceAfter?.let { balance ->
                        Text(
                            text = "  •  Bal ₹%.0f".format(balance),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "%s₹%.2f".format(if (isCredit) "+" else "-", transaction.amount),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isCredit) ArthEmerald else ArthCrimson
                )
                Row(modifier = Modifier.padding(top = 2.dp)) {
                    IconButton(onClick = onChangeCategory, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit Category", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun UnparsedSmsCard(
    item: UnparsedSmsEntity,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Sender: ${item.sender}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = ArthCrimson.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = item.failureReason,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = ArthCrimson,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = item.rawMessage,
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text("Dismiss / Reviewed", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
