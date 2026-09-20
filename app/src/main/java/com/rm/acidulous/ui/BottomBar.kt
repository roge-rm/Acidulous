package com.rm.acidulous.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowColumn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Stable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
 * state their own width instead. Forty-four is now a target size rather than
 * a label size: every anchor carries one glyph, so what sets the floor is
 * the finger and not the text.
 *
 * It is one number because that is the whole point - the same five controls
 * end every row in the app at the same size and in the same order, packed
 * against the right edge, so the one you want is where you left it whichever
 * screen you are on. Undo and redo lead that group rather than standing
 * apart from it: what they undo is whatever this screen edits, which makes
 * them as much a part of the row's right-hand end as stop is.
 */
val BarAnchor = 44.dp

/**
 * A pill carrying a word rather than a glyph.
 *
 * Wide enough for the longest label it will ever show and no wider - the
 * arranger's loop button says "⟳ song" and "⟳ scene" and the wider of those
 * measures 56.5dp with its padding. It used to take a weighted share, which
 * meant it swallowed every spare dp on the screen and came out two hundred
 * wide on a large phone, dwarfing everything beside it.
 *
 * Fixed, so the row now has a minimum: everything in it is a stated width,
 * and below a screen of about 382dp the weighted spacer that holds the
 * transport to the right edge runs out. That is narrower than any phone this
 * has been built for, but it is the number to check if one turns up.
 */
val BarWord = 58.dp
/**
 * What a screen lays its controls out with, whichever way the bar runs.
 *
 * The bar is a row on a phone held upright and a column against the right
 * edge when it is turned, and **the screen should not have to know which**.
 * That was already half true: this file's own note says a screen says what
 * goes in the row and does not get to say how tall it is or where it sits.
 * It was only half true because the content lambda had a `RowScope`, so
 * every call site wrote `Modifier.width(BarAnchor)` and `Modifier.weight(1f)`
 * - two statements about the axis, in the one place that is not supposed to
 * have an opinion about it. The editor's footer had grown three `if
 * (landscape)` branches saying so.
 *
 * Now a pill asks for what it *is* - anchored, a word wide, or sharing what
 * is left - and the bar turns it into the right axis.
 */
@Stable
class BarScope internal constructor(
    /** True when the bar runs down the screen. Rarely needed; glyphs use it. */
    val vertical: Boolean,
    private val weigh: (Modifier, Float) -> Modifier,
) {
    /** A pill that shares what the fixed ones leave. */
    fun Modifier.barWeight(weight: Float = 1f): Modifier = weigh(this, weight)

    /**
     * The anchored size, across the bar's own axis.
     *
     * **Sideways it is a share rather than a stated height.** Anchoring is a
     * portrait rule: upright the bar is the width of the phone and there is
     * room for every pill at 44 dp with space to spare. Turned, the column
     * has whatever is left above the keyboard - about 270 dp on a phone - and
     * eight pills at 44 dp is 380. Stating the height there does not make
     * them 44 dp, it makes the last three fall off the bottom.
     *
     * So they share it, which is what the 300 dp landscape row did before
     * this and for the same reason. A pill comes out about 34 dp tall and the
     * full width of the column, which is the same target area the row gave
     * it at 37 dp wide.
     */
    val anchor: Modifier
        get() = if (vertical) Modifier.width(BarAnchor).height(BarPillH) else Modifier.width(BarAnchor)

    /** Wide enough for a word - see [BarWord]. */
    val word: Modifier
        get() = if (vertical) Modifier.width(BarWord).height(BarPillH) else Modifier.width(BarWord)
}

/**
 * How tall a pill is when the bar runs down the screen.
 *
 * **Portrait's proportions, not a share of the column.** The first turned bar
 * gave every pill a weighted share of the height, which is how eight of them
 * fitted two hundred and seventy dp - and it made each one forty-six by
 * thirty, a flat oval where upright it is a forty-four by forty rounded
 * rectangle with room for the word "fx" in it. Dan, looking at the two side
 * by side: the portrait elements had not been faithfully ported.
 *
 * So a pill is the shape it is upright, and the *column* gives way instead:
 * see [BottomBar], which takes another column of pills when they do not all
 * fit down one. Forty rather than forty-four because that is what Material's
 * own button measures upright, which is what the portrait row actually shows.
 */
val BarPillH = 40.dp

