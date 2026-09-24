package com.rm.acidulous.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/**
 * How much larger than stated the whole interface is drawn.
 *
 * Every size in this app is a fixed `dp` chosen while looking at one phone -
 * `KEYS_H` is 104, `PADS_H` is 132, `GutterWidth` is 34, a `HeaderButton` is
 * 42 wide on a 44 dp band. On a high-DPI screen those are physically small,
 * and somebody whose eyes want them bigger had nothing to ask with: Android's
 * own display size and font scale move the platform's numbers and cannot move
 * ours, because ours are stated in the source.
 *
 * So one multiplier, applied to the *density* at the root of the composition
 * rather than to each of the three hundred and seventy-three places a size is
 * written. Every `dp` and every `sp` in the tree resolves through that one
 * `Density`, which is why this file holds arithmetic and no sizes at all.
 *
 * **Larger only.** Dan, 2026-09-20: "the idea is to allow users to scale the
 * UI so they can use it on higher DPI screens or with worse eyesight (so I am
 * thinking things can get larger, I'm not sure they need to go smaller)". One
 * as the floor also settles the touch-target question for nothing: a control
 * that clears a finger today cannot stop clearing one, because nothing ever
 * gets smaller than it is now.
 *
 * What each step is called is the string array `settings_size_choices`, in
 * this order.
 */
val UiScaleSteps = listOf(1.0f, 1.1f, 1.2f, 1.3f)

/**
 * The narrowest and shortest the app may be asked to lay itself out in.
 *
 * A scale does not make a screen bigger; it makes the screen *report* less of
 * itself. At 1.3 a Pixel 5's 393 x 851 dp arrives as 302 x 655, and somewhere
 * below that the editor stops being able to hold a header, two folded lanes, a
 * keyboard and a roll at once. These two numbers are where that is, and they
 * are the reason the steps stop at 1.3 rather than going further.
 *
 * The short edge is the load-bearing one, and it is deliberately measured
 * rather than taken from the orientation: for a phone it is the same number
 * whichever way round it is held, so a cap worked out from it is the same cap
 * in portrait and in landscape. An app that changed size when you turned it
 * would be worse than one that was slightly too small.
 */
const val MinShortEdgeDp = 300f
const val MinLongEdgeDp = 560f

/**
 * The scale actually in force: what was asked for, capped by what there is.
 *
 * The same sentence `TabbedDialog` already writes about a window's height -
 * the cap is the smaller of what was asked for and what there is - and the
 * reason the milestone's second rule ("the grid must still fit its slot at the
 * largest") needs no table of sizes per phone. On a 393 dp phone nothing
 * binds; on a 320 dp one it does, and on a tablet neither term is close.
 *
 * Never below one, whatever the arithmetic says: a screen too small for the
 * app at its stated size is not a reason to draw the app smaller still.
 *
 * Pure, and top-level, so it can be tested without a device.
 */
fun appliedScale(chosen: Float, shortEdgeDp: Float, longEdgeDp: Float): Float {
    if (shortEdgeDp <= 0f || longEdgeDp <= 0f) return chosen
    return minOf(chosen, shortEdgeDp / MinShortEdgeDp, longEdgeDp / MinLongEdgeDp)
        .coerceAtLeast(1f)
}

/**
 * The applied scale, for the two places that need the number itself.
 *
 * Almost nothing does: a size written in `dp` is scaled by the density and
 * never learns about it. What cannot be scaled that way is a *count* - the
 * roll's rows, the pinch's ceiling - because a count divides whatever height
 * it is given and so keeps its answer the same physical size however large
 * everything around it grows. Those read this.
 *
 * Not recoverable from `LocalDensity` downstream: a composable can see the
 * scaled density but not the one it was scaled from.
 */
val LocalUiScale = compositionLocalOf { 1f }

