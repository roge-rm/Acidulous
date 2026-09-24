package com.rm.acidulous.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.model.GATE_LENGTHS
import com.rm.acidulous.model.MUTE_ON
import com.rm.acidulous.model.MUTE_ON_BAR
import com.rm.acidulous.model.MUTE_ON_NOW
import com.rm.acidulous.model.PAD_X_MODES
import com.rm.acidulous.model.PPQN
import com.rm.acidulous.model.PAD_Y_MODES
import com.rm.acidulous.model.REPEAT_LENGTHS
import com.rm.acidulous.model.RISER_LENGTHS
import com.rm.acidulous.model.STOP_LENGTHS
import com.rm.acidulous.model.Song
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.model.THROW_TIMES
import com.rm.acidulous.ui.theme.Acid
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.stringArrayResource
import com.rm.acidulous.R

/**
 * What the perform pages are holding.
 *
 * Kept above the pages rather than in them, so that a repeat latched on the
 * hold page is still on - and still shown as on - after a turn to the live
 * page to drop a track out. Each value is what was last sent to the engine.
 */
class PerformState {
    var holdLatch by mutableStateOf(false)
    var padLatch by mutableStateOf(false)
    var repeat by mutableStateOf(0)
    var gate by mutableStateOf(0)
    var reverse by mutableStateOf(false)
    var stop by mutableStateOf(false)
    var riser by mutableStateOf(false)
    /** Low, mid and high. */
    var kills by mutableStateOf(listOf(false, false, false))
    /** Where the pad is, normalised with y up; null at rest. */
    var pad by mutableStateOf<Offset?>(null)

    /**
     * The transport stopped and the engine let go of everything, so this
     * forgets it too. Nothing is sent: there is nothing left on to turn off.
     */
    fun forgetHeld() {
        repeat = 0
        gate = 0
        reverse = false
        stop = false
        riser = false
        kills = listOf(false, false, false)
        pad = null
    }
}

/** Sends the held controls, recorded into [track]'s clip while recording. */
private class PerformSender(private val track: Int) {
    fun send(name: String, v: Float) = NativeEngine.setParam(track, "perform", name, v, record = true)
    fun repeat(k: Int) = send("repeat", k / REPEAT_LENGTHS.size.toFloat())
    fun gate(k: Int) = send("gate", k / GATE_LENGTHS.size.toFloat())
    fun reverse(on: Boolean) = send("reverse", if (on) 1f else 0f)
    fun stop(on: Boolean) = send("stop", if (on) 1f else 0f)
    fun riser(on: Boolean) = send("riser", if (on) 1f else 0f)
    fun kill(band: Int, on: Boolean) = send(KILLS[band], if (on) 1f else 0f)
    fun pad(at: Offset?) {
        send("x", at?.x ?: 0.5f)
        send("y", at?.y ?: 0f)
    }
}

private val KILLS = listOf("killlow", "killmid", "killhigh")
private val KILL_H = 52.dp

/**
 * Things that happen in time: repeat, gate, reverse, tape stop and the riser.
 *
 * Held, or with **latch** on, tapped on and tapped off. Turning latch off
 * lets go of whatever it was holding.
 */
