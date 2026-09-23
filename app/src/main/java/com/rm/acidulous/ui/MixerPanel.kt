package com.rm.acidulous.ui

import androidx.compose.ui.layout.onSizeChanged
import com.rm.acidulous.model.withGroupInsertBypass
import com.rm.acidulous.model.withGroupInsertParam
import com.rm.acidulous.model.withGroupInsert
import com.rm.acidulous.model.withGroupSolo
import com.rm.acidulous.model.withGroupMute
import com.rm.acidulous.model.withGroupVolume
import com.rm.acidulous.model.deleteGroup
import com.rm.acidulous.model.renameGroup
import com.rm.acidulous.model.addGroup
import com.rm.acidulous.model.groupInsertUnit
import com.rm.acidulous.model.MixGroup
import com.rm.acidulous.model.GROUP_INSERT_SLOTS
import com.rm.acidulous.model.MAX_GROUPS
import androidx.compose.runtime.LaunchedEffect
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
import com.rm.acidulous.model.MASTER_INSERT_SLOTS
import com.rm.acidulous.model.SEND_SLOTS
import com.rm.acidulous.model.masterInsertUnit
import com.rm.acidulous.model.withMasterInsert
import com.rm.acidulous.model.withMasterInsertBypass
import com.rm.acidulous.model.withMasterInsertParam
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
    // The output row is there only in a song that has a group to route to.
    val groups = song.master.groups.map { it.name }
    val chrome = STRIP_CHROME + if (groups.isEmpty()) 0.dp else OUTPUT_ROW
    val faderH = if (room == Dp.Infinity) {
        FADER_H
    } else {
        (room - chrome).coerceIn(FADER_MIN, FADER_H)
    }
    // Only once even the floor will not fit does anything scroll.
    val tight = room != Dp.Infinity && room < chrome + FADER_MIN
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
        val density = androidx.compose.ui.platform.LocalDensity.current
        var stripH by remember { mutableStateOf(Dp.Unspecified) }
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
            // The first strip is measured, and the groups and the master are
            // made its height, so the whole row ends on one line.
            Box(if (index == 0) Modifier.onSizeChanged { stripH = with(density) { it.height.toDp() } } else Modifier) {
                ChannelStrip(track, index, rackPeaks.getOrElse(index) { 0f }, editor, trackColour(index), automated, faderH, room, tight, sendNames, groups)
            }
        }
        val fullH = if (song.tracks.isEmpty() || tight) Dp.Unspecified else stripH
        // The groups: strips of their own between the tracks and the master,
        // and a button to add one while there is room.
        song.master.groups.forEachIndexed { g, group -> GroupStrip(g, group, song, editor, faderH, room, tight, fullH) }
        if (song.master.groups.size < MAX_GROUPS) AddGroupStrip(editor, room, fullH)
        MasterStrip(song, editor, masterPeak, clickOn, onClick, faderH, room, tight, fullH)
    }
    }
}

/**
 * One of the mixer's groups: a name, a meter and fader, mute and solo, and two
 * inserts. Tracks are routed here from the output row under their own fader.
 * Tap the name to rename it, hold it to delete the group.
 */
