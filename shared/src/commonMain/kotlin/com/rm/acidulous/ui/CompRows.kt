package com.rm.acidulous.ui

import com.rm.acidulous.io.*

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.EngineSync
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.model.TakeRef
import com.rm.acidulous.model.findPhrases
import com.rm.acidulous.model.lengthTicks
import com.rm.acidulous.model.phraseAt
import com.rm.acidulous.model.phraseLevels
import com.rm.acidulous.model.stack
import com.rm.acidulous.model.withTake
import com.rm.acidulous.res.*
import com.rm.acidulous.ui.theme.Acid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Comping a stacked Bias lane: its takes as thin rows under it, lined up in
 * time with the lane, and the phrases they share.
 *
 * Tap a take's row and that take plays over the phrase under your finger.
 * Drag along a row and it plays over exactly the stretch you dragged. Each is
 * one undo step that writes the lane's pieces; the engine places the joins.
 */
private val RowH = 16.dp

/** The region frame under [x] on a lane [width] wide. */
internal fun frameAt(take: TakeRef, total: Int, width: Float, x: Float): Int {
    val from = width * take.startTick / total
    val span = width * take.lengthTicks() / total
    if (span <= 0f) return 0
    return ((x - from) / span * take.frames).toInt().coerceIn(0, take.frames)
}

/**
 * The phrase marks: a line at each cut and the picked phrase shaded. [from]
 * and [span] place the region, [to] is where it's cut off by the cell.
 */
internal fun DrawScope.drawPhrases(
    take: TakeRef, cuts: List<Int>?, phrase: Int, from: Float, span: Float, to: Float, line: Color, shade: Color,
) {
    if (cuts == null || take.frames <= 0) return
    fun xOf(f: Int) = (from + span * f / take.frames).coerceAtMost(to)
    if (phrase in 0 until cuts.size - 1) {
        val a = xOf(cuts[phrase])
        val b = xOf(cuts[phrase + 1])
        drawRect(shade.copy(alpha = 0.16f), Offset(a, 0f), Size((b - a).coerceAtLeast(1f), size.height))
    }
    for (i in 1 until cuts.size - 1) {
        val x = xOf(cuts[i])
        if (x < to) drawLine(line.copy(alpha = 0.55f), Offset(x, 0f), Offset(x, size.height), 1f)
    }
}

/**
 * Where each stack's phrases are, worked out off the main thread from the
 * takes' fine envelopes and kept by stack, so reopening a lane is instant.
 */
object Phrasing {
    private val cache = com.rm.acidulous.util.LruMap<String, List<Int>>(16)

    /** Everything the phrases depend on: the region's length and each take's place. */
    private fun keyOf(t: TakeRef): String = buildString {
        append(t.frames)
        for (s in t.stack()) append('|').append(s.file).append('@').append(s.offset).append(':').append(s.start).append('-').append(s.end)
    }

    /** [take]'s phrase cuts, or null until they're ready (or for no take). */
    @Composable
    fun rememberCuts(root: File?, take: TakeRef?): List<Int>? {
        val key = take?.let { keyOf(it) }.orEmpty()
        var cuts by remember(key) { mutableStateOf(com.rm.acidulous.util.locked(this) { cache[key] }) }
        LaunchedEffect(key) {
            if (cuts != null || take == null) return@LaunchedEffect
            cuts = withContext(Dispatchers.Default) {
                val envelopes = take.stack().map { it.file }.distinct().associateWith { TakePeaks.envelope(root, it) }
                findPhrases(take.phraseLevels { envelopes[it] }, take.frames)
                    .also { com.rm.acidulous.util.locked(this@Phrasing) { cache[key] = it } }
            }
        }
        return cuts
    }
}

/**
 * Changes lane [lane]'s stack as one undo step, with its grid shape redrawn
 * from what it now plays. Any take file not measured yet is read first, off
 * the main thread, so the shape is never left from before the edit. [f]
 * returning null empties the lane.
 */
