package com.arthvault.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.arthvault.ui.theme.Spacing

/**
 * The one card in the app.
 *
 * There were eleven call sites repeating `BorderStroke(1.dp, outlineVariant.copy(alpha = …))`
 * with three different alphas, four different corner radii and five different internal
 * paddings. The "same" card was never quite the same card twice. Everything about the
 * container is decided here now; callers supply content and, at most, an accent.
 *
 * @param accent tints the border and fill — pass a semantic colour for a card that
 *   carries status (an alert, a danger zone). Null gives the neutral resting card.
 */
@Composable
fun VaultCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    contentPadding: androidx.compose.ui.unit.Dp = Spacing.standard,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(
            width = 1.dp,
            color = accent?.copy(alpha = 0.32f)
                ?: MaterialTheme.colorScheme.outlineVariant,
        ),
        colors = CardDefaults.cardColors(
            containerColor = accent?.copy(alpha = 0.10f)
                ?: MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/**
 * A list-row card: one step down in radius from [VaultCard], to say "this is an item
 * inside a section" rather than "this is a section".
 */
@Composable
fun VaultRowCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            // A row is one thing to a screen reader, not eight. Without this,
            // TalkBack reads merchant, category, channel, date, balance and amount
            // as six separate unlabelled stops.
            .semantics(mergeDescendants = true) {},
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(
            width = 1.dp,
            color = accent?.copy(alpha = 0.32f)
                ?: MaterialTheme.colorScheme.outlineVariant,
        ),
        colors = CardDefaults.cardColors(
            containerColor = accent?.copy(alpha = 0.10f)
                ?: MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(Spacing.snug), content = content)
    }
}

/**
 * The title line inside a card: icon, title, and an optional second line naming the
 * window or qualifying the claim.
 *
 * The icon is 20dp and tinted by the caller; the title is always `titleMedium` and
 * always carries its weight from the role rather than an inline override.
 */
@Composable
fun CardHeading(
    title: String,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    subtitle: String? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(Spacing.tight))
        }
        Text(text = title, style = MaterialTheme.typography.titleMedium)
    }
    if (subtitle != null) {
        Spacer(Modifier.size(Spacing.hairline))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The one divider style. Used only where whitespace genuinely cannot do the job —
 * inside a card, separating a forecast from the figures it was derived from.
 */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    androidx.compose.material3.HorizontalDivider(
        modifier = modifier,
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** A stack of rows on the grid, so callers stop hand-spacing every `Spacer`. */
@Composable
fun VaultColumn(
    modifier: Modifier = Modifier,
    spacing: androidx.compose.ui.unit.Dp = Spacing.tight,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}