/**
 * [vertical] runs the bar down the screen instead of across it.
 *
 * The [readout] is **not drawn when vertical**: it is two lines of position
 * and diagnostics, and the column is about as wide as one pill. A screen that
 * wants it sideways places it itself, where there is width for it.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun BottomBar(
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
    /**
     * The same pills, standing in somebody else's row.
     *
     * Sideways the editor puts its transport in the header beside the clip's
     * name rather than in a bar of its own - Dan, over a screenshot with an
     * arrow drawn from the right edge to the top corner: "that's wasted space
     * in landscape". So: no background, no width taken, no readout, and the
     * caller owns where it sits. Everything else - which pills, in what
     * order, at what size - is still this file's, which is the whole point
     * of the file.
     */
    inline: Boolean = false,
    /** Lines above the buttons - the arranger's position and diagnostics. */
    readout: @Composable ColumnScope.() -> Unit = {},
    content: @Composable BarScope.() -> Unit,
) {
    if (inline) {
        Row(
            modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // **No weights in here.** A weighted child of a row that was
            // measured with an unbounded width gets nothing, and "nothing" is
            // a pill that is not drawn: fx, the view toggle and the draw/select
            // toggle all vanished from the header, leaving undo onwards. The
            // header is not a bar across the screen - it is a row as wide as
            // what is in it - so every pill here is its anchored size.
            BarScope(false) { m, _ -> m.width(BarAnchor) }.content()
        }
        return
    }
    if (vertical) {
        // **The pills keep their shape and the column takes another column.**
        //
        // A `FlowColumn` fills top to bottom and then starts again to the
        // right, so the reading order down the bar is the reading order along
        // the row upright, and the last pill - play, always play - lands in
        // the bottom corner nearest the thumb, which is where the row's own
        // note says it belongs.
        //
        // How many fit down one is measured rather than counted, because it
        // depends on what the keyboard divider was dragged to. `maxItemsInMainAxis`
        // is the whole mechanism: say how many go in a column and the flow
        // decides how many columns that needs.
        BoxWithConstraints(modifier.fillMaxHeight().background(Acid.colors.bar)) {
            val perColumn = ((maxHeight - 16.dp) / (BarPillH + 4.dp)).toInt().coerceAtLeast(1)
            FlowColumn(
                Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                maxItemsInEachColumn = perColumn,
            ) {
                BarScope(true) { m, _ -> m.width(BarAnchor).height(BarPillH) }.content()
            }
        }
        return
    }
    Column(
        modifier.fillMaxWidth()
            .background(Acid.colors.bar)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        readout()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BarScope(false) { m, w -> with(this@Row) { m.weight(w) } }.content()
        }
    }
}

/**
 * A pill that is *held* rather than pressed.
 *
 * The only one in the app, and it exists because fill is the only control
 * whose whole meaning is "while my finger is down". A BarButton fires on
 * release, which for this would mean the fill landed after the bar it was
 * meant for. Built on the same OutlinedButton so it is its neighbours' shape
 * by construction rather than by arithmetic, with the click disabled and the
 * gesture taken directly.
 */
