package com.rm.acidulous.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.rm.acidulous.ui.theme.Acid

/**
 * Set where a panel has a whole screen to fill, the second screen's. The
 * cards are sized by their controls and laid out in bands down the room, see
 * [GridCards].
 */
internal val LocalPanelGrid = compositionLocalOf { false }

/** What one card needs: its control count and the size of its biggest control, in px. */
internal data class CardNeed(val count: Int, val cellW: Int, val cellH: Int)

/** Where a card goes: its band, its grid of cells and its width once its band is stretched, in px. */
internal data class CardShape(val band: Int, val cols: Int, val lines: Int, val width: Int)

/** How a section's cards are laid out: [bands] rows of cards, each [bandH] tall. */
internal data class BandPlan(
    val bands: Int,
    val bandH: Int,
    val cards: List<CardShape>,
    /** The widest band before stretching. Wider than the room means the panel scrolls. */
    val widest: Int,
    /** How wide the cards are laid out: the room, or the widest band when that's wider. */
    val contentW: Int,
)

/**
 * A card's cells for [count] controls [cellH] tall in a body [bodyH] tall:
 * as many lines as fit, then as few columns as hold them, then as few lines
 * as those columns need. Returns columns and lines.
 */
internal fun cardGrid(count: Int, cellH: Int, bodyH: Int): Pair<Int, Int> {
    if (count <= 1) return 1 to 1
    val fit = if (cellH <= 0) count else (bodyH / cellH).coerceIn(1, count)
    val cols = (count + fit - 1) / fit
    return cols to (count + cols - 1) / cols
}

/**
 * Lays [cards] out in bands down a room [width] x [height], cards kept in
 * order. [padW] and [padH] are what a card adds around its cells (its padding
 * and title), [gap] the space between cards and bands.
 *
 * The fewest bands whose widest band fits the width win. When no number of
 * bands fits, the one with the narrowest widest band does, and it scrolls.
 * Every band is stretched to the widest band or the room, whichever is wider,
 * its cards sharing the extra.
 */
internal fun planBands(cards: List<CardNeed>, width: Int, height: Int, gap: Int, padW: Int, padH: Int): BandPlan {
    val n = cards.size
    if (n == 0) return BandPlan(1, height, emptyList(), 0, width)
    data class Try(val bands: Int, val bandH: Int, val widths: List<Int>, val cols: List<Pair<Int, Int>>, val starts: List<Int>, val widest: Int)
    fun attempt(b: Int): Try {
        val bandH = ((height - gap * (b - 1)) / b).coerceAtLeast(0)
        val grids = cards.map { cardGrid(it.count, it.cellH, bandH - padH) }
        val widths = cards.mapIndexed { i, c -> grids[i].first * c.cellW + padW }
        val (starts, widest) = partition(widths, b, gap)
        return Try(b, bandH, widths, grids, starts, widest)
    }
    // Bands so short a card can't fit one line are out, unless even one band is.
    val tries = (1..n).filter { b ->
        val bandH = (height - gap * (b - 1)) / b
        cards.all { it.cellH + padH <= bandH }
    }.ifEmpty { listOf(1) }.map(::attempt)
    val best = tries.firstOrNull { it.widest <= width } ?: tries.minBy { it.widest }
    val contentW = maxOf(width, best.widest)
    val shapes = ArrayList<CardShape>(n)
    for (band in 0 until best.bands) {
        val from = best.starts[band]
        val to = if (band + 1 < best.bands) best.starts[band + 1] else n
        val m = to - from
        val used = (from until to).sumOf { best.widths[it] } + gap * (m - 1)
        val extra = contentW - used
        for (i in from until to) {
            // The extra split evenly, the odd pixels to the first cards.
            val share = extra / m + if (i - from < extra % m) 1 else 0
            shapes += CardShape(band, best.cols[i].first, best.cols[i].second, best.widths[i] + share)
        }
    }
    return BandPlan(best.bands, best.bandH, shapes, best.widest, contentW)
}

/**
 * Splits [widths] into [bands] runs in order, making the widest run as narrow
 * as it can be. A run is its widths plus a [gap] between each. Returns where
 * each run starts and the widest run.
 */