fun compEdit(scope: CoroutineScope, editor: SongEditor, trackIndex: Int, sceneId: String, lane: Int, f: (TakeRef) -> TakeRef?) {
    val held = editor.song.tracks.getOrNull(trackIndex)?.clips?.get(sceneId)?.audio?.lane(lane) ?: return
    scope.launch {
        val files = held.stack().map { it.file }.distinct()
        val shapes = withContext(Dispatchers.Default) {
            files.associateWith { TakePeaks.load(EngineSync.sampleRoot, it) }
        }
        editor.editClip(trackIndex, sceneId) { cl ->
            val now = cl.audio?.lane(lane) ?: return@editClip cl
            val next = f(now) ?: return@editClip cl.withTake(lane, null)
            if (next == now) return@editClip cl
            val peaks = if (next.pieces.isEmpty()) {
                shapes[next.file]?.let { TakePeaks.slice(it, next.offset, next.frames) }
            } else {
                TakePeaks.comp(next) { shapes[it] }
            }
            cl.withTake(lane, next.copy(peaks = peaks ?: next.peaks))
        }
    }
}

/** A drag along a take's row, in region frames. */
private data class Pick(val take: Int, val from: Int, val to: Int)

/**
 * A stacked lane's takes, one thin row each, take 1 at the top. The number
 * at the left opens the take's menu. The rows scroll, with a bar, once
 * there are more than [maxHeight] holds.
 *
 * While listening through ([onKeep] set) the take playing is lit, and a tap
 * on any row keeps that take for the phrase. [cursor] is the keyboard's take.
 */
