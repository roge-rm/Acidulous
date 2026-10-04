package com.rm.acidulous.ui

import com.rm.acidulous.util.System

import com.rm.acidulous.util.format

import androidx.compose.ui.graphics.graphicsLayer

import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import com.rm.acidulous.model.BPM_MAX
import com.rm.acidulous.model.BPM_MIN
import com.rm.acidulous.model.Clip
import com.rm.acidulous.model.ClipClipboard
import com.rm.acidulous.model.hasContent
import com.rm.acidulous.model.PPQN
import com.rm.acidulous.model.PlayMode
import com.rm.acidulous.model.Scene
import com.rm.acidulous.model.SceneTempo
import com.rm.acidulous.model.Signature
import com.rm.acidulous.model.SWING_MAX
import com.rm.acidulous.model.SWING_STRAIGHT
import com.rm.acidulous.model.SWING_TRIPLET
import com.rm.acidulous.model.Scales
import com.rm.acidulous.model.Song
import com.rm.acidulous.model.SongKey
import com.rm.acidulous.res.*

/**
 * The scene's "4/4 × 1" chip, expanded: name, signature, repeat, tempo, fades.
 *
 * Built from the same cards and knobs as the other windows here.
 */
@Composable
fun SceneSettingsDialog(
    scene: Scene, songSignature: Signature, onDismiss: () -> Unit,
    /** How long the scene is, which is as far back as a ramp can reach. */
    bars: Int = 16,
    /** The starting tempo when the scene has none of its own. */
    songTempo: Float = 120f,
    onConfirm: (Scene) -> Unit,
) {
    var name by remember { mutableStateOf(scene.name) }
    var signature by remember { mutableStateOf(scene.signature) } // null = song default
    var repeat by remember { mutableStateOf(scene.repeat) }
    var ownTempo by remember { mutableStateOf(scene.tempo != null) }
    var bpm by remember { mutableStateOf(scene.tempo?.bpm ?: 120f) }
    var smooth by remember { mutableStateOf(scene.tempo?.smooth ?: false) }
    var fadeIn by remember { mutableStateOf(scene.fadeIn) }
    var fadeOut by remember { mutableStateOf(scene.fadeOut) }
    var ramp by remember { mutableStateOf(scene.ramp) }

    PlainDialog(
        title = stringResource(Res.string.scene_title),
        onDismiss = onDismiss,
        confirmLabel = stringResource(Res.string.ok),
        onConfirm = {
            onConfirm(
                scene.copy(
                    name = name.ifBlank { scene.name },
                    signature = signature,
                    repeat = repeat,
                    tempo = if (ownTempo) SceneTempo(bpm = bpm, smooth = smooth) else null,
                    fadeIn = fadeIn,
                    fadeOut = fadeOut,
                    ramp = ramp,
                ),
            )
        },
    ) {
        ListSection(stringResource(Res.string.scene_name)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                modifier = Modifier.typing() then Modifier.fillMaxWidth(),
            )
        }

        // Cards of knobs and switches below the name, laid out like the arp
        // window, as every editor window is. The nine signatures are a stepped
        // knob that shows its value.
        WindowCards {
            WindowCard(stringResource(Res.string.scene_time)) {
                val sigIndex = signature?.let { SIGNATURES.indexOf(it) + 1 } ?: 0
                val songSig = stringResource(Res.string.scene_signature_song, songSignature.beats, songSignature.unit)
                CountKnob(
                    stringResource(Res.string.scene_signature), sigIndex, 0..SIGNATURES.size,
                    if (sigIndex == 0) songSig
                    else SIGNATURES[sigIndex - 1].let { "${it.beats}/${it.unit}" },
                    choices = listOf(songSig) + SIGNATURES.map { "${it.beats}/${it.unit}" },
                ) { i -> signature = if (i == 0) null else SIGNATURES[i - 1] }
                CountKnob(stringResource(Res.string.scene_repeat), repeat, 1..32, "×$repeat", choices = (1..32).map { "×$it" }) { repeat = it }
                val offOn = stringArrayResource(Res.array.off_on).toList()
                SwitchGrid(stringResource(Res.string.scene_fade_in), offOn, if (fadeIn) 1 else 0) { fadeIn = it == 1 }
                SwitchGrid(stringResource(Res.string.scene_fade_out), offOn, if (fadeOut) 1 else 0) { fadeOut = it == 1 }
            }
            WindowCard(stringResource(Res.string.scene_tempo)) {
                SwitchGrid(stringResource(Res.string.scene_tempo_from), stringArrayResource(Res.array.scene_tempo_from_choices).toList(), if (ownTempo) 1 else 0) { ownTempo = it == 1 }
                if (ownTempo) {
                    // Whole bpm, only set when the knob moves, so a scene
                    // saved at 72.5 keeps it until you turn it.
                    CountKnob(stringResource(Res.string.scene_bpm), bpm.roundToInt(), 40..240, "%.0f".format(bpm), PanelAmber) { bpm = it.toFloat() }
                    SwitchGrid(stringResource(Res.string.scene_change), stringArrayResource(Res.array.scene_change_choices).toList(), if (smooth) 1 else 0) { smooth = it == 1 }
                }
                // A tempo change on the scene's last pass: slowing into the
                // next scene, or speeding up across it.
                val start = if (ownTempo) bpm else songTempo
                SwitchGrid(stringResource(Res.string.scene_ramp), stringArrayResource(Res.array.off_on).toList(), if (ramp != null) 1 else 0) {
                    ramp = if (it == 1) (ramp ?: com.rm.acidulous.model.TempoRamp((start * 0.75f).roundToInt().toFloat(), minOf(2, bars))) else null
                }
                ramp?.let { r ->
                    CountKnob(stringResource(Res.string.scene_ramp_to), r.toBpm.roundToInt(), 40..240, "%.0f".format(r.toBpm), PanelAmber) { ramp = r.copy(toBpm = it.toFloat()) }
                    val most = bars.coerceAtLeast(1)
                    CountKnob(
                        stringResource(Res.string.scene_ramp_over), r.bars.coerceIn(1, most), 1..most,
                        r.bars.coerceAtMost(most).let { pluralStringResource(Res.plurals.bars, it, it) },
                        choices = (1..most).map { pluralStringResource(Res.plurals.bars, it, it) },
                    ) { ramp = r.copy(bars = it) }
                }
            }
        }
    }
}

