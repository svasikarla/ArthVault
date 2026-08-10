package com.arthvault.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.arthvault.ui.theme.VaultTheme

/**
 * The merchant's initials, in a circle tinted by category.
 *
 * Every row of the feed used to open with the same 44dp circle holding an up or down
 * arrow tinted by direction — forty near-identical discs carrying one bit that the
 * amount's sign and the amount's colour were each already carrying. The column was
 * pure texture with nothing in it to catch on.
 *
 * Initials plus a category tint make the feed scannable by shape before it is read:
 * the three teal `SW`s are the same shop, the run of clay-coloured discs is a week of
 * fuel. Nothing here conveys status — the ramp is [VaultSemantics.categorical], which
 * exists precisely so that a category can be coloured without borrowing the meaning of
 * income, declined or overspent.
 */
@Composable
fun MerchantAvatar(
    merchant: String,
    category: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    val ramp = VaultTheme.semantics.categorical
    val tint = ramp[colorIndexFor(category, ramp.size)]

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.16f))
            // A hairline at the same hue: on the light canvas a 16%-alpha fill on white
            // is almost not there, and the disc loses its edge.
            .border(1.dp, tint.copy(alpha = 0.28f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initialsOf(merchant),
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Up to two letters standing in for a merchant name.
 *
 * `SWIGGY` gives `SW`, `AMAZON INDIA` gives `AI`, `ICICI Bank Credit` gives `IB`.
 * Splitting on non-alphanumerics rather than whitespace matters because parsed
 * merchants arrive as `BLINKIT-GROCERY` and `PAYTM*UBER` about as often as they
 * arrive with spaces.
 */
internal fun initialsOf(merchant: String): String {
    val words = merchant.split(NON_WORD).filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

/**
 * A category's slot in the ramp.
 *
 * `String.hashCode` is specified by the JDK rather than left to the implementation, so
 * the same category is the same colour on every device and across every launch — which
 * is the whole value of the tint. `floorMod`, not `%`, because hash codes go negative.
 */
internal fun colorIndexFor(category: String, rampSize: Int): Int =
    Math.floorMod(category.hashCode(), rampSize)
