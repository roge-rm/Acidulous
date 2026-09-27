package com.rm.acidulous.ui

import androidx.compose.foundation.layout.LayoutScopeMarker
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * Lays the top row out around the camera hole.
 *
 * A phone with a camera hole reports a safe inset for the whole top edge,
 * and starting the app below it wastes a full-width strip to avoid something
 * the size of a fingertip. The top row is a few buttons and a label, which
 * can easily step around a hole.
 *
 * So the row is told where the hole is and flows around it. A hole on the
 * left pushes everything right, a hole on the right pulls the trailing
 * controls in, and a hole in the middle splits the row in two with the
 * flexible child giving up the width. If what's left is too narrow, the row
 * goes below the hole.
 */

/**
 * The insets the app keeps clear: the sides, for a cutout in landscape or a
 * curved edge, and the bottom, for the gesture handle. Never the top, which
 * the headers lay out around themselves. [CutoutRow] uses this to find its
 * own left edge, so the two must stay the same expression.
 */
val AppContentInsets: WindowInsets
    @Composable get() = WindowInsets.displayCutout.union(WindowInsets.navigationBars)
        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)

@LayoutScopeMarker
interface CutoutRowScope {
    /**
     * Marks the one child that gives up its width to the hole, i.e. the row's
     * label. Like [androidx.compose.foundation.layout.RowScope.weight] here:
     * everything else keeps the width it asks for.
     */
    fun Modifier.flexible(): Modifier
}

private object FlexibleMarker

private object FlexibleParentData : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = FlexibleMarker
}

private object CutoutRowScopeImpl : CutoutRowScope {
    override fun Modifier.flexible(): Modifier = this.then(FlexibleParentData)
}

/** One usable run of the row, between the padding and the hole. */
private data class Segment(val start: Int, val end: Int) {
    val width get() = end - start
}

/** Where everything goes: which segment leads, and how many trailing children follow it there. */
private data class RowPlan(val segment: Int, val spill: Int, val flexWidth: Int, val below: Boolean)

/**
 * How tall a header control can be without costing any space.
 *
 * A row next to the camera hole is already as tall as the hole (see `rowH`
 * below), so shorter controls would leave a gap under them. The row
 * publishes its band height and header controls fill it, so on a cutout
 * phone they get bigger for free and elsewhere they use [MIN_BAND].
 */
val LocalHeaderBand = androidx.compose.runtime.compositionLocalOf { 44.dp }

/** The minimum band height, cutout or not. Material asks for 48, and glyph
 *  buttons only 30 wide are easy to miss. */
private val MIN_BAND = 44.dp

/**
 * A row for the top edge of the screen that steps around the camera hole.
 *
 * Place it full width as the first child of the screen, at window y = 0. It
 * applies [contentPadding] itself because it has to compare the hole's
 * position with its children in one coordinate space, and it grows to the
 * height of the hole so whatever follows clears it.
 *
 * At most one child may use [CutoutRowScope.flexible], and it takes whatever
 * is left in its run. If that's less than [minFlexible], the row lays out
 * below the hole instead.
 */
