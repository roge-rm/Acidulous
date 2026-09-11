package com.rm.acidulous.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/** What the person chose, which is not the same as what is on screen. */
enum class ThemeMode { Auto, Light, Dark }

/**
 * Material's scheme, built from ours.
 *
 * Nearly everything in this app draws its own colours, but the parts that
 * come out of Material - dialogs, dropdown menus, text fields, the buttons
 * inside them - read the scheme, and while that scheme was the template's
 * purple (with dynamic colour on top, so it took its cue from the phone's
 * wallpaper) those parts never matched the app. A file menu that opened
 * white over a black arranger was the visible half of that.
 */
private fun scheme(c: AcidColors) = if (c.dark) {
    darkColorScheme(
        primary = c.accent, onPrimary = c.onAccent,
        secondary = c.teal, onSecondary = c.onAccent,
        tertiary = c.pink, onTertiary = c.onAccent,
        background = c.bg, onBackground = c.textHi,
        surface = c.card, onSurface = c.textHi,
        surfaceVariant = c.control, onSurfaceVariant = c.textDim,
        // M3 gives a dialog, a menu and a sheet their own surface roles, and
        // left unset they are tinted from the primary palette - which is how
        // a lilac dialog turned up over a grey one.
        surfaceContainerLowest = c.bgDeep, surfaceContainerLow = c.panel,
        surfaceContainer = c.card, surfaceContainerHigh = c.card,
        surfaceContainerHighest = c.cardHi,
        surfaceBright = c.cardHi, surfaceDim = c.bgDeep,
        inverseSurface = c.textHi, inverseOnSurface = c.bg,
        outline = c.raised, outlineVariant = c.line,
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
        outline = c.raised, outlineVariant = c.line,
        error = c.red, onError = c.card,
        scrim = c.textFaint,
    )
}

/**
 * [mode] is the setting; Auto asks the OS. Dynamic colour is deliberately
 * not used: this app has a look of its own, and a wallpaper has no opinion
 * worth taking about the colour of a piano roll.
 */
@Composable
fun AcidulousTheme(mode: ThemeMode = ThemeMode.Dark, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.Auto -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val colors = if (dark) DarkColors else LightColors
    CompositionLocalProvider(LocalAcidColors provides colors) {
        MaterialTheme(colorScheme = scheme(colors), typography = Typography, content = content)
    }
}
