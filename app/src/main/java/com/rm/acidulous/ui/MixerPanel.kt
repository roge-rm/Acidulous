package com.rm.acidulous.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.rm.acidulous.model.SEND_SLOTS
import com.rm.acidulous.model.INPUT_SLOTS
import com.rm.acidulous.model.UnitSlot
import com.rm.acidulous.model.withInputFxBypass
import com.rm.acidulous.model.inputUnit
import com.rm.acidulous.model.sendUnit
import com.rm.acidulous.model.withInputFx
import com.rm.acidulous.model.withInputFxParam
import com.rm.acidulous.model.withSend
import com.rm.acidulous.model.withSendBypass
import com.rm.acidulous.model.withSendParam
import com.rm.acidulous.engine.NativeEngine
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.EngineSync
import com.rm.acidulous.model.EngineParams
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.rm.acidulous.model.laneUnit
import com.rm.acidulous.model.laneParam
import com.rm.acidulous.model.Mixer
import com.rm.acidulous.model.Song
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.model.Track
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.ui.theme.AcidColors

/**
 * The mixer section as a slide-up panel: a strip per track, then the
 * master. Fader drags are one undo step (per track, or song-level for the
 * master) and also go straight to the engine for immediacy.
 */
@Composable
fun MixerPanel(
    song: Song,
    editor: SongEditor,
    rackPeaks: FloatArray,
    masterPeak: Float,
    clickOn: Boolean,
    onClick: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Acid.colors
    // **The fader takes what is left, when there is a "left" to take.**
    //
    // A hundred and ten dp is a fair fader on a phone held upright, where the
    // mixer slides up over a song grid that can spare it. It is not a fair
    // fader in the editor's panel column turned sideways, which is about two
    // hundred dp tall in total and also carries a name, a pan, two sends and
    // two rows of chips - so the strip simply overhung and the bottom of it
    // was not there.
    //
    // So the fader is worked out from the room there actually is, floored at
    // a height you can still drag and capped at what it is upright. The
    // branch is on the measurement rather than on the orientation, because
    // the squeeze is a fact about the box this is in and not about which way
    // the phone is held - the same panel is tight in a turned editor and
    // roomy in an upright arranger.
    BoxWithConstraints(modifier) {
    // **A floor, and a scroll under it.** `weight(1f)` alone gave the fader
    // whatever was left, and in the editor's panel column what was left came
    // to about fifteen dp - everything visible and nothing draggable, which
    // is the wrong half of the problem to solve. So the height is worked out
    // instead, floored at something you can still move, and the strip scrolls
    // when even that will not fit. The scroll is the safety net rather than
    // the mechanism: at any ordinary size nothing scrolls at all.
    // **Capped, not filled.** `fillMaxHeight` was the first answer and it was
    // wrong in the ordinary case: a Column hands its children a bounded max
    // height whether or not the space is tight, so upright - where there was
    // never a problem - the strips stretched down the whole screen with a
    // hand's width of nothing under the chips. What is wanted is a ceiling.
    val room = if (constraints.hasBoundedHeight) maxHeight else Dp.Infinity
    val faderH = if (room == Dp.Infinity) {
        FADER_H
    } else {
        (room - STRIP_CHROME).coerceIn(FADER_MIN, FADER_H)
    }
    // Only once even the floor will not fit does anything scroll.
    val tight = room != Dp.Infinity && room < STRIP_CHROME + FADER_MIN
    // **Against the right edge, not the left.** The master is the last strip
    // and the thing you reach for, and with three tracks in a song the row
    // used to sit in the left third of the screen with two thirds of nothing
    // beside it - Dan: "the mixer panel should snap to the right side of the
    // screen, not the left".
    //
    // A minimum width of the viewport is what does it: the row is then at
    // least as wide as what it is in, so `Alignment.End` has somewhere to push
    // from. Once the strips are wider than that the minimum stops binding and
    // it scrolls exactly as before.
    Row(
        Modifier.background(c.panelAlt).horizontalScrollWithBar(rememberScrollState()).padding(6.dp)
            .then(if (room == Dp.Infinity) Modifier else Modifier.widthIn(min = maxWidth - 12.dp)),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
    ) {
        // What the two send sliders are called on every channel: whatever is
        // on the send. They said "rev" and "dly" when that was all they could
        // ever be, and went on saying it after the sends became slots, which
        // is a label describing the send it used to be.
        val sendNames = List(SEND_SLOTS) { slot ->
            song.master.sendAt(slot).type.ifEmpty { "send ${slot + 1}" }.lowercase()
        }
        song.tracks.forEachIndexed { index, track ->
            // Which of this channel's controls a clip is driving. A lane wins
            // over the fader on every pass, so a channel with one is a channel
            // whose fader will not appear to work - and nothing here said so.
            val automated = remember(track) {
                track.clips.values
                    .flatMap { it.automation.keys }
                    .filter { laneUnit(it) == "channel" }
                    .map { laneParam(it) }
                    .distinct()
                    .sorted()
            }
            ChannelStrip(track, index, rackPeaks.getOrElse(index) { 0f }, editor, trackColour(index), automated, faderH, room, tight, sendNames)
        }
        MasterStrip(song, editor, masterPeak, clickOn, onClick, faderH, room, tight)
    }
    }
}


