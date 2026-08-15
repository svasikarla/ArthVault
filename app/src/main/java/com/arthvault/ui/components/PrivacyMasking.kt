package com.arthvault.ui.components

import androidx.compose.runtime.compositionLocalOf
import java.text.NumberFormat
import java.util.Locale

/**
 * CompositionLocal providing whether sensitive financial figures are masked.
 */
val LocalPrivacyMasking = compositionLocalOf { false }

/**
 * Formats a currency value, masking it as `₹ • • • •` when [isMasked] is true.
 */
fun formatCurrencyMasked(amount: Double, isMasked: Boolean, symbol: String = "₹"): String {
    if (isMasked) {
        return "$symbol • • • •"
    }
    val formatter = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"))
    return formatter.format(amount)
}