@Composable
fun HoldPage(song: Song, editor: SongEditor, track: Int, state: PerformState, modifier: Modifier = Modifier) {
    val c = Acid.colors
    val out = remember(track) { PerformSender(track) }
    val settings = song.master.perform
    Column(modifier.background(c.panelAlt).padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Caption(stringResource(R.string.perform_repeat))
            Spacer(Modifier.weight(1f))
            TargetChip(song, editor, Modifier)
        }
        LengthStrip(REPEAT_LENGTHS, state.repeat, state.holdLatch, Modifier.fillMaxWidth().weight(1f), name = stringResource(R.string.perform_repeat)) { k ->
            state.repeat = k
            out.repeat(k)
        }
        Caption(stringResource(R.string.perform_gate))
        LengthStrip(GATE_LENGTHS, state.gate, state.holdLatch, Modifier.fillMaxWidth().weight(1f), name = stringResource(R.string.perform_gate)) { k ->
            state.gate = k
            out.gate(k)
        }
        Row(Modifier.fillMaxWidth().weight(1.2f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HoldPad(stringResource(R.string.perform_reverse), state.reverse, state.holdLatch, c.accent, Modifier.weight(1f).fillMaxHeight()) { on ->
                state.reverse = on
                out.reverse(on)
            }
            HoldPad(stringResource(R.string.perform_stop), state.stop, state.holdLatch, c.red, Modifier.weight(1f).fillMaxHeight()) { on ->
                state.stop = on
                out.stop(on)
            }
            HoldPad(stringResource(R.string.perform_riser), state.riser, state.holdLatch, c.accent, Modifier.weight(1f).fillMaxHeight()) { on ->
                state.riser = on
                out.riser(on)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LatchChip(state.holdLatch, Modifier.weight(1f)) {
                if (state.holdLatch) {
                    if (state.repeat != 0) { state.repeat = 0; out.repeat(0) }
                    if (state.gate != 0) { state.gate = 0; out.gate(0) }
                    if (state.reverse) { state.reverse = false; out.reverse(false) }
                    if (state.stop) { state.stop = false; out.stop(false) }
                    if (state.riser) { state.riser = false; out.riser(false) }
                }
                state.holdLatch = !state.holdLatch
            }
            Setting(stringResource(R.string.perform_stop), stringArrayResource(R.array.perform_stop_lengths)[settings.stopLen], Modifier.weight(1f)) {
                editor.editSong { s ->
                    s.copy(master = s.master.copy(perform = s.master.perform.copy(stopLen = (s.master.perform.stopLen + 1) % STOP_LENGTHS)))
                }
            }
            Setting(stringResource(R.string.perform_riser), stringArrayResource(R.array.perform_riser_lengths)[settings.riserLen], Modifier.weight(1f)) {
                editor.editSong { s ->
                    s.copy(master = s.master.copy(perform = s.master.perform.copy(riserLen = (s.master.perform.riserLen + 1) % RISER_LENGTHS)))
                }
            }
        }
    }
}

/**
 * Shaping the sound: the pad, and the kills under it. Across the pad is a
 * filter or a crush, and up it is how much of the mix is thrown into an echo
 * or a wash.
 */
@Composable
fun PadPage(song: Song, editor: SongEditor, track: Int, state: PerformState, modifier: Modifier = Modifier) {
    val c = Acid.colors
    val out = remember(track) { PerformSender(track) }
    val settings = song.master.perform
    Column(modifier.background(c.panelAlt).padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Setting(stringResource(R.string.perform_x), stringArrayResource(R.array.perform_pad_x_modes)[settings.xMode], Modifier.weight(1f)) {
                        editor.editSong { s ->
                            s.copy(master = s.master.copy(perform = s.master.perform.copy(xMode = (s.master.perform.xMode + 1) % PAD_X_MODES)))
                        }
                    }
                    Setting(stringResource(R.string.perform_y), stringArrayResource(R.array.perform_pad_y_modes)[settings.yMode], Modifier.weight(1f)) {
                        editor.editSong { s ->
                            s.copy(master = s.master.copy(perform = s.master.perform.copy(yMode = (s.master.perform.yMode + 1) % PAD_Y_MODES)))
                        }
                    }
                }
                // A wash has a time of its own; only the echo's is a setting.
                if (settings.yMode == 0) {
                    Setting(stringResource(R.string.perform_echo), THROW_TIMES[settings.throwTime], Modifier.fillMaxWidth()) {
                        editor.editSong { s ->
                            s.copy(master = s.master.copy(perform = s.master.perform.copy(throwTime = (s.master.perform.throwTime + 1) % THROW_TIMES.size)))
                        }
                    }
                }
                Caption(stringResource(R.string.perform_feedback), Modifier.silent())
                MiniSlider(
                    settings.feedback / 0.9f, Modifier.fillMaxWidth().height(20.dp),
                    name = stringResource(R.string.perform_feedback),
                    onStart = { editor.beginSongGesture() },
                    onChange = { v ->
                        NativeEngine.setParam(track, "perform", "feedback", v, record = false)
                        editor.updateSongGesture { s ->
                            s.copy(master = s.master.copy(perform = s.master.perform.copy(feedback = v * 0.9f)))
                        }
                    },
                    onEnd = { editor.endSongGesture() },
                )
                Spacer(Modifier.weight(1f))
                TargetChip(song, editor, Modifier.fillMaxWidth())
                LatchChip(state.padLatch, Modifier.fillMaxWidth()) {
                    if (state.padLatch) {
                        if (state.pad != null) { state.pad = null; out.pad(null) }
                        state.kills.forEachIndexed { b, on -> if (on) out.kill(b, false) }
                        state.kills = listOf(false, false, false)
                    }
                    state.padLatch = !state.padLatch
                }
            }
            XyPad(
                state.pad, state.padLatch, Modifier.weight(1.4f).fillMaxHeight(),
                left = stringResource(if (settings.xMode == 0) R.string.perform_pad_low else R.string.perform_pad_rate),
                right = stringResource(if (settings.xMode == 0) R.string.perform_pad_high else R.string.perform_pad_bits),
                up = stringResource(if (settings.yMode == 0) R.string.perform_pad_throw else R.string.perform_pad_wash),
            ) { at ->
                state.pad = at
                out.pad(at)
            }
        }
        // The kills under the pad, so a hand on the pad has them in reach.
        Row(Modifier.fillMaxWidth().height(KILL_H), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val kills = stringArrayResource(R.array.perform_kills)
            for (b in 0 until 3) {
                HoldPad(kills[b], state.kills[b], state.padLatch, c.red, Modifier.weight(1f).fillMaxHeight()) { on ->
                    state.kills = state.kills.toMutableList().also { it[b] = on }
                    out.kill(b, on)
                }
            }
        }
    }
}