@Composable
private fun GroupStrip(g: Int, group: MixGroup, song: Song, editor: SongEditor, faderH: Dp, room: Dp, tight: Boolean, fullH: Dp) {
    val c = Acid.colors
    val n = g + 1
    var peak by remember { mutableStateOf(0f) }
    LaunchedEffect(g) {
        while (true) {
            peak = NativeEngine.groupPeak(g)
            kotlinx.coroutines.delay(80)
        }
    }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var editingInsert by remember { mutableStateOf<Int?>(null) }
    if (renaming) {
        TextInputDialog("Group name", group.name, onDismiss = { renaming = false }) { name ->
            renaming = false
            if (name.isNotBlank()) editor.editSong { s -> s.renameGroup(g, name.trim()) }
        }
    }
    if (deleting) {
        PlainDialog(
            title = "Delete ${group.name}?",
            onDismiss = { deleting = false },
            confirmLabel = "Delete",
            onConfirm = { deleting = false; editor.editSong { s -> s.deleteGroup(g) } },
        ) {
            Text("Its tracks go back to the master.", color = c.textDim, fontSize = 11.sp)
        }
    }
    editingInsert?.let { slot ->
        SongSlotDialog(
            "${group.name} fx${slot + 1}", slot, { s -> groupInsertUnit(g, s) },
            { song, s -> song.master.groups.getOrNull(g)?.insertAt(s) ?: UnitSlot() },
            { song, s, t -> song.withGroupInsert(g, s, t) },
            { song, s, name, v -> song.withGroupInsertParam(g, s, name, v) },
            hideMix = false, editor = editor,
        ) { editingInsert = null }
    }
    Column(
        Modifier.width(STRIP_W).then(
            if (fullH != Dp.Unspecified) Modifier.height(fullH)
            else if (room == Dp.Infinity) Modifier else Modifier.heightIn(max = room),
        )
            .clip(RoundedCornerShape(6.dp)).background(c.cardHi)
            .then(if (tight) Modifier.verticalScrollWithBar(rememberScrollState()) else Modifier)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            group.name, color = c.accent, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.onLongPress { deleting = true }.clickable { renaming = true },
        )
        Row(Modifier.height(faderH), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Meter(peak, Modifier.width(8.dp).fillMaxHeight())
            VerticalFader(
                value = EngineParams.volume01(group.volume),
                modifier = Modifier.width(36.dp).fillMaxHeight().mappable(MapTargets.param(0, "master", "g${n}gain")),
                accent = c.accent,
                onStart = { editor.beginSongGesture() },
                onChange = { v ->
                    NativeEngine.setParam(0, "master", "g${n}gain", v)
                    editor.updateSongGesture { s -> s.withGroupVolume(g, EngineParams.volumeFrom01(v)) }
                },
                onEnd = { editor.endSongGesture() },
            )
        }
        // What is routed here, in the room a channel spends on pan and sends.
        val members = song.tracks.filter { it.mixer.output == g + 1 }.map { it.name }
        Text(
            if (members.isEmpty()) "nothing routed here" else members.joinToString("\n"),
            color = c.textDim, fontSize = 10.sp, lineHeight = 13.sp,
            maxLines = 6, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        )
        if (fullH != Dp.Unspecified) Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ToggleChip("M", group.mute, c.red) { editor.editSong { s -> s.withGroupMute(g, !group.mute) } }
            ToggleChip("S", group.solo, c.accent) { editor.editSong { s -> s.withGroupSolo(g, !group.solo) } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (slot in 0 until GROUP_INSERT_SLOTS) {
                val fx = group.insertAt(slot)
                GridChip(if (fx.isEmpty) "fx${slot + 1}" else shortFx(fx.type), !fx.isEmpty && !fx.bypass, c.accent,
                         Modifier.weight(1f).onLongPress { editingInsert = slot }) {
                    if (fx.isEmpty) editingInsert = slot
                    else {
                        val bypass = !fx.bypass
                        editor.editSong { s -> s.withGroupInsertBypass(g, slot, bypass) }
                        NativeEngine.setParam(0, groupInsertUnit(g, slot), "bypass", if (bypass) 1f else 0f, record = false)
                    }
                }
            }
        }
    }
}

