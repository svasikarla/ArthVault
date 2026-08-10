package com.arthvault.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.arthvault.ui.theme.Spacing

/**
 * A designed empty state — icon, one-line explanation, and the action that fixes it.
 *
 * The two existing empty states were most of the way there but stopped short of the
 * last part: the empty ledger told the user to "tap Seed Sample SMS above" instead of
 * simply giving them the button. An empty state that names an action it doesn't offer
 * is a dead end with good manners.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    iconTint: androidx.compose.ui.graphics.Color? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.section),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.snug),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = iconTint ?: MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Spacing.hairline))
            Button(onClick = onAction, shape = MaterialTheme.shapes.small) {
                Text(actionLabel)
            }
        }
    }
}

/**
 * A placeholder block that shimmers, unless the user has asked it not to.
 *
 * The component catalog is explicit that skeletons matching the real layout beat
 * spinners — they feel faster and they say what is about to arrive. The analytics
 * recompute runs six passes over the whole ledger and was acknowledged only by a
 * 2dp progress line.
 *
 * `fillMaxWidth` is applied before the caller's modifier so a caller that wants a
 * fixed width (`Modifier.width(72.dp)`) still wins.
 */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    height: Dp = 16.dp,
    widthFraction: Float = 1f,
) {
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    val alpha = if (reduceMotion()) {
        0.7f
    } else {
        val transition = rememberInfiniteTransition(label = "skeleton")
        transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 0.95f,
            animationSpec = infiniteRepeatable(
                animation = tween(900),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "skeleton-alpha",
        ).value
    }

    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .then(modifier)
            .height(height)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(base.copy(alpha = alpha)),
    )
}

/** The analytics screen while it recomputes: the shape of what is coming, not a spinner. */
@Composable
fun AnalyticsSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Recalculating your figures" },
        verticalArrangement = Arrangement.spacedBy(Spacing.standard),
    ) {
        VaultCard {
            SkeletonBlock(height = 14.dp, widthFraction = 0.35f)
            Spacer(Modifier.height(Spacing.snug))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.snug)) {
                repeat(3) {
                    Column(Modifier.weight(1f)) {
                        SkeletonBlock(height = 10.dp, widthFraction = 0.6f)
                        Spacer(Modifier.height(Spacing.tight))
                        SkeletonBlock(height = 22.dp, widthFraction = 0.9f)
                    }
                }
            }
            Spacer(Modifier.height(Spacing.standard))
            SkeletonBlock(height = 12.dp)
        }
        VaultCard {
            SkeletonBlock(height = 14.dp, widthFraction = 0.4f)
            Spacer(Modifier.height(Spacing.standard))
            SkeletonBlock(height = 110.dp)
        }
    }
}

/** A single placeholder row, matching the transaction card's shape. */
@Composable
fun RowSkeleton(modifier: Modifier = Modifier) {
    VaultRowCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
            Spacer(Modifier.width(Spacing.snug))
            Column(Modifier.weight(1f)) {
                SkeletonBlock(height = 14.dp, widthFraction = 0.55f)
                Spacer(Modifier.height(Spacing.tight))
                SkeletonBlock(height = 10.dp, widthFraction = 0.35f)
            }
            SkeletonBlock(modifier = Modifier.width(72.dp), height = 16.dp)
        }
    }
}
