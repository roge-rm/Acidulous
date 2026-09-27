package com.rm.acidulous.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit

/**
 * A line of text read bottom to top, for places with no width to spare.
 *
 * The rotation happens at draw time, not in layout. The node is measured
 * flat and then turned, so:
 *
 *  - the length must be forced before turning with `requiredWidth`, not
 *    `width`, which gets clamped by the narrow parent and leaves one
 *    character;
 *  - the parent still reserves the unrotated box, so nothing that clips may
 *    wrap it. `Group` clips to its rounded shape, which is why the turned
 *    knob puts its strips inside its row;
 *  - the bounds tools report are neither the flat nor the turned box, so
 *    don't trust them for tap positions.
 *
 * [length] set is for a parent that's always the same size, like the note
 * lane and automation strip gutters. [length] null measures the height it's
 * given, which anything in a column of varying height needs, or long names
 * run off short screens.
 */
@Composable
fun SideText(
    text: String,
    colour: Color,
    size: TextUnit,
    modifier: Modifier = Modifier,
    /** Null to take it from the height this is given. */
    length: Dp? = null,
    family: FontFamily? = null,
) {
    if (length != null) {
        Text(
            text, color = colour, fontSize = size, fontFamily = family,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            modifier = modifier.requiredWidth(length).rotate(-90f),
            textAlign = TextAlign.Center,
        )
        return
    }
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text, color = colour, fontSize = size, fontFamily = family,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.requiredWidth(maxHeight).rotate(-90f),
            textAlign = TextAlign.Center,
        )
    }
}