/**
 * The song's parts: every track's mute, and fill.
 *
 * A mute here is the mixer's own mute, and the strip shows it too. While the
 * song plays it waits for the next bar (or beat, from **mute on**) and is
 * drawn outlined until it lands. The song's own mute only changes once the
 * engine's has, so nothing pushed in between can land it early. Fill is the
 * same held fill the editor has, and like it is never recorded.
 */
@Composable
fun LivePage(song: Song, editor: SongEditor, playing: Boolean, scene: Int, modifier: Modifier = Modifier) {
    val c = Acid.colors
    val settings = song.master.perform
    // What each track's mute is on its way to, while it waits for its line.
    val waiting = remember { mutableStateMapOf<Int, Boolean>() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30)
            for ((i, want) in waiting.toMap()) {
                if ((NativeEngine.paramNormalized(i, "channel", "mute") >= 0.5f) == want) {
                    waiting.remove(i)
                    editor.edit(i, push = false) { t -> t.copy(mixer = t.mixer.copy(mute = want)) }
                }
            }
        }
    }
    // A stop drops whatever was waiting, in the engine and so here.
    LaunchedEffect(playing) { if (!playing) waiting.clear() }
    fun toggle(i: Int) {
        val track = song.tracks.getOrNull(i) ?: return
        val want = !(waiting[i] ?: track.mixer.mute)
        val on = settings.muteOn
        if (!playing || on == MUTE_ON_NOW) {
            waiting.remove(i)
            editor.edit(i) { t -> t.copy(mixer = t.mixer.copy(mute = want)) }
            return
        }
        val bar = song.scenes.getOrNull(scene)?.let { song.signatureOf(it) } ?: song.signature
        val quantise = if (on == MUTE_ON_BAR) bar.ticksPerBar else PPQN
        waiting[i] = want
        NativeEngine.setParam(i, "channel", "mute", if (want) 1f else 0f, record = true, quantise = quantise)
    }
    Row(modifier.background(c.panelAlt).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(3f).fillMaxHeight().together(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Caption(stringResource(R.string.perform_mute))
            // Four across, the way the track picker lays sixteen out, and
            // always four rows tall so a short song's buttons are not huge.
            val rows = song.tracks.indices.chunked(4)
            for (row in rows) {
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (i in row) {
                        val track = song.tracks[i]
                        TrackMute(
                            track.name, trackColour(i, track.colour), track.mixer.mute, waiting[i],
                            Modifier.weight(1f).fillMaxHeight(),
                        ) { toggle(i) }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            repeat((4 - rows.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
        }
        Column(Modifier.weight(1f).fillMaxHeight().together(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Caption(stringResource(R.string.perform_fill))
            HoldPad(stringResource(R.string.perform_fill), UiPrefs.fillHeld, latch = false, colour = c.accent, modifier = Modifier.fillMaxWidth().weight(1f)) { on ->
                UiPrefs.holdFill(on)
            }
            Setting(stringResource(R.string.perform_mute_on), stringArrayResource(R.array.perform_mute_on_choices)[settings.muteOn], Modifier.fillMaxWidth()) {
                editor.editSong { s ->
                    s.copy(master = s.master.copy(perform = s.master.perform.copy(muteOn = (s.master.perform.muteOn + 1) % MUTE_ON)))
                }
            }
        }
    }
}

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Acid.colors.textDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace, modifier = modifier)
}

/** A setting you tap through: its name, and what it is set to. */
@Composable
private fun Setting(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Acid.colors
    Box(
        modifier.height(28.dp).clip(RoundedCornerShape(4.dp)).background(c.raised)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(stringResource(R.string.perform_setting, label, value), color = c.textMid, fontSize = 11.sp, maxLines = 1, softWrap = false) }
}

/**
 * Where the held effects run: "on all", or on one of the groups. Only there
 * when the song has a group; a tap moves to the next.
 */
@Composable
private fun TargetChip(song: Song, editor: SongEditor, modifier: Modifier) {
    val groups = song.master.groups
    if (groups.isEmpty()) return
    val c = Acid.colors
    val target = song.master.perform.target.takeIf { it in 1..groups.size } ?: 0
    Box(
        modifier.height(28.dp).clip(RoundedCornerShape(4.dp))
            .background(if (target > 0) c.accent.copy(alpha = 0.25f) else c.raised)
            .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                editor.editSong { s ->
                    s.copy(master = s.master.copy(perform = s.master.perform.copy(target = (target + 1) % (groups.size + 1))))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.perform_on, if (target == 0) stringResource(R.string.perform_on_all) else groups[target - 1].name), color = if (target > 0) c.accent else c.textMid,
            fontSize = 11.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}

@Composable
private fun LatchChip(on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Acid.colors
    Box(
        modifier.height(28.dp).clip(RoundedCornerShape(4.dp))
            .background(if (on) c.accent.copy(alpha = 0.25f) else c.raised).clickable(onClick = onClick)
            .button(stringResource(R.string.perform_latch), stringResource(if (on) R.string.a11y_on else R.string.a11y_off)),
        contentAlignment = Alignment.Center,
    ) { Text(stringResource(R.string.perform_latch), color = if (on) c.accent else c.textMid, fontSize = 11.sp) }
}

/**
 * One track's mute: its colour down the side, its name, red while muted.
 * [waiting] is the state it is on its way to, drawn as an outline in that
 * state's colour until it lands.
 */
@Composable
private fun TrackMute(name: String, colour: Color, muted: Boolean, waiting: Boolean?, modifier: Modifier, onClick: () -> Unit) {
    val c = Acid.colors
    val shape = RoundedCornerShape(4.dp)
    Row(
        modifier.clip(shape)
            .background(if (muted) c.red.copy(alpha = 0.25f) else c.raised)
            .then(if (waiting != null) Modifier.border(2.dp, if (waiting) c.red else c.textMid, shape) else Modifier)
            .clickable(onClick = onClick)
            .button(
                stringResource(R.string.a11y_mute, name),
                listOfNotNull(
                    stringResource(if (muted) R.string.a11y_muted else R.string.a11y_off),
                    if (waiting != null) stringResource(R.string.a11y_mute_pending) else null,
                ).joinToString(stringResource(R.string.list_separator)),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(colour))
        Text(
            name, color = if (muted) c.red else c.textMid, fontSize = 11.sp, maxLines = 1, softWrap = false,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/**
 * Lengths side by side, played as one strip. Held: press one, slide along to
 * change it, lift to stop. Latched: tap one on, tap it again for off, tap
 * another to change.
 */
@Composable
private fun LengthStrip(labels: List<String>, held: Int, latch: Boolean, modifier: Modifier, name: String = "", onChange: (Int) -> Unit) {
    val c = Acid.colors
    val cb by rememberUpdatedState(onChange)
    val current by rememberUpdatedState(held)
    val latched by rememberUpdatedState(latch)
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).pointerInput(labels.size) {
            awaitEachGesture {
                fun at(x: Float) = ((x / size.width) * labels.size).toInt().coerceIn(0, labels.size - 1) + 1
                val down = awaitFirstDown()
                down.consume()
                val first = at(down.position.x)
                // Latched, a tap on the lit one turns it off.
                val wasOn = latched && current == first
                if (!wasOn) cb(first)
                var now = first
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (!change.pressed) break
                    val k = at(change.position.x)
                    if (k != now) { now = k; cb(k) }
                }
                if (!latched || (wasOn && now == first)) cb(0)
            }
        },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val on = held == i + 1
            Box(
                Modifier.weight(1f).fillMaxHeight().background(if (on) c.accent.copy(alpha = 0.35f) else c.raised)
                    // A double tap holds it, and another lets go.
                    .choice(stringResource(R.string.a11y_named, name, label), on) { cb(if (on) 0 else i + 1) },
                contentAlignment = Alignment.Center,
            ) { Text(label, color = if (on) c.accent else c.textMid, fontSize = 12.sp, maxLines = 1, softWrap = false) }
        }
    }
}

/** On while a finger is on it, or with [latch], tapped on and off. */
@Composable
private fun HoldPad(label: String, on: Boolean, latch: Boolean, colour: Color, modifier: Modifier, onHold: (Boolean) -> Unit) {
    val c = Acid.colors
    val cb by rememberUpdatedState(onHold)
    val current by rememberUpdatedState(on)
    val latched by rememberUpdatedState(latch)
    Box(
        modifier.clip(RoundedCornerShape(6.dp)).background(if (on) colour.copy(alpha = 0.3f) else c.raised)
            .button(label, stringResource(if (on) R.string.a11y_on else R.string.a11y_off), onClick = { cb(!current) })
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val latchedNow = latched
                    cb(if (latchedNow) !current else true)
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) break
                    }
                    if (!latchedNow) cb(false)
                }
            },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (on) colour else c.textMid, fontSize = 13.sp) }
}

