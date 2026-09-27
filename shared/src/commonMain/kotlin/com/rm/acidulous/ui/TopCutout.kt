package com.rm.acidulous.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable

/**
 * The top edge's blocked span, in window pixels. [left] to [right] may cover
 * the whole width, meaning the strip is blocked but its shape is unknown (a
 * notch, or a device whose rectangles can't be trusted).
 */
@Immutable
data class TopCutout(val left: Int, val right: Int, val height: Int)

/**
 * The camera hole on the top edge, or null when nothing blocks it.
 *
 * The inset is read through Compose so this recomposes when the phone turns.
 * The activity handles rotation itself and is never recreated, so nothing
 * else would notice. The rectangles come from androidx, which has none below
 * API 28.
 *
 * When the inset says the strip is blocked but the rectangles don't agree,
 * the whole width is reported as blocked. Wrongly thinking it's free puts a
 * title under the camera, wrongly thinking it's full costs nothing.
 */
@Composable
expect fun rememberTopCutout(): TopCutout?
