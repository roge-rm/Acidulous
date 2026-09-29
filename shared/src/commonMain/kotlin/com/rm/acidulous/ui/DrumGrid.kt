package com.rm.acidulous.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.rm.acidulous.model.Clip
import com.rm.acidulous.model.DrumVoice
import com.rm.acidulous.model.Note
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.res.*

/**
 * A drum machine's step grid, a row per voice and a column per grid step. Tap
 * toggles a hit, long-press toggles its accent. It edits the same clip as the
 * piano roll.
 *
 * It shows the same window of the clip as the roll (a first tick and a span),
 * so both editors scroll and zoom together from one set of state in the Edit
 * screen.
 */
@Composable
fun DrumGrid(
    clip: Clip,
    ticksPerBar: Int,
    voices: List<DrumVoice>,
    playheadTick: Long?,
    /** The window this shows, in ticks from the start of the clip. */
    firstTick: Int,
    visibleTicks: Int,
    onSetHit: (tick: Int, note: Int, hit: Note?) -> Unit, // null clears
    /** Two-finger sideways drag moves the window by this many ticks. */
    onScrollTime: (ticks: Float) -> Unit = {},
    /** A pinch. Sideways changes the span, the other way the row height. */
    onZoomTime: (scale: Float) -> Unit = {},
    /** Lock mode: a tap on a hit selects its step instead of taking the hit away. */
    lockMode: Boolean = false,
    selectedTicks: Set<Int> = emptySet(),
    /** Steps with a parameter lock, marked in the corner. */
    lockedTicks: Set<Int> = emptySet(),
    onSelectStep: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val grid = clip.grid.coerceAtLeast(1)
    val steps = (visibleTicks / grid).coerceIn(1, 64)
    val scroll = rememberScrollState()
    // Row height per voice. 0 means nobody has pinched yet, so the rows are
    // sized to fill the height this is given. After a pinch the pinched height
    // is used, and it survives a rotation.
    var pinched by rememberSaveable { mutableStateOf(0f) }

    // The height available, from the constraints as the first frame is laid
    // out. Measured afterwards instead, the first frame drew rows at a
    // placeholder height and they stretched to fit a frame later, which on
    // opening the editor (a slow frame) showed as a half-second jump. Below
    // the floor the grid scrolls instead of shrinking rows. The ceiling only
    // applies to the fit, not to a pinch.
    BoxWithConstraints(modifier) {
    val density = LocalDensity.current
    val slotPx = if (constraints.hasBoundedHeight) {
        (constraints.maxHeight - (2f * GridPadding.value * density.density).toInt()).coerceAtLeast(0)
    } else 0
    // The cells start after the name column, so a step is this much narrower
    // than the row. That's what a two-finger drag moves by.
    val gutterPx = with(density) { GutterWidth.toPx() }
    val ceiling = if (largeScreen()) MaxRowLarge else MaxRow
    val fitted = if (voices.isEmpty() || slotPx == 0) 24f else {
        val dp = with(density) { slotPx.toDp().value }
        ((dp - RowGap * (voices.size - 1)) / voices.size).coerceIn(MinRow, ceiling)
    }
    val rowHeight = if (pinched > 0f) pinched else fitted
    val resources = AppStrings

    Column(
        // Vertical padding only. Horizontal padding would put the cells on a
        // different tick axis from the lane, automation strip, roll and
        // playhead, which all map ticks across the full width after the gutter.
        Modifier.fillMaxSize().background(Acid.colors.bg).padding(vertical = GridPadding)
            // Mouse wheel on desktop (see onWheel). Up/down is left to the
            // column, which scrolls itself. Sideways moves time a tenth of the
            // window per notch, Ctrl zooms, and Ctrl+Shift changes the row
            // height like a pinch.
            .onWheel { w ->
                when {
                    w.zoom && w.shift -> {
                        pinched = (rowHeight / w.zoomFactor).coerceIn(MinRow, maxOf(MaxRow, fitted))
                        true
                    }
                    w.zoom -> { onZoomTime(w.zoomFactor); true }
                    w.across != 0f -> { onScrollTime(w.across * visibleTicks / 10f); true }
                    else -> false
                }
            }
            // Two fingers move the view, one finger edits. This watches the
            // Initial pass (parent to child) because the clickable cells and
            // the scrolling column would otherwise take the gesture first.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var second = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val down = event.changes.filter { it.pressed }
                        if (down.isEmpty()) break
                        if (down.size >= 2) { second = true; break }
                    }
                    if (!second) return@awaitEachGesture

                    val start = TwoFingers.of(currentEvent) ?: return@awaitEachGesture
                    var last = start
                    var mode = TwoFingerMode.Undecided
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        // From here everything is consumed, so the pad the
                        // finger landed on isn't toggled on release.
                        event.changes.forEach { it.consume() }
                        val now = TwoFingers.of(event) ?: break
                        if (mode == TwoFingerMode.Undecided) mode = decideTwoFinger(start, now)

                        // One thing at a time, decided once - see TwoFingerMode.
                        when (mode) {
                            TwoFingerMode.Pan -> {
                                onScrollTime(
                                    -(now.centre.x - last.centre.x) /
                                        (((size.width - gutterPx).coerceAtLeast(1f)) / steps) * grid,
                                )
                                scroll.dispatchRawDelta(last.centre.y - now.centre.y)
                            }
                            TwoFingerMode.ZoomTime ->
                                if (last.spreadX > TwoFingers.MinSpread && now.spreadX > TwoFingers.MinSpread) {
                                    onZoomTime(last.spreadX / now.spreadX)
                                }
                            TwoFingerMode.ZoomPitch ->
                                if (last.spreadY > TwoFingers.MinSpread && now.spreadY > TwoFingers.MinSpread) {
                                    pinched = (rowHeight * now.spreadY / last.spreadY)
                                        .coerceIn(MinRow, maxOf(MaxRow, fitted))
                                }
                            TwoFingerMode.Undecided -> {}
                        }
                        last = now
                    }
                }
            },
    ) {
        // Read here because a draw lambda can't reach the theme.
        val colors = Acid.colors
        // The cells are only for TalkBack and the keyboard, since the rows draw
        // them and take their touches, so a touch-only phone never builds them.
        // Otherwise they come a frame after the grid, so opening the editor
        // doesn't wait for them.
        val beyondTouch = rememberBeyondTouch()
        var later by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { withFrameNanos { }; later = true }
        val cells = beyondTouch && later
        Column(Modifier.verticalScrollWithBar(scroll), verticalArrangement = Arrangement.spacedBy(RowGap.dp)) {
            for (voice in voices) key(voice.note) {
                // What each step shows, worked out once for the row's drawing,
                // its touches and its cells.
                val hits = arrayOfNulls<Note>(steps)
                for (n in clip.notes) {
                    if (n.pitch != voice.note || n.tick < firstTick) continue
                    val s = (n.tick - firstTick) / grid
                    if (s < steps && (n.tick - firstTick) % grid == 0 && hits[s] == null) hits[s] = n
                }
                fun tickOf(s: Int) = firstTick + s * grid
                fun accented(s: Int) = (hits[s]?.velocity ?: 0) >= 100
                fun press(s: Int) {
                    val tick = tickOf(s)
                    val hit = hits[s]
                    if (lockMode) { if (hit != null) onSelectStep(tick) }
                    else onSetHit(tick, voice.note, if (hit == null) Note(tick, 30, voice.note, 90) else null)
                }
                fun hold(s: Int) {
                    val hit = hits[s] ?: return
                    if (!lockMode) onSetHit(tickOf(s), voice.note, hit.copy(velocity = if (accented(s)) 90 else 110))
                }
                val latestPress by rememberUpdatedState(::press)
                val latestHold by rememberUpdatedState(::hold)
                Row(
                    Modifier.fillMaxWidth().height(rowHeight.dp)
                        // The cells are drawn here in one go, not each by
                        // itself: a couple of hundred boxes with their own
                        // clip, colour and click took longer to build than the
                        // rest of the editor.
                        .drawBehind {
                            val gutter = GutterWidth.toPx()
                            val w = (size.width - gutter) / steps
                            val inset = 1.dp.toPx()
                            val corner = CornerRadius(3.dp.toPx())
                            for (s in 0 until steps) {
                                val tick = tickOf(s)
                                val hit = hits[s]
                                val active = playheadTick != null && playheadTick >= tick && playheadTick < tick + grid
                                val left = gutter + s * w + inset
                                val cell = Size((w - 2 * inset).coerceAtLeast(0f), size.height)
                                drawRoundRect(
                                    when {
                                        accented(s) -> colors.accent
                                        hit != null -> colors.green
                                        active -> colors.cardHi
                                        ((tick / grid) % 4) == 0 -> colors.cardAlt
                                        else -> colors.bar
                                    },
                                    Offset(left, 0f), cell, corner,
                                )
                                if (hit == null) continue
                                translate(left, 0f) { stepMarks(cell, hit.hasTrig, tick in lockedTicks, colors.teal, colors.pink) }
                                if (lockMode && tick in selectedTicks) {
                                    val b = 2.dp.toPx()
                                    drawRoundRect(
                                        colors.text, Offset(left + b / 2, b / 2), Size(cell.width - b, cell.height - b),
                                        CornerRadius((3.dp.toPx() - b / 2).coerceAtLeast(0f)), style = Stroke(b),
                                    )
                                }
                            }
                        }
                        .pointerInput(steps) {
                            detectTapGestures(
                                onTap = { at -> stepAt(at.x, size.width, steps)?.let { latestPress(it) } },
                                onLongPress = { at -> stepAt(at.x, size.width, steps)?.let { latestHold(it) } },
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Same width and size as the roll's pitch gutter.
                    Text(
                        voice.short, color = colors.textMid,
                        fontSize = NameTextSize, fontFamily = FontFamily.Monospace,
                        maxLines = 1, softWrap = false,
                        // Every step says its voice's full name.
                        modifier = Modifier.width(GutterWidth).silent(),
                    )
                    if (cells) for (s in 0 until steps) {
                        val tick = tickOf(s)
                        Box(
                            Modifier.weight(1f).fillMaxHeight().drawnCell(
                                // What TalkBack says: the voice, the step and what's on it.
                                name = { resources.getString(Res.string.a11y_step, resources.panelWord(voice.name), tick / grid + 1) },
                                state = {
                                    val hit = hits[s]
                                    resources.getString(
                                        when {
                                            accented(s) -> Res.string.a11y_hit_accent
                                            hit != null -> Res.string.a11y_step_hit
                                            else -> Res.string.a11y_step_empty
                                        },
                                    ).let { if (hit?.hasTrig == true) resources.getString(Res.string.a11y_step_condition, it) else it }
                                        .let { if (hit != null && tick in lockedTicks) resources.getString(Res.string.a11y_step_locked, it) else it }
                                        .let { if (lockMode && tick in selectedTicks && hit != null) resources.getString(Res.string.a11y_step_chosen, it) else it }
                                },
                                actions = {
                                    if (!lockMode && hits[s] != null) listOf(
                                        action(resources.getString(if (accented(s)) Res.string.a11y_accent_remove else Res.string.a11y_accent_add)) { hold(s) },
                                    ) else emptyList()
                                },
                                ring = colors.accent,
                                onClick = { press(s) },
                            ),
                        )
                    }
                }
            }
        }
    }
    }
}
/** Space above and below the rows. */
private val GridPadding = 4.dp