/** The clip's "1 Bar" chip, expanded: bars, play mode, mute, grid. */
@Composable
fun ClipSettingsDialog(
    clip: Clip,
    onDismiss: () -> Unit,
    /** The song's tempo here, to tell whether a freeze can still be used. */
    tempo: Float = 0f,
    /** Renders this clip to audio, or throws the render away. Both dismiss. */
    onFreeze: () -> Unit = {},
    onThaw: () -> Unit = {},
    /** Empties it of notes and automation, keeping its settings. Dismisses. */
    onClear: () -> Unit = {},
    /** Puts this clip on the clipboard. [onCut] copies and then clears. Both dismiss. */
    onCopy: () -> Unit = {},
    onCut: () -> Unit = {},
    /** Replaces this clip with the clipboard. Dismisses. */
    onPaste: () -> Unit = {},
    onConfirm: (Clip) -> Unit,
) {
    var bars by remember { mutableStateOf(clip.bars) }
    var mode by remember { mutableStateOf(clip.playMode) }
    var mute by remember { mutableStateOf(clip.mute) }
    var grid by remember { mutableStateOf(clip.grid) }
    var seed by remember { mutableStateOf(clip.seed) }
    var free by remember { mutableStateOf(clip.freeRoll) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmPaste by remember { mutableStateOf(false) }
    // Only shown when the clip uses chance. Most clips never do.
    val rolls = clip.notes.any { it.chance < 100 }

    PlainDialog(
        title = stringResource(Res.string.clip_title),
        onDismiss = onDismiss,
        confirmLabel = stringResource(Res.string.ok),
        onConfirm = {
            onConfirm(clip.copy(bars = bars, playMode = mode, mute = mute, grid = grid, seed = seed, freeRoll = free))
        },
    ) {
        // The actions come first, since they're what you usually open this
        // window for, and the settings below are set once and left. Clear is
        // with them and asks first, as does pasting over a clip that isn't
        // empty. Cut doesn't ask, because what it takes is on the clipboard.
        //
        // Cards of knobs and switches laid out like the arp window, all in
        // view at once.
        val held = ClipClipboard.clip
        val what = listOfNotNull(
            if (clip.notes.isNotEmpty()) pluralStringResource(Res.plurals.clip_notes, clip.notes.size, clip.notes.size) else null,
            if (clip.automation.isNotEmpty()) pluralStringResource(Res.plurals.clip_lanes, clip.automation.size, clip.automation.size) else null,
            if (clip.frozen != null) stringResource(Res.string.clip_frozen) else null,
        ).joinToString(stringResource(Res.string.list_separator)).ifEmpty { stringResource(Res.string.clip_empty) }
        val frozen = clip.frozen
        val stale = frozen != null && tempo > 0f && kotlin.math.abs(frozen.bpm - tempo) >= 0.01f
        WindowCards {
            // The card's title says what's in the clip, which is what its
            // actions act on.
            WindowCard(stringResource(Res.string.clip_card, what)) {
                SwitchGrid(
                    if (held != null) stringResource(Res.string.clip_clipboard_has, ClipClipboard.from) else stringResource(Res.string.clip_clipboard),
                    stringArrayResource(Res.array.clip_actions).toList(), -1, columns = 2,
                    enabled = listOf(clip.hasContent() || clip.notes.isNotEmpty(), clip.hasContent(), held != null, clip.hasContent()),
                ) { i ->
                    when (i) {
                        0 -> onCopy()
                        1 -> onCut()
                        2 -> if (clip.hasContent()) confirmPaste = true else onPaste()
                        else -> confirmClear = true
                    }
                }
                // Freeze is an action, not a setting, so it runs and closes
                // straight away. Everything else waits for OK.
                if (frozen != null) {
                    SwitchGrid(stringResource(if (stale) Res.string.clip_stale else Res.string.clip_audio), listOf(stringResource(Res.string.clip_thaw)), -1) { onThaw() }
                } else if (clip.notes.isNotEmpty()) {
                    SwitchGrid(stringResource(Res.string.clip_audio), listOf(stringResource(Res.string.clip_freeze)), -1) { onFreeze() }
                }
            }
            WindowCard(stringResource(Res.string.clip_length)) {
                CountKnob(stringResource(Res.string.clip_bars), bars, 1..16, choices = (1..16).map { pluralStringResource(Res.plurals.bars, it, it) }) { bars = it }
                SwitchGrid(stringResource(Res.string.clip_grid), GRIDS.map { it.first }, GRIDS.indexOfFirst { it.second == grid }, columns = 3) { grid = GRIDS[it].second }
            }
            WindowCard(stringResource(Res.string.clip_plays)) {
                SwitchGrid(stringResource(Res.string.clip_mode), stringArrayResource(Res.array.clip_mode_choices).toList(), if (mode == PlayMode.OneShot) 1 else 0) {
                    mode = if (it == 1) PlayMode.OneShot else PlayMode.Loop
                }
                SwitchGrid(stringResource(Res.string.clip_mute), stringArrayResource(Res.array.off_on).toList(), if (mute) 1 else 0) { mute = it == 1 }
                // Only shown when the clip uses chance.
                if (rolls) {
                    SwitchGrid(stringResource(Res.string.clip_dice), stringArrayResource(Res.array.clip_dice_choices).toList(), if (free) 1 else 0) { free = it == 1 }
                    if (!free) CountKnob(stringResource(Res.string.clip_seed), seed, 0..63) { seed = it }
                }
            }
        }
        // Explains that a freeze at another tempo isn't what's playing.
        if (frozen != null && stale) {
            Text(
                stringResource(Res.string.clip_stale_note, frozen.bpm, tempo),
                color = com.rm.acidulous.ui.theme.Acid.colors.textDim, fontSize = 11.sp, lineHeight = 14.sp,
            )
        }
    }

    if (confirmPaste) {
        PlainDialog(
            title = stringResource(Res.string.clip_paste_title),
            onDismiss = { confirmPaste = false },
            confirmLabel = stringResource(Res.string.clip_paste),
            onConfirm = { confirmPaste = false; onPaste() },
        ) {
            Text(
                stringResource(Res.string.clip_paste_note, ClipClipboard.from),
                color = com.rm.acidulous.ui.theme.Acid.colors.textDim, fontSize = 11.sp, lineHeight = 14.sp,
            )
        }
    }

    if (confirmClear) {
        PlainDialog(
            title = stringResource(Res.string.clip_clear_title),
            onDismiss = { confirmClear = false },
            confirmLabel = stringResource(Res.string.clip_clear),
            onConfirm = { confirmClear = false; onClear() },
        ) {
            Text(
                stringResource(Res.string.clip_clear_note),
                color = com.rm.acidulous.ui.theme.Acid.colors.textDim, fontSize = 11.sp, lineHeight = 14.sp,
            )
        }
    }
}

@Composable
internal fun WindowCard(title: String, content: @Composable () -> Unit) =
    Group(title, perLine = 4, centred = true, background = com.rm.acidulous.ui.theme.Acid.colors.cardAlt, content = content)

/** The height every machine row shares: a name and its line. */
private val MACHINE_ROW_H = 68.dp

/**
 * A group's name with the "ish" in italics, since in "realish" it's a
 * qualifier and should look like one.
 */
private fun chipLabel(label: String): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        val cut = if (label.endsWith("ish") && label.length > 3) label.length - 3 else label.length
        append(label.substring(0, cut))
        if (cut < label.length) {
            withStyle(androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                append(label.substring(cut))
            }
        }
    }

/**
 * The machine picker: four groups behind chips, each machine with a line
 * saying what it is.
 */
