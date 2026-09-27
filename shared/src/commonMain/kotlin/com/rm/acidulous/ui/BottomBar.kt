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
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.model.Action
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.res.*

/**
 * The bar along the bottom of a screen, and the pills in it.
 *
 * The layout lives here rather than on each screen, so every screen's bar
 * looks the same and the controls you reach for without looking stay in the
 * same place. A screen says what goes in the row, not how tall it is, what's
 * behind it or where it sits.
 *
 * Every row follows this layout:
 *
 *     [ leading ] [ ....... middle, weighted ....... ] [ mix ] [ rec ] [ play ]
 *
 * The three on the right are the same controls in the same order on every
 * screen, at a fixed width, so they land in the same place. Play is last
 * because the corner is the easiest target one-handed, and rec is just
 * inside it where a thumb reaching for the edge won't hit it.
 *
 * Everything in between is weighted, so the middle can't overfill the row
 * and squash the last pills.
 */

/**
 * How wide an anchored pill is, on every screen.
 *
 * Material's minimum is 58dp, and five anchors at that leave almost nothing
 * on a phone, so the anchors set their own width. Every anchor holds one
 * glyph, so the width is set by finger size rather than text.
 *
 * It's one number so the same controls end every row at the same size and in
 * the same order, packed against the right edge. Undo and redo lead that
 * group, since they undo whatever the current screen edits.
 */
val BarAnchor = 44.dp

/**
 * A pill that shows a word rather than a glyph.
 *
 * Wide enough for its longest label and no wider: the arranger's loop button
 * says "⟳ song" or "⟳ scene", and the wider one measures 56.5dp with padding.
 *
 * Because it's fixed, the row has a minimum width. Below about 382dp the
 * weighted spacer that holds the transport to the right runs out, so check
 * this if a narrower phone turns up.
 */
val BarWord = 58.dp
/**
 * What a screen lays its controls out with, whichever way the bar runs.
 *
 * The bar is a row on an upright phone and a column on the right edge when
 * it's turned, and the screen shouldn't need to know which. So a pill asks
 * for what it is (anchored, a word wide, or sharing what's left) and the bar
 * turns that into the right axis.
 */
@Stable
class BarScope internal constructor(
    /** True when the bar runs down the screen. Rarely needed; glyphs use it. */
    val vertical: Boolean,
    /**
     * False when the row is too narrow to spell things out, so a pill with a
     * word shows its glyph alone.
     *
     * Everything in the row has a fixed width, so a larger interface scale can
     * push it below its minimum (at 1.3 a Pixel 5 has 302dp and the arranger's
     * row wants 318). Words are the cheapest thing to drop, so they go first
     * and the row only shrinks for whatever is still missing. See [BottomBar].
     */
    val words: Boolean = true,
    private val weigh: (Modifier, Float) -> Modifier,
    private val space: (Modifier, Float) -> Modifier = weigh,
) {
    /**
     * A pill that shares what the fixed ones leave.
     *
     * Different from [barSpace] even though both are weights. A pill sharing
     * the slack still wants an anchor's width, and the row's fitting has to
     * know that, or at a large interface scale the view toggles end up as
     * slivers.
     */
    fun Modifier.barWeight(weight: Float = 1f): Modifier = weigh(this, weight)

    /**
     * The slack between the two ends of the row, which may be nothing.
     *
     * Holds the transport against the right edge. Unlike [barWeight] it isn't
     * a control and needs no width, so the row can use all of it before it
     * starts shrinking anything.
     */
    fun Modifier.barSpace(weight: Float = 1f): Modifier = space(this, weight)

    /**
     * The anchored size across the bar's axis. When the bar runs down the
     * screen the pill also gets [BarPillH] as its height.
     */
    val anchor: Modifier
        get() = if (vertical) Modifier.width(BarAnchor).height(BarPillH) else Modifier.width(BarAnchor)

    /**
     * Wide enough for a word (see [BarWord]), or an anchor's width when
     * [words] says there's no room.
     */
    val word: Modifier
        get() {
            val w = if (words) BarWord else BarAnchor
            return if (vertical) Modifier.width(w).height(BarPillH) else Modifier.width(w)
        }
}

/**
 * How tall a pill is when the bar runs down the screen.
 *
 * Pills keep their upright shape instead of sharing the column's height,
 * which would squash them into flat ovals. If they don't all fit, [BottomBar]
 * adds another column. 40 because that's the height of Material's button
 * upright.
 */
