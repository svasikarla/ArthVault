package com.arthvault.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The 4dp grid.
 *
 * Card padding across the app used to be 18dp (×15), 14dp (×9), 16dp (×7), 12dp,
 * 10dp — the two most common values both off-grid — and spacers ran 2, 3, 6, 10,
 * 14, 18 and 36dp. No single one of those is visible; together they mean two cards
 * on the same screen have optically different insets and vertical rhythm inside a
 * card never repeats twice.
 *
 * Every value below is on the grid, and there are deliberately only seven of them.
 */
object Spacing {
    /** Between a label and the value it labels. */
    val hairline = 4.dp

    /** Between list items; between an icon and its adjacent text. */
    val tight = 8.dp

    /** Between related rows inside a card. */
    val snug = 12.dp

    /** Screen margin, card inset, and the gap between sections. The default. */
    val standard = 16.dp

    /** Between unrelated blocks inside one card; above a divided sub-section. */
    val loose = 24.dp

    /** Screen-level breathing room; the tail spacer at the end of a scroll. */
    val section = 32.dp

    /** Empty-state and lock-screen insets, where the content is centred and alone. */
    val page = 48.dp
}
