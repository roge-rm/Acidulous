package com.rm.acidulous.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.layout.Layout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.rm.acidulous.ui.theme.Acid
import kotlin.math.ceil

/**
 * Set where a panel has a whole screen to fill, the second screen's. The
 * cards share the room in even columns and equal rows and the knobs grow to
 * fill them, see [GridCards].
 */
internal val LocalPanelGrid = compositionLocalOf { false }

/** How the cards and the cells in them are laid out. */
private data class GridPlan(
    /** Cards to a row. */
    val across: Int = 1,
    /** Cells to a line in a card, and lines. */
    val cols: Int = 1,
    val lines: Int = 1,
    /** The dial every knob in the panel is drawn at. */
    val dial: Dp = 52.dp,
)

private val LocalGridPlan = compositionLocalOf<GridPlan?> { null }

/** Each card reports how many controls it holds as it is measured. */
private val LocalGridCount = compositionLocalOf<MutableMap<Any, Int>?> { null }

/** The dial and text style from outside the cards, for a window opened from one. */
private val LocalOutsideGrid = compositionLocalOf<Pair<Dp, androidx.compose.ui.text.TextStyle>?> { null }

/** Puts back what a window opened from a grid card shouldn't inherit from it. */
@Composable
internal fun OutsideGrid(content: @Composable () -> Unit) {
    val outside = LocalOutsideGrid.current
    if (outside == null && !LocalPanelGrid.current) { content(); return }
    CompositionLocalProvider(
        LocalPanelGrid provides false,
        LocalGridPlan provides null,
        LocalGridCount provides null,
        LocalKnobDial provides (outside?.first ?: LocalKnobDial.current),
        androidx.compose.material3.LocalTextStyle provides (outside?.second ?: androidx.compose.material3.LocalTextStyle.current),
        content = content,
    )
}

private val GridGap = 6.dp
private val CardPad = 6.dp
private val CardTitleH = 14.dp
/** A knob's label and value lines under and over the dial. */
private val KnobTextH = 28.dp
private val DialMin = 24.dp
private val DialMax = 72.dp

/**
 * The cards of one section filling the room. Every card gets the same size,
 * and every card has the same cells, sized for the card with the most controls,
 * so a card with fewer leaves cells empty and the knobs of cards side by side
 * line up. The number of cards to a row is the one that gives the biggest knobs.
 */
@Composable
internal fun GridCards(content: @Composable () -> Unit) {
    var plan by remember { mutableStateOf(GridPlan()) }
    // One entry per card, kept while the card is composed, so a remeasure that skips a card keeps its count.
    val counts = remember { mutableMapOf<Any, Int>() }
    SubcomposeLayout(Modifier.fillMaxSize()) { constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val gap = GridGap.roundToPx()
        val cards = subcompose("cards") {
            CompositionLocalProvider(LocalGridPlan provides plan, LocalGridCount provides counts) { content() }
        }
        val n = cards.size
        val across = plan.across.coerceIn(1, n.coerceAtLeast(1))
        val rows = if (n == 0) 1 else (n + across - 1) / across
        val cardW = ((w - gap * (across - 1)) / across).coerceAtLeast(0)
        val cardH = ((h - gap * (rows - 1)) / rows).coerceAtLeast(0)
        val fixed = Constraints.fixed(cardW, cardH)
        val placed = cards.map { it.measure(fixed) }
        // Counts are known now, so work out the plan the next frame draws.
        val kids = counts.values.maxOrNull() ?: 1
        val next = planFor(n, kids, w / density, h / density)
        if (next != plan) plan = next
        layout(w, h) {
            placed.forEachIndexed { i, p ->
                p.place((i % across) * (cardW + gap), (i / across) * (cardH + gap))
            }
        }
    }
}

private fun planFor(n: Int, kids: Int, w: Float, h: Float): GridPlan {
    if (n == 0) return GridPlan()
    val gap = GridGap.value
    val pad = CardPad.value * 2
    var best = GridPlan()
    var bestScore = -1f
    for (across in 1..n) {
        val rows = (n + across - 1) / across
        val cardW = (w - gap * (across - 1)) / across
        val cardH = (h - gap * (rows - 1)) / rows
        val bodyW = cardW - pad
        val bodyH = cardH - pad - CardTitleH.value
        for (cols in 1..kids) {
            val lines = ceil(kids / cols.toFloat()).toInt()
            val d = minOf(bodyW / cols - 6f, bodyH / lines - KnobTextH.value, DialMax.value)
            // Full rows of cards look even, so they win a close call.
            val score = d * (if (across * rows == n) 1.08f else 1f)
            if (score > bestScore) {
                bestScore = score
                best = GridPlan(across, cols, lines, d.coerceAtLeast(DialMin.value).dp)
            }
        }
    }
    return best
}

/** One card in [GridCards]: its title, then its controls in the shared cells. */
@Composable
internal fun GridCard(title: String, background: Color, content: @Composable () -> Unit) {
    val plan = LocalGridPlan.current ?: GridPlan()
    val counts = LocalGridCount.current
    val me = remember { Any() }
    DisposableEffect(counts, me) { onDispose { counts?.remove(me) } }
    val outsideDial = LocalKnobDial.current
    val outsideStyle = androidx.compose.material3.LocalTextStyle.current
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(background).padding(CardPad).together(),
    ) {
        Text(
            if (title.isEmpty()) " " else panelWord(title), color = Acid.colors.teal, fontSize = 9.sp,
            fontFamily = FontFamily.Monospace, maxLines = 1,
            modifier = Modifier.semantics { heading() },
        )
        // Label and value lines just taller than their words, so the dial gets the height.
        val tight = androidx.compose.material3.LocalTextStyle.current.merge(
            androidx.compose.ui.text.TextStyle(lineHeight = 1.3.em),
        )
        CompositionLocalProvider(
            LocalKnobDial provides plan.dial,
            LocalOutsideGrid provides (LocalOutsideGrid.current ?: (outsideDial to outsideStyle)),
            androidx.compose.material3.LocalTextStyle provides tight,
        ) {
            Layout(content, Modifier.fillMaxSize()) { ms, c ->
                counts?.put(me, ms.size)
                val cellW = c.maxWidth / plan.cols
                val cellH = c.maxHeight / plan.lines
                val loose = Constraints(0, cellW, 0, cellH)
                val ps = ms.map { it.measure(loose) }
                layout(c.maxWidth, c.maxHeight) {
                    ps.forEachIndexed { i, p ->
                        val col = i % plan.cols
                        val line = i / plan.cols
                        p.place(col * cellW + (cellW - p.width) / 2, line * cellH + (cellH - p.height) / 2)
                    }
                }
            }
        }
    }
}

/** Whether [Group] is drawing as a grid card here. */
@Composable
internal fun inGridCards(): Boolean = LocalGridPlan.current != null