/**
 * The pad. Held, it goes back to rest when the finger lifts; latched, it
 * stays where it was left.
 */
@Composable
private fun XyPad(
    at: Offset?,
    latch: Boolean,
    modifier: Modifier,
    left: String,
    right: String,
    up: String,
    onMove: (Offset?) -> Unit,
) {
    val c = Acid.colors
    val cb by rememberUpdatedState(onMove)
    val latched by rememberUpdatedState(latch)
    Box(
        modifier.clip(RoundedCornerShape(6.dp)).background(c.raised)
            .button(
                stringResource(R.string.a11y_xy, left, right, up),
                actions = listOf(
                    action(stringResource(R.string.a11y_xy_press)) { cb(Offset(0.5f, 0.6f)) },
                    action(stringResource(R.string.a11y_xy_release)) { cb(null) },
                ),
            ),
    ) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    fun send(p: Offset) = cb(
                        Offset((p.x / size.width).coerceIn(0f, 1f), (1f - p.y / size.height).coerceIn(0f, 1f)),
                    )
                    val down = awaitFirstDown()
                    down.consume()
                    send(down.position)
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) break
                        send(change.position)
                    }
                    if (!latched) cb(null)
                }
            },
        ) {
            val mid = size.width / 2f
            drawLine(c.line, Offset(mid, 0f), Offset(mid, size.height), strokeWidth = 1.dp.toPx())
            at?.let { f ->
                drawCircle(c.accent, radius = 14.dp.toPx(), center = Offset(f.x * size.width, (1f - f.y) * size.height))
            }
        }
        Text(left, color = c.textDim, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp))
        Text(right, color = c.textDim, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp))
        Text(up, color = c.textDim, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopCenter).padding(6.dp))
    }
}
