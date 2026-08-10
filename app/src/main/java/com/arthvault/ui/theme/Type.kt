package com.arthvault.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.sp
import com.arthvault.R

/**
 * Inter, bundled — not downloaded.
 *
 * Downloadable Fonts would resolve through a Play Services provider at runtime. This
 * app ships without `android.permission.INTERNET` and renders a live "no network
 * access" certificate on the Vault screen read from the merged manifest; fetching a
 * typeface would undercut the one claim the product is built around. So the variable
 * file lives in `res/font` and costs ~860 KB, which is the correct trade here.
 *
 * Inter specifically, over the alternatives, for three reasons that matter to a ledger:
 * it ships genuine tabular figures (`tnum`) — the app aligns ~50 money values in
 * columns and they used to jitter as digits changed; it stays legible at the 11–12sp
 * the overlines and chart captions live at; and `zero` + `cv05` disambiguate 0/O and
 * 1/l/I, which is worth having beside raw bank SMS full of reference numbers.
 *
 * The file carries `opsz` as well as `wght`, so display sizes get their intended
 * weight balance rather than a scaled-up body cut.
 */
// FontVariation.Setting for a non-standard axis name is still marked experimental;
// the `opsz` axis itself is a stable OpenType feature and the font ships it.
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun interOf(weight: Int, opticalSize: Float) = Font(
    resId = R.font.inter_variable,
    weight = FontWeight(weight),
    style = FontStyle.Normal,
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.Setting("opsz", opticalSize),
    ),
)

/** Body/UI optical size — Inter's `opsz` axis runs 14–32. */
private val Inter = FontFamily(
    interOf(400, 14f),
    interOf(500, 14f),
    interOf(600, 14f),
    interOf(700, 14f),
)

/** Display optical size, for anything 24sp and up. */
private val InterDisplay = FontFamily(
    interOf(600, 28f),
    interOf(700, 28f),
)

/**
 * Tabular, slashed-zero figures.
 *
 * `tnum` fixes every digit to the same advance width so a total changing from
 * ₹9,999 to ₹10,000 does not shift the column it sits in. In a screen that is
 * mostly aligned numbers this is the single most visible typographic setting.
 */
private const val TABULAR = "tnum, zero"

/**
 * The scale: fifteen roles on one ratio.
 *
 * The previous file defined nine and left six to the M3 defaults — including
 * `bodySmall`, which is referenced 50 times and is the app's single most-used
 * style. It was silently resolving to 12sp/16sp and carrying multi-sentence
 * explanatory copy at the legibility floor. Body work now starts at 14sp and
 * 11–12sp is reserved for genuine captions and overlines.
 *
 * Weight lives here, not at the call site. There were 40-odd inline
 * `fontWeight = FontWeight.ExtraBold/Bold/SemiBold` overrides across the screens;
 * a role whose weight is overridden everywhere is not a role.
 */
val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = InterDisplay, fontWeight = FontWeight.Bold,
        fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-1.2).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = InterDisplay, fontWeight = FontWeight.Bold,
        fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-1.0).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = InterDisplay, fontWeight = FontWeight.Bold,
        fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.7).sp,
    ),

    headlineLarge = TextStyle(
        fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.35).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp,
    ),

    titleLarge = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = (-0.1).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
    ),

    bodyLarge = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp,
    ),

    labelLarge = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp,
    ),
    // The ALL-CAPS overline: INCOME / SPENT / NET / SAFE TO SPEND. Positive
    // tracking, because caps set at their natural spacing read as a jumble.
    labelSmall = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.8.sp,
    ),
)

// --- money -----------------------------------------------------------------

/**
 * Amounts get their own roles, and every ₹ figure in the app uses one of them.
 *
 * Nothing distinguishes an amount from a heading structurally, so without these
 * a money value renders in whatever role happened to be nearby and loses its
 * tabular figures. Three sizes cover every use: the cash-position headline, a
 * list row, and an inline stat.
 */
val Typography.moneyLarge: TextStyle
    get() = displaySmall.copy(fontFeatureSettings = TABULAR)

val Typography.moneyMedium: TextStyle
    get() = titleLarge.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = TABULAR)

val Typography.moneySmall: TextStyle
    get() = titleMedium.copy(fontFeatureSettings = TABULAR)

/** Dates, counts, percentages in aligned columns — tabular but not emphasised. */
val Typography.numeric: TextStyle
    get() = bodySmall.copy(fontFeatureSettings = TABULAR)

/** Raw SMS payloads and regex patterns. The one place a monospace face belongs. */
val Typography.payload: TextStyle
    get() = bodySmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 0.sp)