@Composable
fun CutoutRow(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    spacing: Dp = 0.dp,
    minFlexible: Dp = 72.dp,
    content: @Composable CutoutRowScope.() -> Unit,
) {
    val cutout = rememberTopCutout()
    val direction = LocalLayoutDirection.current
    val density = LocalDensity.current
    val originX = AppContentInsets.getLeft(density, direction)

    val band = with(density) {
        val pads = contentPadding.calculateTopPadding() + contentPadding.calculateBottomPadding()
        val beside = ((cutout?.height ?: 0).toDp() + CLEARANCE) - pads
        if (beside > MIN_BAND) beside else MIN_BAND
    }

    Layout(
        content = {
            androidx.compose.runtime.CompositionLocalProvider(LocalHeaderBand provides band) {
                CutoutRowScopeImpl.content()
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val padStart = contentPadding.calculateStartPadding(direction).roundToPx()
        val padEnd = contentPadding.calculateEndPadding(direction).roundToPx()
        val padTop = contentPadding.calculateTopPadding().roundToPx()
        val padBottom = contentPadding.calculateBottomPadding().roundToPx()
        val gap = spacing.roundToPx()
        val minFlexPx = minFlexible.roundToPx()
        val clearance = CLEARANCE.roundToPx()

        // The row is a screen-width header. An unbounded width means it's
        // been put somewhere it can't work.
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val bandStart = padStart
        val bandEnd = max(bandStart, width - padEnd)

        val flexIndex = measurables.indexOfFirst { it.parentData === FlexibleMarker }
        val child = Constraints(maxWidth = Constraints.Infinity, maxHeight = constraints.maxHeight)
        val fixed = arrayOfNulls<Placeable>(measurables.size)
        measurables.forEachIndexed { i, m -> if (i != flexIndex) fixed[i] = m.measure(child) }

        // A collapsed menu anchor measures zero and shouldn't get a gap.
        val leadIdx = (0 until (if (flexIndex >= 0) flexIndex else measurables.size))
            .filter { (fixed[it]?.width ?: 0) > 0 }
        val trailIdx = (if (flexIndex >= 0) flexIndex + 1 until measurables.size else IntRange.EMPTY)
            .filter { (fixed[it]?.width ?: 0) > 0 }
        fun packed(idx: List<Int>) =
            if (idx.isEmpty()) 0 else idx.sumOf { fixed[it]!!.width } + gap * (idx.size - 1)
        val leadW = packed(leadIdx)

        val holeHeight = cutout?.height ?: 0
        val segments = if (cutout == null) {
            listOf(Segment(bandStart, bandEnd))
        } else {
            val hl = cutout.left - originX
            val hr = cutout.right - originX
            listOf(
                Segment(bandStart, min(bandEnd, hl)),
                Segment(max(bandStart, hr), bandEnd),
            ).filter { it.width > 0 }
        }

        // Try each run in turn, and within it move the trailing controls
        // over one at a time. With a hole in the middle, the ones that fit
        // stay next to the label and the rest go past the hole.
        var plan: RowPlan? = null
        outer@ for (s in segments.indices) {
            if (leadW > segments[s].width) continue
            val last = segments.lastIndex
            for (spill in 0..trailIdx.size) {
                val head = trailIdx.take(spill)
                val tail = trailIdx.drop(spill)
                val tailW = packed(tail)
                if (s != last && tailW > segments[last].width) continue
                val headW = if (s == last) packed(trailIdx) else packed(head)
                val gaps = (if (leadW > 0) gap else 0) + (if (headW > 0) gap else 0)
                val room = segments[s].width - leadW - headW - (if (flexIndex >= 0) gaps else max(0, gaps - gap))
                val fits = if (flexIndex >= 0) room >= minFlexPx else room >= 0
                if (fits) {
                    plan = RowPlan(s, if (s == last) trailIdx.size else spill, max(0, room), false)
                    break@outer
                }
            }
        }
        val final = plan ?: RowPlan(0, trailIdx.size, max(0, bandEnd - bandStart - leadW - packed(trailIdx) -
            (if (leadW > 0) gap else 0) - (if (trailIdx.isNotEmpty()) gap else 0)), true)

        val flexPlaceable = if (flexIndex < 0) null
        else measurables[flexIndex].measure(
            Constraints(minWidth = final.flexWidth, maxWidth = final.flexWidth, maxHeight = constraints.maxHeight),
        )
        val contentH = max(fixed.maxOfOrNull { it?.height ?: 0 } ?: 0, flexPlaceable?.height ?: 0)
        // Sitting next to the hole only works if the row is as tall as it,
        // or whatever follows would run underneath.
        val bandTop = if (final.below) holeHeight + clearance else 0
        val rowH = (
            if (final.below) bandTop + padTop + contentH + padBottom
            else max(padTop + contentH + padBottom, holeHeight + clearance)
            ).coerceIn(constraints.minHeight, constraints.maxHeight)

        layout(width, rowH) {
            // Aligned to the top of its band rather than centred in the
            // taller height, so a taller hole doesn't push the header down.
            fun y(h: Int) = bandTop + padTop + max(0, (contentH - h) / 2)
            fun put(p: Placeable, x: Int) =
                p.place(if (direction == LayoutDirection.Rtl) width - x - p.width else x, y(p.height))

            val segs = if (final.below) listOf(Segment(bandStart, bandEnd)) else segments
            val seg = segs.getOrElse(final.segment) { Segment(bandStart, bandEnd) }
            var x = seg.start
            for (i in leadIdx) {
                put(fixed[i]!!, x)
                x += fixed[i]!!.width + gap
            }
            flexPlaceable?.let {
                put(it, x)
                x += it.width + gap
            }
            val head = trailIdx.take(final.spill)
            for (i in head) {
                put(fixed[i]!!, x)
                x += fixed[i]!!.width + gap
            }
            // Whatever's left goes at the end of the last run, which puts it
            // on the far side of a hole in the middle.
            val tail = trailIdx.drop(final.spill)
            x = segs.last().end - packed(tail)
            for (i in tail) {
                put(fixed[i]!!, x)
                x += fixed[i]!!.width + gap
            }
            // Zero-width nodes still need placing; a DropdownMenu is one.
            for (i in measurables.indices) {
                val p = fixed[i] ?: continue
                if (p.width == 0) p.place(seg.start, bandTop)
            }
        }
    }
}

/** A small gap between a control and the camera hole. */
private val CLEARANCE = 2.dp
