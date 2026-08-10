package com.arthvault.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * One corner scale for the whole app.
 *
 * `MaterialTheme` was previously built without `shapes`, so every surface free-styled
 * its own radius: 4, 6, 8, 10, 12, 14, 16, 20 and 50dp were all in use, and two cards
 * in the same list differed by 2dp — close enough to read as a mistake rather than a
 * distinction. Five steps, mapped by role, and nothing outside this file picks a number.
 */
val AppShapes = Shapes(
    // Badges, category tags, the DECLINED pill, raw-SMS blocks.
    extraSmall = RoundedCornerShape(8.dp),
    // Chips, buttons, text fields.
    small = RoundedCornerShape(12.dp),
    // List-item cards: transactions, recurring charges, alerts, parser rules.
    medium = RoundedCornerShape(16.dp),
    // Section cards: cash position, charts, category breakdown, vault panels.
    large = RoundedCornerShape(20.dp),
    // Dialogs and bottom sheets.
    extraLarge = RoundedCornerShape(28.dp),
)
