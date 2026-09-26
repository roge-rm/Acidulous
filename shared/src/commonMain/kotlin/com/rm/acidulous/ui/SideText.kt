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
 * A line of text read bottom to top, for somewhere there is no width to spare.
 *
 * **Rotation is a draw-time transform, not a layout one.** The node is
 * measured flat and then spun, so three things follow and all three have cost
 * a morning at least once:
 *
 *  - the length has to be *demanded* before it turns, with `requiredWidth`
 *    rather than `width`. A plain `width` is clamped by the incoming
 *    constraint, and the incoming constraint here is the narrow thing that
 *    made vertical text necessary - a forty-four dp column, an eighteen dp
 *    gutter - so a plain width gives you one character;
 *  - the parent still reserves the *pre*-rotation box, so nothing that clips
 *    may wrap one. `Group` is a `clip(RoundedCornerShape(6.dp))`, which is
 *    why the turned knob puts its strips inside its row rather than the other
 *    way round;
 *  - and the bounds a tool reports are neither the flat box nor the turned
 *    one, which is how three device captures in a row drove taps at a y the
 *    lane was nowhere near.
 *
 * Two forms. [length] stated is for a strip whose parent is always the same
 * size - the note lane's gutter and the automation strip's are both always
 * eighty-eight dp, so a hundred and twenty is simply more than enough and the
 * overhang is harmless. [length] left null *measures*, which is what anything
 * in a column whose height is "whatever is left above the keyboard" has to
 * do: stated, a long patch name runs off the bottom of a short screen and
 * floats in the middle of a tall one.
 *
 * This was written out five times before it was written down once.
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
