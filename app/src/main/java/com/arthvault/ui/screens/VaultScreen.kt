package com.arthvault.ui.screens

import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.arthvault.data.backup.BackupCodec
import com.arthvault.ui.components.CardHeading
import com.arthvault.ui.components.LocalSnackbar
import com.arthvault.ui.components.VaultCard
import com.arthvault.ui.components.VaultScaffold
import com.arthvault.ui.theme.Spacing
import com.arthvault.ui.theme.VaultTheme
import com.arthvault.ui.theme.payload
import com.arthvault.ui.vault.SystemUiGuard
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
    val semantics = VaultTheme.semantics

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

    VaultScaffold(title = "Vault") { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Spacing.standard),
            verticalArrangement = Arrangement.spacedBy(Spacing.standard)
        ) {
            item {
                Text(
                    text = "Your data, on your device — verifiable in this build.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Zero network egress certificate
            item {
                val ok = !hasNetworkPermission
                val accent = if (ok) semantics.positive else semantics.negative
                VaultCard(accent = accent) {
                    CardHeading(
                        title = if (ok) "No network access" else "Network access detected",
                        icon = Icons.Outlined.Shield,
                        iconTint = accent
                    )
                    Spacer(modifier = Modifier.height(Spacing.tight))
                    Text(
                        text = if (ok) {
                            "This build cannot reach the network. It declares no internet " +
                                "permission and links no networking library."
                        } else {
                            "This build declares android.permission.INTERNET. Your ledger " +
                                "could leave this device. This is a bug — please report it."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(Spacing.snug))
                    Text(
                        text = "PERMISSIONS THIS BUILD DECLARES",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(Spacing.hairline))
                    Text(
                        text = declaredPermissions.joinToString("\n") { "• $it" }
                            .ifBlank { "• (none)" },
                        style = MaterialTheme.typography.payload
                    )
                    Spacer(modifier = Modifier.height(Spacing.tight))
                    Text(
                        text = "• Data residence: on-device SQLite only.\n" +
                            "• At rest: SQLCipher (AES-256), key held in secure hardware.\n" +
                            "• Cloud auto-backup: disabled.\n" +
                            "• Telemetry / crash reporting: none.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Export
            item {
                VaultCard {
                    CardHeading(
                        title = "Export your ledger",
                        icon = Icons.Outlined.Download,
                        iconTint = MaterialTheme.colorScheme.primary,
                        subtitle = "Plain files you can open anywhere. Not encrypted."
                    )
                    Spacer(modifier = Modifier.height(Spacing.snug))
                    // One filled button per surface: CSV is the common case, JSON is
                    // the secondary. Two filled buttons side by side made neither read
                    // as the primary.
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.tight)) {
                        Button(
                            onClick = { viewModel.exportCsv() },
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(Spacing.tight))
                            Text("Export CSV")
                        }
                        OutlinedButton(
                            onClick = { viewModel.exportJson() },
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text("Export JSON")
                        }
                    }

                    exportedFile?.let { file ->
                        Spacer(modifier = Modifier.height(Spacing.snug))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(Spacing.snug)) {
                                Text(
                                    "Created ${file.name}",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Spacer(modifier = Modifier.height(Spacing.tight))
                                OutlinedButton(
                                    onClick = {
                                        val uri = FileProvider.getUriForFile(
                                            context, "${context.packageName}.fileprovider", file
                                        )
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/csv"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        SystemUiGuard.enter()
                                        context.startActivity(
                                            Intent.createChooser(shareIntent, "Share ledger CSV")
                                        )
                                    },
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text("Share the file")
                                }
                            }
                        }
                    }

                    exportedJson?.let { json ->
                        Spacer(modifier = Modifier.height(Spacing.snug))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(Spacing.snug)) {
                                Text(
                                    "JSON preview",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Spacer(modifier = Modifier.height(Spacing.hairline))
                                Text(
                                    text = json.take(300) + "…",
                                    style = MaterialTheme.typography.payload
                                )
                            }
                        }
                    }
                }
            }

            // F5.3 — Encrypted backup to a location the user picks
            item {
                VaultCard {
                    CardHeading(
                        title = "Encrypted backup",
                        icon = Icons.Outlined.Lock,
                        iconTint = semantics.info
                    )
                    Spacer(modifier = Modifier.height(Spacing.tight))
                    Text(
                        text = "Writes your whole vault — transactions, corrections, rules and " +
                            "sender list — to one encrypted .avault file wherever you choose.\n\n" +
                            "The file is sealed with a passphrase you pick, not with this " +
                            "phone's hardware key. That is deliberate: a hardware key cannot " +
                            "leave the device, so a backup sealed with it could never be " +
                            "restored to a new one. Nobody can recover this passphrase for you.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(Spacing.snug))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.tight)) {
                        Button(
                            onClick = { passphrasePrompt = PassphrasePrompt.Backup },
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(Spacing.tight))
                            Text("Back up")
                        }
                        OutlinedButton(
                            onClick = { passphrasePrompt = PassphrasePrompt.Restore },
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text("Restore")
                        }
                    }

                    backupResult?.let { result ->
                        Spacer(modifier = Modifier.height(Spacing.snug))
                        Text(
                            text = result.error
                                ?: "Backup written — ${result.transactionCount} transactions, " +
                                "${result.byteCount / 1024} KB, encrypted.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (result.error != null) semantics.negative else semantics.positive
                        )
                    }

                    restoreResult?.let { result ->
                        Spacer(modifier = Modifier.height(Spacing.snug))
                        Text(
                            text = result.error
                                ?: "Restored ${result.transactionsRestored} transactions " +
                                "(${result.duplicatesSkipped} already present).",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (result.error != null) semantics.negative else semantics.positive
                        )
                    }
                }
            }

            // Danger zone
            item {
                VaultCard(accent = semantics.negative) {
                    CardHeading(
                        title = "Delete everything on this device",
                        icon = Icons.Outlined.DeleteForever,
                        iconTint = semantics.negative
                    )
                    Spacer(modifier = Modifier.height(Spacing.tight))
                    Text(
                        text = "Irreversibly deletes every transaction, unparsed message and " +
                            "custom rule stored here. Back up first if you might want any of it.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(Spacing.snug))
                    // Outlined, not filled: a destructive action should not look like
                    // the thing the screen wants you to do.
                    OutlinedButton(
                        onClick = { showWipeConfirmDialog = true },
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Delete all data")
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(Spacing.section)) }
        }

        // The dialogs live inside the scaffold's content, not beside it.
        //
        // LocalSnackbar is provided by VaultScaffold and therefore only reaches this
        // lambda. As a sibling of VaultScaffold, the wipe dialog below read the local
        // with no provider above it and threw the moment "Delete all data" was tapped.
        // Dialogs compose into their own window and contribute no layout node here, so
        // nesting them costs nothing.
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
            val snackbar = LocalSnackbar.current
            AlertDialog(
                onDismissRequest = { showWipeConfirmDialog = false },
                shape = MaterialTheme.shapes.extraLarge,
                title = { Text("Delete everything?") },
                text = {
                    Text(
                        "Every transaction and rule stored on this device will be erased. " +
                            "This cannot be undone."
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.fullWipe()
                            showWipeConfirmDialog = false
                            snackbar.show("All local ledger data deleted")
                        }
                    ) {
                        Text("Delete everything", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showWipeConfirmDialog = false }) { Text("Cancel") }
                }
            )
        }
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
        shape = MaterialTheme.shapes.extraLarge,
        title = {
            Text(if (isBackup) "Choose a backup passphrase" else "Enter the backup passphrase")
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
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(Spacing.snug))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase") },
                    singleLine = true,
                    isError = passphrase.isNotEmpty() && tooShort,
                    supportingText = if (passphrase.isNotEmpty() && tooShort) {
                        { Text("At least ${BackupCodec.MIN_PASSPHRASE_LENGTH} characters") }
                    } else null,
                    shape = MaterialTheme.shapes.small,
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (isBackup) {
                    Spacer(modifier = Modifier.height(Spacing.tight))
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        label = { Text("Confirm passphrase") },
                        singleLine = true,
                        isError = mismatch,
                        supportingText = if (mismatch) {
                            { Text("The two entries do not match") }
                        } else null,
                        shape = MaterialTheme.shapes.small,
                        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Row(
                    modifier = Modifier.heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = reveal, onCheckedChange = { reveal = it })
                    Text("Show passphrase", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(passphrase) }, enabled = canProceed) {
                Text(if (isBackup) "Choose location" else "Choose file")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
