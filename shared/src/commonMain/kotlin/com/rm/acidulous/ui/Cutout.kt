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
 * Laying the top row out around the camera.
 *
 * A phone with a hole punched in its screen reports a safe inset for the
 * whole top edge, and the easy thing is to start the app below it. That
 * throws away a strip the width of the screen to dodge something the size of
 * a fingertip - on this project's emulator, 136 pixels of height to avoid a
 * 136 pixel box in one corner. The row that lives up there is a handful of
 * buttons and one label, which is exactly the kind of content that can step
 * around a hole instead.
 *
 * So the row is told where the hole is and flows around it. A hole at the
 * left pushes everything right; a hole at the right pulls the trailing
 * controls in; a hole in the middle splits the row in two, and the elastic
 * child gives up the width. When what is left is too narrow to use, the row
 * sits below the hole, which is where it would have been anyway.
 */

/**
 * What the app gives up around its content: the sides, for a cutout in
 * landscape or a curved edge, and the bottom, for the gesture handle. Never
 * the top - that strip is the headers' to lay out in, and [CutoutRow]
 * measures against this to know where its own left edge is, so the two must
 * be the same expression.
 */
val AppContentInsets: WindowInsets
    @Composable get() = WindowInsets.displayCutout.union(WindowInsets.navigationBars)
        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)

@LayoutScopeMarker
interface CutoutRowScope {
    /**
     * Marks the one child that gives up its width to the hole - the row's
     * label. This is [androidx.compose.foundation.layout.RowScope.weight]'s
     * job here: everything else keeps the width it asks for.
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

/** Where everything goes: which segment leads, how many trailing children follow it there. */
private data class RowPlan(val segment: Int, val spill: Int, val flexWidth: Int, val below: Boolean)

/**
 * A row for the top edge of the screen that steps around the camera hole.
 *
 * Place it full bleed and as the first child of the screen, at window y = 0 -
 * it applies [contentPadding] itself, because it has to compare the hole's
 * position against its children in one coordinate space, and it grows to the
 * height of the hole so that whatever follows clears the glass.
 *
 * At most one child may call [CutoutRowScope.flexible]; it takes whatever is
 * left in its run. If that falls below [minFlexible] the row gives up the
 * strip and lays out below the hole instead.
 */
/**
 * How tall a header control may be without costing the screen anything.
 *
 * A row standing beside the camera hole is already as tall as the hole -
 * that is the rule in `rowH` below, because whatever follows would
 * otherwise run underneath it. Anything shorter than that leaves dead space
 * under the buttons, which is exactly the gap you can see on a phone with a
 * tall cutout. So the row publishes the height of its own band and the
 * header controls fill it: on a cutout phone they get bigger for nothing,
 * and everywhere else they take the floor below.
 */
val LocalHeaderBand = androidx.compose.runtime.compositionLocalOf { 44.dp }

/** No smaller than this, cutout or not; Material asks for 48 and a glyph
 *  button that is only 30 wide is the one people miss. */
private val MIN_BAND = 44.dp

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

        // The row is a screen-width header; an unbounded width would mean it
        // has been put somewhere it cannot do its job.
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val bandStart = padStart
        val bandEnd = max(bandStart, width - padEnd)

        val flexIndex = measurables.indexOfFirst { it.parentData === FlexibleMarker }
        val child = Constraints(maxWidth = Constraints.Infinity, maxHeight = constraints.maxHeight)
        val fixed = arrayOfNulls<Placeable>(measurables.size)
        measurables.forEachIndexed { i, m -> if (i != flexIndex) fixed[i] = m.measure(child) }

        // A collapsed menu anchor measures zero and must not earn a gap.
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

        // Try each run in turn, and within it try handing the trailing
        // controls over one at a time: with a hole in the middle, the ones
        // that still fit stay beside the label and the rest go past it.
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
        // Standing beside the hole only works if the row is as tall as it,
        // or whatever follows would run underneath.
        val bandTop = if (final.below) holeHeight + clearance else 0
        val rowH = (
            if (final.below) bandTop + padTop + contentH + padBottom
            else max(padTop + contentH + padBottom, holeHeight + clearance)
            ).coerceIn(constraints.minHeight, constraints.maxHeight)

        layout(width, rowH) {
            // Anchored to the top of its band rather than centred in the
            // inflated height: a taller hole must not push the header down.
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
            // Whatever is left hangs off the end of the last run, which is
            // what puts it on the far side of a hole in the middle.
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

/** A hair of air between a control and the camera glass. */
private val CLEARANCE = 2.dp
