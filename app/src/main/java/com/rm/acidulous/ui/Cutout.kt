package com.rm.acidulous.ui

import androidx.compose.foundation.layout.LayoutScopeMarker
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
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
 * 136 pixel box in the corner. The row that lives up there is a handful of
 * buttons and one label, which is exactly the kind of content that can step
 * around a hole instead.
 *
 * So: the row is told where the hole is and flows around it. A hole at the
 * left pushes everything right; a hole at the right pulls the trailing
 * controls in; a hole in the middle takes its width out of the one elastic
 * child, which ellipsizes. When what is left is too narrow to use, the row
 * gives up and sits below the hole, which is where it would have been anyway.
 */

/** The top edge's blocked span, in window pixels. */
@Immutable
data class TopCutout(val left: Int, val right: Int, val height: Int)

/**
 * The camera hole on the top edge, or null when the screen has none.
 *
 * The inset value is read through Compose so that this recomposes when the
 * phone turns - the activity handles rotation itself and is never recreated,
 * so nothing else would notice. The rectangles come from androidx, which
 * returns nothing below API 28 and so answers null there.
 */
@Composable
fun rememberTopCutout(): TopCutout? {
    val view = LocalView.current
    val density = LocalDensity.current
    val topInset = WindowInsets.displayCutout.getTop(density)
    val orientation = LocalConfiguration.current.orientation
    return remember(topInset, orientation, view) {
        if (topInset <= 0) return@remember null
        val cutout = ViewCompat.getRootWindowInsets(view)?.displayCutout ?: return@remember null
        var left = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = 0
        for (rect in cutout.boundingRects) {
            // Only the top edge matters here; a side cutout is left to the
            // ordinary window insets. Two holes on one edge are covered as
            // one span, which is conservative and still correct.
            if (rect.top > 0 || rect.isEmpty) continue
            left = min(left, rect.left)
            right = max(right, rect.right)
            bottom = max(bottom, rect.bottom)
        }
        if (right <= left) null else TopCutout(left, right, max(bottom, topInset))
    }
}

@LayoutScopeMarker
interface CutoutRowScope {
    /**
     * Marks the one child that gives up its width to the hole - the row's
     * label. Everything else keeps the width it asks for.
     */
    fun Modifier.fill(): Modifier
}

private object FillMarker

private object FillParentData : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = FillMarker
}

private object CutoutRowScopeImpl : CutoutRowScope {
    override fun Modifier.fill(): Modifier = this.then(FillParentData)
}

/**
 * A row for the top edge of the screen that steps around the camera hole.
 *
 * Place it full bleed - it applies [contentPadding] itself - because it
 * works in window coordinates and the only thing it can account for between
 * itself and the window edge is the insets the Scaffold already applied.
 *
 * At most one child may call [CutoutRowScope.fill]; it takes whatever width
 * is left in its segment. If that falls below [minFill] the row abandons the
 * strip and lays out below the hole instead.
 */