@Composable
fun MachinePickerDialog(current: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val groups = com.rm.acidulous.model.MachineUi.machineGroups
    val known = remember { com.rm.acidulous.engine.NativeEngine.machineTypes.toSet() }
    var tab by rememberSaveable {
        mutableStateOf(groups.indexOfFirst { current in it.machines }.coerceAtLeast(0))
    }
    val c = com.rm.acidulous.ui.theme.Acid.colors
    // Anything the engine has that no group lists still needs to be
    // reachable, so it goes in the last group.
    val listed = groups.flatMap { it.machines }.toSet()
    val contents = groups.mapIndexed { i, g ->
        g.machines.filter { it in known } +
            (if (i == groups.lastIndex) known.filter { it !in listed } else emptyList())
    }
    TabbedDialog(
        title = stringResource(Res.string.picker_machine),
        selected = tab,
        dismissLabel = stringResource(Res.string.cancel),
        onDismiss = onDismiss,
        chips = { SectionChipsStyled(groups.map { chipLabel(stringResource(it.label)) }, tab) { tab = it } },
        pageNames = groups.map { stringResource(it.label) },
        onSelectPage = { tab = it },
        pages = contents.map { types ->
            {
                run {
                    for (type in types) {
                        val on = type == current
                        Column(
                            Modifier.fillMaxWidth().height(MACHINE_ROW_H)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (on) c.accentDim else c.control)
                                .clickable { onPick(type) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(type, color = if (on) c.accent else c.text, fontSize = 14.sp)
                            Text(
                                com.rm.acidulous.model.MachineUi.describe(type)?.let { stringResource(it) }.orEmpty(),
                                color = c.textDim, fontSize = 11.sp, maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
fun PickerDialog(title: String, options: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    PlainDialog(title = title, onDismiss = onDismiss, spacing = 6.dp) {
        if (options.isEmpty()) {
            Text(stringResource(Res.string.picker_empty), color = com.rm.acidulous.ui.theme.Acid.colors.textDim, fontSize = 12.sp)
        }
        for (o in options) DialogRow(mark = "·", name = o) { onPick(o) }
    }
}

@Composable
fun TextInputDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    PlainDialog(
        title = title,
        onDismiss = onDismiss,
        confirmLabel = stringResource(Res.string.ok),
        confirmEnabled = value.isNotBlank(),
        onConfirm = { if (value.isNotBlank()) onConfirm(value.trim()) },
    ) {
        OutlinedTextField(
            value = value, onValueChange = { value = it }, singleLine = true,
            modifier = Modifier.typing() then Modifier.fillMaxWidth(),
        )
    }
}

/**
 * A number set by dragging: the name, its value on the right, a line saying
 * what it is, and the slider underneath.
 *
 * For anything continuous. Chips suit a few named choices, but a range like
 * thirty-two repeat counts would wrap onto several rows. A slider is one row
 * whatever the range, which keeps settings pages short.
 *
 * Put it in a Column, not a `Section`. A Section's content is a FlowRow,
 * which gives a slider constraints it can't handle and draws it broken.
 */
@Composable
internal fun SliderSection(
    title: String,
    value: String,
    note: String,
    position: Float,
    range: ClosedFloatingPointRange<Float>,
    /** Stops to draw. 0 for a plain slider; see the note in the body. */
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    val c = com.rm.acidulous.ui.theme.Acid.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = c.teal, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text(value, color = c.accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        // Tight line spacing, since these lines make up most of a settings
        // page.
        if (note.isNotEmpty()) {
            Text(note, color = c.textDim, fontSize = 11.sp, lineHeight = 14.sp)
        }
        // Tick marks only when there are few enough to count. Eight stops
        // help, two hundred are just a dotted line.
        Slider(
            value = position,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Tempo, and everything that goes with it.
 *
 * The tempo, the click and the count-in are all about how fast, what you
 * hear it against and how long before it starts, so they're together here
 * rather than in settings, which is for things you set once.
 *
 * Split into tabs like the settings window, opening on tempo since that's
 * what the button says.
 *
 * The tempo is part of the song, so it waits for OK. The click settings
 * belong to the device and apply as you touch them, so Cancel doesn't undo
 * them.
 */
@Composable
fun TempoDialog(
    song: Song, onDismiss: () -> Unit,
    /** The tunings to choose from: built in and imported. */
    tunings: List<com.rm.acidulous.model.Tuning> = com.rm.acidulous.model.Tunings.builtIn,
    onConfirm: (Song) -> Unit,
) {
    var bpm by remember { mutableStateOf(song.tempo) }
    var signature by remember { mutableStateOf(song.signature) }
    var swing by remember { mutableStateOf(song.swing) }
    var swingUnit by remember { mutableStateOf(song.swingUnit) }
    var key by remember { mutableStateOf(song.key) }
    var tuning by remember { mutableStateOf(song.tuning) }
    var tab by rememberSaveable { mutableStateOf(0) }
    // On a square phone the key gets its own page, since tempo, bar and key
    // don't quite fit. See [compactWindow].
    val keyPage = compactWindow()
    // Link goes last, and only when it's available; see AppHost.hasLink.
    val hasLink = com.rm.acidulous.AppHost.current.hasLink
    val tabNames = stringArrayResource(Res.array.tempo_tabs).toList().let {
        if (keyPage) listOf(it[0], stringResource(Res.string.tempo_tab_key)) + it.drop(1) else it
    }.let { if (hasLink) it else it.dropLast(1) }
    val keyCard: @Composable () -> Unit = {
        WindowCards { KeySection(key, { key = it }, tuning, tunings, { tuning = it }) }
    }
    val linkPage: (@Composable () -> Unit)? = if (hasLink) { { LinkPage() } } else null
    TabbedDialog(
        title = stringResource(Res.string.tempo_title),
        selected = tab,
        onDismiss = onDismiss,
        dismissLabel = stringResource(Res.string.cancel),
        confirmLabel = stringResource(Res.string.ok),
        onConfirm = {
            onConfirm(
                song.copy(
                    tempo = bpm, signature = signature,
                    swing = swing, swingUnit = swingUnit, key = key,
                    tuning = tuning?.takeIf { !it.isEqual },
                ),
            )
        },
        spacing = 6.dp,
        chips = { SectionChips(tabNames, tab.coerceAtMost(tabNames.lastIndex)) { tab = it } },
        pageNames = tabNames,
        onSelectPage = { tab = it },
        pages = listOfNotNull(
            {
                TempoPage(
                    bpm, signature, swing, swingUnit, key,
                    onBpm = { bpm = it }, onSignature = { signature = it },
                    onSwing = { swing = it }, onSwingUnit = { swingUnit = it }, onKey = { key = it },
                    tuning = tuning, tunings = tunings, onTuning = { tuning = it },
                    withKey = !keyPage,
                )
            },
            keyCard.takeIf { keyPage },
            { ClickPage() },
            linkPage,
        ),
    )
}

/**
 * Ableton Link: everyone on this Wi-Fi at one tempo, with one bar line.
 *
 * It's here with the tempo rather than with MIDI clock in the MIDI window,
 * because both decide who sets the tempo and only one can. Turning this on
 * turns clock following off, in the engine and on screen.
 */
@Composable
private fun LinkPage() {
    val hub = com.rm.acidulous.engine.LinkHub
    WindowCards {
        WindowCard(stringResource(Res.string.link_card)) {
            SwitchGrid(stringResource(Res.string.link_tempo_sync), stringArrayResource(Res.array.off_on).toList(), if (hub.enabled) 1 else 0) { i ->
                val on = i == 1
                UiPrefs.chooseLink(on)
                hub.chooseEnabled(on)
                // Only one tempo source at a time. The engine enforces it and
                // the screen should match.
                if (on && com.rm.acidulous.midi.MidiHub.follow != com.rm.acidulous.midi.MidiHub.Follow.Off) {
                    UiPrefs.chooseFollow(com.rm.acidulous.midi.MidiHub.Follow.Off)
                }
            }
            SwitchGrid(stringResource(Res.string.link_start_stop), stringArrayResource(Res.array.link_start_stop_choices).toList(), if (hub.startStop) 0 else 1) { UiPrefs.chooseLinkStartStop(it == 0) }
        }
    }
    // What the switches can't show: what following a session costs, and
    // whether there is one.
    if (hub.enabled) {
        ListSection(
            stringResource(Res.string.link_session),
            stringResource(if (hub.multicast) Res.string.link_session_note else Res.string.link_session_note_no_multicast),
        ) {
            Readout(
                stringResource(
                    Res.string.link_readout,
                    pluralStringResource(Res.plurals.link_peers, hub.peers, hub.peers),
                    if (hub.sessionTempo > 0f) stringResource(Res.string.link_session_tempo, hub.sessionTempo) else stringResource(Res.string.link_no_tempo),
                    hub.phaseMs,
                    stringResource(if (hub.multicast) Res.string.link_multicast_held else Res.string.link_multicast_not_held),
                ),
                good = hub.multicast && hub.peers > 0,
            )
        }
    }
}

@Composable
private fun TempoPage(
    bpm: Float, signature: Signature, swing: Float, swingUnit: Int, key: SongKey?,
    onBpm: (Float) -> Unit, onSignature: (Signature) -> Unit,
    onSwing: (Float) -> Unit, onSwingUnit: (Int) -> Unit, onKey: (SongKey?) -> Unit,
    tuning: com.rm.acidulous.model.Tuning? = null,
    tunings: List<com.rm.acidulous.model.Tuning> = emptyList(),
    onTuning: (com.rm.acidulous.model.Tuning?) -> Unit = {},
    /** Whether the key is on this page or on its own page. */
    withKey: Boolean = true,
) {
    // Cards laid out like the arp window, like every other window. The tempo
    // itself stays a field with a step button either side (see BpmRow),
    // since a knob over two hundred values can't land on one exactly.
    WindowCards {
        WindowCard(stringResource(Res.string.tempo_tempo)) {
            BpmRow(bpm, onBpm)
            TapTempo(onBpm)
        }
        WindowCard(stringResource(Res.string.tempo_bar)) {
            val sigIndex = SIGNATURES.indexOf(signature).coerceAtLeast(0)
            CountKnob(
                stringResource(Res.string.tempo_signature), sigIndex, 0 until SIGNATURES.size, "${signature.beats}/${signature.unit}",
                choices = SIGNATURES.map { "${it.beats}/${it.unit}" },
            ) { onSignature(SIGNATURES[it]) }
            CountKnob(
                stringResource(Res.string.tempo_swing), swing.roundToInt(), SWING_STRAIGHT.toInt()..SWING_MAX.toInt(),
                if (swing <= SWING_STRAIGHT + 0.05f) stringResource(Res.string.tempo_straight) else "%.0f%%".format(swing), PanelAmber,
            ) { onSwing(it.toFloat()) }
            SwitchGrid(stringResource(Res.string.tempo_swing_on), listOf("1/16", "1/8"), unit(swingUnit)) { onSwingUnit(it) }
            // The two feels worth naming. Neither is lit in between, like a
            // knob at 58%.
            SwitchGrid(
                stringResource(Res.string.tempo_feel), stringArrayResource(Res.array.tempo_feel_choices).toList(),
                when {
                    swing <= SWING_STRAIGHT + 0.05f -> 0
                    kotlin.math.abs(swing - SWING_TRIPLET) < 0.5f -> 1
                    else -> -1
                },
            ) { onSwing(if (it == 0) SWING_STRAIGHT else SWING_TRIPLET) }
        }
        if (withKey) KeySection(key, onKey, tuning, tunings, onTuning)
    }
}

private fun unit(u: Int) = if (u == 1) 1 else 0

/**
 * A window's cards: stacked upright, each wrapping its controls, and side by
 * side when the phone is turned, each a row, like a machine's panel. A turned
 * window is wide but short, so a column of cards would need scrolling.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun WindowCards(content: @Composable () -> Unit) {
    // On a square phone the cards are packed. Each card is as wide as its
    // controls, cards that fit side by side share a line, and a busy one
    // still takes a line and wraps its controls like it does upright.
    if (LocalDialogCompact.current && !LocalDialogWide.current) {
        androidx.compose.runtime.CompositionLocalProvider(LocalPanelStacked provides true, LocalCardsPacked provides true) {
            PackedCards(4.dp, content)
        }
        return
    }
    // Wide, the cards also share each line, stretched to fill it, so two
    // small cards don't float in an empty window. The window is only as
    // wide as its widest line needs; see DialogFit.
    if (LocalDialogWide.current) {
        androidx.compose.runtime.CompositionLocalProvider(LocalPanelStacked provides false) {
            PackedCards(6.dp, content)
        }
        return
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalPanelStacked provides true) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

/**
 * Cards in lines, as many per line as fit at their own widths. Each line is
 * then stretched to fill the width and made as tall as its tallest card, so
 * two small cards read as equal halves and a lone one fills the line.
 *
 * Measured rather than asked. A card is a flow row and sometimes a
 * BoxWithConstraints, and their intrinsic heights come back too short, which
 * clips the bottom rows. So the content is composed three times: once loose
 * for the widths, once at those widths for the heights, and once to show,
 * as [TallestOf] does for pages. Only the last is placed, so only its state
 * is what a finger changes.
 */
@Composable
private fun PackedCards(gap: Dp, content: @Composable () -> Unit) {
    val fit = LocalDialogFit.current
    val me = androidx.compose.runtime.remember { Any() }
    if (fit != null) androidx.compose.runtime.DisposableEffect(fit) { onDispose { fit.forget(me) } }
    androidx.compose.ui.layout.SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val width = constraints.maxWidth
        val space = gap.roundToPx()
        val loose = androidx.compose.ui.unit.Constraints(maxWidth = maxOf(width, fit?.probe ?: 0))
        val parts = subcompose("widths", content)
        // The narrowest each card can be without cutting anything off (a
        // switch, a knob, a word). Only asked when a page is being tried next
        // to another. A card Compose can't answer for (one built on a
        // SubcomposeLayout) is assumed to fit.
        val least = if ((fit?.probe ?: 0) > 0) parts.map { m ->
            runCatching { m.minIntrinsicWidth(androidx.compose.ui.unit.Constraints.Infinity) }.getOrDefault(0)
        } else null
        val said = parts.map { it.measure(loose).width }
        val natural = said.map { minOf(it, width) }
        // Greedy lines of indices.
        val lines = mutableListOf<MutableList<Int>>()
        var used = 0
        natural.forEachIndexed { i, w ->
            val line = lines.lastOrNull()
            if (line == null || used + space + w > width) {
                lines += mutableListOf(i); used = w
            } else {
                line += i; used += space + w
            }
        }
        // Each line's cards share out the leftover width in proportion. The
        // widest unstretched line is reported to the window below.
        val widths = IntArray(natural.size)
        for (line in lines) {
            val sum = line.sumOf { natural[it] }.coerceAtLeast(1)
            val spare = (width - line.sumOf { natural[it] } - space * (line.size - 1)).coerceAtLeast(0)
            var given = 0
            line.forEachIndexed { k, i ->
                val extra = if (k == line.lastIndex) spare - given else spare * natural[i] / sum
                given += extra
                widths[i] = natural[i] + extra
            }
        }
        val tall = subcompose("heights", content).mapIndexed { i, m ->
            m.measure(androidx.compose.ui.unit.Constraints.fixedWidth(widths[i])).height
        }
        fit?.tell(
            me,
            lines.maxOfOrNull { line -> line.sumOf { natural[it] } + space * (line.size - 1) } ?: 0,
            least?.indices?.any { i -> least[i] > widths[i] } ?: false,
        )
        val lineH = IntArray(natural.size)
        for (line in lines) {
            val h = line.maxOf { tall[it] }
            line.forEach { lineH[it] = h }
        }
        val placeables = subcompose("shown", content).mapIndexed { i, m ->
            m.measure(androidx.compose.ui.unit.Constraints.fixed(widths[i], lineH[i]))
        }
        val total = lines.sumOf { lineH[it.first()] } + space * (lines.size - 1).coerceAtLeast(0)
        layout(width, total) {
            var y = 0
            for (line in lines) {
                var x = 0
                for (i in line) { placeables[i].place(x, y); x += widths[i] + space }
                y += lineH[line.first()] + space
            }
        }
    }
}

/**
 * A row of text or readings inside a card: the card's full width when cards
 * are stacked, and its own width up to a limit when they're side by side,
 * where filling the row could leave text one letter wide.
 */
@Composable
internal fun Modifier.cardLine(): Modifier =
    if (LocalDialogWide.current) this.widthIn(max = CardLineW) else this.fillMaxWidth()

private val CardLineW = 480.dp

/** A card line that's always that width in a wide window, for text that changes while you read it. */
internal fun Modifier.cardLineFull(): Modifier = this.widthIn(max = CardLineW).fillMaxWidth()

/** Whether this window is laid out sideways; see [DialogShell] and [WindowCards]. */
internal val LocalDialogWide = androidx.compose.runtime.compositionLocalOf { false }

/**
 * How wide a window's cards want to be, reported by the cards to the window.
 *
 * Compose won't ask a [PackedCards] (a SubcomposeLayout) for its intrinsic
 * width, so each one reports its widest line here and [DialogShell] makes the
 * window as wide as the widest. Otherwise a wide window would always be the
 * full 1100dp even for one small card. A window with no cards (text, a list)
 * keeps its usual width.
 */
internal class DialogFit(
    /**
     * How wide a card may say it is beyond the width it's laid out in. A page
     * next to another measures its cards against the whole window, so one that
     * doesn't wrap reports its real width. 0 means the width it's in.
     */
    val probe: Int = 0,
) {
    private val widths = androidx.compose.runtime.mutableStateMapOf<Any, Int>()
    private val cramped = androidx.compose.runtime.mutableStateMapOf<Any, Boolean>()
    /** The widest line any of the window's card blocks wants, in px; 0 when none has reported. */
    val natural: Int get() = widths.values.maxOrNull() ?: 0
    /** Whether some card was laid out narrower than it can be without cutting something off. */
    val squeezed: Boolean get() = cramped.values.any { it }
    fun tell(who: Any, px: Int, cut: Boolean = false) {
        if (widths[who] != px) widths[who] = px
        if (cramped[who] != cut) cramped[who] = cut
    }
    fun forget(who: Any) { widths.remove(who); cramped.remove(who) }
}

internal val LocalDialogFit = androidx.compose.runtime.compositionLocalOf<DialogFit?> { null }

/**
 * How wide a window of text or a list reads best, reported to the window like
 * the cards do. Otherwise a manual page on a desktop would run 1100dp per
 * line, which is too long to read comfortably.
 */
@Composable
internal fun WindowWidth(width: Dp) {
    val fit = LocalDialogFit.current ?: return
    val me = remember { Any() }
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { width.roundToPx() }
    androidx.compose.runtime.DisposableEffect(fit, px) {
        fit.tell(me, px)
        onDispose { fit.forget(me) }
    }
}

/** Whether the window is on a square phone's screen, short and narrow; see [DialogShell]. */
internal val LocalDialogCompact = androidx.compose.runtime.compositionLocalOf { false }

/** Whether the window's header shows the body's own row (its `wideHeader`), so the body leaves it out. */
internal val LocalDialogHeaderRow = androidx.compose.runtime.compositionLocalOf { false }

/**
 * The tempo: a number you can type, with a step button either side.
 *
 * A slider over two hundred values can't reliably land on one, and preset
 * chips only cover some tempos. Typing gets any tempo and the arrows step to
 * the next one.
 */
@Composable
private fun BpmRow(bpm: Float, onBpm: (Float) -> Unit) {
    val c = com.rm.acidulous.ui.theme.Acid.colors
    // What's been typed, kept separately so a half-finished number ("1", or
    // an empty field mid-delete) doesn't become the tempo and snap the field
    // back while you type.
    var typed by remember(bpm) { mutableStateOf(formatBpm(bpm)) }
    val fieldSaid = stringResource(Res.string.a11y_tempo_field)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(Res.string.tempo_bpm), color = c.textDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StepButton("\u2212", stringResource(Res.string.a11y_tempo_down)) { onBpm((bpm - 1f).coerceIn(BPM_MIN, BPM_MAX)) }
            OutlinedTextField(
                value = typed,
                onValueChange = { text ->
                    typed = text.filter { it.isDigit() || it == '.' }.take(6)
                    typed.toFloatOrNull()?.let { if (it in BPM_MIN..BPM_MAX) onBpm(it) }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle = LocalTextStyle.current.copy(
                    fontSize = 20.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center,
                ),
                modifier = Modifier.typing() then Modifier.width(120.dp).semantics { contentDescription = fieldSaid },
            )
            StepButton("+", stringResource(Res.string.a11y_tempo_up)) { onBpm((bpm + 1f).coerceIn(BPM_MIN, BPM_MAX)) }
        }
    }
}

@Composable
private fun StepButton(label: String, said: String, onClick: () -> Unit) {
    val c = com.rm.acidulous.ui.theme.Acid.colors
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(c.control).clickable(onClick = onClick).button(said),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = c.accent, fontSize = 20.sp) }
}

/**
 * Tap in time and the tempo is the average gap between the taps.
 *
 * The average rather than the last gap, because taps scatter by around 20ms
 * either way, which at 120 bpm is four bpm of jitter. A gap over two seconds
 * starts again.
 */
@Composable
private fun TapTempo(onBpm: (Float) -> Unit) {
    val c = com.rm.acidulous.ui.theme.Acid.colors
    val taps = remember { mutableStateListOf<Long>() }
    var shown by remember { mutableStateOf(0f) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(Res.string.tempo_tap), color = c.textDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Box(
            Modifier.width(96.dp).height(52.dp).clip(RoundedCornerShape(6.dp))
                .background(c.control)
                .clickable {
                    val now = System.nanoTime() / 1_000_000L
                    if (taps.isNotEmpty() && now - taps.last() > 2000L) taps.clear()
                    taps.add(now)
                    while (taps.size > 8) taps.removeAt(0)
                    if (taps.size >= 2) {
                        val span = (taps.last() - taps.first()).toFloat() / (taps.size - 1)
                        if (span > 1f) {
                            val found = (60000f / span).coerceIn(BPM_MIN, BPM_MAX)
                            shown = found
                            onBpm(found)
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (taps.size < 2) stringResource(Res.string.tempo_tap_button) else formatBpm(shown),
                color = c.accent, fontSize = 15.sp, fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * The song's key, which nothing is forced into.
 *
 * It shades the rows outside the key in the roll and gives new tracks a
 * matching scale. It doesn't move existing notes or change tracks that have
 * their own scale.
 */
@Composable
private fun KeySection(
    key: SongKey?, onKey: (SongKey?) -> Unit,
    tuning: com.rm.acidulous.model.Tuning? = null,
    tunings: List<com.rm.acidulous.model.Tuning> = emptyList(),
    onTuning: (com.rm.acidulous.model.Tuning?) -> Unit = {},
) {
    WindowCard(stringResource(Res.string.tempo_key)) {
        // 0 is no key, then the twelve roots spelled for the chosen scale, so
        // E flat major is E♭ and not D♯. `Scales.rootName` is the same logic
        // the roll's labels use, so they always agree.
        val scale = key?.scale ?: 0
        CountKnob(
            stringResource(Res.string.tempo_root), key?.let { it.root + 1 } ?: 0, 0..12, key?.let { Scales.rootName(it.root, scale) } ?: stringResource(Res.string.none), PanelAmber,
            choices = listOf(stringResource(Res.string.none)) + (0 until 12).map { Scales.rootName(it, scale) },
        ) { i ->
            onKey(if (i == 0) null else SongKey(i - 1, scale))
        }
        if (key != null) {
            CountKnob(stringResource(Res.string.tempo_scale), key.scale, 0 until Scales.names.size, Scales.names[key.scale], width = 108.dp, choices = Scales.names) {
                onKey(key.copy(scale = it))
            }
        }
        // How the notes are tuned, counted from the root. A song's tuning
        // that this phone has no file for (say from a bundle) is kept at the
        // top of the list.
        if (tunings.isNotEmpty()) TuningKnob(tuning, tunings, onTuning)
    }
}

/**
 * Which tuning, as a knob that shows its name and opens the list on a hold.
 * [followLabel] adds a first choice meaning "none of its own", like a
 * track's "song" that follows the song's tuning.
 */
@Composable
internal fun TuningKnob(
    tuning: com.rm.acidulous.model.Tuning?,
    tunings: List<com.rm.acidulous.model.Tuning>,
    onTuning: (com.rm.acidulous.model.Tuning?) -> Unit,
    followLabel: String? = null,
) {
    val current = tuning ?: if (followLabel == null) com.rm.acidulous.model.Tunings.EQUAL else null
    val list = if (current != null && tunings.none { it == current }) listOf(current) + tunings else tunings
    val entries: List<com.rm.acidulous.model.Tuning?> = (if (followLabel != null) listOf(null) else emptyList()) + list
    val index = entries.indexOfFirst { it == current }.coerceAtLeast(0)
    CountKnob(
        stringResource(Res.string.tempo_tuning), index, 0 until entries.size, entries[index]?.name ?: followLabel!!, width = 108.dp,
        choices = entries.map { it?.name ?: followLabel!! },
    ) { onTuning(entries[it]) }
}


private fun formatBpm(bpm: Float): String =
    if (kotlin.math.abs(bpm - bpm.toInt()) < 0.05f) "%.0f".format(bpm) else "%.1f".format(bpm)

@Composable
private fun ClickPage() {
    // These all apply as you touch them, since they belong to the device and
    // not the song, so Cancel doesn't undo them.
    WindowCards {
        WindowCard(stringResource(Res.string.click_card)) {
            SwitchGrid(stringResource(Res.string.click_sound), stringArrayResource(Res.array.click_voices).toList(), UiPrefs.clickVoice, columns = 3) { UiPrefs.chooseClickVoice(it) }
            SwitchGrid(stringResource(Res.string.click_ticks_on), stringArrayResource(Res.array.click_divisions).toList(), UiPrefs.clickDivision, columns = 3) { UiPrefs.chooseClickDivision(it) }
            SwitchGrid(stringResource(Res.string.click_plays), stringArrayResource(Res.array.click_when).toList(), UiPrefs.clickWhen, columns = 1) { UiPrefs.chooseClickWhen(it) }
            CountKnob(stringResource(Res.string.click_level), (UiPrefs.clickVolume * 100f).roundToInt(), 0..100, "%.0f%%".format(UiPrefs.clickVolume * 100f)) {
                UiPrefs.chooseClickVolume(it / 100f)
            }
        }
    }
}

internal val SIGNATURES = listOf(
    Signature(4, 4), Signature(3, 4), Signature(2, 4), Signature(5, 4),
    Signature(6, 8), Signature(7, 8), Signature(9, 8), Signature(12, 8),
)

internal val GRIDS = listOf("1/4" to PPQN, "1/8" to PPQN / 2, "1/16" to PPQN / 4, "1/32" to PPQN / 8, "1/8T" to PPQN / 3, "1/16T" to PPQN / 6)

/**
 * The layout every tabbed window uses, so they all match: a card the width of
 * the screen, a title, a row of chips, a body, and one button on the right.
 *
 * The body is as tall as the tallest page, not the current one. Every page
 * is composed and measured and only the chosen one is placed. Otherwise
 * changing tab would resize the window and move its button out from under
 * your thumb.
 */
@Composable
fun TabbedDialog(
    title: String,
    selected: Int,
    pages: List<@Composable () -> Unit>,
    onDismiss: () -> Unit,
    dismissLabel: String = stringResource(Res.string.done),
    maxBodyHeight: Dp = 560.dp,
    /** Spacing between the items a page puts in itself. */
    spacing: Dp = 6.dp,
    confirmLabel: String = "",
    onConfirm: (() -> Unit)? = null,
    /** The body's own row, for the header when there's room; see [PlainDialog]. */
    wideHeader: (@Composable () -> Unit)? = null,
    /**
     * The tab names, with [onSelectPage], for a window that has its tabs drawn
     * for it rather than bringing its own [chips]. Only one page shows at a
     * time, in every layout.
     */
    pageNames: List<String>? = null,
    onSelectPage: ((Int) -> Unit)? = null,
    chips: (@Composable () -> Unit)? = null,
) {
    val tabs: (@Composable () -> Unit)? = chips ?: if (pageNames != null && onSelectPage != null) {
        { SectionChips(pageNames, selected) { onSelectPage(it) } }
    } else {
        null
    }
    // The page keys step through the tabs, round and round (L1 and R1 on a
    // controller).
    val n = pages.size
    val keys = if (onSelectPage != null && n > 1) listOf(
        KeyAction.PagePrev to { onSelectPage((selected - 1 + n) % n) },
        KeyAction.PageNext to { onSelectPage((selected + 1) % n) },
    ) else emptyList()
    DialogShell(
        title, onDismiss, dismissLabel, maxBodyHeight,
        confirmLabel = confirmLabel, onConfirm = onConfirm, chips = tabs, wideHeader = wideHeader, keys = keys,
    ) {
        TallestOf(selected, pages, spacing)
    }
}

/**
 * The same window without tabs, for single-page windows.
 *
 * It uses the same `DialogShell` as a tabbed window, so the two can't drift
 * apart. [confirmLabel] adds an action to the left of the dismiss button, for
 * a window that does something rather than just changing settings as you
 * touch them.
 */
@Composable
fun PlainDialog(
    title: String,
    onDismiss: () -> Unit,
    dismissLabel: String = stringResource(Res.string.cancel),
    confirmLabel: String = "",
    confirmEnabled: Boolean = true,
    onConfirm: (() -> Unit)? = null,
    maxBodyHeight: Dp = 560.dp,
    spacing: Dp = 16.dp,
    /**
     * Something for the header row when the window is sideways, between the
     * title and the buttons, like a unit's bypass. Upright there's no header
     * row, so the window draws it in its body instead.
     */
    wideHeader: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    DialogShell(
        title, onDismiss, dismissLabel, maxBodyHeight,
        confirmLabel = confirmLabel, confirmEnabled = confirmEnabled, onConfirm = onConfirm, chips = null,
        wideHeader = wideHeader,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) { content() }
    }
}

/** The card every window in the app is. */
@Composable
private fun DialogShell(
    title: String,
    onDismiss: () -> Unit,
    dismissLabel: String,
    maxBodyHeight: Dp,
    confirmLabel: String = "",
    confirmEnabled: Boolean = true,
    onConfirm: (() -> Unit)? = null,
    chips: (@Composable () -> Unit)?,
    wideHeader: (@Composable () -> Unit)? = null,
    /** What the window's keys do, like its tabs' previous and next. */
    keys: List<Pair<KeyAction, () -> Unit>> = emptyList(),
    body: @Composable () -> Unit,
) {
    val c = com.rm.acidulous.ui.theme.Acid.colors
    // The height cap is the smaller of what was asked for and the screen.
    // 560dp suits an upright phone, but a turned phone has less than that,
    // and the window's Done button would end up off screen.
    val windowHeight = with(androidx.compose.ui.platform.LocalDensity.current) {
        androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp()
    }
    // The card is capped and the body gives way. The title, chips and footer
    // take what they need and the body gets the rest by weight, so nothing
    // needs counting and nothing gets pushed off the bottom.
    //
    // The floor (200dp) can't be taller than the window either, or on a very
    // short screen the card would again be taller than the screen.
    //
    // A square phone uses a smaller edge, since its window is short and the
    // chrome would otherwise take a big share. See `compact` below.
    val compactScreen = compactWindow()
    val cardMax = (windowHeight - if (compactScreen) DialogEdgeCompactH else DialogEdgeH)
        .coerceAtLeast(minOf(200.dp, windowHeight))
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // A window lays itself out rather than inheriting the layout of what
        // opened it (like the editor's side column). Its cards decide how
        // they stack (WindowCards).
        androidx.compose.runtime.CompositionLocalProvider(
            LocalPanelStacked provides false,
            LocalStackedPerLine provides StackedPerLine,
        ) {
        // A dialog is its own window, so its keys don't pass through
        // MainActivity. The hub is asked here instead, before and after the
        // window's controls. Esc is left to the window, which closes on it,
        // and the screen's letter shortcuts stop at the window (see
        // KeyScope's `window`).
        KeyScope(*keys.toTypedArray(), window = true)
        WindowKeys()
        // Opened from the keyboard, the window moves focus to its first
        // control so the next key goes there.
        val bodyFocus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            // Keep trying, since the controls aren't there to focus until the
            // window has been laid out, a frame or two later.
            if (KeyHub.usingKeys) {
                for (attempt in 0 until 10) {
                    kotlinx.coroutines.delay(32)
                    if (runCatching { bodyFocus.requestFocus() }.getOrDefault(false)) break
                }
            }
        }
        ScaledWindow {
            // Sideways, the header is one row with the title, tabs and
            // buttons across the top and the body below. Upright they're
            // stacked with the footer on its own row, which would take too
            // much height sideways. Square phones count too, since they're
            // as short as a turned phone.
            val wide = screenShape() != ScreenShape.Tall
            // Cards only go side by side where two fit. A square phone is
            // short like a turned one but narrow like an upright one, so
            // below this width the cards stack and wrap as they do upright,
            // and the tabs get their own row.
            val roomy = with(androidx.compose.ui.platform.LocalDensity.current) {
                androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toDp()
            } >= WideCardsMinW
            // Short and narrow means a square phone. The chrome shrinks: a
            // smaller edge, tighter padding and gaps, and 40dp header buttons
            // instead of 48. A unit's own row goes next to the title when
            // there are no tabs sharing the line.
            val compact = wide && !roomy
            // When roomy, the tabs go in the header's middle. When compact,
            // they get their own row and the middle is free.
            val headerRow = wideHeader != null && wide && (chips == null || !roomy)
            // Sideways, the window is as wide as its cards want (see
            // DialogFit) but never narrower than the header needs. The first
            // frame is laid out before any card has reported, so the window
            // stays hidden until the second.
            val fit = androidx.compose.runtime.remember { DialogFit() }
            var settled by androidx.compose.runtime.remember { mutableStateOf(false) }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                androidx.compose.runtime.withFrameNanos { }
                androidx.compose.runtime.withFrameNanos { }
                settled = true
            }
            val maxW = if (!wide) 720.dp else if (!roomy || fit.natural <= 0) 1100.dp else with(
                androidx.compose.ui.platform.LocalDensity.current,
            ) {
                (fit.natural.toDp() + FitChromeW).coerceIn(if (chips != null) FitMinTabsW else FitMinW, 1100.dp)
            }
            androidx.compose.material3.Surface(
                // The width cap goes before fillMaxWidth. After it, the fill
                // would already have fixed the width and the cap couldn't
                // lower it.
                Modifier.padding(horizontal = 10.dp).widthIn(max = maxW).fillMaxWidth()
                    .heightIn(max = cardMax)
                    .graphicsLayer { alpha = if (settled || !wide || !roomy) 1f else 0f },
                shape = RoundedCornerShape(16.dp),
                color = c.card,
            ) {
                val footer = dismissLabel.isNotEmpty() || onConfirm != null
                @Composable
                fun Buttons() {
                    if (dismissLabel.isNotEmpty()) {
                        TextButton(onClick = onDismiss) { Text(dismissLabel) }
                    }
                    if (onConfirm != null) {
                        Button(onClick = onConfirm, enabled = confirmEnabled) { Text(confirmLabel) }
                    }
                }
                Column(Modifier.padding(horizontal = 16.dp, vertical = if (compact) 6.dp else if (wide) 10.dp else 14.dp)) {
                    if (wide) {
                        androidx.compose.runtime.CompositionLocalProvider(
                            androidx.compose.material3.LocalMinimumInteractiveComponentSize provides if (compact) 40.dp else 48.dp,
                        ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                title, color = c.text, fontSize = 20.sp, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 280.dp).padding(end = 12.dp),
                            )
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                // The unit's own row only goes here when its
                                // body is laid out wide and leaves it out; see SlotRow.
                                if (roomy && chips != null) chips() else if (headerRow) wideHeader?.invoke()
                            }
                            if (footer) {
                                Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) { Buttons() }
                            }
                        }
                        }
                        if (chips != null && !roomy) Box(Modifier.padding(top = if (compact) 2.dp else 6.dp)) { chips() }
                        Box(Modifier.padding(top = if (compact) 4.dp else 8.dp))
                    } else {
                        Text(title, color = c.text, fontSize = 20.sp)
                        if (chips != null) {
                            Box(Modifier.padding(top = 12.dp, bottom = 6.dp)) { chips() }
                        } else {
                            Box(Modifier.padding(top = 10.dp))
                        }
                    }
                    Box(
                        // The scroll bar is drawn on the outer edge of this
                        // box, so the content is inset to leave it room.
                        // `fill = false` so it can only be smaller than its
                        // content, never stretched, or a short page would push
                        // the button to the bottom of the screen.
                        Modifier.weight(1f, fill = false)
                            .heightIn(max = maxBodyHeight)
                            .verticalScrollWithBar(rememberScrollState())
                            .padding(end = 10.dp)
                            // Where the keyboard's first focus goes: the
                            // body's first control, not the footer's button.
                            .focusRequester(bodyFocus)
                            .focusGroup(),
                    ) {
                        androidx.compose.runtime.CompositionLocalProvider(
                            LocalDialogWide provides (wide && roomy),
                            LocalDialogHeaderRow provides headerRow,
                            LocalDialogCompact provides compact,
                            LocalDialogFit provides (if (wide && roomy) fit else null),
                        ) { body() }
                    }
                    // No dismiss label and no action means no footer at all,
                    // for a window that's showing progress and mustn't be
                    // dismissed while it works.
                    if (footer && !wide) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) { Buttons() }
                    }
                }
            }
        }
        }
    }
}

/**
 * How much of the screen's height a window leaves free, so the card still
 * looks like a card rather than the whole screen.
 */
private val DialogEdgeH = 24.dp

/** The same on a square phone, where every dp of height counts; see [DialogShell]. */
private val DialogEdgeCompactH = 8.dp

/**
 * Whether a window opened now is on a square phone (short, and too narrow
 * for cards side by side), where the chrome is trimmed and the busiest
 * windows split into pages. See [DialogShell].
 */
@Composable
internal fun compactWindow(): Boolean =
    screenShape() == ScreenShape.Square && with(androidx.compose.ui.platform.LocalDensity.current) {
        androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toDp()
    } < WideCardsMinW

/** What a wide window adds around its cards: the body's padding either side and the scroll bar's gutter. */
private val FitChromeW = 42.dp

/** The narrowest a fitted window may be: a title and two buttons. */
private val FitMinW = 480.dp

/** The same with tabs beside the title. */
private val FitMinTabsW = 640.dp

/** The narrowest window that lays a window's cards side by side; see [DialogShell]. */
private val WideCardsMinW = 600.dp

/** Measures every page, shows one, and takes the height of the biggest. */
@Composable
private fun TallestOf(selected: Int, pages: List<@Composable () -> Unit>, spacing: Dp) {
    androidx.compose.ui.layout.SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val loose = constraints.copy(minHeight = 0)
        // Each page is wrapped in a column here rather than trusted to be one
        // box. Otherwise a page with three sibling sections would be measured
        // as three children and placed on top of each other.
        val measured = pages.indices.map { i ->
            subcompose(i) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
                    pages[i]()
                }
            }.map { it.measure(loose) }
        }
        val height = measured.maxOfOrNull { page -> page.maxOfOrNull { it.height } ?: 0 } ?: 0
        val shown = measured.getOrNull(selected).orEmpty()
        layout(constraints.maxWidth, height) { shown.forEach { it.place(0, 0) } }
    }
}