@Composable
private fun ChannelStrip(
    track: Track, index: Int, peak: Float, editor: SongEditor, colour: Color,
    /** Channel controls some clip of this track has a lane for. */
    automated: List<String> = emptyList(),
    /** What the panel worked out this strip can spend on its fader. */
    faderH: Dp = FADER_H,
    /** The most the strip may be, or Infinity where nothing is pressing. */
    room: Dp = Dp.Infinity,
    /** Even the shortest usable strip will not fit, so this one scrolls. */
    tight: Boolean = false,
    /** What the two send sliders are called - the effects that are on them. */
    sendNames: List<String> = listOf("send 1", "send 2"),
) {
    val c = Acid.colors
    var askClear by remember { mutableStateOf(false) }
    val m = track.mixer
    fun live(name: String, v01: Float) = NativeEngine.setParam(index, "channel", name, v01)
    fun gesture(name: String, v01: Float, update: (Mixer) -> Mixer) {
        live(name, v01)
        editor.updateGesture { t -> t.copy(mixer = update(t.mixer)) }
    }
    fun tap(update: (Mixer) -> Mixer) = editor.edit(index) { t -> t.copy(mixer = update(t.mixer)) }
    fun map(name: String) = MapTargets.param(index, "channel", name)

    if (askClear) {
        PlainDialog(
            title = "Clear automation on ${track.name}?",
            onDismiss = { askClear = false },
            confirmLabel = "Clear",
            onConfirm = {
                askClear = false
                // Every clip on this track, because a lane in a scene you are
                // not playing will bite you the moment that scene comes round.
                editor.edit(index) { t ->
                    t.copy(clips = t.clips.mapValues { (_, clip) ->
                        val kept = clip.automation.filterKeys { laneUnit(it) != "channel" }
                        if (kept.size == clip.automation.size) clip else clip.copy(automation = kept)
                    })
                }
            },
        ) {
            Text(
                "This channel's " + automated.joinToString(", ") + " " +
                    (if (automated.size == 1) "is" else "are") +
                    " being driven by recorded movement, which is why the control does not stay where you put it. " +
                    "Clearing removes those lanes from every clip on this track; the notes are untouched.",
                color = c.textDim, fontSize = 11.sp, lineHeight = 14.sp,
            )
        }
    }

    Column(
        Modifier.width(STRIP_W).then(if (room == Dp.Infinity) Modifier else Modifier.heightIn(max = room))
            .clip(RoundedCornerShape(6.dp)).background(c.cardAlt)
            .then(if (tight) Modifier.verticalScrollWithBar(rememberScrollState()) else Modifier)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(track.name, color = c.text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // A lane on this channel, said out loud. Without it a fader that is
        // being overwritten every pass simply looks broken, which is exactly
        // how it was found: "the level didn't seem to respond to my controls".
        if (automated.isNotEmpty()) {
            Text(
                "\u223F " + automated.joinToString(" "),
                color = c.accent, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(3.dp))
                    .background(c.accentDim)
                    .clickable { askClear = true }
                    .padding(horizontal = 3.dp, vertical = 1.dp),
                textAlign = TextAlign.Center,
            )
        }
        Row(
            Modifier.height(faderH),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Meter(peak, Modifier.width(8.dp).fillMaxHeight())
            VerticalFader(
                value = EngineParams.volume01(m.volume),
                modifier = Modifier.width(36.dp).fillMaxHeight().mappable(map("gain")),
                accent = colour,
                onStart = { editor.beginGesture(index) },
                onChange = { v -> gesture("gain", v) { it.copy(volume = EngineParams.volumeFrom01(v)) } },
                onEnd = { editor.endGesture() },
            )
        }
        Labeled("pan") {
            MiniSlider(
                value = EngineParams.pan01(m.pan), centered = true,
                modifier = Modifier.width(STRIP_W - 8.dp).height(20.dp).mappable(map("pan")),
                onStart = { editor.beginGesture(index) },
                onChange = { v -> gesture("pan", v) { it.copy(pan = EngineParams.panFrom01(v)) } },
                onEnd = { editor.endGesture() },
            )
        }
        Labeled(sendNames.getOrElse(0) { "send 1" }) {
            MiniSlider(
                value = m.sendReverb,
                modifier = Modifier.width(STRIP_W - 8.dp).height(20.dp).mappable(map("sendreverb")),
                onStart = { editor.beginGesture(index) },
                onChange = { v -> gesture("sendreverb", v) { it.copy(sendReverb = v) } },
                onEnd = { editor.endGesture() },
            )
        }
        Labeled(sendNames.getOrElse(1) { "send 2" }) {
            MiniSlider(
                value = m.sendDelay,
                modifier = Modifier.width(STRIP_W - 8.dp).height(20.dp).mappable(map("senddelay")),
                onStart = { editor.beginGesture(index) },
                onChange = { v -> gesture("senddelay", v) { it.copy(sendDelay = v) } },
                onEnd = { editor.endGesture() },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ToggleChip("M", m.mute, c.red, Modifier.mappable(map("mute"))) { tap { it.copy(mute = !it.mute) } }
            ToggleChip("S", m.solo, c.accent, Modifier.mappable(map("solo"))) { tap { it.copy(solo = !it.solo) } }
        }
        // Where this track's notes go. Off, both, or out only - and at "out"
        // the machine is not asked at all, which is how driving something
        // else gives the CPU back. The channel sits beside it because one
        // without the other is no use.
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val label = when (m.midiMode) { 1 -> "both"; 2 -> "out"; else -> "midi" }
            ToggleChip(label, m.midiMode != 0, c.teal) {
                tap { it.copy(midiMode = (it.midiMode + 1) % 3) }
            }
            if (m.midiMode != 0) {
                ToggleChip("${m.midiChannel + 1}", true, c.accentDim) {
                    tap { it.copy(midiChannel = (it.midiChannel + 1) % 16) }
                }
            }
        }
    }
}

