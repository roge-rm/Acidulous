package com.rm.acidulous.ui

import com.rm.acidulous.util.format

import androidx.compose.ui.layout.onSizeChanged
import com.rm.acidulous.model.withSlotSetting
import com.rm.acidulous.model.withGroupInsertBypass
import com.rm.acidulous.model.withGroupInsertParam
import com.rm.acidulous.model.withGroupInsert
import com.rm.acidulous.model.withGroupSolo
import com.rm.acidulous.model.withGroupPan
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
import androidx.compose.foundation.layout.FlowRow
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
import com.rm.acidulous.res.*

/**
 * The mixer as a slide-up panel: a strip per track, then the master. A fader
 * drag is one undo step (per track, or song-level for the master) and also goes
 * straight to the engine.
 */
@Composable
fun MixerPanel(
    song: Song,
    editor: SongEditor,
    /** Read by each strip's meter, not here, so a level changing redraws the meter and nothing else. */
    rackPeaks: () -> FloatArray,
    masterPeak: () -> Float,
    clickOn: Boolean,
    onClick: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Whether the strips may wrap into rows. Yes in the editor's column. Not
     * under the song grid, where the mixer is a bar along the bottom and two
     * rows would push the grid off the screen.
     */
    rows: Boolean = true,
) {
    val c = Acid.colors
    // The fader height comes from the room available, floored at a height you
    // can still drag and capped at FADER_H. In the editor's panel column turned
    // sideways there's only about 200 dp for the whole strip. This goes by the
    // measured size, not by orientation.
    BoxWithConstraints(modifier) {
    // The height is worked out instead of using weight(1f), which left about 15
    // dp in the editor's column, and capped instead of fillMaxHeight, which
    // stretched the strips down the whole screen. The strip only scrolls when
    // even the floor doesn't fit.
    val room = if (constraints.hasBoundedHeight) maxHeight else Dp.Infinity
    // The output row only appears when the song has a group to route to.
    val groups = song.master.groups.map { it.name }
    val chrome = STRIP_CHROME + if (groups.isEmpty()) 0.dp else OUTPUT_ROW
    // Wrap into rows where there's height for them, like the editor's column on
    // a tablet or desktop. When two rows fit at the shortest fader the strips
    // wrap (right-aligned), the height is shared between two rows and the
    // column scrolls for the rest. Strips that fit in one line lay out as
    // usual.
    val count = song.tracks.size + song.master.groups.size + (if (song.master.groups.size < MAX_GROUPS) 1 else 0) + 1
    val across = (STRIP_W + 6.dp) * count + 6.dp
    val wrap = rows && room != Dp.Infinity && across > maxWidth && room >= (chrome + FADER_MIN) * 2 + 6.dp
    val faderH = if (room == Dp.Infinity) {
        FADER_H
    } else if (wrap) {
        ((room - 6.dp) / 2 - chrome).coerceIn(FADER_MIN, FADER_H)
    } else {
        (room - chrome).coerceIn(FADER_MIN, FADER_H)
    }
    // Only scroll once even the floor won't fit.
    val tight = room != Dp.Infinity && room < chrome + FADER_MIN
    // Aligned to the right edge, since the master is the last strip and the one
    // you reach for. The row gets a minimum width of the viewport so
    // Alignment.End has room to push. Once the strips are wider it scrolls as
    // usual.
    val strips: @Composable () -> Unit = {
        // The send sliders are labelled with whatever effect is on each send.
        val density = androidx.compose.ui.platform.LocalDensity.current
        var stripH by remember { mutableStateOf(Dp.Unspecified) }
        val sendNames = List(SEND_SLOTS) { slot ->
            song.master.sendAt(slot).type.lowercase().ifEmpty { stringResource(Res.string.mixer_send, slot + 1) }
        }
        song.tracks.forEachIndexed { index, track ->
            // Which of this channel's controls a clip is automating. A lane
            // overrides the fader on every pass, so the fader would seem not to
            // work without this.
            val automated = remember(track) {
                track.clips.values
                    .flatMap { it.automation.keys }
                    .filter { laneUnit(it) == "channel" }
                    .map { laneParam(it) }
                    .distinct()
                    .sorted()
            }
            // The first strip is measured and the groups and master are given
            // its height, so the whole row ends level.
            Box(if (index == 0) Modifier.onSizeChanged { stripH = with(density) { it.height.toDp() } } else Modifier) {
                ChannelStrip(track, index, { rackPeaks().getOrElse(index) { 0f } }, editor, trackColour(index, track.colour), automated, faderH, room, tight, sendNames, groups)
            }
        }
        val fullH = if (song.tracks.isEmpty() || tight) Dp.Unspecified else stripH
        // The groups get their own strips between the tracks and the master,
        // plus a button to add one while there's room.
        song.master.groups.forEachIndexed { g, group -> GroupStrip(g, group, song, editor, faderH, room, tight, fullH) }
        if (song.master.groups.size < MAX_GROUPS) AddGroupStrip(editor, room, fullH)
        MasterStrip(song, editor, masterPeak, clickOn, onClick, faderH, room, tight, fullH)
    }
    if (wrap) {
        FlowRow(
            Modifier.fillMaxWidth().background(c.panelAlt).verticalScrollWithBar(rememberScrollState()).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) { strips() }
    } else {
        Row(
            Modifier.background(c.panelAlt).horizontalScrollWithBar(rememberScrollState()).padding(6.dp)
                .then(if (room == Dp.Infinity) Modifier else Modifier.widthIn(min = maxWidth - 12.dp)),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        ) { strips() }
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
        TextInputDialog(stringResource(Res.string.mixer_group_name_title), group.name, onDismiss = { renaming = false }) { name ->
            renaming = false
            if (name.isNotBlank()) editor.editSong { s -> s.renameGroup(g, name.trim()) }
        }
    }
    if (deleting) {
        PlainDialog(
            title = stringResource(Res.string.mixer_group_delete_title, group.name),
            onDismiss = { deleting = false },
            confirmLabel = stringResource(Res.string.mixer_delete),
            onConfirm = { deleting = false; editor.editSong { s -> s.deleteGroup(g) } },
        ) {
            Text(stringResource(Res.string.mixer_group_delete_note), color = c.textDim, fontSize = 11.sp)
        }
    }
    editingInsert?.let { slot ->
        SongSlotDialog(
            stringResource(Res.string.mixer_group_insert, group.name, slot + 1), slot, { s -> groupInsertUnit(g, s) },
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
            .padding(4.dp).together(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Tap the name to rename, the cross beside it removes the group.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                group.name, color = c.accent, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).onLongPress { deleting = true }.clickable { renaming = true },
            )
            Text("✕", color = c.textDim, fontSize = 11.sp, modifier = Modifier.clickable { deleting = true }.padding(horizontal = 3.dp))
        }
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
                name = stringResource(Res.string.a11y_volume, group.name),
            )
        }
        Labeled(stringResource(Res.string.mixer_pan)) {
            MiniSlider(
                value = EngineParams.pan01(group.pan), centered = true,
                name = stringResource(Res.string.a11y_pan, group.name),
                state = panSaid(group.pan),
                modifier = Modifier.width(STRIP_W - 8.dp).height(20.dp).mappable(MapTargets.param(0, "master", "g${n}pan")),
                onStart = { editor.beginSongGesture() },
                onChange = { v ->
                    NativeEngine.setParam(0, "master", "g${n}pan", v)
                    editor.updateSongGesture { s -> s.withGroupPan(g, EngineParams.panFrom01(v)) }
                },
                onEnd = { editor.endSongGesture() },
            )
        }
        // What's routed here, in the space a channel uses for its sends.
        val members = song.tracks.filter { it.mixer.output == g + 1 }.map { it.name }
        Text(
            if (members.isEmpty()) stringResource(Res.string.mixer_nothing_routed) else members.joinToString("\n"),
            color = c.textDim, fontSize = 10.sp, lineHeight = 13.sp,
            maxLines = 4, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        )
        if (fullH != Dp.Unspecified) Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            GridChip(stringResource(Res.string.mixer_mute_short), group.mute, c.red, Modifier.weight(1f), said = stringResource(Res.string.a11y_mute, group.name)) { editor.editSong { s -> s.withGroupMute(g, !group.mute) } }
            GridChip(stringResource(Res.string.mixer_solo_short), group.solo, c.accent, Modifier.weight(1f), said = stringResource(Res.string.a11y_solo, group.name)) { editor.editSong { s -> s.withGroupSolo(g, !group.solo) } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (slot in 0 until GROUP_INSERT_SLOTS) {
                val fx = group.insertAt(slot)
                GridChip(if (fx.isEmpty) stringResource(Res.string.mixer_fx_slot, slot + 1) else shortFx(fx.type), !fx.isEmpty && !fx.bypass, c.accent,
                         Modifier.weight(1f).onLongPress { editingInsert = slot },
                         said = stringResource(Res.string.a11y_insert, group.name, slot + 1, fx.type.ifEmpty { stringResource(Res.string.a11y_slot_empty) }),
                         actions = listOf(action(stringResource(Res.string.a11y_edit)) { editingInsert = slot })) {
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

/** The button for a new group: a strip no wider than its rotated label. */
@Composable
private fun AddGroupStrip(editor: SongEditor, room: Dp, fullH: Dp) {
    val c = Acid.colors
    val resources = AppStrings
    Box(
        Modifier.width(26.dp).then(
            if (fullH != Dp.Unspecified) Modifier.height(fullH)
            else if (room == Dp.Infinity) Modifier.height(120.dp) else Modifier.heightIn(max = room),
        )
            .clip(RoundedCornerShape(6.dp)).background(c.cardAlt)
            .clickable { editor.editSong { s -> s.addGroup(resources.getString(Res.string.mixer_group_default, s.master.groups.size + 1)) } },
        contentAlignment = Alignment.Center,
    ) { SideText(stringResource(Res.string.mixer_add_group), c.textMid, 11.sp) }
}

@Composable
private fun ChannelStrip(
    track: Track, index: Int, peak: () -> Float, editor: SongEditor, colour: Color,
    /** Channel controls that some clip of this track has a lane for. */
    automated: List<String> = emptyList(),
    /** The fader height the panel worked out for this strip. */
    faderH: Dp = FADER_H,
    /**
     * The most height the strip can use, or Infinity when it's not constrained.
     */
    room: Dp = Dp.Infinity,
    /** Even the shortest usable strip won't fit, so this one scrolls. */
    tight: Boolean = false,
    /** The send sliders' labels, the effects on the sends. */
    sendNames: List<String> = List(2) { stringResource(Res.string.mixer_send, it + 1) },
    /** The mixer's groups, which this track can be routed into. */
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
     * The strip as it was when the mixer opened, for a long press to reset to,
     * like the panels' knobs.
     *
     * Taken at composition, unlike a machine panel which waits for its first
     * poll of the engine. A strip reads the document, which is already correct.
     * The mixer leaves composition when closed, so reopening takes a fresh
     * copy.
     */
    val opened = remember(index) { m }

    /** Put one value back, in the document and in the engine. */
    fun back(name: String, v01: Float, update: (Mixer) -> Mixer) {
        live(name, v01)
        tap(update)
    }

    if (askClear) {
        PlainDialog(
            title = stringResource(Res.string.mixer_clear_title, track.name),
            onDismiss = { askClear = false },
            confirmLabel = stringResource(Res.string.mixer_clear),
            onConfirm = {
                askClear = false
                // Every clip on this track, since a lane in another scene will
                // kick in when that scene plays.
                editor.edit(index) { t ->
                    t.copy(clips = t.clips.mapValues { (_, clip) ->
                        val kept = clip.automation.filterKeys { laneUnit(it) != "channel" }
                        if (kept.size == clip.automation.size) clip else clip.copy(automation = kept)
                    })
                }
            },
        ) {
            Text(
                pluralStringResource(
                    Res.plurals.mixer_clear_note, automated.size,
                    automated.joinToString(stringResource(Res.string.list_separator)),
                ),
                color = c.textDim, fontSize = 11.sp, lineHeight = 14.sp,
            )
        }
    }

    Column(
        Modifier.width(STRIP_W).then(if (room == Dp.Infinity) Modifier else Modifier.heightIn(max = room))
            .clip(RoundedCornerShape(6.dp)).background(c.cardAlt)
            .then(if (tight) Modifier.verticalScrollWithBar(rememberScrollState()) else Modifier)
            .padding(4.dp).together(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Mark automated channels next to the name, otherwise a fader that's
        // being overwritten every pass just looks broken. It's on the name line
        // so it costs no height and the faders still line up. Tapping it opens
        // a window listing the lanes.
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
            LiveMeter(peak, Modifier.width(8.dp).fillMaxHeight())
            VerticalFader(
                value = EngineParams.volume01(m.volume),
                modifier = Modifier.width(36.dp).fillMaxHeight().mappable(map("gain")),
                accent = colour,
                onStart = { editor.beginGesture(index) },
                onChange = { v -> gesture("gain", v) { it.copy(volume = EngineParams.volumeFrom01(v)) } },
                onEnd = { editor.endGesture() },
                onReset = { back("gain", EngineParams.volume01(opened.volume)) { it.copy(volume = opened.volume) } },
                name = stringResource(Res.string.a11y_volume, track.name),
            )
        }
        Labeled(stringResource(Res.string.mixer_pan)) {
            MiniSlider(
                value = EngineParams.pan01(m.pan), centered = true,
                modifier = Modifier.width(STRIP_W - 8.dp).height(20.dp).mappable(map("pan")),
                onStart = { editor.beginGesture(index) },
                onChange = { v -> gesture("pan", v) { it.copy(pan = EngineParams.panFrom01(v)) } },
                onEnd = { editor.endGesture() },
                onReset = { back("pan", EngineParams.pan01(opened.pan)) { it.copy(pan = opened.pan) } },
                name = stringResource(Res.string.a11y_pan, track.name),
                state = panSaid(m.pan),
            )
        }
        Labeled(sendNames.getOrElse(0) { stringResource(Res.string.mixer_send, 1) }) {
            MiniSlider(
                value = m.sendReverb,
                modifier = Modifier.width(STRIP_W - 8.dp).height(20.dp).mappable(map("sendreverb")),
                onStart = { editor.beginGesture(index) },
                onChange = { v -> gesture("sendreverb", v) { it.copy(sendReverb = v) } },
                onEnd = { editor.endGesture() },
                onReset = { back("sendreverb", opened.sendReverb) { it.copy(sendReverb = opened.sendReverb) } },
                name = stringResource(Res.string.a11y_send, track.name, sendNames.getOrElse(0) { stringResource(Res.string.mixer_send, 1) }),
            )
        }
        Labeled(sendNames.getOrElse(1) { stringResource(Res.string.mixer_send, 2) }) {
            MiniSlider(
                value = m.sendDelay,
                modifier = Modifier.width(STRIP_W - 8.dp).height(20.dp).mappable(map("senddelay")),
                onStart = { editor.beginGesture(index) },
                onChange = { v -> gesture("senddelay", v) { it.copy(sendDelay = v) } },
                onEnd = { editor.endGesture() },
                onReset = { back("senddelay", opened.sendDelay) { it.copy(sendDelay = opened.sendDelay) } },
                name = stringResource(Res.string.a11y_send, track.name, sendNames.getOrElse(1) { stringResource(Res.string.mixer_send, 2) }),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ToggleChip(stringResource(Res.string.mixer_mute_short), m.mute, c.red, Modifier.mappable(map("mute")), said = stringResource(Res.string.a11y_mute, track.name)) { tap { it.copy(mute = !it.mute) } }
            ToggleChip(stringResource(Res.string.mixer_solo_short), m.solo, c.accent, Modifier.mappable(map("solo")), said = stringResource(Res.string.a11y_solo, track.name)) { tap { it.copy(solo = !it.solo) } }
        }
        // Where this track's notes go: off, both, or out only. At out only the
        // machine isn't run at all, which saves CPU when driving external gear.
        // The MIDI channel sits next to it.
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val label = stringResource(when (m.midiMode) { 1 -> Res.string.mixer_midi_both; 2 -> Res.string.mixer_midi_out; else -> Res.string.mixer_midi_off })
            ToggleChip(label, m.midiMode != 0, c.teal, said = stringResource(Res.string.a11y_midi_out, track.name), state = label) {
                tap { it.copy(midiMode = (it.midiMode + 1) % 3) }
            }
            if (m.midiMode != 0) {
                ToggleChip("${m.midiChannel + 1}", true, c.accentDim, said = stringResource(Res.string.a11y_midi_channel, track.name), state = "${m.midiChannel + 1}") {
                    tap { it.copy(midiChannel = (it.midiChannel + 1) % 16) }
                }
            }
        }
        // Where the sound goes: the master, or one of the mixer's groups. A tap
        // steps through them.
        if (groups.isNotEmpty()) {
            val to = groups.getOrNull(m.output - 1)
            ToggleChip(stringResource(Res.string.mixer_to, to ?: stringResource(Res.string.mixer_master)), to != null, c.teal, Modifier.width(STRIP_W - 8.dp).mappable(map("output")), padding = 3.dp,
                said = stringResource(Res.string.a11y_output, track.name), state = to ?: stringResource(Res.string.mixer_master)) {
                tap { it.copy(output = if (it.output in 0 until groups.size) it.output + 1 else 0) }
            }
        }
    }
}

@Composable
private fun LoudnessFigure(label: String, value: String, colour: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Acid.colors.textMid, fontSize = 8.sp, lineHeight = 9.sp, maxLines = 1, softWrap = false)
        Text(value, color = colour, fontSize = 10.sp, lineHeight = 11.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun MasterStrip(
    song: Song, editor: SongEditor, peak: () -> Float, clickOn: Boolean, onClick: (Boolean) -> Unit,
    faderH: Dp = FADER_H,
    room: Dp = Dp.Infinity,
    tight: Boolean = false,
    /** A channel strip's height to match, unspecified when there's none. */
    fullH: Dp = Dp.Unspecified,
) {
    val c = Acid.colors
    val master = song.master
    fun live(name: String, v01: Float) = NativeEngine.setParam(0, "master", name, v01)
    fun gesture(name: String, v01: Float, update: (Song) -> Song) {
        live(name, v01)
        editor.updateSongGesture(update)
    }
    // The master is rack 0 by convention. It has no rack of its own, and a
    // mapping to it never follows the routing.
    fun map(name: String) = MapTargets.param(0, "master", name)

    // Which send's editor is open, if any.
    var editing by remember { mutableStateOf<Int?>(null) }
    editing?.let { slot -> SendDialog(slot, editor) { editing = null } }
    var editingInsert by remember { mutableStateOf<Int?>(null) }
    editingInsert?.let { slot ->
        SongSlotDialog(stringResource(Res.string.mixer_master_insert, slot + 1), slot, ::masterInsertUnit, { s, i -> s.master.insertAt(i) },
                       Song::withMasterInsert, Song::withMasterInsertParam,
                       // In series with the mix like a track insert, so a dry
                       // blend makes sense.
                       hideMix = false, editor = editor) { editingInsert = null }
    }

    Column(
        Modifier.width(MASTER_W).then(
            if (fullH != Dp.Unspecified) Modifier.height(fullH)
            else if (room == Dp.Infinity) Modifier else Modifier.heightIn(max = room),
        )
            .clip(RoundedCornerShape(6.dp)).background(c.cardHi)
            .then(if (tight) Modifier.verticalScrollWithBar(rememberScrollState()) else Modifier)
            .padding(4.dp).together(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(Res.string.mixer_master), color = c.text, fontSize = 11.sp)
        // Loudness: integrated since play started, with short-term and true
        // peak under it. Polled while the mixer is open, which also keeps the
        // engine measuring. A tap resets the integrated figure.
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
            LiveMeter(peak, Modifier.width(8.dp).fillMaxHeight())
            VerticalFader(
                value = EngineParams.volume01(master.volume),
                modifier = Modifier.width(36.dp).fillMaxHeight().mappable(map("volume")),
                accent = Acid.colors.knobPointer,
                onStart = { editor.beginSongGesture() },
                onChange = { v -> gesture("volume", v) { s -> s.copy(master = s.master.copy(volume = EngineParams.volumeFrom01(v))) } },
                onEnd = { editor.endSongGesture() },
                name = stringResource(Res.string.a11y_volume, stringResource(Res.string.a11y_master)),
            )
        }
        Labeled(stringResource(Res.string.mixer_limit_drive)) {
            MiniSlider(master.limiter.drive, Modifier.width(STRIP_W - 8.dp).height(20.dp).mappable(map("limiterdrive")),
                onStart = { editor.beginSongGesture() },
                onChange = { v -> gesture("limiterdrive", v) { s -> s.copy(master = s.master.copy(limiter = s.master.limiter.copy(drive = v))) } },
                onEnd = { editor.endSongGesture() },
                name = stringResource(Res.string.a11y_limit_drive))
        }
        // Under the limiter rather than above the fader, so the master's fader
        // lines up with every channel's. It takes the height left between the
        // limiter and the buttons, so the buttons always fit.
        Column(
            Modifier.fillMaxWidth().then(if (fullH != Dp.Unspecified) Modifier.weight(1f) else Modifier)
                .clip(RoundedCornerShape(4.dp)).background(c.cardAlt)
                .clickable { NativeEngine.resetLoudness() }.padding(horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // The unit is small next to the number, and the two small readings
            // sit side by side under it, since "-14.8 LUFS" at one size is
            // wider than the strip.
            Row(verticalAlignment = Alignment.Bottom) {
                Text(fmt(lufs[2]), color = c.textHi, fontSize = 15.sp, lineHeight = 17.sp, maxLines = 1, softWrap = false,
                    modifier = Modifier.alignByBaseline())
                Text("LUFS", color = c.textMid, fontSize = 8.sp, maxLines = 1, softWrap = false,
                    modifier = Modifier.padding(start = 2.dp).alignByBaseline())
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                LoudnessFigure(stringResource(Res.string.mixer_lufs_short), fmt(lufs[1]), c.textMid)
                LoudnessFigure(stringResource(Res.string.mixer_true_peak), fmt(lufs[3]), if (lufs[3] > -1f) c.red else c.textMid)
            }
        }
        // Six buttons in three rows so the strip is no taller than a channel's:
        // the two sends, the two master inserts, then the limiter and the
        // click. Tap toggles, hold opens a send or insert to choose and set up
        // the effect.
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
                GridChip(if (send.isEmpty) stringResource(Res.string.mixer_send_short, slot + 1) else shortFx(send.type), !send.isEmpty && !send.bypass, c.teal,
                         Modifier.weight(1f).onLongPress { editing = slot }, height = 26.dp,
                         said = stringResource(Res.string.a11y_send_slot, slot + 1, send.type.ifEmpty { stringResource(Res.string.a11y_slot_empty) }),
                         actions = listOf(action(stringResource(Res.string.a11y_edit)) { editing = slot })) { sendTap(slot) }
            }
        }
        Row(grid, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (slot in 0 until MASTER_INSERT_SLOTS) {
                val fx = master.insertAt(slot)
                GridChip(if (fx.isEmpty) stringResource(Res.string.mixer_fx_slot, slot + 1) else shortFx(fx.type), !fx.isEmpty && !fx.bypass, c.accent,
                         Modifier.weight(1f).onLongPress { editingInsert = slot }, height = 26.dp,
                         said = stringResource(Res.string.a11y_insert, stringResource(Res.string.a11y_master), slot + 1, fx.type.ifEmpty { stringResource(Res.string.a11y_slot_empty) }),
                         actions = listOf(action(stringResource(Res.string.a11y_edit)) { editingInsert = slot })) { insertTap(slot) }
            }
        }
        Row(grid, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            GridChip(stringResource(Res.string.mixer_limiter), master.limiter.on, c.teal, Modifier.weight(1f).mappable(map("limiteron")), height = 26.dp, said = stringResource(Res.string.a11y_limiter)) {
                editor.editSong { s -> s.copy(master = s.master.copy(limiter = s.master.limiter.copy(on = !s.master.limiter.on))) }
            }
            GridChip("♩", clickOn, c.accent, Modifier.weight(1f), height = 26.dp, said = stringResource(Res.string.a11y_click)) { onClick(!clickOn) }
        }
    }
}

/** An effect's name shortened to fit half a strip. */
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
    "Rotary" -> "rot"
    "Grain" -> "grn"
    "Resonator" -> "reso"
    "Smash" -> "smsh"
    "Mouth" -> "mth"
    "Slicer" -> "slce"
    "Magneto" -> "mgnt"
    "Horn" -> "horn"
    "Spectral" -> "spec"
    "Formula" -> "frml"
    else -> type.lowercase().take(4)
}

/** A half-width chip for the master strip's grid. */
@Composable
private fun GridChip(
    label: String, on: Boolean, colour: Color, modifier: Modifier = Modifier, height: Dp = 28.dp,
    /** What TalkBack says when [label] is short, and what a hold does. */
    said: String? = null,
    actions: List<androidx.compose.ui.semantics.CustomAccessibilityAction> = emptyList(),
    onClick: () -> Unit,
) {
    val c = Acid.colors
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(4.dp))
            .background(if (on) colour.copy(alpha = 0.25f) else c.raised)
            .clickable(onClick = onClick)
            .then(if (said != null) Modifier.button(said, stringResource(if (on) Res.string.a11y_on else Res.string.a11y_off), actions) else Modifier),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (on) colour else c.textMid, fontSize = 12.sp, maxLines = 1, softWrap = false) }
}