@Composable
fun CutoutRow(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    spacing: Dp = 0.dp,
    minFill: Dp = 72.dp,
    content: @Composable CutoutRowScope.() -> Unit,
) {
    val cutout = rememberTopCutout()
    val direction = LocalLayoutDirection.current
    val density = LocalDensity.current
    val originX = WindowInsets.displayCutout.union(WindowInsets.navigationBars).getLeft(density, direction)

    Layout(content = { CutoutRowScopeImpl.content() }, modifier = modifier) { measurables, constraints ->
        val padStart = contentPadding.calculateStartPadding(direction).roundToPx()
        val padEnd = contentPadding.calculateEndPadding(direction).roundToPx()
        val padTop = contentPadding.calculateTopPadding().roundToPx()
        val padBottom = contentPadding.calculateBottomPadding().roundToPx()
        val gap = spacing.roundToPx()
        val minFillPx = minFill.roundToPx()

        val width = constraints.maxWidth
        val innerLeft = padStart
        val innerRight = max(innerLeft, width - padEnd)

        val fillIndex = measurables.indexOfFirst { it.parentData === FillMarker }
        val loose = Constraints(maxWidth = if (constraints.hasBoundedWidth) width else Constraints.Infinity)

        // Everything but the elastic child at its own width.
        val fixed = arrayOfNulls<androidx.compose.ui.layout.Placeable>(measurables.size)
        measurables.forEachIndexed { i, m -> if (i != fillIndex) fixed[i] = m.measure(loose) }
        fun spanOf(range: IntRange): Int {
            var total = 0
            for (i in range) {
                val w = fixed[i]?.width ?: 0
                if (w == 0) continue // a collapsed menu anchor is not a column
                if (total > 0) total += gap
                total += w
            }
            return total
        }
        val beforeW = spanOf(0 until (if (fillIndex >= 0) fillIndex else measurables.size))
        val afterW = if (fillIndex >= 0) spanOf(fillIndex + 1 until measurables.size) else 0
        val fillGaps = if (fillIndex >= 0) (if (beforeW > 0) gap else 0) + (if (afterW > 0) gap else 0) else 0

        // The hole in this row's own coordinates, clipped to what it owns.
        val holeLeft = if (cutout == null) 0 else cutout.left - originX
        val holeRight = if (cutout == null) 0 else cutout.right - originX
        val holeHeight = cutout?.height ?: 0
        val blocked = cutout != null && holeRight > innerLeft && holeLeft < innerRight

        // Three ways to stand beside a hole, in the order they are worth
        // trying: past it, short of it, or astride it.
        data class Plan(val leadX: Int, val fillW: Int, val trailX: Int, val below: Boolean)
        fun straight(from: Int, to: Int): Plan {
            val room = max(0, to - from - beforeW - afterW - fillGaps)
            return Plan(from, room, to - afterW, false)
        }
        val plan = when {
            !blocked -> straight(innerLeft, innerRight)
            holeLeft <= innerLeft -> straight(max(innerLeft, holeRight), innerRight)
            holeRight >= innerRight -> straight(innerLeft, min(innerRight, holeLeft))
            else -> {
                // Astride: the lead and the label take the space before the
                // hole, the trailing controls the space after it.
                val room = max(0, holeLeft - innerLeft - beforeW - fillGaps)
                if (afterW <= innerRight - holeRight) Plan(innerLeft, room, innerRight - afterW, false)
                else straight(max(innerLeft, holeRight), innerRight)
            }
        }
        val usable = if (fillIndex >= 0) plan.fillW >= minFillPx else plan.fillW >= 0
        val final = if (usable || !blocked) plan else straight(innerLeft, innerRight).copy(below = true)

        val fillPlaceable = if (fillIndex < 0) null else measurables[fillIndex].measure(
            Constraints(minWidth = max(0, final.fillW), maxWidth = max(0, final.fillW)),
        )
        val contentH = max(
            fixed.maxOfOrNull { it?.height ?: 0 } ?: 0,
            fillPlaceable?.height ?: 0,
        )
        // Standing beside the hole only works if the row is as tall as it;
        // otherwise whatever follows would run underneath.
        val bandTop = if (final.below) holeHeight + padTop else padTop
        val rowH = if (final.below) bandTop + contentH + padBottom
        else max(contentH + padTop + padBottom, holeHeight)

        layout(width, rowH) {
            fun y(h: Int) = if (final.below) bandTop + (contentH - h) / 2 else (rowH - h) / 2
            val lead = if (fillIndex >= 0) fillIndex else measurables.size

            var x = final.leadX
            for (i in 0 until lead) {
                val p = fixed[i] ?: continue
                p.place(x, y(p.height))
                if (p.width > 0) x += p.width + gap
            }
            fillPlaceable?.let {
                it.place(x, y(it.height))
            }
            // The trailing controls hang off the right of their own segment,
            // which is what puts them on the far side of a centred hole.
            x = final.trailX
            for (i in lead + 1 until measurables.size) {
                val p = fixed[i] ?: continue
                p.place(x, y(p.height))
                if (p.width > 0) x += p.width + gap
            }
        }
    }
}
