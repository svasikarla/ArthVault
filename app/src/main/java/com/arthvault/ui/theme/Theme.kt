package com.arthvault.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/**
 * Gold in both themes.
 *
 * `primary` used to be gold in dark and emerald in light, so the FAB, the scan
 * button and every active state changed hue when the user switched themes — and
 * the light-mode primary was the same green that signals income, which made the
 * primary button read as a "success" control. The identity is Obsidian & Gold;
 * it is gold in both, at the tone each ground can carry.
 *
 * Every role M3 defines is set. Leaving them unset does not mean "use the app's
 * colours" — it means "use the M3 baseline palette", which is purple. That
 * baseline was what `NavigationBar` (`surfaceContainer`), every `AlertDialog`
 * (`surfaceContainerHigh`) and every `OutlinedTextField` border (`outline`)
 * actually rendered with.
 */
private val DarkColorScheme = darkColorScheme(
    primary = GoldGlow,
    onPrimary = ObsidianBackground,
    primaryContainer = GoldContainerDark,
    onPrimaryContainer = GoldGlowText,
    inversePrimary = GoldInk,

    secondary = OnObsidianVariant,
    onSecondary = ObsidianBackground,
    secondaryContainer = ObsidianContainerHigh,
    onSecondaryContainer = OnObsidian,

    tertiary = InfoDark,
    onTertiary = ObsidianBackground,
    tertiaryContainer = Color(0xFF1E1B4B),
    onTertiaryContainer = InfoDark,

    background = ObsidianBackground,
    onBackground = OnObsidian,

    surface = ObsidianSurface,
    onSurface = OnObsidian,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = OnObsidianVariant,
    surfaceTint = GoldGlow,

    // The tonal ladder. Containers step in lightness, not hue — that is the M3
    // elevation language, and the app had none of it.
    surfaceContainerLowest = ObsidianContainerLowest,
    surfaceContainerLow = ObsidianContainerLow,
    surfaceContainer = ObsidianSurface,
    surfaceContainerHigh = ObsidianContainerHigh,
    surfaceContainerHighest = ObsidianContainerHighest,
    surfaceBright = ObsidianContainerHighest,
    surfaceDim = ObsidianBackground,

    inverseSurface = OnObsidian,
    inverseOnSurface = ObsidianSurface,

    outline = ObsidianOutline,
    outlineVariant = ObsidianOutlineVariant,
    scrim = Color(0xFF000000),

    error = NegativeDark,
    onError = ObsidianBackground,
    errorContainer = Color(0xFF450A0A),
    onErrorContainer = NegativeDark,
)

private val LightColorScheme = lightColorScheme(
    primary = GoldInk,
    onPrimary = Color.White,
    primaryContainer = GoldContainerLight,
    onPrimaryContainer = Color(0xFF7C2D12),
    inversePrimary = GoldGlow,

    secondary = OnCanvasVariant,
    onSecondary = Color.White,
    secondaryContainer = CanvasContainerHigh,
    onSecondaryContainer = OnCanvas,

    tertiary = InfoLight,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE0E7FF),
    onTertiaryContainer = Color(0xFF312E81),

    background = CanvasBackground,
    onBackground = OnCanvas,

    surface = CanvasSurface,
    onSurface = OnCanvas,
    surfaceVariant = CanvasSurfaceVariant,
    onSurfaceVariant = OnCanvasVariant,
    surfaceTint = GoldInk,

    surfaceContainerLowest = CanvasContainerLowest,
    surfaceContainerLow = CanvasContainerLow,
    surfaceContainer = CanvasSurfaceVariant,
    surfaceContainerHigh = CanvasContainerHigh,
    surfaceContainerHighest = CanvasContainerHighest,
    surfaceBright = CanvasSurface,
    surfaceDim = CanvasContainerHighest,

    inverseSurface = OnCanvas,
    inverseOnSurface = CanvasSurface,

    outline = CanvasOutline,
    outlineVariant = CanvasOutlineVariant,
    scrim = Color(0xFF000000),

    error = NegativeLight,
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

/**
 * Dynamic colour stays off deliberately.
 *
 * Material You would harmonise the app with the user's wallpaper at the cost of the
 * one thing this app's surface is trying to communicate — that it is a sealed vault
 * with a fixed identity. The branded scheme also guarantees the contrast ratios above
 * hold, which a wallpaper-derived palette cannot.
 */
@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val semantics = if (darkTheme) DarkSemantics else LightSemantics

    CompositionLocalProvider(LocalVaultSemantics provides semantics) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = AppShapes,
            content = content
        )
    }
}
