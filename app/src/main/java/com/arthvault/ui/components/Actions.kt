package com.arthvault.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.arthvault.ui.theme.Spacing

/**
 * A top-bar action that says what it does.
 *
 * These were icon-only after the ledger's controls moved out of the header card and
 * into the app bar: a scanner glyph and a sparkle glyph, with the meaning available
 * only to TalkBack. Moving the actions was right — three filled buttons and a FAB were
 * all competing to be the primary action — but dropping their labels was not. The
 * component catalog is blunt about it: don't rely on an icon alone to convey critical
 * meaning, and "Scan SMS inbox" is the action that fills the entire app with data.
 *
 * A text button in the actions slot costs a little width and removes the guesswork.
 */
@Composable
fun BarAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null, // the label beside it already says this
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(Spacing.hairline))
        Text(text = label, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * An icon-only button that can still tell you what it is.
 *
 * For actions that repeat on every row of a list, where a visible label on each one
 * would be pure noise — editing a category, voiding a transaction. Long-press (or
 * hover) reveals the label, and the same string is the content description, so the
 * meaning is available by touch, by pointer and by screen reader rather than only to
 * whoever already knew.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TooltipIconButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(
            onClick = onClick,
            // 48dp, the touch-target minimum. These were 30dp.
            modifier = modifier.size(48.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(18.dp),
                tint = tint,
            )
        }
    }
}
