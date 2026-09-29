package com.rm.acidulous.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager

/** Whether this is in a screen kept behind another, see [KeepBuilt]. Its keys stop while it is. */
val LocalHidden = compositionLocalOf { false }

/**
 * A screen that stays built while another is open over it, so going back to it
 * is quick. Rebuilding the song screen took most of a second.
 *
 * Hidden, it isn't measured, placed or drawn, so it takes no touches, TalkBack
 * doesn't see it and nothing in it can be focused. It keeps its state.
 */
@Composable
fun KeepBuilt(shown: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // A control focused when it was hidden would keep the keys.
    val focus = LocalFocusManager.current
    LaunchedEffect(shown) { if (!shown) focus.clearFocus(force = true) }
    CompositionLocalProvider(LocalHidden provides !shown) {
        Layout(content, modifier) { measurables, constraints ->
            if (!shown) return@Layout layout(constraints.minWidth, constraints.minHeight) {}
            val placeables = measurables.map { it.measure(constraints) }
            layout(
                placeables.maxOfOrNull { it.width } ?: constraints.minWidth,
                placeables.maxOfOrNull { it.height } ?: constraints.minHeight,
            ) { placeables.forEach { it.place(0, 0) } }
        }
    }
}
