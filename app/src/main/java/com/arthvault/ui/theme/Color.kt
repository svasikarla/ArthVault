package com.arthvault.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The Arth Vault palette.
 *
 * Three groups, and the separation between them is the whole point:
 *
 *  - **Neutrals** carry ~90% of every screen. Cool slate, biased away from the warm
 *    accent so the two never muddle.
 *  - **Brand** is gold, and gold only. One accent, spent sparingly.
 *  - **Semantics** are income/spend/info/caution. They are *not* brand colours and
 *    must never be used for decoration, and brand must never be used for status.
 *
 * Every colour below exists as a light/dark pair. The previous palette had one hex
 * per colour used identically in both themes; they were tuned against the obsidian
 * canvas and measured 2.2:1–4.5:1 against the light one, which fails WCAG AA. The
 * ratios in the comments are computed against the surface each tone actually lands
 * on — #FFFFFF for light, #111827 for dark.
 *
 * Do not import these constants into screens. Go through [VaultSemantics] (semantic
 * intent) or `MaterialTheme.colorScheme` (everything else) so the value can never be
 * taken from the wrong theme.
 */

// --- neutrals: dark ("obsidian & slate") ----------------------------------

internal val ObsidianBackground = Color(0xFF090D16)
internal val ObsidianContainerLowest = Color(0xFF070A11)
internal val ObsidianContainerLow = Color(0xFF0D131C)
internal val ObsidianSurface = Color(0xFF111827)
internal val ObsidianContainerHigh = Color(0xFF1A2333)
internal val ObsidianContainerHighest = Color(0xFF232E40)
internal val ObsidianSurfaceVariant = Color(0xFF1F2937)
internal val ObsidianOutline = Color(0xFF6B7280)
internal val ObsidianOutlineVariant = Color(0xFF374151)

internal val OnObsidian = Color(0xFFF9FAFB)          // 17.4:1 on surface
internal val OnObsidianVariant = Color(0xFF9CA3AF)   //  6.99:1 on surface

// --- neutrals: light ------------------------------------------------------

internal val CanvasBackground = Color(0xFFF8FAFC)
internal val CanvasContainerLowest = Color(0xFFFFFFFF)
internal val CanvasContainerLow = Color(0xFFF8FAFC)
internal val CanvasSurface = Color(0xFFFFFFFF)
internal val CanvasContainerHigh = Color(0xFFEEF2F7)
internal val CanvasContainerHighest = Color(0xFFE7ECF3)
internal val CanvasSurfaceVariant = Color(0xFFF1F5F9)
internal val CanvasOutline = Color(0xFF64748B)
internal val CanvasOutlineVariant = Color(0xFFE2E8F0)

internal val OnCanvas = Color(0xFF111827)            // 16.9:1 on surface
internal val OnCanvasVariant = Color(0xFF4B5563)     //  7.56:1 on surface

// --- brand: gold, one accent, two tones -----------------------------------

/** Light-theme primary. 5.02:1 on white, and white on it is also 5.02:1. */
internal val GoldInk = Color(0xFFB45309)

/** Dark-theme primary. 8.26:1 on the obsidian surface. */
internal val GoldGlow = Color(0xFFF59E0B)

/** Gold as small text on dark. 10.63:1 — used where [GoldGlow] would be marginal. */
internal val GoldGlowText = Color(0xFFFBBF24)

internal val GoldContainerLight = Color(0xFFFEF3C7)
internal val GoldContainerDark = Color(0xFF422006)

// --- semantics: light tones (on #FFFFFF) ----------------------------------

internal val PositiveLight = Color(0xFF047857)   // 5.48:1
internal val NegativeLight = Color(0xFFB91C1C)   // 6.47:1
internal val InfoLight = Color(0xFF4338CA)       // 7.90:1
internal val CautionLight = Color(0xFF92400E)    // 7.36:1

// --- semantics: dark tones (on #111827) -----------------------------------

internal val PositiveDark = Color(0xFF34D399)    // 9.23:1
internal val NegativeDark = Color(0xFFF87171)    // 6.41:1
internal val InfoDark = Color(0xFF818CF8)        // 5.95:1
internal val CautionDark = Color(0xFFFBBF24)     // 10.63:1

// --- categorical: charts only ---------------------------------------------

/**
 * The category ramp for the spending donut and any future series.
 *
 * Deliberately muted and deliberately *not* the semantic set. The donut previously
 * drew its slices from `listOf(ArthGold, ArthEmerald, ArthIndigo, ArthCrimson, …)`,
 * so a "Groceries" slice rendered in the same red that means declined, duplicated
 * and overspent, and the slice beside it in the green that means income. Status was
 * reading as category. These six are distinguishable from each other without
 * borrowing meaning from anything else on the screen.
 */
internal val CategoricalLight = listOf(
    Color(0xFFB07A14), // gold — first slice stays on-brand, it is the largest
    Color(0xFF4A7FA8), // dusty blue
    Color(0xFFA25F3E), // clay
    Color(0xFF5E7F4C), // sage
    Color(0xFF7A5E96), // muted violet
    Color(0xFF3F7F76), // muted teal
)

internal val CategoricalDark = listOf(
    Color(0xFFE0A93B),
    Color(0xFF7FA8C9),
    Color(0xFFC98B6B),
    Color(0xFF8FA97E),
    Color(0xFFA98FC0),
    Color(0xFF6FA9A0),
)
