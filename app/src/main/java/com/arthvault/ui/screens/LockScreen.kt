package com.arthvault.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.arthvault.ui.theme.Spacing
import com.arthvault.ui.theme.VaultTheme
import com.arthvault.ui.vault.VaultState

/**
 * T3.2 — the gate in front of the ledger.
 *
 * This is not decorative. The database passphrase is sealed by a Keystore key
 * created with `setUserAuthenticationRequired(true)`, so until the prompt behind
 * this screen succeeds the secure hardware will not perform the unwrap and there
 * is no key with which to read a single row.
 *
 * It is also the first screen anyone sees, and it was the only one not on the type
 * system — nine hardcoded `fontSize` values between 13 and 28sp.
 */
@Composable
fun LockScreen(
    state: VaultState,
    pendingCount: Int,
    isFirstRun: Boolean,
    onUnlock: () -> Unit,
    onOpenSecuritySettings: () -> Unit,
    onDiscardVault: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(Spacing.section),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (state) {
            is VaultState.NeedsDeviceLock -> NeedsLockContent(onOpenSecuritySettings)
            is VaultState.KeyInvalidated -> InvalidatedContent(onDiscardVault)
            is VaultState.Unlocking -> UnlockingContent()
            else -> LockedContent(
                message = (state as? VaultState.Locked)?.message,
                pendingCount = pendingCount,
                isFirstRun = isFirstRun,
                onUnlock = onUnlock
            )
        }
    }
}

@Composable
private fun LockedContent(
    message: String?,
    pendingCount: Int,
    isFirstRun: Boolean,
    onUnlock: () -> Unit
) {
    Icon(
        imageVector = Icons.Default.Lock,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(56.dp)
    )
    Spacer(Modifier.height(Spacing.loose))
    Text(
        text = "Arth Vault",
        style = MaterialTheme.typography.headlineLarge,
        color = MaterialTheme.colorScheme.onBackground
    )
    Spacer(Modifier.height(Spacing.tight))
    Text(
        text = if (isFirstRun) {
            "Authenticate to create your encrypted ledger. The key is held in this " +
                "device's secure hardware and never leaves it."
        } else {
            "Your ledger is encrypted. Authenticate to unlock it."
        },
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (pendingCount > 0) {
        Spacer(Modifier.height(Spacing.loose))
        Text(
            text = if (pendingCount == 1) {
                "1 message arrived while you were away"
            } else {
                "$pendingCount messages arrived while you were away"
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = Spacing.snug, vertical = Spacing.tight)
        )
    }

    if (message != null) {
        Spacer(Modifier.height(Spacing.loose))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.error
        )
    }

    Spacer(Modifier.height(Spacing.section))
    Button(
        onClick = onUnlock,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small
    ) {
        Icon(Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Spacing.tight))
        Text(if (isFirstRun) "Create vault" else "Unlock")
    }
}

@Composable
private fun UnlockingContent() {
    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(Spacing.loose))
    Text(
        text = "Opening the ledger and catching up on new messages…",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun NeedsLockContent(onOpenSecuritySettings: () -> Unit) {
    Icon(
        imageVector = Icons.Default.Warning,
        contentDescription = null,
        tint = VaultTheme.semantics.caution,
        modifier = Modifier.size(56.dp)
    )
    Spacer(Modifier.height(Spacing.loose))
    Text(
        text = "A screen lock is required",
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onBackground
    )
    Spacer(Modifier.height(Spacing.snug))
    Text(
        text = "Arth Vault seals your ledger with a key the secure hardware will only " +
            "release after you authenticate. With no PIN, pattern or biometric set, " +
            "there is nothing for it to check — and storing your finances unprotected " +
            "is not an option this app offers.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(Spacing.loose))
    Button(
        onClick = onOpenSecuritySettings,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small
    ) {
        Text("Open security settings")
    }
}

@Composable
private fun InvalidatedContent(onDiscardVault: () -> Unit) {
    Icon(
        imageVector = Icons.Default.Warning,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.error,
        modifier = Modifier.size(56.dp)
    )
    Spacer(Modifier.height(Spacing.loose))
    Text(
        text = "The vault key is gone",
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onBackground
    )
    Spacer(Modifier.height(Spacing.snug))
    Text(
        text = "The secure hardware no longer holds the key that unlocks this ledger — " +
            "usually because the screen lock was removed or the device was reset. " +
            "The encrypted data cannot be recovered without it.\n\n" +
            "If you have a backup file, discard this vault and restore it afterwards.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(Spacing.loose))
    OutlinedButton(
        onClick = onDiscardVault,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
    ) {
        Text("Discard the unreadable vault")
    }
}