private fun partition(widths: List<Int>, bands: Int, gap: Int): Pair<List<Int>, Int> {
    val n = widths.size
    val sum = IntArray(n + 1).also { s -> for (i in 0 until n) s[i + 1] = s[i] + widths[i] }
    fun run(from: Int, to: Int) = sum[to] - sum[from] + gap * (to - from - 1)
    // best[b][j]: the widest run splitting the first j widths into b runs; cut[b][j] where the last run starts.
    val inf = Int.MAX_VALUE
    val best = Array(bands + 1) { IntArray(n + 1) { inf } }
    val cut = Array(bands + 1) { IntArray(n + 1) }
    best[0][0] = 0
    for (b in 1..bands) for (j in b..n) for (i in (b - 1) until j) {
        if (best[b - 1][i] == inf) continue
        val w = maxOf(best[b - 1][i], run(i, j))
        if (w < best[b][j]) { best[b][j] = w; cut[b][j] = i }
    }
    val starts = IntArray(bands)
    var j = n
    for (b in bands downTo 1) { starts[b - 1] = cut[b][j]; j = cut[b][j] }
    return starts.toList() to best[bands][n]
}

/** What [GridCards] shares with its cards. */
private class GridState {
    /** Each card's need, keyed by the card, as it was last measured. */
    val needs = mutableStateMapOf<Any, CardNeed>()
    /** The room, which also bounds a control measured for its natural size. */
    var viewW = 0
    var viewH = 0
}

private val LocalGridState = compositionLocalOf<GridState?> { null }

/** The dial and text style from outside the cards, for a window opened from one. */
private val LocalOutsideGrid = compositionLocalOf<Pair<Dp, androidx.compose.ui.text.TextStyle>?> { null }

/** Puts back what a window opened from a grid card shouldn't inherit from it. */
@Composable
internal fun OutsideGrid(content: @Composable () -> Unit) {
    val outside = LocalOutsideGrid.current
    if (outside == null && !LocalPanelGrid.current) { content(); return }
    CompositionLocalProvider(
        LocalPanelGrid provides false,
        LocalGridState provides null,
        LocalKnobDial provides (outside?.first ?: LocalKnobDial.current),
        androidx.compose.material3.LocalTextStyle provides (outside?.second ?: androidx.compose.material3.LocalTextStyle.current),
        content = content,
    )
}

private val GridGap = 6.dp
private val CardPad = 6.dp
private val CardTitleH = 14.dp
/** A knob's label and value lines at the cards' tight line height. */
private val KnobTextH = 24.dp
/** The air either side of a dial in a cell. */
private val CellAir = 6.dp

/**
 * The cards of one section filling the room. Every knob is drawn at the
 * panel's usual dial. A card's cells are the size of its biggest control,
 * as many lines of them as its band's height holds, and the cards go in
 * bands down the room, see [planBands]. When they don't fit the width the
 * panel scrolls sideways.
 *
 * Each card reports its need while it's measured, and the next frame lays
 * out by it. A need only grows while the card keeps its control count, so
 * the layout settles instead of going back and forth.
 */
@Composable
internal fun GridCards(content: @Composable () -> Unit) {
    val state = remember { GridState() }
    // Sizes of anything placed here that isn't a card, measured loosely.
    val others = remember { mutableStateMapOf<Int, IntSize>() }
    val scroll = rememberScrollState()
    SubcomposeLayout(
        // The scroll keeps the room's width as the least width, so that's the room.
        Modifier.fillMaxSize().horizontalScrollWithBar(scroll),
    ) { c ->
        val viewW = c.minWidth
        val h = c.maxHeight
        state.viewW = viewW
        state.viewH = h
        val gap = GridGap.roundToPx()
        val padW = CardPad.roundToPx() * 2
        val padH = CardPad.roundToPx() * 2 + CardTitleH.roundToPx()
        val kids = subcompose("cards") {
            CompositionLocalProvider(LocalGridState provides state) { content() }
        }
        val needs = kids.mapIndexed { i, k ->
            val id = k.layoutId
            if (id is GridKey) state.needs[id]
            else others[i]?.let { CardNeed(1, (it.width - padW).coerceAtLeast(0), (it.height - padH).coerceAtLeast(0)) }
        }
        val ready = needs.all { it != null }
        val plan = planBands(needs.map { it ?: CardNeed(1, 0, 0) }, viewW, h, gap, padW, padH)
        val placed = kids.mapIndexed { i, k ->
            val shape = plan.cards[i]
            if (k.layoutId is GridKey) {
                k.measure(Constraints.fixed(shape.width.coerceAtLeast(0), plan.bandH))
            } else {
                k.measure(Constraints(maxWidth = viewW.coerceAtLeast(0), maxHeight = h)).also {
                    val size = IntSize(it.width, it.height)
                    if (others[i] != size) others[i] = size
                }
            }
        }
        layout(if (ready) plan.contentW else viewW, h) {
            // Until every card has reported, the layout would be wrong for a frame, so it waits.
            if (!ready) return@layout
            var x = 0
            var band = 0
            placed.forEachIndexed { i, p ->
                val shape = plan.cards[i]
                if (shape.band != band) { band = shape.band; x = 0 }
                p.place(x, band * (plan.bandH + gap))
                x += shape.width + gap
            }
        }
    }
}