/** Where a new group comes from: a narrow strip with one button. */
@Composable
private fun AddGroupStrip(editor: SongEditor, room: Dp, fullH: Dp) {
    val c = Acid.colors
    Box(
        Modifier.width(48.dp).then(
            if (fullH != Dp.Unspecified) Modifier.height(fullH)
            else if (room == Dp.Infinity) Modifier.height(120.dp) else Modifier.heightIn(max = room),
        )
            .clip(RoundedCornerShape(6.dp)).background(c.cardAlt)
            .clickable { editor.editSong { s -> s.addGroup("Group ${s.master.groups.size + 1}") } },
        contentAlignment = Alignment.Center,
    ) { Text("+\ngroup", color = c.textMid, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
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
    /** The names of the mixer's groups, which this track can be routed into. */
    groups: List<String> = emptyList(),
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

    /**
     * The strip as it was when the mixer opened, for a long press to go back
     * to - the same gesture the panels' knobs have.
     *
     * **Taken at composition, unlike a panel's.** A machine panel waits for
     * its first poll because it reads the *engine* and knows nothing until it
     * has asked. A strip reads the document, which is right here and already
     * correct, so there is nothing to wait for. The mixer leaving composition
     * when it closes is what makes reopening take a fresh reading.
     */
    val opened = remember(index) { m }

    /** Put one value back, in the document and in the engine both. */
    fun back(name: String, v01: Float, update: (Mixer) -> Mixer) {
        live(name, v01)
        tap(update)
    }

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
                    " automated, so the control won't stay where you put it. " +
                    "Clearing removes those lanes from every clip on this track. Notes aren't touched.",
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
        // A lane on this channel, said out loud. Without it a fader that is
        // being overwritten every pass simply looks broken, which is exactly
        // how it was found: "the level didn't seem to respond to my controls".
        //
        // **Beside the name, not above the fader.** It used to be its own
        // full-width row, which meant the one strip that had automation stood
        // a line lower than every strip beside it - the faders no longer
        // started at the same height and the mixer stopped reading as a row of
        // like things. A mark on the name line costs no height at all, so
        // every strip still lines up, and the lanes themselves are named in
        // the window a tap opens, which had more room to say it properly than
        // a 9sp line ever did.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(track.name, color = c.text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (automated.isNotEmpty()) {
                Text(
                    "\u223F",
                    color = c.accent,
                    fontSize = 11.sp,
                    modifier = Modifier.clickable { askClear = true }.padding(start = 3.dp),
                )
            }
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
                onReset = { back("gain", EngineParams.volume01(opened.volume)) { it.copy(volume = opened.volume) } },
            )
        }
        Labeled("pan") {
            MiniSlider(
                value = EngineParams.pan01(m.pan), centered = true,
                modifier = Modifier.width(STRIP_W - 8.dp).height(20.dp).mappable(map("pan")),
                onStart = { editor.beginGesture(index) },
                onChange = { v -> gesture("pan", v) { it.copy(pan = EngineParams.panFrom01(v)) } },
                onEnd = { editor.endGesture() },
                onReset = { back("pan", EngineParams.pan01(opened.pan)) { it.copy(pan = opened.pan) } },
            )
        }
        Labeled(sendNames.getOrElse(0) { "send 1" }) {
            MiniSlider(
                value = m.sendReverb,
                modifier = Modifier.width(STRIP_W - 8.dp).height(20.dp).mappable(map("sendreverb")),
                onStart = { editor.beginGesture(index) },
                onChange = { v -> gesture("sendreverb", v) { it.copy(sendReverb = v) } },
                onEnd = { editor.endGesture() },
                onReset = { back("sendreverb", opened.sendReverb) { it.copy(sendReverb = opened.sendReverb) } },
            )
        }
        Labeled(sendNames.getOrElse(1) { "send 2" }) {
            MiniSlider(
                value = m.sendDelay,
                modifier = Modifier.width(STRIP_W - 8.dp).height(20.dp).mappable(map("senddelay")),
                onStart = { editor.beginGesture(index) },
                onChange = { v -> gesture("senddelay", v) { it.copy(sendDelay = v) } },
                onEnd = { editor.endGesture() },
                onReset = { back("senddelay", opened.sendDelay) { it.copy(sendDelay = opened.sendDelay) } },
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
        // Where the sound goes: the master, or one of the mixer's groups. A
        // tap steps through them.
        if (groups.isNotEmpty()) {
            val to = groups.getOrNull(m.output - 1)
            ToggleChip("→ ${to ?: "master"}", to != null, c.teal, Modifier.width(STRIP_W - 8.dp).mappable(map("output")), padding = 3.dp) {
                tap { it.copy(output = if (it.output in 0 until groups.size) it.output + 1 else 0) }
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
    /** A channel strip's height, to match; unspecified where there is none to match. */
    fullH: Dp = Dp.Unspecified,
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
    var editingInsert by remember { mutableStateOf<Int?>(null) }
    editingInsert?.let { slot ->
        SongSlotDialog("master ${slot + 1}", slot, ::masterInsertUnit, { s, i -> s.master.insertAt(i) },
                       Song::withMasterInsert, Song::withMasterInsertParam,
                       // In series with the mix, like a track's insert, so a
                       // dry blend is a real thing to want.
                       hideMix = false, editor = editor) { editingInsert = null }
    }

    Column(
        Modifier.width(MASTER_W).then(
            if (fullH != Dp.Unspecified) Modifier.height(fullH)
            else if (room == Dp.Infinity) Modifier else Modifier.heightIn(max = room),
        )
            .clip(RoundedCornerShape(6.dp)).background(c.cardHi)
            .then(if (tight) Modifier.verticalScrollWithBar(rememberScrollState()) else Modifier)
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("master", color = c.text, fontSize = 11.sp)
        // Loudness: integrated since play started, and the short-term and true
        // peak under it. Polled while the mixer is open, which is also what
        // keeps the engine measuring; a tap starts the integrated figure again.
        var lufs by remember { mutableStateOf(floatArrayOf(-120f, -120f, -120f, -120f)) }
        LaunchedEffect(Unit) {
            while (true) {
                lufs = NativeEngine.loudness()
                kotlinx.coroutines.delay(250)
            }
        }
        fun fmt(v: Float) = if (v <= -70f) "-" else "%.1f".format(v)
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
        // Under the limiter rather than over the fader, so the master's fader
        // lines up with every channel's.
        Column(Modifier.fillMaxWidth().clickable { NativeEngine.resetLoudness() }) {
            Text("${fmt(lufs[2])} LUFS", color = c.text, fontSize = 10.sp, maxLines = 1)
            Text("TP ${fmt(lufs[3])}", color = if (lufs[3] > -1f) c.red else c.textDim, fontSize = 9.sp, maxLines = 1)
        }
        // The buttons sit at the foot of the strip, level with the channels'.
        if (fullH != Dp.Unspecified) Spacer(Modifier.weight(1f))
        // **Six buttons in a grid of three rows, so the strip is no taller
        // than a channel's.** The two sends, the two master inserts, then the
        // limiter and the click. Tap switches one off and on; hold opens a
        // send or an insert to choose the effect and set it up.
        fun sendTap(slot: Int) {
            val send = master.sendAt(slot)
            if (send.isEmpty) { editing = slot; return }
            val bypass = !send.bypass
            editor.editSong { s -> s.withSendBypass(slot, bypass) }
            NativeEngine.setParam(0, sendUnit(slot), "bypass", if (bypass) 1f else 0f, record = false)
        }
        fun insertTap(slot: Int) {
            val fx = master.insertAt(slot)
            if (fx.isEmpty) { editingInsert = slot; return }
            val bypass = !fx.bypass
            editor.editSong { s -> s.withMasterInsertBypass(slot, bypass) }
            NativeEngine.setParam(0, masterInsertUnit(slot), "bypass", if (bypass) 1f else 0f, record = false)
        }
        val grid = Modifier.fillMaxWidth()
        Row(grid, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (slot in 0 until SEND_SLOTS) {
                val send = master.sendAt(slot)
                GridChip(if (send.isEmpty) "s${slot + 1}" else shortFx(send.type), !send.isEmpty && !send.bypass, c.teal,
                         Modifier.weight(1f).onLongPress { editing = slot }) { sendTap(slot) }
            }
        }
        Row(grid, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (slot in 0 until MASTER_INSERT_SLOTS) {
                val fx = master.insertAt(slot)
                GridChip(if (fx.isEmpty) "fx${slot + 1}" else shortFx(fx.type), !fx.isEmpty && !fx.bypass, c.accent,
                         Modifier.weight(1f).onLongPress { editingInsert = slot }) { insertTap(slot) }
            }
        }
        Row(grid, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            GridChip("lim", master.limiter.on, c.teal, Modifier.weight(1f).mappable(map("limiteron"))) {
                editor.editSong { s -> s.copy(master = s.master.copy(limiter = s.master.limiter.copy(on = !s.master.limiter.on))) }
            }
            GridChip("♩", clickOn, c.accent, Modifier.weight(1f)) { onClick(!clickOn) }
        }
    }
}

/** An effect's name cut to fit half a strip. */
private fun shortFx(type: String): String = when (type) {
    "Reverb" -> "verb"
    "Delay" -> "dly"
    "Compressor" -> "comp"
    "Distortion" -> "dist"
    "Bitcrusher" -> "crsh"
    "Chorus" -> "chor"
    "Flanger" -> "flng"
    "Phaser" -> "phas"
    "Tremolo" -> "trem"
    "Filter" -> "filt"
    "Width" -> "wide"
    "Shifter" -> "shft"
    "Harmonizer" -> "harm"
    else -> type.lowercase().take(4)
}

/** A half-width chip for the master strip's grid. */
@Composable
private fun GridChip(label: String, on: Boolean, colour: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Acid.colors
    Box(
        modifier
            .height(20.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (on) colour.copy(alpha = 0.25f) else c.raised)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (on) colour else c.textMid, fontSize = 10.sp, maxLines = 1) }
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
    padding: Dp = 8.dp,
    onClick: () -> Unit,
) {
    val c = Acid.colors
    Box(
        modifier
            .height(22.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (on) colour.copy(alpha = 0.25f) else c.raised)
            .clickable(onClick = onClick)
            .padding(horizontal = padding),
        contentAlignment = Alignment.Center,
    ) {
        // Never wrapped: a label that wraps at its space and is then cut to one
        // line shows only its first word - "→ Rhythm" showed as "→".
        Text(label, color = if (on) colour else c.textMid, fontSize = 11.sp, maxLines = 1,
             softWrap = false, overflow = TextOverflow.Ellipsis)
    }
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

/** The output row a strip grows when the song has a group to route to. */
private val OUTPUT_ROW = 26.dp
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
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    ScaledMenu(menuScroll) {
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