/** The step under [x] in a row [width] wide, or null over the names. */
private fun androidx.compose.ui.unit.Density.stepAt(x: Float, width: Int, steps: Int): Int? {
    val gutter = GutterWidth.toPx()
    if (x < gutter) return null
    return ((x - gutter) / ((width - gutter) / steps)).toInt().coerceIn(0, steps - 1)
}

/**
 * A hit's corner marks: a wedge top right for a trig condition (set in the lane
 * under the grid, this only shows there is one) and a diamond bottom left for a
 * parameter lock.
 */
private fun DrawScope.stepMarks(cell: Size, trig: Boolean, locked: Boolean, mark: Color, lockMark: Color) {
    if (locked) {
        val w = cell.minDimension * 0.22f
        val cx = w * 1.3f
        val cy = cell.height - w * 1.3f
        drawPath(Path().apply { moveTo(cx, cy - w); lineTo(cx + w, cy); lineTo(cx, cy + w); lineTo(cx - w, cy); close() }, lockMark)
    }
    if (trig) {
        val w = cell.minDimension * 0.34f
        // Clipped to the cell's rounded corner, as the cell was when it was a box.
        clipPath(Path().apply { addRoundRect(RoundRect(0f, 0f, cell.width, cell.height, CornerRadius(3.dp.toPx()))) }) {
            drawPath(Path().apply { moveTo(cell.width, 0f); lineTo(cell.width - w, 0f); lineTo(cell.width, w); close() }, mark)
        }
    }
}