/** A card's identity in [GridCards], and what it measures by. */
private class GridKey {
    /** The biggest control seen while the card has had [count] controls. */
    var count = -1
    var cellW = 0
    var cellH = 0
}

/** One card in [GridCards]: its title, then its controls in cells of one size. */
@Composable
internal fun GridCard(title: String, background: Color, content: @Composable () -> Unit) {
    val state = LocalGridState.current
    val me = remember { GridKey() }
    DisposableEffect(state, me) { onDispose { state?.needs?.remove(me) } }
    val dial = LocalKnobDial.current
    val outsideStyle = androidx.compose.material3.LocalTextStyle.current
    Column(
        Modifier.layoutId(me).fillMaxSize().clip(RoundedCornerShape(6.dp)).background(background).padding(CardPad).together(),
    ) {
        Text(
            if (title.isEmpty()) " " else panelWord(title), color = Acid.colors.teal, fontSize = 9.sp,
            fontFamily = FontFamily.Monospace, maxLines = 1,
            // A fixed height, so the body's height is what GridCards planned for.
            modifier = Modifier.height(CardTitleH).semantics { heading() },
        )
        // Label and value lines just taller than their words, so a cell is little more than its dial.
        val tight = androidx.compose.material3.LocalTextStyle.current.merge(
            androidx.compose.ui.text.TextStyle(lineHeight = 1.3.em),
        )
        CompositionLocalProvider(
            LocalOutsideGrid provides (LocalOutsideGrid.current ?: (dial to outsideStyle)),
            androidx.compose.material3.LocalTextStyle provides tight,
            // Switches fill a control's height, which here is a knob's.
            LocalControlH provides dial + KnobTextH,
        ) {
            Layout(content, Modifier.fillMaxSize()) { ms, c ->
                // Each control at its own size, bounded by the room so nothing measures to infinity.
                val loose = Constraints(maxWidth = (state?.viewW ?: c.maxWidth).coerceAtLeast(0), maxHeight = (state?.viewH ?: c.maxHeight).coerceAtLeast(0))
                val ps = ms.map { it.measure(loose) }
                val minW = dial.roundToPx() + CellAir.roundToPx() * 2
                val w = maxOf(minW, ps.maxOfOrNull { it.width } ?: 0)
                val h = ps.maxOfOrNull { it.height } ?: 0
                if (me.count != ps.size) { me.count = ps.size; me.cellW = w; me.cellH = h }
                else { me.cellW = maxOf(me.cellW, w); me.cellH = maxOf(me.cellH, h) }
                val need = CardNeed(ps.size, me.cellW, me.cellH)
                if (state != null && state.needs[me] != need) state.needs[me] = need
                val (cols, lines) = cardGrid(ps.size, me.cellH, c.maxHeight)
                val cellW = c.maxWidth / cols
                val cellH = c.maxHeight / lines
                layout(c.maxWidth, c.maxHeight) {
                    ps.forEachIndexed { i, p ->
                        val col = i % cols
                        val line = i / cols
                        p.place(col * cellW + (cellW - p.width) / 2, line * cellH + (cellH - p.height) / 2)
                    }
                }
            }
        }
    }
}

/** Whether [Group] is drawing as a grid card here. */
@Composable
internal fun inGridCards(): Boolean = LocalGridState.current != null