val BarPillH = 40.dp

/**
 * [vertical] runs the bar down the screen instead of across it.
 *
 * The [readout] isn't drawn when vertical, since the column is only about one
 * pill wide. A screen that wants it sideways places it somewhere else.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun BottomBar(
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
    /**
     * The same pills inside another row.
     *
     * Sideways, the editor puts its transport in the header next to the clip
     * name instead of in its own bar, to save space. So there's no
     * background, no width taken and no readout, and the caller decides
     * where it goes. Which pills, their order and their size still come from
     * here.
     */
    inline: Boolean = false,
    /** Lines above the buttons, like the arranger's position and diagnostics. */
    readout: @Composable ColumnScope.() -> Unit = {},
    content: @Composable BarScope.() -> Unit,
) {
    if (inline) {
        Row(
            modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // No weights here. A weighted child of a row measured with
            // unbounded width gets no space and isn't drawn. The header row is
            // only as wide as its contents, so every pill gets its anchored
            // size.
            BarScope(false, true, { m, _ -> m.width(BarAnchor) }).content()
        }
        return
    }
    if (vertical) {
        // The pills keep their shape and the bar adds another column.
        //
        // A `FlowColumn` fills top to bottom and then starts a new column to
        // the right, so the order down the bar matches the order along the
        // upright row, and play always ends up in the bottom corner nearest
        // the thumb.
        //
        // How many fit in a column is measured, because it depends on where
        // the keyboard divider is. `maxItemsInEachColumn` sets that and the
        // flow works out how many columns it needs.
        BoxWithConstraints(modifier.fillMaxHeight().background(Acid.colors.bar)) {
            val perColumn = ((maxHeight - 16.dp) / (BarPillH + 4.dp)).toInt().coerceAtLeast(1)
            FlowColumn(
                Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                maxItemsInEachColumn = perColumn,
            ) {
                BarScope(true, true, { m, _ -> m.width(BarAnchor).height(BarPillH) }).content()
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
        FittedBarRow(content)
    }
}

/**
 * The pill row, at its set widths if they fit and as close as it can get if
 * they don't. Always one line.
 *
 * Everything in the row has a fixed width, so the row has a minimum (about
 * 382dp). A larger interface scale can push any phone below it. An
 * overfilled `Row` pushes mix, rec and play off the right edge, and wrapping
 * puts the transport on its own line. Neither is OK for these controls.
 *
 * So it gives things up in order, cheapest first:
 *
 * 1. The words. A word pill is 14dp wider than an anchor and there's only one
 *    per row, so the loop pill drops to its glyph first.
 * 2. Everything, proportionally. Whatever is still missing comes off the
 *    whole row by drawing it at a smaller density, so pills, gaps and glyphs
 *    all shrink together and nothing clips.
 *
 * At a scale of 1.0 every supported phone fits the row with words, so none
 * of this kicks in.
 *
 * The natural width is measured with no width constraint at all, since a
 * weighted child of an unbounded row takes nothing. That gives exactly what
 * the fixed pills need.
 */
@Composable
private fun FittedBarRow(content: @Composable BarScope.() -> Unit) {
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val room = constraints.maxWidth
        val wide = subcompose(BarPass.Wide) { BarProbeRow(true, content) }
            .first().measure(Constraints()).width
        val words = wide <= room
        val need = if (words) wide else {
            subcompose(BarPass.Narrow) { BarProbeRow(false, content) }
                .first().measure(Constraints()).width
        }
        val squeeze = if (need > room && need > 0) room.toFloat() / need else 1f
        val row = subcompose(BarPass.Body) {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density * squeeze, base.fontScale),
            ) { BarPillRow(words, content) }
        }.first().measure(constraints.copy(minWidth = room))
        layout(row.width, row.height) { row.place(0, 0) }
    }
}

private enum class BarPass { Wide, Narrow, Body }

@Composable
private fun BarPillRow(words: Boolean, content: @Composable BarScope.() -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BarScope(
            false, words,
            weigh = { m, w -> with(this@Row) { m.weight(w) } },
            space = { m, w -> with(this@Row) { m.weight(w) } },
        ).content()
    }
}

/**
 * The same row, with every pill at the width it wants and the slack at zero.
 *
 * This is what's measured to see if the row fits. Measuring the real row
 * unbounded would give the weighted view toggles no width, but they need an
 * anchor each, and here they get it.
 */
