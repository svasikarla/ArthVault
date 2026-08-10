package com.arthvault.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Input
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arthvault.data.parser.SmsParserEngine
import com.arthvault.data.parser.rules.ParserRuleSeeder
import com.arthvault.data.parser.rules.RuleLoadResult
import com.arthvault.ui.components.CardHeading
import com.arthvault.ui.components.LocalSnackbar
import com.arthvault.ui.components.VaultCard
import com.arthvault.ui.components.VaultRowCard
import com.arthvault.ui.components.VaultScaffold
import com.arthvault.ui.format.formatMoneyPrecise
import com.arthvault.ui.theme.Spacing
import com.arthvault.ui.theme.VaultTheme
import com.arthvault.ui.theme.payload
import com.arthvault.ui.vault.SystemUiGuard
import com.arthvault.ui.viewmodel.LedgerViewModel
import com.arthvault.ui.viewmodel.VaultViewModel

@Composable
fun IngestionScreen(
    ledgerViewModel: LedgerViewModel,
    vaultViewModel: VaultViewModel
) {
    val context = LocalContext.current
    var pasteSmsText by remember { mutableStateOf("") }
    var testResultText by remember { mutableStateOf<String?>(null) }
    var testSucceeded by remember { mutableStateOf(false) }
    var showAddRuleDialog by remember { mutableStateOf(false) }
    var showAddSenderDialog by remember { mutableStateOf(false) }
    var newSenderId by remember { mutableStateOf("") }
    var newSenderLabel by remember { mutableStateOf("") }

    // The tail awaiting a name. Marking an account changes what counts as spending,
    // so it goes through a dialog rather than happening on tap.
    var pendingOwnAccountTail by remember { mutableStateOf<String?>(null) }
    var newAccountLabel by remember { mutableStateOf("") }

    val activeRules by vaultViewModel.activeParserRules.collectAsState()
    val allowedSenders by ledgerViewModel.allowedSenders.collectAsState()
    val ownAccounts by ledgerViewModel.ownAccounts.collectAsState()
    val observedTails by ledgerViewModel.observedAccountTails.collectAsState()
    val importResult by ledgerViewModel.importResult.collectAsState()
    val parserEngine = remember { SmsParserEngine() }
    val semantics = VaultTheme.semantics

    // OpenDocument rather than a storage permission — T6.3 keeps the app to
    // SMS permissions only, and the picker grants access to just this one file.
    val csvPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { ledgerViewModel.importCsv(it) } }

    // T2.2 / 5.3 — picking a rule file only *inspects* it. Installing is a separate,
    // explicit confirmation, because these rules decide how money is read.
    val ruleFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vaultViewModel.inspectRuleFile(it) } }

    val pendingRuleFile by vaultViewModel.pendingRuleFile.collectAsState()
    val ruleFileOutcome by vaultViewModel.ruleFileOutcome.collectAsState()

    VaultScaffold(
        title = "Rules & parsing",
        actions = { ScanInboxAction(viewModel = ledgerViewModel) }
    ) { padding ->
        val snackbar = LocalSnackbar.current

        LaunchedEffect(ruleFileOutcome) {
            val outcome = ruleFileOutcome ?: return@LaunchedEffect
            snackbar.show(
                when (outcome) {
                    is ParserRuleSeeder.Outcome.Applied ->
                        "Installed ${outcome.ruleCount} rules (version ${outcome.rulesVersion})"
                    is ParserRuleSeeder.Outcome.AlreadyCurrent ->
                        "Already on rules version ${outcome.rulesVersion} — nothing changed"
                    is ParserRuleSeeder.Outcome.Rejected ->
                        "Rule file rejected. Your existing rules are unchanged."
                }
            )
            vaultViewModel.clearRuleFileOutcome()
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Spacing.standard),
            verticalArrangement = Arrangement.spacedBy(Spacing.standard)
        ) {
            item {
                Text(
                    text = "Regex matching, on this device. No message is sent anywhere.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Parser playground
            item {
                VaultCard {
                    CardHeading(
                        title = "Test a message",
                        icon = Icons.Default.Input,
                        iconTint = MaterialTheme.colorScheme.primary,
                        subtitle = "Paste a bank SMS to see how the live rules read it."
                    )
                    Spacer(modifier = Modifier.height(Spacing.snug))
                    OutlinedTextField(
                        value = pasteSmsText,
                        onValueChange = { pasteSmsText = it },
                        label = { Text("Message text") },
                        placeholder = { Text("e.g. Paid Rs 850.00 to SWIGGY via UPI on 08-Aug-2026") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        shape = MaterialTheme.shapes.small
                    )
                    Spacer(modifier = Modifier.height(Spacing.snug))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.tight)) {
                        // Testing is the safe, repeatable action, so it is the
                        // outlined one; importing writes to the ledger.
                        OutlinedButton(
                            onClick = {
                                if (pasteSmsText.isNotBlank()) {
                                    // The live rule set, not a default. Since T2.2
                                    // there are no built-in patterns behind the
                                    // engine, so omitting these makes the
                                    // playground report "no rule matched" for
                                    // every message ever pasted into it.
                                    val res = parserEngine.parseMessage(
                                        sender = "TEST_SMS",
                                        body = pasteSmsText,
                                        timestamp = System.currentTimeMillis(),
                                        parserRules = activeRules
                                    )
                                    val parsed = res.parsedTransaction
                                    testSucceeded = parsed != null
                                    testResultText = if (parsed != null) {
                                        "Matched.\nMerchant: ${parsed.merchant}\n" +
                                            "Amount: ${formatMoneyPrecise(parsed.amount)}\n" +
                                            "Direction: ${parsed.direction}\n" +
                                            "Category: ${parsed.category}"
                                    } else {
                                        "No rule matched this text format."
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(Spacing.hairline))
                            Text("Test")
                        }

                        Button(
                            onClick = {
                                if (pasteSmsText.isNotBlank()) {
                                    ledgerViewModel.importRawSmsBatch(listOf(pasteSmsText))
                                    snackbar.show("Added to your ledger")
                                    pasteSmsText = ""
                                    testResultText = null
                                }
                            },
                            enabled = pasteSmsText.isNotBlank(),
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text("Add to ledger")
                        }
                    }

                    testResultText?.let { res ->
                        Spacer(modifier = Modifier.height(Spacing.snug))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = res,
                                modifier = Modifier.padding(Spacing.snug),
                                style = MaterialTheme.typography.payload,
                                color = if (testSucceeded) semantics.positive else semantics.caution
                            )
                        }
                    }
                }
            }

            // Active parser rules
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.tight)) {
                    Text(
                        text = "Active parser rules (${activeRules.size})",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.tight)) {
                        // T2.2 / 5.3 — a signed rule file fixes a parser bug without
                        // an app update. Same signature check as the bundled asset,
                        // so a sideloaded file is exactly as trustworthy and no more.
                        OutlinedButton(
                            onClick = {
                                SystemUiGuard.enter()
                                ruleFilePickerLauncher.launch(arrayOf("application/json", "*/*"))
                            },
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text("Load rule file")
                        }
                        OutlinedButton(
                            onClick = { showAddRuleDialog = true },
                            shape = MaterialTheme.shapes.small
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(Spacing.hairline))
                            Text("Add your own")
                        }
                    }
                }
            }

            items(activeRules, key = { it.id }) { rule ->
                VaultRowCard(modifier = Modifier.animateItem()) {
                    CardHeading(
                        title = rule.ruleName,
                        icon = Icons.Default.Rule,
                        iconTint = semantics.info
                    )
                    Spacer(modifier = Modifier.height(Spacing.tight))
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = rule.regexPattern,
                            modifier = Modifier.padding(Spacing.tight),
                            style = MaterialTheme.typography.payload
                        )
                    }
                }
            }

            // F1.1 — sender allowlist
            item {
                VaultCard {
                    CardHeading(
                        title = "Bank senders (${allowedSenders.size})",
                        icon = Icons.Default.FilterAlt,
                        iconTint = semantics.info,
                        subtitle = "Only messages from these senders are read. Everything else " +
                            "in your inbox is ignored entirely. Sender IDs are matched on the " +
                            "bank code, so \"AD-HDFCBK-S\" matches HDFCBK."
                    )
                    Spacer(modifier = Modifier.height(Spacing.snug))
                    OutlinedButton(
                        onClick = { showAddSenderDialog = true },
                        shape = MaterialTheme.shapes.small
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(Spacing.hairline))
                        Text("Add sender")
                    }
                    Spacer(modifier = Modifier.height(Spacing.tight))
                    allowedSenders.take(8).forEach { s ->
                        ListRow(
                            primary = s.senderId,
                            secondary = s.label,
                            actionLabel = "Remove",
                            destructive = true,
                            onAction = { ledgerViewModel.removeAllowedSender(s.senderId) }
                        )
                    }
                    if (allowedSenders.size > 8) {
                        Text(
                            "+ ${allowedSenders.size - 8} more",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Own accounts — what counts as spending rather than moving money
            item {
                VaultCard {
                    CardHeading(
                        title = "My accounts (${ownAccounts.size})",
                        icon = Icons.Default.AccountBalance,
                        iconTint = semantics.positive,
                        subtitle = "Moving money between your own accounts is not spending, but " +
                            "your bank describes it exactly like a payment. Mark an account here " +
                            "and transfers to it stop counting as spend — and transfers from it " +
                            "stop counting as income."
                    )

                    if (ownAccounts.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(Spacing.snug))
                        ownAccounts.forEach { account ->
                            ListRow(
                                primary = "••••${account.tail}",
                                secondary = account.label,
                                actionLabel = "Not mine",
                                destructive = true,
                                onAction = { ledgerViewModel.unmarkAccount(account.tail) }
                            )
                        }
                    }

                    // Only accounts the parser has actually seen are offered. Typing
                    // a tail from memory is how you mark the wrong one and quietly
                    // delete real spending from your totals.
                    val unmarked = observedTails.filter { tail ->
                        ownAccounts.none { it.tail == tail }
                    }
                    if (unmarked.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(Spacing.snug))
                        Text(
                            "Seen in your ledger",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        unmarked.take(10).forEach { tail ->
                            ListRow(
                                primary = "••••$tail",
                                secondary = null,
                                actionLabel = "This is mine",
                                destructive = false,
                                onAction = { pendingOwnAccountTail = tail }
                            )
                        }
                    } else if (ownAccounts.isEmpty()) {
                        Spacer(modifier = Modifier.height(Spacing.snug))
                        Text(
                            "No accounts seen yet. Scan your inbox first.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // F1.5 — CSV import
            item {
                VaultCard {
                    CardHeading(
                        title = "Import a CSV",
                        icon = Icons.Default.UploadFile,
                        iconTint = semantics.positive,
                        subtitle = "Bring in cash entries and gaps from a spreadsheet. Expects the " +
                            "same columns Arth Vault exports, so an export re-imports cleanly."
                    )
                    Spacer(modifier = Modifier.height(Spacing.snug))
                    OutlinedButton(
                        onClick = {
                            SystemUiGuard.enter()
                            csvPickerLauncher.launch(
                                arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")
                            )
                        },
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text("Choose a file")
                    }

                    importResult?.let { result ->
                        Spacer(modifier = Modifier.height(Spacing.snug))
                        Text(
                            text = result.error
                                ?: "Imported ${result.imported} • ${result.duplicates} already " +
                                "present • ${result.skipped} unreadable",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (result.error != null) semantics.negative else semantics.positive
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(Spacing.section)) }
        }
    }

    // T2.2 / 5.3 — what the file is, and whether it is trustworthy, before it is
    // installed. A rejected file is shown with its reason rather than a generic
    // failure: "signature does not verify" and "this is not a rule file" call for
    // completely different responses from the user.
    pendingRuleFile?.let { pending ->
        val inspection = pending.inspection
        val document = (inspection as? RuleLoadResult.Loaded)?.document

        AlertDialog(
            onDismissRequest = { vaultViewModel.dismissRuleFile() },
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text(if (document != null) "Install parser rules?" else "Rule file rejected") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.tight)) {
                    when (inspection) {
                        is RuleLoadResult.Loaded -> {
                            Text(
                                "Signature verified against this app's key.",
                                style = MaterialTheme.typography.titleSmall,
                                color = VaultTheme.semantics.positive
                            )
                            Text("Rules version: ${document!!.rulesVersion}")
                            Text("Issued: ${document.issuedAt}")
                            Text("Rules: ${document.rules.size}")
                            Text(
                                "These rules decide how your bank messages are read. " +
                                    "Your own custom rules are kept.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        RuleLoadResult.BadSignature -> Text(
                            "The signature does not match this app's key. The file has been " +
                                "modified, or it was signed by someone else. It will not be installed."
                        )

                        is RuleLoadResult.UnsupportedSchema -> Text(
                            "This file uses rule format ${inspection.found}, but this version of " +
                                "Arth Vault understands format ${inspection.supported}. Update the app."
                        )

                        is RuleLoadResult.Malformed -> Text(
                            "This does not look like a parser rule file: ${inspection.reason}"
                        )
                    }
                }
            },
            confirmButton = {
                if (document != null) {
                    Button(onClick = { vaultViewModel.confirmRuleFile() }) { Text("Install") }
                } else {
                    TextButton(onClick = { vaultViewModel.dismissRuleFile() }) { Text("Close") }
                }
            },
            dismissButton = {
                if (document != null) {
                    TextButton(onClick = { vaultViewModel.dismissRuleFile() }) { Text("Cancel") }
                }
            }
        )
    }

    if (showAddRuleDialog) {
        AddRuleDialog(
            onDismiss = { showAddRuleDialog = false },
            onSave = { name, pattern, amountGroup, merchantGroup ->
                vaultViewModel.addCustomParserRule(name, ".*", pattern, amountGroup, merchantGroup)
                showAddRuleDialog = false
            }
        )
    }

    if (showAddSenderDialog) {
        AlertDialog(
            onDismissRequest = { showAddSenderDialog = false },
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text("Add a bank sender") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.snug)) {
                    Text(
                        "Paste the sender ID exactly as it appears in your messages app. " +
                            "The bank code is extracted automatically.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = newSenderId,
                        onValueChange = { newSenderId = it },
                        label = { Text("Sender ID") },
                        placeholder = { Text("AD-HDFCBK-S") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newSenderLabel,
                        onValueChange = { newSenderLabel = it },
                        label = { Text("Bank name (optional)") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newSenderId.isNotBlank()) {
                            ledgerViewModel.addAllowedSender(newSenderId, newSenderLabel)
                            newSenderId = ""
                            newSenderLabel = ""
                            showAddSenderDialog = false
                        }
                    },
                    enabled = newSenderId.isNotBlank()
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddSenderDialog = false }) { Text("Cancel") }
            }
        )
    }

    pendingOwnAccountTail?.let { tail ->
        AlertDialog(
            onDismissRequest = { pendingOwnAccountTail = null; newAccountLabel = "" },
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text("Mark ••••$tail as yours?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.snug)) {
                    Text(
                        "Transfers to this account will stop counting as spending, and " +
                            "transfers from it will stop counting as income. Your ledger " +
                            "still shows every one of them, and the analytics screen says " +
                            "how much was excluded.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = newAccountLabel,
                        onValueChange = { newAccountLabel = it },
                        label = { Text("What do you call it?") },
                        placeholder = { Text("HDFC Savings") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        ledgerViewModel.markAccountAsOwn(
                            tail = tail,
                            label = newAccountLabel.ifBlank { "Account ••••$tail" }
                        )
                        pendingOwnAccountTail = null
                        newAccountLabel = ""
                    }
                ) { Text("This is mine") }
            },
            dismissButton = {
                TextButton(onClick = { pendingOwnAccountTail = null; newAccountLabel = "" }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * A settings-style row: monospace identifier, optional label, one trailing action.
 *
 * The senders list and both account lists were three copies of the same `Row` with
 * slightly different spacing each time.
 */
@Composable
private fun ListRow(
    primary: String,
    secondary: String?,
    actionLabel: String,
    destructive: Boolean,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(primary, style = MaterialTheme.typography.payload)
            if (secondary != null) {
                Text(
                    secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        TextButton(onClick = onAction) {
            Text(
                actionLabel,
                color = if (destructive) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
        }
    }
}

/** Scanning the inbox, shared with the Ledger screen's app bar. */
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
        if (granted) runScan() else {
            snackbar.show("Arth Vault needs SMS access to read your bank messages")
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

@Composable
private fun AddRuleDialog(
    onDismiss: () -> Unit,
    onSave: (String, String, Int, Int) -> Unit
) {
    var ruleName by remember { mutableStateOf("") }
    var regexPattern by remember { mutableStateOf("") }
    var amountGroupStr by remember { mutableStateOf("1") }
    var merchantGroupStr by remember { mutableStateOf("2") }

    // A rule that will not compile is worse than no rule: it silently matches
    // nothing, and the user finds out weeks later via a missing transaction.
    val patternError = remember(regexPattern) {
        if (regexPattern.isBlank()) null
        else runCatching { Regex(regexPattern) }.exceptionOrNull()?.message
    }
    val canSave = ruleName.isNotBlank() && regexPattern.isNotBlank() && patternError == null

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text("Add a parser rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.snug)) {
                OutlinedTextField(
                    value = ruleName,
                    onValueChange = { ruleName = it },
                    label = { Text("Rule name") },
                    placeholder = { Text("Custom Co-op Bank") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = regexPattern,
                    onValueChange = { regexPattern = it },
                    label = { Text("Regex pattern") },
                    isError = patternError != null,
                    supportingText = patternError?.let { { Text(it) } },
                    textStyle = MaterialTheme.typography.payload,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.tight)) {
                    OutlinedTextField(
                        value = amountGroupStr,
                        onValueChange = { amountGroupStr = it },
                        label = { Text("Amount group") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = merchantGroupStr,
                        onValueChange = { merchantGroupStr = it },
                        label = { Text("Merchant group") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        ruleName,
                        regexPattern,
                        amountGroupStr.toIntOrNull() ?: 1,
                        merchantGroupStr.toIntOrNull() ?: 2
                    )
                },
                enabled = canSave
            ) { Text("Save rule") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
