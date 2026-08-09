package com.arthvault.ui.screens

import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NoCell
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.arthvault.data.backup.BackupCodec
import com.arthvault.ui.vault.SystemUiGuard
import com.arthvault.ui.theme.ArthCrimson
import com.arthvault.ui.theme.ArthEmerald
import com.arthvault.ui.theme.ArthGold
import com.arthvault.ui.theme.ArthIndigo
import com.arthvault.ui.viewmodel.VaultViewModel

/**
 * Reads the permissions this build actually declares, straight from the merged
 * manifest at runtime. The privacy card renders this rather than a fixed string,
 * so the claim cannot drift away from the truth the way a hardcoded one did.
 */
@Composable
private fun rememberDeclaredPermissions(): List<String> {
    val context = LocalContext.current
    return remember {
        runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                .orEmpty()
                .map { it.substringAfterLast('.') }
                .sorted()
        }.getOrDefault(emptyList())
    }
}

@Composable
fun VaultScreen(
    viewModel: VaultViewModel
) {
    val context = LocalContext.current
    val declaredPermissions = rememberDeclaredPermissions()
    val hasNetworkPermission = declaredPermissions.any { it == "INTERNET" }
    val exportedFile by viewModel.exportedFile.collectAsState()
    val exportedJson by viewModel.exportedJson.collectAsState()
    val backupResult by viewModel.backupResult.collectAsState()
    val restoreResult by viewModel.restoreResult.collectAsState()
    var showWipeConfirmDialog by remember { mutableStateOf(false) }

    // F5.3 — the passphrase is collected first, then the file location, so a
    // cancelled or mistyped passphrase never leaves an empty file behind.
    var passphrasePrompt by remember { mutableStateOf<PassphrasePrompt?>(null) }
    var pendingPassphrase by remember { mutableStateOf("") }

    // ActivityResultContracts, not a storage permission: T6.3 rules out
    // storage-wide access, and the document picker never needed it anyway.
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupCodec.MIME_TYPE)
    ) { uri ->
        if (uri != null && pendingPassphrase.isNotEmpty()) {
            viewModel.writeBackup(uri, pendingPassphrase)
        }
        pendingPassphrase = ""
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && pendingPassphrase.isNotEmpty()) {
            viewModel.restoreBackup(uri, pendingPassphrase)
        }
        pendingPassphrase = ""
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
                    text = "Vault Privacy & Security",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "Your data, on your device • Verifiable in this build",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Zero Network Egress Certificate Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, ArthEmerald.copy(alpha = 0.35f)),
                    colors = CardDefaults.cardColors(
                        containerColor = if (hasNetworkPermission) {
                            ArthCrimson.copy(alpha = 0.08f)
                        } else {
                            ArthEmerald.copy(alpha = 0.08f)
                        }
                    )
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Shield,
                                contentDescription = null,
                                tint = if (hasNetworkPermission) ArthCrimson else ArthEmerald,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (hasNetworkPermission) {
                                    "Network Access Detected"
                                } else {
                                    "No Network Access"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (hasNetworkPermission) ArthCrimson else ArthEmerald
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = if (hasNetworkPermission) {
                                "This build declares android.permission.INTERNET. Your ledger " +
                                    "could leave this device. This is a bug — please report it."
                            } else {
                                "This build cannot reach the network. It declares no internet " +
                                    "permission and links no networking library."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "PERMISSIONS THIS BUILD DECLARES",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = declaredPermissions.joinToString("\n") { "• $it" }
                                .ifBlank { "• (none)" },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "• Data residence: on-device SQLite only.\n" +
                                "• At rest: SQLCipher (AES-256), key held in secure hardware.\n" +
                                "• Cloud auto-backup: disabled.\n" +
                                "• Telemetry / crash reporting: none.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Data Export & Backup Section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Download, contentDescription = null, tint = ArthGold)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Export & Backup Ledger Data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { viewModel.exportCsv() },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                            ) {
                                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Export CSV", fontWeight = FontWeight.SemiBold)
                            }
                            OutlinedButton(
                                onClick = { viewModel.exportJson() },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Export JSON", fontWeight = FontWeight.SemiBold)
                            }
                        }

                        exportedFile?.let { file ->
                            Spacer(modifier = Modifier.height(14.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text("CSV File Created: ${file.name}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Button(
                                        onClick = {
                                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/csv"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            SystemUiGuard.enter()
                                            context.startActivity(Intent.createChooser(shareIntent, "Share Ledger CSV"))
                                        },
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text("Share / Save File")
                                    }
                                }
                            }
                        }

                        exportedJson?.let { json ->
                            Spacer(modifier = Modifier.height(14.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text("JSON Export Preview:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = json.take(300) + "...",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // F5.3 — Encrypted backup to a location the user picks
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, ArthIndigo.copy(alpha = 0.35f)),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = ArthIndigo)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Encrypted Backup",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Writes your whole vault — transactions, corrections, rules and " +
                                "sender list — to one encrypted .avault file wherever you choose.\n\n" +
                                "The file is sealed with a passphrase you pick, not with this " +
                                "phone's hardware key. That is deliberate: a hardware key cannot " +
                                "leave the device, so a backup sealed with it could never be " +
                                "restored to a new one. Nobody can recover this passphrase for you.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { passphrasePrompt = PassphrasePrompt.Backup },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Back up", fontWeight = FontWeight.SemiBold)
                            }
                            OutlinedButton(
                                onClick = { passphrasePrompt = PassphrasePrompt.Restore },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Restore", fontWeight = FontWeight.SemiBold)
                            }
                        }

                        backupResult?.let { result ->
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = result.error
                                    ?: "Backup written — ${result.transactionCount} transactions, " +
                                    "${result.byteCount / 1024} KB, encrypted.",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (result.error != null) ArthCrimson else ArthEmerald
                            )
                        }

                        restoreResult?.let { result ->
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = result.error
                                    ?: "Restored ${result.transactionsRestored} transactions " +
                                    "(${result.duplicatesSkipped} already present).",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (result.error != null) ArthCrimson else ArthEmerald
                            )
                        }
                    }
                }
            }

            // Danger Zone: Local Data Wipe
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, ArthCrimson.copy(alpha = 0.35f)),
                    colors = CardDefaults.cardColors(containerColor = ArthCrimson.copy(alpha = 0.08f))
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.DeleteForever, contentDescription = null, tint = ArthCrimson)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Full Local Wipe", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ArthCrimson)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Irreversibly delete all ledger transactions, unparsed SMS queues, and custom rule overrides from this device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = { showWipeConfirmDialog = true },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ArthCrimson, contentColor = androidx.compose.ui.graphics.Color.White)
                        ) {
                            Icon(Icons.Default.DeleteForever, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Execute Full Local Wipe", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }

    passphrasePrompt?.let { prompt ->
        PassphraseDialog(
            prompt = prompt,
            onDismiss = { passphrasePrompt = null },
            onConfirm = { passphrase ->
                pendingPassphrase = passphrase
                passphrasePrompt = null
                viewModel.clearBackupResults()
                // The picker stops this activity; without this the lock policy
                // would close the database before the file is chosen.
                SystemUiGuard.enter()
                when (prompt) {
                    PassphrasePrompt.Backup ->
                        backupLauncher.launch("arth-vault-${System.currentTimeMillis()}.${BackupCodec.FILE_EXTENSION}")
                    PassphrasePrompt.Restore ->
                        restoreLauncher.launch(arrayOf("*/*"))
                }
            }
        )
    }

    if (showWipeConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showWipeConfirmDialog = false },
            title = { Text("Confirm Full Local Wipe", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to delete all stored transactions and database records? This operation cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.fullWipe()
                        showWipeConfirmDialog = false
                        Toast.makeText(context, "All local ledger data wiped.", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ArthCrimson)
                ) {
                    Text("Wipe All Data")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWipeConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}


enum class PassphrasePrompt { Backup, Restore }

/**
 * Collects the backup passphrase.
 *
 * Backing up asks twice, because a typo in a write-only secret is discovered at
 * the worst possible moment — when the backup is the only copy left. Restoring
 * asks once; the GCM tag will tell us soon enough whether it was right.
 */
@Composable
private fun PassphraseDialog(
    prompt: PassphrasePrompt,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }

    val isBackup = prompt == PassphrasePrompt.Backup
    val tooShort = passphrase.length < BackupCodec.MIN_PASSPHRASE_LENGTH
    val mismatch = isBackup && confirmation.isNotEmpty() && passphrase != confirmation
    val canProceed = !tooShort && (!isBackup || passphrase == confirmation)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isBackup) "Choose a backup passphrase" else "Enter the backup passphrase",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = if (isBackup) {
                        "This passphrase is the only thing that can open the backup file. " +
                            "It is not stored anywhere and cannot be reset."
                    } else {
                        "The passphrase used when this backup was written."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase") },
                    singleLine = true,
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (isBackup) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        label = { Text("Confirm passphrase") },
                        singleLine = true,
                        isError = mismatch,
                        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = reveal, onCheckedChange = { reveal = it })
                    Text("Show passphrase", style = MaterialTheme.typography.bodySmall)
                }
                if (passphrase.isNotEmpty() && tooShort) {
                    Text(
                        text = "At least ${BackupCodec.MIN_PASSPHRASE_LENGTH} characters.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ArthCrimson
                    )
                }
                if (mismatch) {
                    Text(
                        text = "The two entries do not match.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ArthCrimson
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(passphrase) }, enabled = canProceed) {
                Text(if (isBackup) "Choose location" else "Choose file")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
