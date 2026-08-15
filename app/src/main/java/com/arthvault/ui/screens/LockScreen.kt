package com.arthvault.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.arthvault.R
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
 * It is also the first screen anyone sees, and it carries two different jobs. On
 * first run it is the only chance to say what the app is before asking someone to
 * hand over their SMS inbox, so it introduces itself in full. On every unlock after
 * that it is a turnstile standing between someone and the thing they opened the app
 * to do, so it shrinks to the mark, one line and the button. A feature tour shown
 * daily is an obstacle, not an explanation.
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
    // Centred while the content is shorter than the viewport, scrollable once it is
    // not. A plain centred Column silently clipped the first-run copy on a short
    // screen or at a large font scale, and this screen has no way back from that.
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val viewportHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = viewportHeight)
                    .padding(horizontal = Spacing.loose, vertical = Spacing.section),
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
    }
}

/**
 * The app mark.
 *
 * Drawn from the launcher's own foreground vector rather than a second copy, so the
 * icon on the home screen and the mark on this screen cannot drift apart. It carries
 * its own palette — the shield and the rupee are fixed brand colours, not theme
 * roles — which is why it is not tinted to the colour scheme.
 */
@Composable
private fun AppLogo(size: Dp) {
    Image(
        painter = painterResource(R.drawable.ic_launcher_foreground),
        contentDescription = null,
        modifier = Modifier.size(size)
    )
}

@Composable
private fun LockedContent(
    message: String?,
    pendingCount: Int,
    isFirstRun: Boolean,
    onUnlock: () -> Unit
) {
    // 128dp draws a shield of about 66: the vector reserves a wide transparent
    // margin for the launcher's adaptive mask, so the art is roughly half the box
    // it is given. The same margin is why the spacer below it is small.
    AppLogo(size = 128.dp)
    Spacer(Modifier.height(Spacing.tight))

    Text(
        text = "Arth Vault",
        style = MaterialTheme.typography.headlineLarge,
        color = MaterialTheme.colorScheme.onBackground
    )
    Spacer(Modifier.height(Spacing.hairline))
    Text(
        text = "Your bank SMS, turned into a private ledger",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (isFirstRun) {
        Spacer(Modifier.height(Spacing.section))
        WelcomeFeatures()
    }

    Spacer(Modifier.height(Spacing.loose))
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
        Icon(Icons.Outlined.Fingerprint, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Spacing.tight))
        Text(if (isFirstRun) "Create vault" else "Unlock")
    }

    if (isFirstRun) {
        Spacer(Modifier.height(Spacing.snug))
        Text(
            text = "Arth Vault reads your SMS only to build this ledger, and asks for " +
                "that permission when you first scan.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * What the app does, said once, before anyone is asked to trust it with an inbox.
 *
 * Four claims, each one the app actually keeps — the last is the load-bearing one
 * and is structural rather than a promise: `INTERNET` is not declared and is stripped
 * at manifest-merge time, which `NetworkEgressGuardTest` asserts against the merged
 * manifest on every build.
 */
@Composable
private fun WelcomeFeatures() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(Spacing.standard),
            verticalArrangement = Arrangement.spacedBy(Spacing.standard)
        ) {
            FeatureRow(
                icon = Icons.Outlined.ReceiptLong,
                title = "Builds itself from your inbox",
                body = "Bank messages already on this phone become a searchable ledger. " +
                    "Nothing is entered twice."
            )
            FeatureRow(
                icon = Icons.Outlined.Analytics,
                title = "Shows where the money went",
                body = "Spend by category, month-end pace, recurring subscriptions and the " +
                    "price rises hidden in them."
            )
            FeatureRow(
                icon = Icons.Outlined.Lock,
                title = "Encrypted, and opened only by you",
                body = "The ledger is sealed with a key the secure hardware releases after " +
                    "you authenticate — and at no other time."
            )
            FeatureRow(
                icon = Icons.Outlined.CloudOff,
                title = "Cannot reach the internet",
                body = "The app declares no network permission at all, so your finances are " +
                    "unable to leave this device."
            )
        }
    }
}

@Composable
private fun FeatureRow(
    icon: ImageVector,
    title: String,
    body: String
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Icon(
            imageVector = icon,
            // The heading beside it already names the feature; announcing the icon
            // too would read every row twice.
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = Spacing.hairline)
                .size(22.dp)
        )
        Spacer(Modifier.width(Spacing.snug))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(Spacing.hairline))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
        imageVector = Icons.Outlined.Warning,
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
        imageVector = Icons.Outlined.Warning,
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
