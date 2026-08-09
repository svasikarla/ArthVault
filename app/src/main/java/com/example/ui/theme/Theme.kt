package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = ArthGold,
    onPrimary = VaultObsidian,
    primaryContainer = ArthGoldDark,
    onPrimaryContainer = ArthGoldContainer,
    secondary = ArthEmerald,
    onSecondary = VaultObsidian,
    secondaryContainer = ArthEmeraldDark,
    onSecondaryContainer = ArthMintContainer,
    tertiary = ArthIndigoLight,
    onTertiary = VaultObsidian,
    background = VaultObsidian,
    onBackground = TextPrimaryDark,
    surface = VaultSurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = VaultSurfaceVariantDark,
    onSurfaceVariant = TextSecondaryDark,
    outlineVariant = VaultCardBorderDark,
    error = ArthCrimson,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = ArthEmeraldDark,
    onPrimary = Color.White,
    primaryContainer = ArthMintContainer,
    onPrimaryContainer = ArthEmeraldDark,
    secondary = ArthGoldDark,
    onSecondary = Color.White,
    secondaryContainer = ArthGoldContainer,
    onSecondaryContainer = ArthGoldDark,
    tertiary = ArthIndigo,
    onTertiary = Color.White,
    background = VaultCanvasLight,
    onBackground = TextPrimaryLight,
    surface = VaultSurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = VaultSurfaceVariantLight,
    onSurfaceVariant = TextSecondaryLight,
    outlineVariant = VaultCardBorderLight,
    error = ArthCrimson,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Maintain Arth Vault signature brand identity
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