// --- The vocabulary every window shares --------------------------------------------

/**
 * A titled group of chips: the name in teal monospace, the chips under it,
 * and one line saying what the current choice means.
 */
@Composable
internal fun Section(title: String, note: String = "", content: @Composable () -> Unit) {
    val c = com.rm.acidulous.ui.theme.Acid.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = c.teal, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) { content() }
        if (note.isNotEmpty()) Text(note, color = c.textDim, fontSize = 11.sp, lineHeight = 14.sp)
    }
}

/** The same, for a stack of full-width rows rather than a row of chips. */
@Composable
internal fun ListSection(
    title: String,
    note: String = "",
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val c = com.rm.acidulous.ui.theme.Acid.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = c.teal, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        content()
        if (note.isNotEmpty()) Text(note, color = c.textDim, fontSize = 11.sp, lineHeight = 14.sp)
    }
}

/**
 * One of a set, filled when it's the current one.
 *
 * [enabled] is for chips that are actions rather than choices (pasting with
 * an empty clipboard, clearing an empty clip). They're shown disabled rather
 * than hidden, so you don't go looking for them.
 */
@Composable
internal fun Choice(
    label: String,
    on: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onPick: () -> Unit,
) {
    val c = com.rm.acidulous.ui.theme.Acid.colors
    Box(
        modifier.clip(RoundedCornerShape(4.dp))
            .background(if (on) c.accent else c.control)
            .clickable(enabled = enabled, onClick = onPick)
            .choice(label, on)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = when {
                !enabled -> c.textFaint
                on -> c.onAccent
                else -> c.textMid
            },
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * A row in a list: a mark, a name with an optional line under it, and a word
 * on the right saying what tapping does. `on` fills it, like the chosen
 * machine in the picker.
 */
@Composable
internal fun DialogRow(
    mark: String,
    name: String,
    under: String = "",
    trailing: String = "",
    on: Boolean = false,
    monoUnder: Boolean = false,
    /** A second action at the end of the row, usually delete. */
    onRemove: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = com.rm.acidulous.ui.theme.Acid.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(if (on) c.accentDim else c.control)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(mark, color = c.accent, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp).silent())
        Column(Modifier.weight(1f)) {
            Text(name, color = if (on) c.accent else c.text, fontSize = 14.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (under.isNotEmpty()) {
                Text(under, color = c.textDim, fontSize = 11.sp, maxLines = 1,
                    fontFamily = if (monoUnder) FontFamily.Monospace else FontFamily.Default,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
        if (trailing.isNotEmpty()) {
            Text(trailing, color = if (on) c.teal else c.textDim, fontSize = 10.sp)
        }
        if (onRemove != null) {
            val said = stringResource(Res.string.a11y_delete, name)
            Text(
                "✕", color = c.red, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onRemove)
                    .button(said, onClick = onRemove, keyFocus = false)
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            )
        }
    }
}

/** A line of numbers showing whether something is working. */
@Composable
internal fun Readout(text: String, good: Boolean = false) {
    val c = com.rm.acidulous.ui.theme.Acid.colors
    Text(text, color = if (good) c.teal else c.textDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
}

/**
 * Handles the window's keys at the window itself rather than in a control.
 * A window opened by a tap has nothing focused, and keys only go to the
 * focused control, so without this Space, Ctrl+S or a key being learned in
 * the keys window would do nothing until something was tabbed to. A touch
 * works as it does on the screen; see KeyHub.usingKeys.
 */
@Composable
internal expect fun WindowKeys()
