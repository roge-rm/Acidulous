package com.rm.acidulous.ui

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

/**
 * A drum machine's step grid: a row per voice, a column per grid step. Tap
 * toggles a hit; long-press toggles its accent. It is a view over the
 * ordinary clip, so the piano roll shows the same hits.
 *
 * It looks at the same window of the clip the roll does - a first tick and a
 * span - rather than at a bar number, so the two editors scroll and zoom
 * together and one set of state in the Edit screen drives both.
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
    /** Two fingers sideways: the window moves by this many ticks. */
    onScrollTime: (ticks: Float) -> Unit = {},
    /** A pinch. Sideways changes the span; the other way is this grid's own. */
    onZoomTime: (scale: Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val grid = clip.grid.coerceAtLeast(1)
    val steps = (visibleTicks / grid).coerceIn(1, 64)
    val scroll = rememberScrollState()
    // How tall a voice's row is.
    //
    // Nought means nobody has pinched, and then the rows are sized to fill
    // whatever height this has been given: a fixed 24 left a phone with most
    // of a row's worth of empty space under the grid and a tablet with far
    // more, and the right height was never a constant anyway - it depends on
    // the screen and on how many voices the machine has. A pinch still wins
    // once there has been one, because which compromise you want is a matter
    // of what you are doing, and it survives a rotation.
    var pinched by rememberSaveable { mutableStateOf(0f) }

    // The height on offer, shared out. Measured rather than assumed: the slot
    // is a weight(1f) of whatever is left after the header, the automation
    // strip, the machine panel and the pads, so only the layout knows it.
    // Below the floor there is no point growing the rows - the grid scrolls
    // instead, which is what it is for - and the ceiling only applies to the
    // fit, not to a pinch.
    var slotPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val fitted = if (voices.isEmpty() || slotPx == 0) 24f else {
        val dp = with(density) { slotPx.toDp().value }
        ((dp - RowGap * (voices.size - 1)) / voices.size).coerceIn(MinRow, MaxRow)
    }
    val rowHeight = if (pinched > 0f) pinched else fitted

    Column(
        modifier.background(Acid.colors.bg).padding(4.dp)
            .onSizeChanged { slotPx = it.height }
            // Two fingers move the view; one still edits. Watched on the
            // Initial pass, which travels parent to child, because the cells
            // below have a clickable each and the column scrolls - both would
            // otherwise have taken the gesture before this saw it.
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
                        // From here everything is eaten, so the finger that
                        // landed on a pad does not toggle it on the way up.
                        event.changes.forEach { it.consume() }
                        val now = TwoFingers.of(event) ?: break
                        if (mode == TwoFingerMode.Undecided) mode = decideTwoFinger(start, now)

                        // One thing at a time, decided once - see TwoFingerMode.
                        when (mode) {
                            TwoFingerMode.Pan -> {
                                onScrollTime(-(now.centre.x - last.centre.x) / (size.width.toFloat() / steps) * grid)
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
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Same width and size as the roll's pitch gutter: the
                    // two editors show the same clip and are read as one.
                    Text(
                        voice.short, color = Acid.colors.textMid,
                        fontSize = NameTextSize, fontFamily = FontFamily.Monospace,
                        maxLines = 1, softWrap = false,
                        modifier = Modifier.width(GutterWidth),
                    )
                    for (s in 0 until steps) {
                        val tick = firstTick + s * grid
                        val hit = clip.notes.firstOrNull { it.tick == tick && it.pitch == voice.note }
                        val accent = hit != null && hit.velocity >= 100
                        val active = playheadTick != null && playheadTick >= tick && playheadTick < tick + grid
                        val beat = ((tick / grid) % 4) == 0
                        Box(
                            Modifier.weight(1f).height(rowHeight.dp).clip(RoundedCornerShape(3.dp))
                                .background(
                                    when {
                                        accent -> Acid.colors.accent
                                        hit != null -> Acid.colors.green
                                        active -> Acid.colors.cardHi
                                        beat -> Acid.colors.cardAlt
                                        else -> Acid.colors.bar
                                    },
                                )
                                .combinedClickable(
                                    onClick = { onSetHit(tick, voice.note, if (hit == null) Note(tick, 30, voice.note, 90) else null) },
                                    onLongClick = { hit?.let { onSetHit(tick, voice.note, it.copy(velocity = if (accent) 90 else 110)) } },
                                ),
                        )
                    }
                }
            }
        }
    }
}
