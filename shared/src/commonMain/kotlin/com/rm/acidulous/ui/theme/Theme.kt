package com.rm.acidulous.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/** The theme the user chose. Auto follows the system. */
enum class ThemeMode { Auto, Light, Dark, HighContrast }

/**
 * Material's colour scheme, built from ours. Most of the app draws its own
 * colours, but dialogs, menus, text fields and their buttons come from
 * Material and read the scheme, so it has to match.
 */
private fun scheme(c: AcidColors) = if (c.dark) {
    darkColorScheme(
        primary = c.accent, onPrimary = c.onAccent,
        secondary = c.teal, onSecondary = c.onAccent,
        tertiary = c.pink, onTertiary = c.onAccent,
        background = c.bg, onBackground = c.textHi,
        surface = c.card, onSurface = c.textHi,
        surfaceVariant = c.control, onSurfaceVariant = c.textDim,
        // M3 gives dialogs, menus and sheets their own surface roles, and if they
        // aren't set they're tinted from the primary palette.
        surfaceContainerLowest = c.bgDeep, surfaceContainerLow = c.panel,
        surfaceContainer = c.card, surfaceContainerHigh = c.card,
        surfaceContainerHighest = c.cardHi,
        surfaceBright = c.cardHi, surfaceDim = c.bgDeep,
        inverseSurface = c.textHi, inverseOnSurface = c.bg,
        outline = c.line, outlineVariant = c.line,
        error = c.red, onError = c.onAccent,
        scrim = c.bgDeep,
    )
} else {
    lightColorScheme(
        primary = c.accent, onPrimary = c.card,
        secondary = c.teal, onSecondary = c.card,
        tertiary = c.pink, onTertiary = c.card,
        background = c.bg, onBackground = c.textHi,
        surface = c.card, onSurface = c.textHi,
        surfaceVariant = c.control, onSurfaceVariant = c.textDim,
        surfaceContainerLowest = c.card, surfaceContainerLow = c.cardAlt,
        surfaceContainer = c.card, surfaceContainerHigh = c.card,
        surfaceContainerHighest = c.cardHi,
        surfaceBright = c.card, surfaceDim = c.sunken,
        inverseSurface = c.textHi, inverseOnSurface = c.card,
        outline = c.line, outlineVariant = c.line,
        error = c.red, onError = c.card,
        scrim = c.textFaint,
    )
}

/**
 * [mode] is the setting; Auto asks the OS. Dynamic colour isn't used, since
 * the app has its own look.
 */
@Composable
fun AcidulousTheme(mode: ThemeMode = ThemeMode.Dark, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.Auto -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark, ThemeMode.HighContrast -> true
    }
    val colors = when {
        mode == ThemeMode.HighContrast -> HighContrastColors
        dark -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalAcidColors provides colors) {
        MaterialTheme(colorScheme = scheme(colors), typography = Typography) {
            // Every `clickable` shows a ring while it has keyboard focus, see
            // ui/KeyControls.kt. Presses still ripple as usual.
            CompositionLocalProvider(
                androidx.compose.foundation.LocalIndication provides
                    com.rm.acidulous.ui.FocusRingIndication(androidx.compose.material3.ripple(), colors.accent),
                content = content,
            )
        }
    }
}