@Composable
private fun Labeled(label: String, content: @Composable () -> Unit) {
    Column {
        // The control under it announces its own name.
        Text(label, color = Acid.colors.textDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.silent())
        content()
    }
}

// Material buttons have a 40 dp minimum height, and these stack four high next
// to a fader, so they're plain boxes.
@Composable
private fun ToggleChip(
    label: String,
    on: Boolean,
    colour: Color,
    modifier: Modifier = Modifier,
    padding: Dp = 8.dp,
    /** What TalkBack says, and the state, where [label] is a letter. */
    said: String? = null,
    state: String? = null,
    onClick: () -> Unit,
) {
    val c = Acid.colors
    Box(
        modifier
            .height(22.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (on) colour.copy(alpha = 0.25f) else c.raised)
            .clickable(onClick = onClick)
            .then(if (said != null) Modifier.button(said, state ?: stringResource(if (on) Res.string.a11y_on else Res.string.a11y_off)) else Modifier)
            .padding(horizontal = padding),
        contentAlignment = Alignment.Center,
    ) {
        // Never wrapped. A label that wraps at a space and is then cut to one
        // line only shows its first word.
        Text(label, color = if (on) colour else c.textMid, fontSize = 11.sp, maxLines = 1,
             softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

/** A track's colour: the one it was given, or its position's. */
fun trackColour(index: Int, chosen: Int? = null): Color = PALETTE[(chosen ?: index).mod(PALETTE.size)]
internal val TRACK_COLOURS: Int get() = PALETTE.size

// A track's stripe is the same colour in both themes, since it belongs to the
// track. Mid-saturation hues that work on white and on black.
private val PALETTE = listOf(
    Color(0xFF3FA98D), Color(0xFFE09A3C), Color(0xFFD4688A), Color(0xFF5B8FE0),
    Color(0xFF8CC04E), Color(0xFFE0714A), Color(0xFF9B7BD4), Color(0xFF3FAFC0),
)

private val STRIP_W = 76.dp
/**
 * The master strip is the same width as a channel strip, with everything
 * stacked the way a channel stacks its controls.
 */
private val MASTER_W = STRIP_W
/** The fader height when there's room, and the maximum anywhere. */
private val FADER_H = 110.dp

/** Below this a fader is too short to drag. */
private val FADER_MIN = 56.dp

/**
 * Height a channel strip uses for everything except the fader: the name, three
 * labelled sliders, the mute/solo row, the MIDI row, padding and gaps. None of
 * that can shrink, so the fader does. Counted from the strip below. If you add
 * a row, raise this.
 *
 * It's for a channel strip, which decides the fader height. The master has a
 * few more chips and scrolls if it must.
 */
private val STRIP_CHROME = 204.dp

/** The output row a strip grows when the song has a group to route to. */
private val OUTPUT_ROW = 26.dp

/**
 * The effect on a send bus and all its settings.
 *
 * The same window an insert slot opens. Sends are song-wide, so it's opened
 * from the master strip by holding the chip that names the send.
 *
 * mix isn't offered. The engine pins it fully wet on a send, since a dry path
 * would play the track twice.
 */
@Composable
private fun SendDialog(slot: Int, editor: SongEditor, onDismiss: () -> Unit) =
    SongSlotDialog(stringResource(Res.string.mixer_send, slot + 1), slot, ::sendUnit, { s, i -> s.master.sendAt(i) },
                   Song::withSend, Song::withSendParam,
                   // A send's dry path would play the track twice, so mix is
                   // pinned and hidden.
                   hideMix = true, editor = editor, onDismiss = onDismiss)

/**
 * The two input effects, as chips that open them.
 *
 * Shown on the machine that records so they're at hand when recording, even
 * though the slots belong to the song and not the track.
 */
@Composable
fun InputChainChips(editor: SongEditor) {
    val c = Acid.colors
    var editing by remember { mutableStateOf<Int?>(null) }
    editing?.let { slot ->
        SongSlotDialog(stringResource(Res.string.mixer_input, slot + 1), slot, ::inputUnit, { s, i -> s.inputAt(i) },
                       Song::withInputFx, Song::withInputFxParam,
                       // An input effect is in series with the signal, so a dry
                       // blend makes sense.
                       hideMix = false, editor = editor) { editing = null }
    }
    for (slot in 0 until INPUT_SLOTS) {
        val fx = editor.song.inputAt(slot)
        ToggleChip(
            if (fx.isEmpty) stringResource(Res.string.mixer_input_short, slot + 1) else fx.type.lowercase(),
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
 * Shared by the two sends and the two input effects. They're all song slots
 * addressed by a unit name and edited as one song gesture. Only where the
 * engine runs them differs.
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
    // The editor's song isn't state this window observes (it's in its own
    // Dialog window), so every edit bumps this and reading it redraws the
    // knobs.
    var edits by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val send = edits.let { at(editor.song, slot) }
    val types = remember { NativeEngine.effectTypes }
    var menu by remember { mutableStateOf(false) }
    val info = remember(send.type, hideMix) {
        if (send.isEmpty) emptyList()
        else NativeEngine.effectParamInfo(send.type).filter { !hideMix || it.name != "mix" }
    }
    // Cancel restores the type and every value as the window found them, in one
    // song edit, like the track's effect window.
    val opened = remember { at(editor.song, slot) }
    fun revert() {
        val now = at(editor.song, slot)
        if (now == opened) return
        editor.editSong { s ->
            var out = if (now.type != opened.type) withType(s, slot, opened.type) else s
            for ((n, v) in opened.params) out = withParam(out, slot, n, v)
            if (now.settings != opened.settings) out = out.withSlotSetting(-1, unitOf(slot), "formula", opened.settings["formula"])
            out
        }
        edits++
        if (now.type == opened.type) {
            for (p in info) NativeEngine.setParam(0, unitOf(slot), p.name, opened.params[p.name] ?: p.defaultNormalized, record = false)
        }
    }
    PlainDialog(
        title,
        onDismiss = { revert(); onDismiss() },
        dismissLabel = stringResource(Res.string.cancel),
        confirmLabel = stringResource(Res.string.ok),
        onConfirm = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { menu = true }) {
                    Text(stringResource(Res.string.mixer_menu, if (send.isEmpty) stringResource(Res.string.mixer_none) else send.type), color = c.accent, fontSize = 13.sp)
                }
                val menuScroll = rememberScrollState()
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    ScaledMenu(menuScroll) {
                        DropdownMenuItem(text = { Text(stringResource(Res.string.mixer_none), fontSize = 12.sp) }, onClick = {
                            menu = false
                            editor.editSong { s -> withType(s, slot, "") }
                            edits++
                        })
                        for (t in types) DropdownMenuItem(text = { Text(t, fontSize = 12.sp) }, onClick = {
                            menu = false
                            if (t != send.type) editor.editSong { s -> withType(s, slot, t) }
                            edits++
                        })
                    }
                }
            }
            if (send.type == "Formula") {
                val unit = unitOf(slot)
                EffectFormula(
                    send.settings["formula"].orEmpty(),
                    com.rm.acidulous.engine.EngineSync.effectFormulaErrors[com.rm.acidulous.engine.EngineSync.effectFormulaKey(-1, unit)].orEmpty(),
                ) { new ->
                    editor.editSong { s -> s.withSlotSetting(-1, unit, "formula", new.ifEmpty { null }) }
                    edits++
                }
            }
            if (!send.isEmpty) {
                // The knobs read the document, not the engine. Nothing else
                // controls this slot, and a parameter the song never set shows
                // the effect's default instead of 0.
                SongSlotFace(
                    send.type, info,
                    value = { n -> send.params[n] ?: info.firstOrNull { it.name == n }?.defaultNormalized ?: 0f },
                    start = { editor.beginSongGesture() },
                    change = { n, nv ->
                        NativeEngine.setParam(0, unitOf(slot), n, nv, record = false)
                        editor.updateSongGesture { s -> withParam(s, slot, n, nv) }
                        edits++
                    },
                    end = { editor.endSongGesture() },
                    set = { n, nv ->
                        NativeEngine.setParam(0, unitOf(slot), n, nv, record = false)
                        editor.editSong { s -> withParam(s, slot, n, nv) }
                        edits++
                    },
                )
            }
        }
    }
}

/** A pan position as TalkBack says it: centre, or how far left or right. */
@Composable
private fun panSaid(pan: Float): String {
    val amount = kotlin.math.round(kotlin.math.abs(pan) * 100f).toInt()
    return when {
        amount < 2 -> stringResource(Res.string.a11y_centre)
        pan < 0f -> stringResource(Res.string.a11y_left, amount)
        else -> stringResource(Res.string.a11y_right, amount)
    }
}

/** A meter that reads its level itself, so only it redraws as the level moves. */
@Composable
private fun LiveMeter(level: () -> Float, modifier: Modifier) {
    Meter(level(), modifier)
}