@Composable
internal fun TakeRows(
    take: TakeRef,
    total: Int,
    cuts: List<Int>,
    phrase: Int,
    maxHeight: Dp,
    root: File?,
    playheadTick: () -> Int?,
    /** Take, from, to, and the phrase that was tapped or -1 for a drag. */
    onChoose: (Int, Int, Int, Int) -> Unit,
    onEverywhere: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    playing: Int = -1,
    cursor: Int = -1,
    onKeep: ((Int) -> Unit)? = null,
) {
    val c = Acid.colors
    val keep by rememberUpdatedState(onKeep)
    val stack = take.stack()
    val scroll = rememberScrollState()
    var pick by remember { mutableStateOf<Pick?>(null) }
    val height = (RowH * stack.size).coerceAtMost(maxHeight.coerceAtLeast(RowH))
    Box(Modifier.fillMaxWidth().height(height)) {
        Column(Modifier.fillMaxWidth().verticalScrollWithBar(scroll)) {
            for ((k, t) in stack.withIndex()) key(k) {
                val shape = TakePeaks.rememberShape(root, t.file)
                val heard = take.pieces.ifEmpty { listOf(com.rm.acidulous.model.Piece(0, take.frames, 0)) }.filter { it.take == k }
                var menu by remember { mutableStateOf(false) }
                val lit = k == playing
                val forPhrase = stringResource(Res.string.tape_take_phrase)
                val everywhere = stringResource(Res.string.tape_take_everywhere)
                val delete = stringResource(Res.string.tape_take_delete)
                val actions = buildList {
                    if (onKeep != null) add(action(forPhrase) { onKeep(k) })
                    else if (phrase in 0 until cuts.size - 1) add(action(forPhrase) { onChoose(k, cuts[phrase], cuts[phrase + 1], phrase) })
                    add(action(everywhere) { onEverywhere(k) })
                    add(action(delete) { onDelete(k) })
                }
                Row(Modifier.fillMaxWidth().height(RowH).padding(bottom = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.width(GutterW).fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (lit) c.accent.copy(alpha = 0.45f) else if (heard.isNotEmpty()) c.control else c.card)
                            .clickable { menu = true }
                            .button(
                                stringResource(Res.string.a11y_take_row, k + 1),
                                if (lit) stringResource(Res.string.a11y_playing) else null,
                                actions,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${k + 1}", color = if (heard.isNotEmpty()) c.textHi else c.textDim,
                            fontSize = 9.sp, lineHeight = 9.sp, fontFamily = FontFamily.Monospace,
                        )
                        val menuScroll = rememberScrollState()
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            ScaledMenu(menuScroll) {
                                DropdownMenuItem(
                                    text = { Text(everywhere, fontSize = 12.sp, color = c.text) },
                                    onClick = { menu = false; onEverywhere(k) },
                                )
                                DropdownMenuItem(
                                    text = { Text(delete, fontSize = 12.sp, color = c.red) },
                                    onClick = { menu = false; onDelete(k) },
                                )
                            }
                        }
                    }
                    val latestCuts by rememberUpdatedState(cuts)
                    Box(
                        Modifier.weight(1f).fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (lit) c.accent.copy(alpha = 0.12f) else c.card)
                            .then(if (k == cursor) Modifier.border(1.dp, c.teal, RoundedCornerShape(2.dp)) else Modifier)
                            .pointerInput(k, take, total) {
                                rowGestures(
                                    take, total, k,
                                    onPick = { pick = it },
                                    onTap = { at ->
                                        val keeping = keep
                                        val i = phraseAt(latestCuts, at)
                                        if (keeping != null) keeping(k)
                                        else if (i >= 0) onChoose(k, latestCuts[i], latestCuts[i + 1], i)
                                    },
                                    onDrag = { from, to -> pick = null; onChoose(k, from, to, -1) },
                                )
                            },
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            val from = size.width * take.startTick / total
                            val span = size.width * take.lengthTicks() / total
                            val to = (from + span).coerceAtMost(size.width)
                            val mid = size.height / 2f
                            val half = size.height / 2f - 1f
                            fun xOf(f: Int) = (from + span * f / take.frames.coerceAtLeast(1)).coerceAtMost(to)
                            val whole = shape
                            if (whole != null && whole.frames > 0 && take.frames > 0) {
                                val columns = whole.peaks.size / 2
                                // The take's audio, dim, then the stretches it's heard over on top.
                                fun stretch(a: Int, b: Int, colour: Color) {
                                    val x0 = xOf(a)
                                    val x1 = xOf(b)
                                    if (x1 <= x0) return
                                    val c0 = (columns.toLong() * (t.offset + a).coerceAtLeast(0) / whole.frames).toInt().coerceIn(0, columns - 1)
                                    val c1 = (columns.toLong() * (t.offset + b) / whole.frames).toInt().coerceIn(c0 + 1, columns)
                                    val full = span * (b - a) / take.frames
                                    val shown = if (full > 0f) ((c1 - c0) * (x1 - x0) / full).toInt() else 0
                                    drawShape(whole.peaks, c0, (c0 + shown).coerceIn(c0 + 1, c1), x0, x1, mid, half, colour)
                                }
                                stretch(maxOf(t.start, 0), minOf(t.end, take.frames), c.textDim.copy(alpha = 0.6f))
                                for (p in heard) stretch(maxOf(p.start, t.start), minOf(p.end, t.end), c.accent)
                            }
                            drawPhrases(take, cuts, phrase, from, span, to, c.textMid, c.accent)
                            pick?.takeIf { it.take == k }?.let { p ->
                                val a = xOf(minOf(p.from, p.to))
                                val b = xOf(maxOf(p.from, p.to))
                                drawRect(c.teal.copy(alpha = 0.35f), Offset(a, 0f), Size((b - a).coerceAtLeast(1f), size.height))
                            }
                            val head = playheadTick()
                            if (head != null) {
                                val x = size.width * head.coerceIn(0, total) / total
                                drawLine(c.accent, Offset(x, 0f), Offset(x, size.height), 1f)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A take row's finger: a tap chooses the phrase under it, a sideways drag
 * chooses the stretch it covers. An upward or downward move is left to the
 * rows' scrolling.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.rowGestures(
    take: TakeRef,
    total: Int,
    k: Int,
    onPick: (Pick?) -> Unit,
    onTap: (Int) -> Unit,
    onDrag: (Int, Int) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown()
        val w = size.width.toFloat().coerceAtLeast(1f)
        val start = frameAt(take, total, w, down.position.x)
        var dragging = false
        var at = start
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val dx = change.position.x - down.position.x
            val dy = change.position.y - down.position.y
            if (!dragging) {
                if (abs(dy) > viewConfiguration.touchSlop && abs(dy) > abs(dx)) { onPick(null); return@awaitEachGesture }
                if (abs(dx) > viewConfiguration.touchSlop) dragging = true
            }
            if (dragging) {
                change.consume()
                at = frameAt(take, total, w, change.position.x)
                onPick(Pick(k, start, at))
            }
        }
        if (dragging) {
            if (at != start) onDrag(minOf(start, at), maxOf(start, at)) else onPick(null)
        } else {
            onTap(start)
        }
    }
}