@Composable
private fun BarProbeRow(words: Boolean, content: @Composable BarScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        BarScope(
            false, words,
            weigh = { m, _ -> m.width(BarAnchor) },
            space = { m, _ -> m },
        ).content()
    }
}

/**
 * A pill that's held rather than pressed.
 *
 * Only used for fill, the only control that means "while my finger is
 * down". A BarButton fires on release, which would make the fill land late.
 * Built on the same OutlinedButton so it matches its neighbours, with the
 * click disabled and the gesture handled directly.
 */
@Composable
fun BarHoldButton(
    label: String,
    modifier: Modifier = Modifier,
    held: Boolean = false,
    onHold: (Boolean) -> Unit,
) {
    val hold by rememberUpdatedState(onHold)
    // TalkBack can't hold a button down, so holding becomes a pair of actions.
    val press = stringResource(Res.string.a11y_fill_start)
    val release = stringResource(Res.string.a11y_fill_stop)
    OutlinedButton(
        modifier = modifier.button(
            label, stringResource(if (held) Res.string.a11y_held else Res.string.a11y_off),
            listOf(if (held) action(release) { hold(false) } else action(press) { hold(true) }),
        ).pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                hold(true)
                // Cancelled counts as released. A finger that slides off the
                // pill has stopped asking for a fill.
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
 * 4dp of padding rather than Material's 24, since a weighted middle share is
 * about 60dp on a phone and "⟳ scene" needs 56 of it. The label is one line
 * and never wraps, or the pill would make the row taller.
 */
@Composable
fun BarButton(
    label: String,
    modifier: Modifier = Modifier,
    /** Unspecified keeps the button's own content colour. */
    colour: Color = Color.Unspecified,
    /**
     * Colours the outline instead of the label, for a state that's about the
     * button rather than its label. Arming the transport uses it: a red ring
     * round the pill stands out more than a red glyph, and the glyph can keep
     * showing which state you're in.
     */
    border: Color? = null,
    enabled: Boolean = true,
    fontFamily: FontFamily? = null,
    /**
     * A long press on the same pill. It's handled here rather than with a
     * `Modifier.onLongPress` at the call site, because two detectors on one
     * button both fire: holding the record pill for the metronome would also
     * arm the transport on release. One detector owning both avoids that.
     */
    onLongPress: (() -> Unit)? = null,
    /** What TalkBack calls it, when [label] is a glyph. */
    description: String? = null,
    /** Its current state, for TalkBack. */
    state: String? = null,
    /** What a hold does, named for TalkBack's actions menu. */
    holdName: String? = null,
    /** Actions for a hold handled elsewhere, like a `Modifier.onLongPress` at the call site. */
    actions: List<androidx.compose.ui.semantics.CustomAccessibilityAction> = emptyList(),
    onClick: () -> Unit,
) {
    var suppressClick by remember { mutableStateOf(false) }
    val gestures = if (onLongPress == null) modifier else modifier.onLongPress {
        suppressClick = true
        onLongPress()
    }
    val named = actions + listOfNotNull(if (holdName != null && onLongPress != null) action(holdName, onLongPress) else null)
    OutlinedButton(
        modifier = if (description != null || state != null || named.isNotEmpty()) {
            gestures.button(description ?: label, state, named)
        } else {
            gestures
        },
        onClick = { if (suppressClick) suppressClick = false else onClick() },
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 4.dp),
        // Fall back to Material's outline, not null. Null means no border
        // at all and would strip the outline from every pill.
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
 * Stops everything.
 *
 * There's no button for this on the bar. It's a long press on play/stop, the
 * control your thumb is already on when something goes wrong, in the same
 * corner of every screen. The load meter is in each header on its own
 * (`ui/LoadMeter.kt`). It's also in the About window, on a keyboard
 * shortcut, on the Launchpad, and on `Action.Panic` from a mapped controller.
 *
 * The calls below belong together. After a panic nothing is held, so the
 * MIDI hub's list of sounding notes is cleared too. A hub that still thinks
 * a note is down would never send its note-off, and a synth on the other end
 * of a cable would hold it.
 */
fun panicEverything() {
    // It also stops the transport. Otherwise the sequencer would keep
    // running and send the same notes again on the next block.
    //
    // `Action.Panic` from a controller goes through here too, so it stops as
    // well. `Action.Stop` is still there for a pad that should only stop.
    com.rm.acidulous.engine.NativeEngine.transportStop()
    com.rm.acidulous.engine.NativeEngine.panic()
    com.rm.acidulous.midi.MidiHub.forgetSounding()
}