@Composable
private fun MasterStrip(
    song: Song, editor: SongEditor, peak: Float, clickOn: Boolean, onClick: (Boolean) -> Unit,
    faderH: Dp = FADER_H,
    room: Dp = Dp.Infinity,
    tight: Boolean = false,
) {
    val c = Acid.colors
    val master = song.master
    fun live(name: String, v01: Float) = NativeEngine.setParam(0, "master", name, v01)
    fun gesture(name: String, v01: Float, update: (Song) -> Song) {
        live(name, v01)
        editor.updateSongGesture(update)
    }
    // The master is rack 0 by convention - it has no rack of its own, and a
    // mapping to it never follows the routing.
    fun map(name: String) = MapTargets.param(0, "master", name)

    // Which send's editor is open, if any.
    var editing by remember { mutableStateOf<Int?>(null) }
    editing?.let { slot -> SendDialog(slot, editor) { editing = null } }

    Column(
        Modifier.width(MASTER_W).then(if (room == Dp.Infinity) Modifier else Modifier.heightIn(max = room))
            .clip(RoundedCornerShape(6.dp)).background(c.cardHi)
            .then(if (tight) Modifier.verticalScrollWithBar(rememberScrollState()) else Modifier)
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("master", color = c.text, fontSize = 11.sp)
        Row(
            Modifier.height(faderH),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Meter(peak, Modifier.width(8.dp).fillMaxHeight())
            VerticalFader(
                value = EngineParams.volume01(master.volume),
                modifier = Modifier.width(36.dp).fillMaxHeight().mappable(map("volume")),
                accent = Acid.colors.knobPointer,
                onStart = { editor.beginSongGesture() },
                onChange = { v -> gesture("volume", v) { s -> s.copy(master = s.master.copy(volume = EngineParams.volumeFrom01(v))) } },
                onEnd = { editor.endSongGesture() },
            )
        }
        Labeled("limit drive") {
            MiniSlider(master.limiter.drive, Modifier.width(STRIP_W - 8.dp).height(20.dp).mappable(map("limiterdrive")),
                onStart = { editor.beginSongGesture() },
                onChange = { v -> gesture("limiterdrive", v) { s -> s.copy(master = s.master.copy(limiter = s.master.limiter.copy(drive = v))) } },
                onEnd = { editor.endSongGesture() })
        }
        // **Under the fader, not beside it.** Four chips in a column next to a
        // thirty-six dp fader made the master strip three times a channel's
        // width and left the whole row hugging one side of the screen with a
        // hand's width of nothing on the other - Dan, over a drawing of it
        // going vertical instead. A strip is a column; this one is now the
        // same column as its neighbours, the same width, and the chips are
        // simply more of what is in it.
        // **The two sends, by the name of whatever is on them.** They
        // were a `reverb` chip and a `delay` chip because that is all
        // they could ever be; now the chip says what the slot holds and
        // a hold opens it - the tap/hold grammar every other slot in
        // the app already uses.
        for (slot in 0 until SEND_SLOTS) {
            val send = master.sendAt(slot)
            ToggleChip(
                if (send.isEmpty) "send${slot + 1}" else send.type.lowercase(),
                !send.isEmpty && !send.bypass,
                c.teal,
                Modifier.onLongPress { editing = slot },
            ) {
                if (send.isEmpty) editing = slot
                else {
                    val bypass = !send.bypass
                    editor.editSong { s -> s.withSendBypass(slot, bypass) }
                    NativeEngine.setParam(0, sendUnit(slot), "bypass", if (bypass) 1f else 0f, record = false)
                }
            }
        }
        // **The two on the way in are not here.** They were, beside the sends,
        // on the grounds that both belong to the song rather than to a rack -
        // and that put them where a mix is made rather than where a recording
        // is. Dan: "they are applied on the recordings themselves as they go
        // in". They live in the record window now, where what they do to a
        // take is the thing you are already looking at.
        ToggleChip("limiter", master.limiter.on, c.teal, Modifier.mappable(map("limiteron"))) {
            editor.editSong { s -> s.copy(master = s.master.copy(limiter = s.master.limiter.copy(on = !s.master.limiter.on))) }
        }
        ToggleChip("♩ click", clickOn, c.accent) { onClick(!clickOn) }
    }
}

