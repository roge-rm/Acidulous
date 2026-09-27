package com.rm.acidulous.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp

/**
 * True when the window is wider than it is tall.
 *
 * Uses the window size instead of `Configuration.ORIENTATION`, which is about
 * the device and is wrong in split screen and on foldables.
 */
@Composable
fun isLandscape(): Boolean = screenShape() == ScreenShape.Wide

/**
 * The window's shape. Square screens (the Titan Pocket's 716 x 720, the
 * Clicks Communicator's 1080 x 1280) get their own layout since neither the
 * tall nor the wide one fits them.
 */
enum class ScreenShape { Tall, Wide, Square }

/**
 * This window's shape. Square means the long side is less than [SquareRatio]
 * times the short side and the short side is phone sized. [SquareShortMax]
 * keeps 4:3 tablets on the normal layouts, which work fine there.
 */
@Composable
fun screenShape(): ScreenShape {
    val size = LocalWindowInfo.current.containerSize
    if (size.width <= 0 || size.height <= 0) return ScreenShape.Tall
    val long = maxOf(size.width, size.height).toFloat()
    val short = minOf(size.width, size.height)
    val shortDp = with(androidx.compose.ui.platform.LocalDensity.current) { short.toDp() }
    return when {
        long / short < SquareRatio && shortDp < SquareShortMax -> ScreenShape.Square
        size.width > size.height -> ScreenShape.Wide
        else -> ScreenShape.Tall
    }
}

/** The Clicks is 1.19; the squarest ordinary phone is past 1.9. */
private const val SquareRatio = 1.4f
private val SquareShortMax = 600.dp

/**
 * True on a tablet: the short side is at least [LargeShortMin], the same line
 * as Android's `sw600dp`. Used by the editor and the song grid.
 */
@Composable
fun largeScreen(): Boolean {
    val size = LocalWindowInfo.current.containerSize
    if (size.width <= 0 || size.height <= 0) return false
    val shortDp = with(androidx.compose.ui.platform.LocalDensity.current) { minOf(size.width, size.height).toDp() }
    return shortDp >= LargeShortMin
}

private val LargeShortMin = 600.dp
