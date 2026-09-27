package com.rm.acidulous.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged
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

    // The height available, measured because the slot is a weight(1f) of
    // whatever is left after the rest of the screen. Below the floor the grid
    // scrolls instead of shrinking rows. The ceiling only applies to the fit,
    // not to a pinch.
    var slotPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
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
        modifier.background(Acid.colors.bg).padding(vertical = 4.dp)
            .onSizeChanged { slotPx = it.height }
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
        Column(Modifier.verticalScrollWithBar(scroll), verticalArrangement = Arrangement.spacedBy(RowGap.dp)) {
            for (voice in voices) {
                Row(
                    Modifier.fillMaxWidth().height(rowHeight.dp),
                    // No arrangement spacing. spacedBy would also put a gap
                    // before the first cell and shift the cells off the lane's
                    // step. The gap between cells is padding inside each cell
                    // instead.
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Same width and size as the roll's pitch gutter.
                    Text(
                        voice.short, color = Acid.colors.textMid,
                        fontSize = NameTextSize, fontFamily = FontFamily.Monospace,
                        maxLines = 1, softWrap = false,
                        // Every step says its voice's full name.
                        modifier = Modifier.width(GutterWidth).silent(),
                    )
                    // Read here because a draw lambda can't reach the theme.
                    val mark = Acid.colors.teal
                    val lockMark = Acid.colors.pink
                    val selectEdge = Acid.colors.text
                    for (s in 0 until steps) {
                        val tick = firstTick + s * grid
                        val hit = clip.notes.firstOrNull { it.tick == tick && it.pitch == voice.note }
                        val accent = hit != null && hit.velocity >= 100
                        val active = playheadTick != null && playheadTick >= tick && playheadTick < tick + grid
                        val beat = ((tick / grid) % 4) == 0
                        // What TalkBack says: the voice, the step and what's on it.
                        val said = resources.getString(Res.string.a11y_step, resources.panelWord(voice.name), tick / grid + 1)
                        val state = resources.getString(
                            when {
                                accent -> Res.string.a11y_hit_accent
                                hit != null -> Res.string.a11y_step_hit
                                else -> Res.string.a11y_step_empty
                            },
                        ).let { if (hit?.hasTrig == true) resources.getString(Res.string.a11y_step_condition, it) else it }
                            .let { if (hit != null && tick in lockedTicks) resources.getString(Res.string.a11y_step_locked, it) else it }
                            .let { if (lockMode && tick in selectedTicks && hit != null) resources.getString(Res.string.a11y_step_chosen, it) else it }
                        val accentAction = if (!lockMode && hit != null) listOf(
                            action(resources.getString(if (accent) Res.string.a11y_accent_remove else Res.string.a11y_accent_add)) {
                                onSetHit(tick, voice.note, hit.copy(velocity = if (accent) 90 else 110))
                            },
                        ) else emptyList()
                        Box(
                            Modifier.weight(1f).height(rowHeight.dp)
                                .padding(horizontal = 1.dp).clip(RoundedCornerShape(3.dp))
                                .background(
                                    when {
                                        accent -> Acid.colors.accent
                                        hit != null -> Acid.colors.green
                                        active -> Acid.colors.cardHi
                                        beat -> Acid.colors.cardAlt
                                        else -> Acid.colors.bar
                                    },
                                )
                                .then(
                                    if (lockMode && tick in selectedTicks && hit != null) {
                                        Modifier.border(2.dp, selectEdge, RoundedCornerShape(3.dp))
                                    } else {
                                        Modifier
                                    },
                                )
                                .combinedClickable(
                                    onClick = {
                                        if (lockMode) { if (hit != null) onSelectStep(tick) }
                                        else onSetHit(tick, voice.note, if (hit == null) Note(tick, 30, voice.note, 90) else null)
                                    },
                                    onLongClick = {
                                        if (!lockMode) hit?.let { onSetHit(tick, voice.note, it.copy(velocity = if (accent) 90 else 110)) }
                                    },
                                )
                                .button(said, state, accentAction)
                                // A locked step gets a diamond in the corner
                                // opposite the trig mark.
                                .then(
                                    if (hit == null || tick !in lockedTicks) Modifier else Modifier.drawBehind {
                                        val w = size.minDimension * 0.22f
                                        val cx = w * 1.3f
                                        val cy = size.height - w * 1.3f
                                        drawPath(
                                            Path().apply {
                                                moveTo(cx, cy - w); lineTo(cx + w, cy); lineTo(cx, cy + w); lineTo(cx - w, cy); close()
                                            },
                                            lockMark,
                                        )
                                    },
                                )
                                // A step with a trig condition gets a corner
                                // wedge. The lane under the grid is where
                                // conditions are set, this just shows there is
                                // one. No new gesture here since long press is
                                // already used for accent.
                                .then(
                                    if (hit?.hasTrig != true) Modifier else Modifier.drawBehind {
                                        val w = size.minDimension * 0.34f
                                        drawPath(
                                            Path().apply {
                                                moveTo(size.width, 0f)
                                                lineTo(size.width - w, 0f)
                                                lineTo(size.width, w)
                                                close()
                                            },
                                            mark,
                                        )
                                    },
                                ),
                        )
                    }
                }
            }
        }
    }
}
