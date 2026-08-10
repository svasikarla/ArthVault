package com.arthvault.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The colours that carry meaning rather than identity.
 *
 * These exist because M3's `colorScheme` has no role for "this number is income" or
 * "this projection is not yet reliable", and the app was expressing those by importing
 * raw hex constants (`ArthEmerald`, `ArthCrimson`, `ArthGold`, `ArthIndigo`) straight
 * into composables. A raw constant cannot know which theme it is being drawn in, which
 * is how every one of them ended up failing contrast on the light canvas.
 *
 * Resolving through this class makes the wrong-theme mistake unrepresentable: there is
 * no way to name a colour here without getting the one that matches the surface behind it.
 */
@Immutable
data class VaultSemantics(
    /** Income, credits, a positive net, a passing security check. */
    val positive: Color,
    /** Spend, debits, a negative net, duplicates, destructive actions. */
    val negative: Color,
    /** Forecasts, recurring charges, tap-through affordances — neutral information. */
    val info: Color,
    /** Anomalies, price hikes, an unreliable projection — attention, not failure. */
    val caution: Color,
    /** Chart series colours. Never used for status; see [CategoricalLight]. */
    val categorical: List<Color>,
) {
    /**
     * The tinted fill for a card or pill carrying one of the above.
     *
     * A single alpha, applied one way, replacing the 0.08/0.1/0.12/0.15/0.18 spread
     * that was scattered across the screens.
     */
    fun wash(color: Color): Color = color.copy(alpha = 0.10f)

    /** The hairline for the same card. One alpha, as above. */
    fun edge(color: Color): Color = color.copy(alpha = 0.32f)

    /** Signed money, gauges, deltas: which way is this pointing? */
    fun forAmount(value: Double): Color = if (value >= 0) positive else negative

    /** Ledger direction. Credit is money arriving, debit is money leaving. */
    fun forDirection(isCredit: Boolean): Color = if (isCredit) positive else negative
}

internal val LightSemantics = VaultSemantics(
    positive = PositiveLight,
    negative = NegativeLight,
    info = InfoLight,
    caution = CautionLight,
    categorical = CategoricalLight,
)

internal val DarkSemantics = VaultSemantics(
    positive = PositiveDark,
    negative = NegativeDark,
    info = InfoDark,
    caution = CautionDark,
    categorical = CategoricalDark,
)

internal val LocalVaultSemantics = staticCompositionLocalOf { LightSemantics }

/**
 * Accessors that sit alongside `MaterialTheme`.
 *
 * `VaultTheme.semantics.positive` reads the same way as `MaterialTheme.colorScheme.primary`,
 * which is the point — the two systems should feel like one.
 */
object VaultTheme {
    val semantics: VaultSemantics
        @Composable @ReadOnlyComposable get() = LocalVaultSemantics.current
}