@Composable
fun BarHoldButton(
    label: String,
    modifier: Modifier = Modifier,
    held: Boolean = false,
    onHold: (Boolean) -> Unit,
) {
    val hold by rememberUpdatedState(onHold)
    OutlinedButton(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                hold(true)
                // Cancelled counts as released: a finger that slides off the
                // pill has stopped asking for a fill, and a fill left on
                // because of one is a bar nobody can explain.
                waitForUpOrCancellation()
                hold(false)
            }
        },
        onClick = {},
        contentPadding = PaddingValues(horizontal = 4.dp),
        border = BorderStroke(1.dp, if (held) Acid.colors.accent else Acid.colors.line),
    ) {
        Text(
            label, maxLines = 1, softWrap = false, fontSize = 13.sp,
            color = if (held) Acid.colors.accent else Color.Unspecified,
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
    /**
     * Colours the outline instead of the label, for a state that is about
     * the button rather than about what it says. Arming the transport is
     * one: a red ring round the whole pill carries further than a red glyph
     * inside it, and it leaves the glyph free to go on saying which state
     * you are in rather than doubling as the alarm.
     */
    border: Color? = null,
    enabled: Boolean = true,
    fontFamily: FontFamily? = null,
    /**
     * A second gesture on the same pill, and the reason it lives here rather
     * than as a `Modifier.onLongPress` at the call site.
     *
     * Two detectors on one button both fire: the modifier's long press ran,
     * and then the button's own `onClick` ran on release as well - so holding
     * the record pill for the metronome *also armed the transport*, every
     * time. It was invisible until the click got an indicator of its own, and
     * it is exactly why "record on" and "record and click on" were hard to
     * tell apart. One detector owning both gestures cannot do that.
     */
    onLongPress: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    var suppressClick by remember { mutableStateOf(false) }
    val gestures = if (onLongPress == null) modifier else modifier.onLongPress {
        suppressClick = true
        onLongPress()
    }
    OutlinedButton(
        modifier = gestures,
        onClick = { if (suppressClick) suppressClick = false else onClick() },
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 4.dp),
        // Falling back to Material's own outline, not to null: null means
        // *no* border, and passing it quietly stripped the outline from every
        // pill in the app the day this parameter was added.
        border = border?.let { BorderStroke(1.dp, it) } ?: ButtonDefaults.outlinedButtonBorder,
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
 * The meter is a ladder of segments lit from the bottom - teal to about half,
 * amber past that, red past four fifths, and the whole ladder when a block
 * has actually been dropped. It is the front of every compressor and mixer
 * ever made, which is the point: a smooth fill creeping up a pill reads as a
 * button highlight, and discrete segments read as a level. They also make a
 * small change visible, where a continuous fill just creeps.
 *
 * The thing filling up is the thing you would press, which is the argument
 * for it living here rather than as a number in the corner of a header. It is
 * ornament over a working button, so it stays behind the word and well under
 * full strength - you read the ladder when you look for it, and the rest of
 * the time it is the transport's most-pressed-in-a-hurry control and nothing
 * else.
 *
 * No border of its own any more. Every other pill's outline is all but
 * invisible against the bar, so a red ring here was the only one on the row
 * you actually saw, and it made panic look like a different kind of control
 * rather than the same kind with a different job. The red word is signal
 * enough.
 *
 * An ordinary OutlinedButton, so it is its neighbours' shape by construction
 * rather than by arithmetic. That took two goes. Material expands a button's
 * layout node to the 48dp touch target while drawing its outline at 40dp, so
 * a fill clipped to the node is a bigger stadium than the border around it;
 * built by hand instead, it came out the right height and the wrong width,
 * because MinWidth is Material's and not mine to restate. So the button stays
 * Material's and the ladder is inset to the outline it actually draws.
 */
@Composable
fun PanicButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Acid.colors
    val load = rememberEngineLoad()
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
                // And inside the outline, not up to it. Material strokes the
                // border *centred* on the shape's boundary, so a fill drawn to
                // that same boundary reaches the middle of the stroke and
                // reads as a meter slightly too big for its own pill. Dan:
                // "the fill circle doesn't match the pill outline (it is
                // bigger than the pill)". One stroke width in on every side,
                // and the corner radius follows the height that is left.
                val edge = kPanicStroke.toPx()
                val tall = drawn - edge * 2f
                val wide = size.width - (edge + kPanicSide.toPx()) * 2f
                val top = ((size.height - drawn) / 2f).coerceAtLeast(0f) + edge
                val gap = 1.dp.toPx()
                val h = (tall - gap * (kLadder - 1)) / kLadder
                if (h <= 0f || wide <= 0f) return@drawBehind
                // The rungs are rectangles and the button is a stadium, so
                // they are clipped to its shape. Without this the bottom one
                // runs out past the curve at either end and the meter reads
                // as a spill rather than as a reading.
                val pill = Path().apply {
                    addRoundRect(
                        RoundRect(Rect(Offset(edge + kPanicSide.toPx(), top), Size(wide, tall)), CornerRadius(tall / 2f)),
                    )
                }
                clipPath(pill) {
                    for (i in 0 until kLadder) {
                        // Lit once the level reaches the foot of the rung, so
                        // the count reads as the percentage rather than
                        // trailing it by one.
                        if (level < i.toFloat() / kLadder) break
                        // Colour by where the rung sits on the scale, not by
                        // the reading: a meter's own scale does not change
                        // colour as the needle moves.
                        val at = (i + 0.5f) / kLadder
                        val colour = when {
                            load.dropped -> c.red
                            at > 0.8f -> c.red
                            at > 0.5f -> c.accent
                            else -> c.teal
                        }
                        drawRect(
                            colour.copy(alpha = if (load.dropped) 0.55f else 0.40f),
                            topLeft = Offset(edge + kPanicSide.toPx(), top + (kLadder - 1 - i) * (h + gap)),
                            size = Size(wide, h),
                        )
                    }
                }
            },
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 4.dp),
        // The outline Material draws, stated rather than defaulted, because
        // the meter above has to know how wide it is to sit inside it.
        border = BorderStroke(kPanicStroke, ButtonDefaults.outlinedButtonBorder.brush),
    ) {
        // Red when it means something. The word was always red, which made it
        // the loudest thing on a row where nothing was wrong - so red stopped
        // being a warning and became the name of a button. It now says what
        // the meter says: past nine tenths of a block's time, or a dropout
        // already counted.
        Text(
            "panic",
            color = if (load.dropped || load.level > 0.9f) c.red else c.textMid,
            fontSize = 12.sp, maxLines = 1,
        )
    }
}

/** Segments in the ladder. Seven of them is 4dp a rung in a 40dp pill. */
private const val kLadder = 7

/** Material's own outline width, which the meter is inset by. */
private val kPanicStroke = 1.dp

/**
 * How much narrower the drawn button is than the node it is drawn in.
 *
 * Measured, because Material does not say. The height is easy - the node is
 * the 48 dp touch target and the surface is `MinHeight`, so centring one in
 * the other lands exactly. The width is not: with the meter inset by the
 * border alone it still showed three pixels of green either side of the
 * outline at 2.75x, which is a hair over a decibel... over a *dp*, and two
 * more brings it three pixels inside on both sides instead. Dan saw it before
 * any of this was measured: "I can see green to the left/right of the outside
 * of the panic pill".
 */
private val kPanicSide = 2.dp
