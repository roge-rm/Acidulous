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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
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
fun rememberTopCutout(): TopCutout? {
    val view = LocalView.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.displayCutout
    // All four sides and the window size are keys: a fold, a multi-window
    // resize or a rotation can move the hole in window coordinates without
    // changing the value this row happens to care about.
    val top = insets.getTop(density)
    val bottom = insets.getBottom(density)
    val left = insets.getLeft(density, direction)
    val right = insets.getRight(density, direction)
    val size = LocalWindowInfo.current.containerSize
    return remember(top, bottom, left, right, size, view) {
        if (top <= 0) return@remember null
        val blocked = TopCutout(0, size.width, top)
        val cutout = ViewCompat.getRootWindowInsets(view)?.displayCutout ?: return@remember blocked
        var l = Int.MAX_VALUE
        var r = Int.MIN_VALUE
        var b = 0
        for (rect in cutout.boundingRects) {
            // Only the top edge; a side cutout is left to the ordinary window
            // insets. Two holes on one edge are covered as one span, which is
            // conservative and, with one elastic child, loses nothing.
            if (rect.top > 0 || rect.isEmpty) continue
            l = min(l, rect.left)
            r = max(r, rect.right)
            b = max(b, rect.bottom)
        }
        // The rectangles are in display coordinates and the inset is in window
        // coordinates. They agree for a full-screen window, and when they do
        // not, the rectangles are describing some other frame of reference.
        if (r <= l || b != cutout.safeInsetTop) blocked else TopCutout(l, r, b)
    }
}

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

    Layout(content = { CutoutRowScopeImpl.content() }, modifier = modifier) { measurables, constraints ->
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
