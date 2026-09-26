package com.rm.acidulous.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable

/**
 * The top edge's blocked span, in window pixels. [left] to [right] may cover
 * the whole width, which says the strip is blocked but its shape is not
 * known - a notch, or a device whose rectangles cannot be trusted.
 */
@Immutable
data class TopCutout(val left: Int, val right: Int, val height: Int)

/**
 * The camera hole on the top edge, or null when nothing blocks it.
 *
 * The inset is read through Compose, which is what makes this recompose when
 * the phone turns: the activity handles rotation itself and is never
 * recreated, so nothing else would notice. The rectangles come from
 * androidx, which has none below API 28.
 *
 * When the inset says the strip is blocked but the rectangles do not agree
 * with it, the whole width is reported as blocked rather than nothing. A row
 * that wrongly believes the strip is free puts its title under the glass;
 * one that wrongly believes it is full merely sits where it used to.
 */
@Composable
expect fun rememberTopCutout(): TopCutout?
