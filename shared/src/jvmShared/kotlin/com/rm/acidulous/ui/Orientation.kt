package com.rm.acidulous.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp

/**
 * Is the long edge across?
 *
 * **From the window, not from the configuration.** `Configuration.ORIENTATION`
 * describes the *device*, and there are two ordinary cases where the device
 * and the window disagree: a split-screen app is half a landscape phone and
 * is shaped like a portrait one, and a fold changes shape without the
 * orientation constant moving at all. `ui/Cutout.kt` has read the window size
 * for the camera hole since it was written, for exactly that reason, and this
 * is the same test.
 *
 * It lived as a local in `EditScreen` while the editor was the only screen
 * that knew the phone had turned. M43 turns all four, so it lives here.
 */
@Composable
fun isLandscape(): Boolean = screenShape() == ScreenShape.Wide

/**
 * Three shapes, not two. A phone held upright is about twice as tall as it
 * is wide, and turned it is twice as wide as it is tall; the layouts for
 * those are [Tall] and [Wide]. A square screen - the Titan Pocket's 716 x 720,
 * the Clicks Communicator's 1080 x 1280 - is neither, and given either layout
 * it came out broken: upright the roll had no height left, and the Clicks
 * turned was too narrow for the turned editor's side columns.
 */
enum class ScreenShape { Tall, Wide, Square }

/**
 * Which of the three this window is.
 *
 * **Square when the long side is less than [SquareRatio] times the short,
 * and the short side is phone-sized.** A 4:3 tablet is nearly as square as
 * the Clicks, but it has eight hundred dp each way and the two layouts it
 * already had work there; [SquareShortMax] keeps it on them.
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
 * A tablet, near enough: the short side is at least [LargeShortMin], the line
 * Android's own resources draw (`sw600dp`).
 *
 * Not a fourth [ScreenShape], because a tablet is still tall or wide and most
 * screens want only that. The two that care are the editor, which on a wide
 * tablet has the height for the stacked layout rather than the turned phone's
 * two panes, and the song grid, whose cells grow into the room.
 */
@Composable
fun largeScreen(): Boolean {
    val size = LocalWindowInfo.current.containerSize
    if (size.width <= 0 || size.height <= 0) return false
    val shortDp = with(androidx.compose.ui.platform.LocalDensity.current) { minOf(size.width, size.height).toDp() }
    return shortDp >= LargeShortMin
}

private val LargeShortMin = 600.dp
