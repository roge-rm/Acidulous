package com.rm.acidulous.ui

import com.rm.acidulous.util.Math

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/**
 * How much larger than normal the whole interface is drawn.
 *
 * Every size in the app is a fixed `dp`, which is small on high DPI screens,
 * and Android's display size and font scale don't change them. So one
 * multiplier is applied to the density at the root of the composition, and
 * every `dp` and `sp` goes through that `Density`.
 *
 * It only goes larger, never smaller, so a control that's big enough to
 * touch now always stays that way.
 *
 * The step names are the string array `settings_size_choices`, in this order.
 */
val UiScaleSteps = listOf(1.0f, 1.1f, 1.2f, 1.3f)

/** The desktop's screen scales, 0 first for the system's own; see UiPrefs.screenScale. */
val ScreenScaleSteps = listOf(0f, 1f, 1.5f, 2f, 2.5f, 3f)

/**
 * The smallest size the app may be asked to lay itself out in.
 *
 * Scaling up makes the screen report fewer dp: at 1.3 a Pixel 5's 393 x 851
 * dp becomes 302 x 655. Below these numbers the editor can't fit a header,
 * two folded lanes, a keyboard and a roll at once, which is why the steps
 * stop at 1.3.
 *
 * The cap works from the short edge so it's the same in portrait and
 * landscape and the app doesn't change size when you turn it.
 */
const val MinShortEdgeDp = 300f
const val MinLongEdgeDp = 560f

/**
 * The scale actually in force: what was chosen, capped by what the screen
 * can fit. On a 393 dp phone the cap never kicks in, on a 320 dp one it does.
 *
 * Never below 1, even on a screen too small for the app at normal size.
 *
 * Pure and top-level so it can be tested without a device.
 */
fun appliedScale(chosen: Float, shortEdgeDp: Float, longEdgeDp: Float): Float {
    if (shortEdgeDp <= 0f || longEdgeDp <= 0f) return chosen
    return minOf(chosen, shortEdgeDp / MinShortEdgeDp, longEdgeDp / MinLongEdgeDp)
        .coerceAtLeast(1f)
}

/**
 * The applied scale, for the few places that need the number itself.
 *
 * Sizes in `dp` scale through the density automatically. Counts don't: the
 * roll's rows and the pinch limit divide whatever height they're given, so
 * they read this. It can't be worked out from `LocalDensity` further down,
 * which only has the scaled density.
 */
val LocalUiScale = compositionLocalOf { 1f }

/**
 * How many rows the roll shows once the chrome has grown with the scale.
 *
 * Everything around the roll is in `dp` and grows with the scale, and the
 * roll is the `weight(1f)` that gives up the space. Keeping the row count
 * would make every row smaller as the scale goes up, the opposite of what
 * was asked for.
 *
 * So this works out the slot the roll would have had at 1.0 in the same
 * layout and keeps the same share of it:
 *
 *     rows = base x slot / slot-at-one
 *
 * - At 1.0 it's exactly [base], whatever is folded, so folding still gives
 *   taller rows, not more rows.
 * - Each row comes out exactly [scale] times taller.
 *
 * [slotDp] and [windowDp] are both in the app's scaled `dp`.
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
    // The window at normal size, less what the chrome takes now.
    val atOne = slotDp + windowDp * (scale - 1f)
    if (atOne <= 0f) return minRows
    return Math.round(base * slotDp / atOne).coerceIn(minRows, ceiling)
}

/**
 * The device's own density before the UI scale. Provided at the root next to
 * the scaled one so [ScaledWindow] always builds from this and applying it
 * twice doesn't compound.
 */
val LocalBaseDensity = compositionLocalOf<Density?> { null }

/**
 * Applies the UI scale inside a separate window.
 *
 * A `Dialog` or `DropdownMenu` is its own window, and Compose gives each one
 * a fresh `LocalDensity` from its own view, so the root override doesn't
 * reach it. Our own locals do come through with the composition, so the
 * scale can be applied again here.
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
 * A dropdown's contents at the app's scale, never taller than the screen.
 *
 * `ScaledWindow` alone isn't enough in a menu. `DropdownMenu` measures its
 * allowed height at the outer density, then the scaled rows come out bigger
 * and the last ones are clipped off the screen without scrolling. So the
 * height limit and the scrolling happen inside the scale, where rows have
 * their real size. The menu's own `scrollState` isn't used.
 */
@Composable
fun ScaledMenu(scroll: androidx.compose.foundation.ScrollState, content: @Composable () -> Unit) {
    val base = LocalBaseDensity.current ?: LocalDensity.current
    val scale = LocalUiScale.current
    // The window height in scaled dp.
    val screenDp = windowHeightDp() / scale
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

/** The window's height in dp as the platform counts it: Android's screenHeightDp, or the desktop window's. */
@Composable
internal expect fun windowHeightDp(): Float