@Composable
private fun Labeled(label: String, content: @Composable () -> Unit) {
    Column {
        Text(label, color = Acid.colors.textDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        content()
    }
}

// Material buttons carry a 40 dp minimum height; these have to stack four high
// beside a fader, so they are plain boxes.
@Composable
private fun ToggleChip(
    label: String,
    on: Boolean,
    colour: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = Acid.colors
    Box(
        modifier
            .height(22.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (on) colour.copy(alpha = 0.25f) else c.raised)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (on) colour else c.textMid, fontSize = 11.sp, maxLines = 1) }
}

internal fun trackColour(index: Int): Color = PALETTE[index % PALETTE.size]

// A track's stripe is how that track is recognised at a glance, so it is
// the same colour in both themes - it belongs to the track, not to the
// interface. Mid-saturation hues that hold up on white and on black.
private val PALETTE = listOf(
    Color(0xFF3FA98D), Color(0xFFE09A3C), Color(0xFFD4688A), Color(0xFF5B8FE0),
    Color(0xFF8CC04E), Color(0xFFE0714A), Color(0xFF9B7BD4), Color(0xFF3FAFC0),
)

private val STRIP_W = 76.dp
/**
 * The master strip is a strip.
 *
 * It was two hundred and thirty-two dp - a fader with a column of chips beside
 * it - which is three channels wide for one channel's worth of controls. Now
 * everything in it is stacked the way a channel stacks its own, so it is the
 * same width as its neighbours and the row is a row of equal strips.
 */
private val MASTER_W = STRIP_W
/** What a fader is upright, and the most it is anywhere. */
private val FADER_H = 110.dp

/** Under this it stops being something you drag and becomes a readout. */
private val FADER_MIN = 56.dp

/**
 * What a channel strip spends on everything that is not the fader.
 *
 * The name, three labelled sliders, the mute/solo row, the MIDI row, the
 * padding and the gaps between them - none of which has any give in it, which
 * is why the fader is the piece that gives. Counted off the strip below
 * rather than guessed; if a row is added to it, this goes up.
 */
private val STRIP_CHROME = 204.dp
// It describes a *channel* strip, which is the one that decides how tall a
// fader can be; the master carries a chip or two more and scrolls if it must.


/**
 * What is on a send bus, and everything that effect can do.
 *
 * **The same window an insert slot opens, in the one place a send belongs
 * to.** A send is song-wide, so this is reached from the master strip rather
 * than from a track - hold the chip that names it - and it is the only control
 * in the mixer that is not a fader, a chip or a slider, which is why it is a
 * window and not another column of knobs in a strip that is already full.
 *
 * `mix` is not offered. The engine pins it fully wet when the effect is
 * mounted, because a dry path through a send is the track arriving twice.
 */
