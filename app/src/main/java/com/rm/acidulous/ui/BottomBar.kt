package com.rm.acidulous.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.model.Action
import com.rm.acidulous.ui.theme.Acid

/**
 * The bar along the bottom of a screen, and the pills in it.
 *
 * Both screens have one and they used to be written separately, which is
 * exactly how they drifted: the arranger put play at the far left and its
 * readout *below* the buttons, the editor put play seventh of nine and had
 * no bar behind it at all. Opening a clip therefore moved the two controls
 * you reach for without looking to the other end of the phone.
 *
 * So the arrangement lives here rather than at either call site. A screen
 * says what goes in the row; it does not get to say how tall it is, what is
 * behind it, or where it sits.
 *
 * The grammar every row follows:
 *
 *     [ leading ] [ ....... middle, weighted ....... ] [ mix ] [ rec ] [ play ]
 *
 * The three on the right are the same controls in the same order on every
 * screen, at their natural width - which for a one-glyph pill is
 * ButtonDefaults.MinWidth - so they land in the same place without either
 * screen knowing a number. Play is last because the corner is the easiest
 * target on a phone held one-handed, and rec sits inboard of it where a
 * thumb reaching for the edge cannot catch it.
 *
 * Everything in between takes weight(1f). That is not only for looks: a row
 * of natural-width pills can be asked for more width than the screen has,
 * and when that happened the last child measured silently came out narrower
 * than the rest. A weighted middle cannot over-fill.
 */

/**
 * How wide an anchored pill is, on every screen.
 *
 * Material's own minimum is 58dp, and five anchors at that is 291dp of a
 * phone's 377 - which leaves the arranger's loop button four. So the anchors
 * state their own width instead. Forty-four is the floor: "● REC" is the
 * widest label any of them carries, at 41.8dp measured, and four dp of
 * padding a side is what the rest of the row uses.
 *
 * It is one number because that is the whole point - the same five controls
 * end every row in the app at the same size, so the one you want is where
 * you left it whichever screen you are on.
 */
val BarAnchor = 44.dp
@Composable
fun BottomBar(
    modifier: Modifier = Modifier,
    /** Lines above the buttons - the arranger's position and diagnostics. */
    readout: @Composable ColumnScope.() -> Unit = {},
    content: @Composable RowScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth()
            .background(Acid.colors.bar)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        readout()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

/**
 * One pill.
 *
 * Four dp of padding rather than Material's twenty-four: a weighted middle
 * share is about sixty dp on a phone, and "⟳ scene" wants fifty-six of it.
 * The label is one line and never wraps - a pill that grew a second line
 * would take the row's height with it.
 */
@Composable
fun BarButton(
    label: String,
    modifier: Modifier = Modifier,
    /** Unspecified keeps the button's own content colour. */
    colour: Color = Color.Unspecified,
    enabled: Boolean = true,
    fontFamily: FontFamily? = null,
    onClick: () -> Unit,
) {
    OutlinedButton(
        modifier = modifier,
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        Text(label, color = colour, fontSize = 12.sp, fontFamily = fontFamily, maxLines = 1)
    }
}

/** A line of numbers above the row. */
@Composable
fun BarReadout(text: String, colour: Color, size: Int = 12) {
    Text(
        text, color = colour, fontFamily = FontFamily.Monospace, fontSize = size.sp,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Panic, and the load meter, in one control.
 *
 * The button fills from the bottom as the audio thread runs out of room -
 * teal, amber, red - and floods when a block is actually dropped. The thing
 * filling up is the thing you would press, which is the argument for it
 * living here rather than as a number in the corner of a header.
 *
 * An ordinary OutlinedButton, so it is its neighbours' shape by
 * construction rather than by arithmetic. That took two goes. Material
 * expands a button's layout node to the 48dp touch target while drawing its
 * outline at 40dp, so a fill clipped to the node is a bigger stadium than
 * the border around it; built by hand instead, it came out the right height
 * and the wrong width, because MinWidth is Material's and not mine to
 * restate. So the button stays Material's and the fill is inset to the
 * outline it actually draws.
 */
@Composable
fun PanicButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Acid.colors
    val load = rememberEngineLoad()
    val colour = loadColour(load)
    OutlinedButton(
        modifier = modifier
            // Outermost, as on every other mappable control: inside the clip
            // its highlight is cut away and cannot be seen.
            .mappable(MapTargets.action(Action.Panic.name))
            .drawBehind {
                val level = if (load.dropped) 1f else load.level
                if (level <= 0.001f) return@drawBehind
                // The outline Material actually draws, inside the node it
                // actually occupies.
                val drawn = ButtonDefaults.MinHeight.toPx()
                val top = ((size.height - drawn) / 2f).coerceAtLeast(0f)
                // Translucent, and behind the label: panic is a thing you
                // do, not a state the app is in, and a solid fill would
                // read as "switched on".
                clipRect(top = top + drawn * (1f - level)) {
                    drawRoundRect(
                        colour.copy(alpha = if (load.dropped) 0.45f else 0.30f),
                        topLeft = Offset(0f, top),
                        size = Size(size.width, drawn),
                        cornerRadius = CornerRadius(drawn / 2f),
                    )
                }
            },
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 4.dp),
        border = BorderStroke(1.dp, c.red),
    ) { Text("panic", color = c.red, fontSize = 12.sp, maxLines = 1) }
}
