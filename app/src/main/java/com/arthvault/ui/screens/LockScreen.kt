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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arthvault.ui.vault.VaultState

/**
 * T3.2 — the gate in front of the ledger.
 *
 * This is not decorative. The database passphrase is sealed by a Keystore key
 * created with `setUserAuthenticationRequired(true)`, so until the prompt behind
 * this screen succeeds the secure hardware will not perform the unwrap and there
 * is no key with which to read a single row.
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
            .padding(32.dp),
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
        modifier = Modifier.size(64.dp)
    )
    Spacer(Modifier.height(24.dp))
    Text(
        text = "Arth Vault",
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = if (isFirstRun) {
            "Authenticate to create your encrypted ledger. The key is held in this " +
                "device's secure hardware and never leaves it."
        } else {
            "Your ledger is encrypted. Authenticate to unlock it."
        },
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (pendingCount > 0) {
        Spacer(Modifier.height(20.dp))
        Text(
            text = if (pendingCount == 1) {
                "1 message arrived while you were away"
            } else {
                "$pendingCount messages arrived while you were away"
            },
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                .padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }

    if (message != null) {
        Spacer(Modifier.height(20.dp))
        Text(
            text = message,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.error
        )
    }

    Spacer(Modifier.height(36.dp))
    Button(
        onClick = onUnlock,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(0.dp))
        Text(
            text = if (isFirstRun) "  Create vault" else "  Unlock",
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun UnlockingContent() {
    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(20.dp))
    Text(
        text = "Opening the ledger and catching up on new messages…",
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun NeedsLockContent(onOpenSecuritySettings: () -> Unit) {
    Icon(
        imageVector = Icons.Default.Warning,
        contentDescription = null,
        tint = Color(0xFFFFA726),
        modifier = Modifier.size(56.dp)
    )
    Spacer(Modifier.height(20.dp))
    Text(
        text = "A screen lock is required",
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = "Arth Vault seals your ledger with a key the secure hardware will only " +
            "release after you authenticate. With no PIN, pattern or biometric set, " +
            "there is nothing for it to check — and storing your finances unprotected " +
            "is not an option this app offers.",
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(28.dp))
    Button(onClick = onOpenSecuritySettings, modifier = Modifier.fillMaxWidth()) {
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
    Spacer(Modifier.height(20.dp))
    Text(
        text = "The vault key is gone",
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = "The secure hardware no longer holds the key that unlocks this ledger — " +
            "usually because the screen lock was removed or the device was reset. " +
            "The encrypted data cannot be recovered without it.\n\n" +
            "If you have a backup file, discard this vault and restore it afterwards.",
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(28.dp))
    OutlinedButton(
        onClick = onDiscardVault,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
    ) {
        Text("Discard the unreadable vault")
    }
}