@Composable
private fun SendDialog(slot: Int, editor: SongEditor, onDismiss: () -> Unit) =
    SongSlotDialog("send ${slot + 1}", slot, ::sendUnit, { s, i -> s.master.sendAt(i) },
                   Song::withSend, Song::withSendParam,
                   // A send's dry path is the track arriving twice, so the mix
                   // is pinned open and not offered.
                   hideMix = true, editor = editor, onDismiss = onDismiss)

/**
 * The two effects on the way **in**, as chips that open them.
 *
 * Deliberately reachable from the one machine that records - the hand is
 * already there when somebody decides they want the amp on the take rather
 * than after it - even though the slots themselves belong to the song and not
 * to that track.
 */
@Composable
fun InputChainChips(editor: SongEditor) {
    val c = Acid.colors
    var editing by remember { mutableStateOf<Int?>(null) }
    editing?.let { slot ->
        SongSlotDialog("on the way in ${slot + 1}", slot, ::inputUnit, { s, i -> s.inputAt(i) },
                       Song::withInputFx, Song::withInputFxParam,
                       // An input effect is in series with the signal rather
                       // than beside it, so a dry blend is a real thing to want.
                       hideMix = false, editor = editor) { editing = null }
    }
    for (slot in 0 until INPUT_SLOTS) {
        val fx = editor.song.inputAt(slot)
        ToggleChip(
            if (fx.isEmpty) "in${slot + 1}" else fx.type.lowercase(),
            !fx.isEmpty && !fx.bypass,
            c.pink,
            Modifier.onLongPress { editing = slot },
        ) {
            if (fx.isEmpty) editing = slot
            else {
                val bypass = !fx.bypass
                editor.editSong { s -> s.withInputFxBypass(slot, bypass) }
                NativeEngine.setParam(0, inputUnit(slot), "bypass", if (bypass) 1f else 0f, record = false)
            }
        }
    }
}

/**
 * One song-level effect slot: pick the type, turn its knobs.
 *
 * Shared by the two sends and the two on the input, because they are the same
 * thing in two places - a slot that belongs to the song rather than to a rack,
 * addressed by a unit name, edited as one song gesture. What differs is only
 * *where the engine runs it*, and that is not this window's business.
 */
@Composable
fun SongSlotDialog(
    title: String,
    slot: Int,
    unitOf: (Int) -> String,
    at: (Song, Int) -> UnitSlot,
    withType: (Song, Int, String) -> Song,
    withParam: (Song, Int, String, Float) -> Song,
    hideMix: Boolean,
    editor: SongEditor,
    onDismiss: () -> Unit,
) {
    val c = Acid.colors
    val send = at(editor.song, slot)
    val types = remember { NativeEngine.effectTypes }
    var menu by remember { mutableStateOf(false) }
    val info = remember(send.type, hideMix) {
        if (send.isEmpty) emptyList()
        else NativeEngine.effectParamInfo(send.type).filter { !hideMix || it.name != "mix" }
    }
    PlainDialog(title, onDismiss = onDismiss, dismissLabel = "Done", maxBodyHeight = 420.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { menu = true }) {
                    Text(if (send.isEmpty) "none ▾" else "${send.type} ▾", color = c.accent, fontSize = 13.sp)
                }
                val menuScroll = rememberScrollState()
                DropdownMenu(
                    expanded = menu, onDismissRequest = { menu = false },
                    modifier = Modifier.scrollbar(menuScroll, color = c.scrollbar), scrollState = menuScroll,
                ) {
                    ScaledWindow {
                        DropdownMenuItem(text = { Text("none", fontSize = 12.sp) }, onClick = {
                            menu = false
                            editor.editSong { s -> withType(s, slot, "") }
                        })
                        for (t in types) DropdownMenuItem(text = { Text(t, fontSize = 12.sp) }, onClick = {
                            menu = false
                            if (t != send.type) editor.editSong { s -> withType(s, slot, t) }
                        })
                    }
                }
            }
            for (p in info) {
                // The document is what the slider reads, not the engine: a send
                // has no knob anywhere else to fight with, and a parameter the
                // song has never touched is the effect's own default rather
                // than nought.
                val v = send.params[p.name] ?: p.defaultNormalized
                Labeled("${p.name}  ${p.format(v)}") {
                    MiniSlider(
                        v, Modifier.width(200.dp).height(20.dp),
                        onStart = { editor.beginSongGesture() },
                        onChange = { nv ->
                            NativeEngine.setParam(
                                0, unitOf(slot), p.name, nv, record = false,
                            )
                            editor.updateSongGesture { s -> withParam(s, slot, p.name, nv) }
                        },
                        onEnd = { editor.endSongGesture() },
                    )
                }
            }
        }
    }
}