/**
 * How many rows the roll shows, when the chrome has taken the scale's share of
 * the window.
 *
 * **The roll is the elastic child**: everything around it - the header, the
 * lanes, the machine panel, the footer - is stated in `dp` and grows with the
 * setting, and the roll is the `weight(1f)` that pays for all of it. Leaving
 * the count alone would therefore have made every row *smaller* at every
 * setting above 1.0, which is the opposite of what somebody enlarging the
 * interface is asking for.
 *
 * What this works out is the slot the roll would have had at 1.0 in this same
 * layout - the window at its own size, less the chrome, which is stated in `dp`
 * and so is the same number of `dp` at every setting - and then keeps the same
 * *share* of it:
 *
 *     rows = base x slot / slot-at-one
 *
 * Two things fall out of that, and they are why it is written this way rather
 * than as a target row height:
 *
 * - **At 1.0 it is exactly [base]**, whatever is folded. A row height stated in
 *   `dp` would have made folding the panel show more rows instead of taller
 *   ones, which is a change to how the editor works and not something this
 *   milestone was asked for.
 * - **A row comes out physically [scale] times taller**, exactly, because the
 *   slot's pixels divided by this count reduce to `scale x slot-at-one / base`.
 *   The roll shows fewer pitches and each of them is bigger.
 *
 * [slotDp] and [windowDp] are both in the scaled `dp` the app is laying itself
 * out in; the arithmetic converts.
 */
fun rowsForSlot(
    slotDp: Float,
    windowDp: Float,
    scale: Float,
    base: Int,
    minRows: Int,
    maxRows: Int,
): Int {
    val ceiling = maxRows.coerceAtLeast(minRows)
    if (slotDp <= 0f) return base.coerceIn(minRows, ceiling)
    // The window at its own size, less what the chrome is taking now.
    val atOne = slotDp + windowDp * (scale - 1f)
    if (atOne <= 0f) return minRows
    return Math.round(base * slotDp / atOne).coerceIn(minRows, ceiling)
}

/**
 * The device's own density, before the interface scale was applied to it.
 *
 * Provided once at the root beside the scaled one, so that [ScaledWindow] can
 * be applied twice without compounding: it always builds from this rather than
 * from whatever the density happens to be where it is standing.
 */
val LocalBaseDensity = compositionLocalOf<Density?> { null }

/**
 * The interface scale, carried into a window of its own.
 *
 * **A `Dialog` and a `DropdownMenu` are separate windows**, and Compose hands
 * each one a fresh `LocalDensity` taken from its own view - so the override at
 * the root of the activity reaches everything drawn in the main window and
 * stops at the edge of every window drawn over it. Measured on the emulator at
 * the largest setting: the app behind was 1.3x and the settings window's own
 * chips and Done button were still exactly the size they are at 1.0.
 *
 * Our own locals do come through, because those travel with the composition
 * rather than with the view, so the scale is here to be re-applied.
 */
@Composable
fun ScaledWindow(content: @Composable () -> Unit) {
    val base = LocalBaseDensity.current ?: LocalDensity.current
    val scale = LocalUiScale.current
    CompositionLocalProvider(
        LocalDensity provides Density(base.density * scale, base.fontScale),
        content = content,
    )
}

/**
 * A dropdown's contents: at the app's scale, and never taller than the screen.
 *
 * `ScaledWindow` alone is not enough inside a menu, and the sixteenth effect
 * is what proved it. `DropdownMenu` measures how tall it is allowed to be at
 * the density *outside* it and then scrolls its own column if the content
 * overruns. Wrap the content in a scale and the two numbers stop agreeing:
 * the menu decides seventeen rows fit, because at the outer density they very
 * nearly do, and then draws them larger and clips the last one against the
 * bottom of the screen. Nothing scrolls, because as far as the menu is
 * concerned nothing overflowed.
 *
 * So the bound and the scrolling move *inside* the scale, where the rows are
 * the size they will actually be drawn at. The menu's own `scrollState` is
 * not used; this is the one that moves.
 */
@Composable
fun ScaledMenu(scroll: androidx.compose.foundation.ScrollState, content: @Composable () -> Unit) {
    val base = LocalBaseDensity.current ?: LocalDensity.current
    val scale = LocalUiScale.current
    // The window's height in the scale's own dp: the screen is this many of
    // the outer kind, and each of ours is `scale` times as big.
    val screenDp = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp / scale
    CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
        androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier
                // Not the whole screen: a menu is anchored to the control that
                // opened it and has to fit between that and an edge.
                .heightIn(max = (screenDp * 0.72f).dp)
                .verticalScroll(scroll)
                .scrollbar(scroll, color = com.rm.acidulous.ui.theme.Acid.colors.scrollbar),
        ) { content() }
    }
}
