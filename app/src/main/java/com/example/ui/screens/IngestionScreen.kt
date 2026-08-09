package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Input
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.parser.SmsParserEngine
import com.example.ui.vault.SystemUiGuard
import com.example.ui.theme.ArthCrimson
import com.example.ui.theme.ArthEmerald
import com.example.ui.theme.ArthGold
import com.example.ui.theme.ArthIndigo
import com.example.ui.viewmodel.LedgerViewModel
import com.example.ui.viewmodel.VaultViewModel

@Composable
fun IngestionScreen(
    ledgerViewModel: LedgerViewModel,
    vaultViewModel: VaultViewModel
) {
    val context = LocalContext.current
    var pasteSmsText by remember { mutableStateOf("") }
    var testResultText by remember { mutableStateOf<String?>(null) }
    var showAddRuleDialog by remember { mutableStateOf(false) }
    var showAddSenderDialog by remember { mutableStateOf(false) }
    var newSenderId by remember { mutableStateOf("") }
    var newSenderLabel by remember { mutableStateOf("") }

    val activeRules by vaultViewModel.activeParserRules.collectAsState()
    val allowedSenders by ledgerViewModel.allowedSenders.collectAsState()
    val importResult by ledgerViewModel.importResult.collectAsState()
    val parserEngine = remember { SmsParserEngine() }

    // OpenDocument rather than a storage permission — T6.3 keeps the app to
    // SMS permissions only, and the picker grants access to just this one file.
    val csvPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { ledgerViewModel.importCsv(it) } }

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(context, "Scanning SMS inbox...", Toast.LENGTH_SHORT).show()
            ledgerViewModel.scanInbox { res ->
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
            ledgerViewModel.scanInbox { res ->
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

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "SMS Ingestion & Rule Engine",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "Source-agnostic parsing engine • Regex matching • Zero cloud dependency",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Quick Action: Scan Inbox
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Scan Device SMS Inbox",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Extract transactions directly from your bank SMS messages securely on-device.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Button(
                            onClick = triggerScanWithPermission,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text("Scan Inbox", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Interactive Text Parser Playground Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Input, contentDescription = null, tint = ArthGold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Paste SMS Text / Batch Ingestion", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = pasteSmsText,
                            onValueChange = { pasteSmsText = it },
                            placeholder = { Text("e.g. Paid Rs 850.00 to SWIGGY via UPI on 08-Aug-2026") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    if (pasteSmsText.isNotBlank()) {
                                        val res = parserEngine.parseMessage("TEST_SMS", pasteSmsText, System.currentTimeMillis())
                                        testResultText = if (res.parsedTransaction != null) {
                                            "Match Success!\nMerchant: ${res.parsedTransaction.merchant}\nAmount: ₹%.2f\nDirection: ${res.parsedTransaction.direction}\nCategory: ${res.parsedTransaction.category}".format(res.parsedTransaction.amount)
                                        } else {
                                            "Unparsed: No rule matched this text format."
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Text("Test Regex", fontWeight = FontWeight.SemiBold)
                            }

                            Button(
                                onClick = {
                                    if (pasteSmsText.isNotBlank()) {
                                        ledgerViewModel.importRawSmsBatch(listOf(pasteSmsText))
                                        Toast.makeText(context, "Ingested transaction into ledger!", Toast.LENGTH_SHORT).show()
                                        pasteSmsText = ""
                                        testResultText = null
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                            ) {
                                Text("Import to Ledger", fontWeight = FontWeight.SemiBold)
                            }
                        }

                        testResultText?.let { res ->
                            Spacer(modifier = Modifier.height(14.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = res,
                                    modifier = Modifier.padding(14.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (res.startsWith("Match")) ArthEmerald else ArthCrimson,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            // Section: Active Regex Parser Rules
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Active Parser Rules (${activeRules.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Button(
                        onClick = { showAddRuleDialog = true },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = ArthGold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Add Custom Rule", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            items(activeRules) { rule ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Rule, contentDescription = null, tint = ArthIndigo)
                            Text(rule.ruleName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = rule.regexPattern,
                                modifier = Modifier.padding(10.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // F1.1 — sender allowlist
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.FilterAlt, contentDescription = null, tint = ArthIndigo)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Bank Senders (${allowedSenders.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Only messages from these senders are read. Everything else in " +
                                "your inbox is ignored entirely. Sender IDs are matched on the bank " +
                                "code, so \"AD-HDFCBK-S\" matches HDFCBK.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { showAddSenderDialog = true },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add sender")
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        allowedSenders.take(8).forEach { s ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        s.senderId,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        s.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = { ledgerViewModel.removeAllowedSender(s.senderId) }) {
                                    Text("Remove", color = ArthCrimson)
                                }
                            }
                        }
                        if (allowedSenders.size > 8) {
                            Text(
                                "+ ${allowedSenders.size - 8} more",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // F1.5 — CSV import
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.UploadFile, contentDescription = null, tint = ArthEmerald)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Import CSV",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Bring in cash entries and gaps from a spreadsheet. Expects the " +
                                "same columns Arth Vault exports, so an export re-imports cleanly.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                SystemUiGuard.enter()
                                csvPickerLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*"))
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ArthEmerald)
                        ) {
                            Text("Choose CSV file", fontWeight = FontWeight.SemiBold)
                        }

                        importResult?.let { result ->
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (result.error != null) {
                                    ArthCrimson.copy(alpha = 0.1f)
                                } else {
                                    ArthEmerald.copy(alpha = 0.1f)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = result.error
                                        ?: "Imported ${result.imported} • ${result.duplicates} already present • ${result.skipped} unreadable",
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }

    if (showAddRuleDialog) {
        var ruleName by remember { mutableStateOf("") }
        var regexPattern by remember { mutableStateOf("") }
        var amountGroupStr by remember { mutableStateOf("1") }
        var merchantGroupStr by remember { mutableStateOf("2") }

        AlertDialog(
            onDismissRequest = { showAddRuleDialog = false },
            title = { Text("Add Custom Regex Parser Rule", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = ruleName,
                        onValueChange = { ruleName = it },
                        label = { Text("Rule Name (e.g. Custom Co-op Bank)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = regexPattern,
                        onValueChange = { regexPattern = it },
                        label = { Text("Regex Pattern") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = amountGroupStr,
                        onValueChange = { amountGroupStr = it },
                        label = { Text("Amount Capture Group Index") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = merchantGroupStr,
                        onValueChange = { merchantGroupStr = it },
                        label = { Text("Merchant Capture Group Index") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amtGrp = amountGroupStr.toIntOrNull() ?: 1
                        val merGrp = merchantGroupStr.toIntOrNull() ?: 2
                        if (ruleName.isNotBlank() && regexPattern.isNotBlank()) {
                            vaultViewModel.addCustomParserRule(ruleName, ".*", regexPattern, amtGrp, merGrp)
                            showAddRuleDialog = false
                            Toast.makeText(context, "Added custom parser rule!", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Save Rule")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddRuleDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showAddSenderDialog) {
        AlertDialog(
            onDismissRequest = { showAddSenderDialog = false },
            title = { Text("Add a bank sender", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "Paste the sender ID exactly as it appears in your messages app. " +
                            "The bank code is extracted automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newSenderId,
                        onValueChange = { newSenderId = it },
                        label = { Text("Sender ID (e.g. AD-HDFCBK-S)") },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newSenderLabel,
                        onValueChange = { newSenderLabel = it },
                        label = { Text("Bank name (optional)") },
                        singleLine = true
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
                    }
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddSenderDialog = false }) { Text("Cancel") }
            }
        )
    }
}

