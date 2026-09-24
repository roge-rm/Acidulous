package com.rm.acidulous.ui

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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import com.rm.acidulous.R

/**
 * The scene's "4/4 × 1" chip, expanded: name, signature, repeat, tempo, fades.
 *
 * Rebuilt in the same vocabulary as every other window here. It used to be
 * a Material `AlertDialog` full of switches and outlined buttons, and its
 * signatures sat in two fixed `Row`s - which do not wrap, so 2/4 and 5/4
 * were crushed to slivers and 12/8 came out stacked vertically as "1 2 / 8".
 * A `FlowRow` of chips fits them at any width.
 */
@Composable
fun SceneSettingsDialog(
    scene: Scene, songSignature: Signature, onDismiss: () -> Unit,
    /** How long the scene is, which is as far as a ramp can reach back. */
    bars: Int = 16,
    /** The tempo it starts from when it has none of its own. */
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
        title = stringResource(R.string.scene_title),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.ok),
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
        ListSection(stringResource(R.string.scene_name)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Cards of knobs and switches below the name, the arp window's shape:
        // Dan wants every editor window to look like that one. Nine
        // signatures are a stepped knob that names its step - as chips they
        // wrapped, and as a slider they were a dotted line.
        WindowCards {
            WindowCard(stringResource(R.string.scene_time)) {
                val sigIndex = signature?.let { SIGNATURES.indexOf(it) + 1 } ?: 0
                val songSig = stringResource(R.string.scene_signature_song, songSignature.beats, songSignature.unit)
                CountKnob(
                    stringResource(R.string.scene_signature), sigIndex, 0..SIGNATURES.size,
                    if (sigIndex == 0) songSig
                    else SIGNATURES[sigIndex - 1].let { "${it.beats}/${it.unit}" },
                    choices = listOf(songSig) + SIGNATURES.map { "${it.beats}/${it.unit}" },
                ) { i -> signature = if (i == 0) null else SIGNATURES[i - 1] }
                CountKnob(stringResource(R.string.scene_repeat), repeat, 1..32, "×$repeat", choices = (1..32).map { "×$it" }) { repeat = it }
                val offOn = stringArrayResource(R.array.off_on).toList()
                SwitchGrid(stringResource(R.string.scene_fade_in), offOn, if (fadeIn) 1 else 0) { fadeIn = it == 1 }
                SwitchGrid(stringResource(R.string.scene_fade_out), offOn, if (fadeOut) 1 else 0) { fadeOut = it == 1 }
            }
            WindowCard(stringResource(R.string.scene_tempo)) {
                SwitchGrid(stringResource(R.string.scene_tempo_from), stringArrayResource(R.array.scene_tempo_from_choices).toList(), if (ownTempo) 1 else 0) { ownTempo = it == 1 }
                if (ownTempo) {
                    // Whole beats a minute, set only when the knob moves, so
                    // a scene written at 72.5 keeps it until it is turned.
                    CountKnob(stringResource(R.string.scene_bpm), bpm.roundToInt(), 40..240, "%.0f".format(bpm), PanelAmber) { bpm = it.toFloat() }
                    SwitchGrid(stringResource(R.string.scene_change), stringArrayResource(R.array.scene_change_choices).toList(), if (smooth) 1 else 0) { smooth = it == 1 }
                }
                // A tempo change inside the scene, on its last pass:
                // slowing into what comes next, or speeding up across it.
                val start = if (ownTempo) bpm else songTempo
                SwitchGrid(stringResource(R.string.scene_ramp), stringArrayResource(R.array.off_on).toList(), if (ramp != null) 1 else 0) {
                    ramp = if (it == 1) (ramp ?: com.rm.acidulous.model.TempoRamp((start * 0.75f).roundToInt().toFloat(), minOf(2, bars))) else null
                }
                ramp?.let { r ->
                    CountKnob(stringResource(R.string.scene_ramp_to), r.toBpm.roundToInt(), 40..240, "%.0f".format(r.toBpm), PanelAmber) { ramp = r.copy(toBpm = it.toFloat()) }
                    val most = bars.coerceAtLeast(1)
                    CountKnob(
                        stringResource(R.string.scene_ramp_over), r.bars.coerceIn(1, most), 1..most,
                        r.bars.coerceAtMost(most).let { pluralStringResource(R.plurals.bars, it, it) },
                        choices = (1..most).map { pluralStringResource(R.plurals.bars, it, it) },
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
    /** The song's tempo here, to say whether a freeze can still be used. */
    tempo: Float = 0f,
    /** Render this clip to audio, or throw the render away. Both dismiss. */
    onFreeze: () -> Unit = {},
    onThaw: () -> Unit = {},
    /** Empty it of notes and automation, keeping how it is set up. Dismisses. */
    onClear: () -> Unit = {},
    /** Put this clip on the clipboard. [onCut] copies and then clears. Both dismiss. */
    onCopy: () -> Unit = {},
    onCut: () -> Unit = {},
    /** Replace this clip with what is on the clipboard. Dismisses. */
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
    // Only where the clip actually gambles. A control for a feature this clip
    // is not using is clutter, and most clips never will be.
    val rolls = clip.notes.any { it.chance < 100 }

    PlainDialog(
        title = stringResource(R.string.clip_title),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.ok),
        onConfirm = {
            onConfirm(clip.copy(bars = bars, playMode = mode, mute = mute, grid = grid, seed = seed, freeRoll = free))
        },
    ) {
        // **The actions first.** They are what you opened this window to do -
        // the settings under them are the ones you set once and leave - and a
        // control you reach for often does not belong at the bottom. `clear`
        // sits with them because it is the same kind of thing, and it asks
        // before it does anything; so does pasting over a clip that has
        // something in it. `cut` does not ask, because what it takes is on
        // the clipboard rather than gone.
        //
        // Cards of knobs and switches, the arp window's shape: Dan wants every
        // editor window to look like that one, all of it in view at once.
        val held = ClipClipboard.clip
        val what = listOfNotNull(
            if (clip.notes.isNotEmpty()) pluralStringResource(R.plurals.clip_notes, clip.notes.size, clip.notes.size) else null,
            if (clip.automation.isNotEmpty()) pluralStringResource(R.plurals.clip_lanes, clip.automation.size, clip.automation.size) else null,
            if (clip.frozen != null) stringResource(R.string.clip_frozen) else null,
        ).joinToString(stringResource(R.string.list_separator)).ifEmpty { stringResource(R.string.clip_empty) }
        val frozen = clip.frozen
        val stale = frozen != null && tempo > 0f && kotlin.math.abs(frozen.bpm - tempo) >= 0.01f
        WindowCards {
            // The card's title says what is in it, which is what the
            // actions in it act on.
            WindowCard(stringResource(R.string.clip_card, what)) {
                SwitchGrid(
                    if (held != null) stringResource(R.string.clip_clipboard_has, ClipClipboard.from) else stringResource(R.string.clip_clipboard),
                    stringArrayResource(R.array.clip_actions).toList(), -1, columns = 2,
                    enabled = listOf(clip.hasContent() || clip.notes.isNotEmpty(), clip.hasContent(), held != null, clip.hasContent()),
                ) { i ->
                    when (i) {
                        0 -> onCopy()
                        1 -> onCut()
                        2 -> if (clip.hasContent()) confirmPaste = true else onPaste()
                        else -> confirmClear = true
                    }
                }
                // Freeze is an action rather than a setting, so it does
                // its own thing and closes; everything else waits for OK.
                if (frozen != null) {
                    SwitchGrid(stringResource(if (stale) R.string.clip_stale else R.string.clip_audio), listOf(stringResource(R.string.clip_thaw)), -1) { onThaw() }
                } else if (clip.notes.isNotEmpty()) {
                    SwitchGrid(stringResource(R.string.clip_audio), listOf(stringResource(R.string.clip_freeze)), -1) { onFreeze() }
                }
            }
            WindowCard(stringResource(R.string.clip_length)) {
                CountKnob(stringResource(R.string.clip_bars), bars, 1..16, choices = (1..16).map { pluralStringResource(R.plurals.bars, it, it) }) { bars = it }
                SwitchGrid(stringResource(R.string.clip_grid), GRIDS.map { it.first }, GRIDS.indexOfFirst { it.second == grid }, columns = 3) { grid = GRIDS[it].second }
            }
            WindowCard(stringResource(R.string.clip_plays)) {
                SwitchGrid(stringResource(R.string.clip_mode), stringArrayResource(R.array.clip_mode_choices).toList(), if (mode == PlayMode.OneShot) 1 else 0) {
                    mode = if (it == 1) PlayMode.OneShot else PlayMode.Loop
                }
                SwitchGrid(stringResource(R.string.clip_mute), stringArrayResource(R.array.off_on).toList(), if (mute) 1 else 0) { mute = it == 1 }
                // Only where the clip actually gambles. A control for a
                // feature this clip is not using is clutter, and most
                // clips never will be.
                if (rolls) {
                    SwitchGrid(stringResource(R.string.clip_dice), stringArrayResource(R.array.clip_dice_choices).toList(), if (free) 1 else 0) { free = it == 1 }
                    if (!free) CountKnob(stringResource(R.string.clip_seed), seed, 0..63) { seed = it }
                }
            }
        }
        // The one line nothing else can say: a freeze at another tempo is not
        // what is playing.
        if (frozen != null && stale) {
            Text(
                stringResource(R.string.clip_stale_note, frozen.bpm, tempo),
                color = com.rm.acidulous.ui.theme.Acid.colors.textDim, fontSize = 11.sp, lineHeight = 14.sp,
            )
        }
    }

    if (confirmPaste) {
        PlainDialog(
            title = stringResource(R.string.clip_paste_title),
            onDismiss = { confirmPaste = false },
            confirmLabel = stringResource(R.string.clip_paste),
            onConfirm = { confirmPaste = false; onPaste() },
        ) {
            Text(
                stringResource(R.string.clip_paste_note, ClipClipboard.from),
                color = com.rm.acidulous.ui.theme.Acid.colors.textDim, fontSize = 11.sp, lineHeight = 14.sp,
            )
        }
    }

    if (confirmClear) {
        PlainDialog(
            title = stringResource(R.string.clip_clear_title),
            onDismiss = { confirmClear = false },
            confirmLabel = stringResource(R.string.clip_clear),
            onConfirm = { confirmClear = false; onClear() },
        ) {
            Text(
                stringResource(R.string.clip_clear_note),
                color = com.rm.acidulous.ui.theme.Acid.colors.textDim, fontSize = 11.sp, lineHeight = 14.sp,
            )
        }
    }
}

@Composable
internal fun WindowCard(title: String, content: @Composable () -> Unit) =
    Group(title, perLine = 4, centred = true, background = com.rm.acidulous.ui.theme.Acid.colors.cardAlt, content = content)

/** A name and its line, at a height every row shares. */
private val MACHINE_ROW_H = 68.dp

/**
 * A group's name, with the hedge set in italic: "realish" is doing a
 * qualifier's job, and it should look like one.
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
 * saying what it is. A flat list of twelve names told you nothing unless you
 * already knew, which defeats the point of having twelve.
 */
@Composable
fun MachinePickerDialog(current: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val groups = com.rm.acidulous.model.MachineUi.machineGroups
    val known = remember { com.rm.acidulous.engine.NativeEngine.machineTypes.toSet() }
    var tab by rememberSaveable {
        mutableStateOf(groups.indexOfFirst { current in it.machines }.coerceAtLeast(0))
    }
    val c = com.rm.acidulous.ui.theme.Acid.colors
    // Anything the engine offers that no group claims still has to be
    // reachable, so it lands in the last group.
    val listed = groups.flatMap { it.machines }.toSet()
    val contents = groups.mapIndexed { i, g ->
        g.machines.filter { it in known } +
            (if (i == groups.lastIndex) known.filter { it !in listed } else emptyList())
    }
    TabbedDialog(
        title = stringResource(R.string.picker_machine),
        selected = tab,
        dismissLabel = stringResource(R.string.cancel),
        onDismiss = onDismiss,
        chips = { SectionChipsStyled(groups.map { chipLabel(stringResource(it.label)) }, tab) { tab = it } },
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
            Text(stringResource(R.string.picker_empty), color = com.rm.acidulous.ui.theme.Acid.colors.textDim, fontSize = 12.sp)
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
        confirmLabel = stringResource(R.string.ok),
        confirmEnabled = value.isNotBlank(),
        onConfirm = { if (value.isNotBlank()) onConfirm(value.trim()) },
    ) {
        OutlinedTextField(
            value = value, onValueChange = { value = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * A number set by dragging: the name, its value on the right, a line saying
 * what it is, and the slider under them.
 *
 * This is the shape for anything continuous. Chips are right for a handful
 * of named choices and wrong for a range - thirty-two repeat counts is not
 * a set of chips, and even eight of them wrap onto a second row and push
 * everything below them down the screen. A slider is one row whatever the
 * range, which is how a settings page stays short enough to read without
 * scrolling.
 *
 * It must be given a Column, not a `Section` - a Section's content is a
 * FlowRow, which hands a slider constraints it cannot make sense of and
 * draws it as a bar, a gap and a stray dot.
 */
@Composable
internal fun SliderSection(
    title: String,
    value: String,
    note: String,
    position: Float,
    range: ClosedFloatingPointRange<Float>,
    /** Stops to draw. 0 for a plain slider - see the note in the body. */
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    val c = com.rm.acidulous.ui.theme.Acid.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = c.teal, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text(value, color = c.accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        // Tight leading: these lines are the bulk of a settings page, and at
        // the default they were spaced like prose.
        if (note.isNotEmpty()) {
            Text(note, color = c.textDim, fontSize = 11.sp, lineHeight = 14.sp)
        }
        // Ticks only when you could count them. A stop on each of eight
        // signatures helps; two hundred dots along a tempo is a dotted line.
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
 * Tempo, and everything that counts against it.
 *
 * The metronome used to live in the settings window, a tab away from
 * anything it relates to. It belongs here: the tempo, the click and the
 * count-in are one thought - how fast, what am I hearing it against, and
 * how long before it starts - and you reach for them in the same moment.
 * Settings is for what you set once.
 *
 * Two tabs rather than one long scroll, for the same reason the settings
 * window has them, and it opens on the tempo because that is what the
 * button you pressed says.
 *
 * The window mixes two lifetimes on purpose: the **tempo waits for OK**,
 * being the song's and an edit every time, while the **click settings apply
 * as you touch them**, being the device's - which is why Cancel says
 * nothing about them.
 */
@Composable
fun TempoDialog(
    song: Song, onDismiss: () -> Unit,
    /** The tunings there are to choose from: built in, and imported. */
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
    TabbedDialog(
        title = stringResource(R.string.tempo_title),
        selected = tab,
        onDismiss = onDismiss,
        dismissLabel = stringResource(R.string.cancel),
        confirmLabel = stringResource(R.string.ok),
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
        chips = { SectionChips(stringArrayResource(R.array.tempo_tabs).toList(), tab) { tab = it } },
        pages = listOf(
            {
                TempoPage(
                    bpm, signature, swing, swingUnit, key,
                    onBpm = { bpm = it }, onSignature = { signature = it },
                    onSwing = { swing = it }, onSwingUnit = { swingUnit = it }, onKey = { key = it },
                    tuning = tuning, tunings = tunings, onTuning = { tuning = it },
                )
            },
            { ClickPage() },
            { LinkPage() },
        ),
    )
}

/**
 * Ableton Link: everybody on this Wi-Fi at one tempo, with one bar line.
 *
 * Here, behind the tempo, rather than with the MIDI clock in the MIDI
 * window - because this is the other answer to "who decides the tempo", and
 * the two are alternatives. Following a clock down a cable and following a
 * session over the air at the same time is two masters; switching this on
 * stands the other down, in the engine and on the screen.
 */
@Composable
private fun LinkPage() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val hub = com.rm.acidulous.engine.LinkHub
    WindowCards {
        WindowCard(stringResource(R.string.link_card)) {
            SwitchGrid(stringResource(R.string.link_tempo_sync), stringArrayResource(R.array.off_on).toList(), if (hub.enabled) 1 else 0) { i ->
                val on = i == 1
                UiPrefs.chooseLink(on)
                hub.setEnabled(context, on)
                // One master at a time; the engine enforces it and the screen
                // should not go on claiming otherwise.
                if (on && com.rm.acidulous.midi.MidiHub.follow != com.rm.acidulous.midi.MidiHub.Follow.Off) {
                    UiPrefs.chooseFollow(com.rm.acidulous.midi.MidiHub.Follow.Off)
                }
            }
            SwitchGrid(stringResource(R.string.link_start_stop), stringArrayResource(R.array.link_start_stop_choices).toList(), if (hub.startStop) 0 else 1) { UiPrefs.chooseLinkStartStop(it == 0) }
        }
    }
    // What neither switch can say: what following a session costs, and
    // whether one is there.
    if (hub.enabled) {
        ListSection(
            stringResource(R.string.link_session),
            stringResource(if (hub.multicast) R.string.link_session_note else R.string.link_session_note_no_multicast),
        ) {
            Readout(
                stringResource(
                    R.string.link_readout,
                    pluralStringResource(R.plurals.link_peers, hub.peers, hub.peers),
                    if (hub.sessionTempo > 0f) stringResource(R.string.link_session_tempo, hub.sessionTempo) else stringResource(R.string.link_no_tempo),
                    hub.phaseMs,
                    stringResource(if (hub.multicast) R.string.link_multicast_held else R.string.link_multicast_not_held),
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
) {
    // Cards, the arp window's shape, so this reads like every other window a
    // player reaches for mid-song. The tempo itself stays a field with a step
    // either side - see BpmRow - because a knob over two hundred values
    // cannot land on one of them.
    WindowCards {
        WindowCard(stringResource(R.string.tempo_tempo)) {
            BpmRow(bpm, onBpm)
            TapTempo(onBpm)
        }
        WindowCard(stringResource(R.string.tempo_bar)) {
            val sigIndex = SIGNATURES.indexOf(signature).coerceAtLeast(0)
            CountKnob(
                stringResource(R.string.tempo_signature), sigIndex, 0 until SIGNATURES.size, "${signature.beats}/${signature.unit}",
                choices = SIGNATURES.map { "${it.beats}/${it.unit}" },
            ) { onSignature(SIGNATURES[it]) }
            CountKnob(
                stringResource(R.string.tempo_swing), swing.roundToInt(), SWING_STRAIGHT.toInt()..SWING_MAX.toInt(),
                if (swing <= SWING_STRAIGHT + 0.05f) stringResource(R.string.tempo_straight) else "%.0f%%".format(swing), PanelAmber,
            ) { onSwing(it.toFloat()) }
            SwitchGrid(stringResource(R.string.tempo_swing_on), listOf("1/16", "1/8"), unit(swingUnit)) { onSwingUnit(it) }
            // The two feels worth a name. Neither is lit between them, which
            // is what a knob set to 58% is.
            SwitchGrid(
                stringResource(R.string.tempo_feel), stringArrayResource(R.array.tempo_feel_choices).toList(),
                when {
                    swing <= SWING_STRAIGHT + 0.05f -> 0
                    kotlin.math.abs(swing - SWING_TRIPLET) < 0.5f -> 1
                    else -> -1
                },
            ) { onSwing(if (it == 0) SWING_STRAIGHT else SWING_TRIPLET) }
        }
        KeySection(key, onKey, tuning, tunings, onTuning)
    }
}

private fun unit(u: Int) = if (u == 1) 1 else 0

/**
 * A window's cards: stacked down it upright, each wrapping its controls;
 * side by side when the phone is turned, each a row, the way a machine's
 * panel lays them - a turned window is wide and about four hundred dp tall,
 * and a column of cards in it was a scroll by the second card.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun WindowCards(content: @Composable () -> Unit) {
    if (LocalDialogWide.current) {
        androidx.compose.runtime.CompositionLocalProvider(LocalPanelStacked provides false) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) { content() }
        }
        return
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalPanelStacked provides true) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

/**
 * A row of text or readings inside a card: the card's whole width when the
 * cards are stacked, and its own width, up to a limit, when they stand side
 * by side - where filling the row would give it everything or nothing, and a
 * line of text given nothing comes out one letter wide.
 */
@Composable
internal fun Modifier.cardLine(): Modifier =
    if (LocalDialogWide.current) this.widthIn(max = CardLineW) else this.fillMaxWidth()

private val CardLineW = 480.dp

/** Whether this window is laid out turned: see [DialogShell] and [WindowCards]. */
internal val LocalDialogWide = androidx.compose.runtime.compositionLocalOf { false }

/**
 * The tempo: a number you can type, with a step either side.
 *
 * It was a slider and eight preset chips. A slider over two hundred values
 * cannot reliably land on one of them, which is what the chips were for - and
 * chips only cover the tempos somebody thought of. A field types any of them
 * and the arrows walk to the one next door, which between them is every way
 * anybody sets a tempo.
 */
@Composable
private fun BpmRow(bpm: Float, onBpm: (Float) -> Unit) {
    val c = com.rm.acidulous.ui.theme.Acid.colors
    // What has been typed, while it is being typed. Held separately so a
    // half-finished number - "1", or an empty field mid-delete - does not
    // become the tempo and snap the field back under the finger.
    var typed by remember(bpm) { mutableStateOf(formatBpm(bpm)) }
    val fieldSaid = stringResource(R.string.a11y_tempo_field)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.tempo_bpm), color = c.textDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StepButton("\u2212", stringResource(R.string.a11y_tempo_down)) { onBpm((bpm - 1f).coerceIn(BPM_MIN, BPM_MAX)) }
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
                modifier = Modifier.width(120.dp).semantics { contentDescription = fieldSaid },
            )
            StepButton("+", stringResource(R.string.a11y_tempo_up)) { onBpm((bpm + 1f).coerceIn(BPM_MIN, BPM_MAX)) }
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
 * Four taps in time, and the tempo is the average of the gaps between them.
 *
 * The average rather than the last gap: a person's taps scatter by twenty
 * milliseconds either way, which at 120 is four beats a minute of jitter, and
 * a tempo that jumps around while you are still tapping is one you cannot
 * aim. Gaps longer than two seconds start again, because that is somebody
 * coming back to it rather than counting thirty.
 */
@Composable
private fun TapTempo(onBpm: (Float) -> Unit) {
    val c = com.rm.acidulous.ui.theme.Acid.colors
    val taps = remember { mutableStateListOf<Long>() }
    var shown by remember { mutableStateOf(0f) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.tempo_tap), color = c.textDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Box(
            Modifier.width(96.dp).height(52.dp).clip(RoundedCornerShape(6.dp))
                .background(c.control)
                .clickable {
                    val now = android.os.SystemClock.elapsedRealtime()
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
                if (taps.size < 2) stringResource(R.string.tempo_tap_button) else formatBpm(shown),
                color = c.accent, fontSize = 15.sp, fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * The key the song is in - which nothing is forced into.
 *
 * It shades the rows a note cannot use in the roll and fits a new track with
 * a matching scale. It deliberately does not move anything already written or
 * reach into a track that has chosen its own: a song setting that silently
 * retuned sixteen tracks would be a thing people turned off and left off.
 */
@Composable
private fun KeySection(
    key: SongKey?, onKey: (SongKey?) -> Unit,
    tuning: com.rm.acidulous.model.Tuning? = null,
    tunings: List<com.rm.acidulous.model.Tuning> = emptyList(),
    onTuning: (com.rm.acidulous.model.Tuning?) -> Unit = {},
) {
    WindowCard(stringResource(R.string.tempo_key)) {
        // Nought is no key; then the twelve roots, spelled against the chosen
        // scale, so E flat major is E♭ and not D♯: `Scales.rootName` is the
        // same walk the roll's own labels use, and a chooser that disagreed
        // with the notes it sets would be its own bug.
        val scale = key?.scale ?: 0
        CountKnob(
            stringResource(R.string.tempo_root), key?.let { it.root + 1 } ?: 0, 0..12, key?.let { Scales.rootName(it.root, scale) } ?: stringResource(R.string.none), PanelAmber,
            choices = listOf(stringResource(R.string.none)) + (0 until 12).map { Scales.rootName(it, scale) },
        ) { i ->
            onKey(if (i == 0) null else SongKey(i - 1, scale))
        }
        if (key != null) {
            CountKnob(stringResource(R.string.tempo_scale), key.scale, 0 until Scales.names.size, Scales.names[key.scale], width = 108.dp, choices = Scales.names) {
                onKey(key.copy(scale = it))
            }
        }
        // How the notes are tuned, counted from the root. A song's own tuning
        // that this phone has no file for - one that came in a bundle - is
        // kept, at the top of the list.
        if (tunings.isNotEmpty()) TuningKnob(tuning, tunings, onTuning)
    }
}

/**
 * Which tuning, as a knob that names it and opens the list on a hold.
 * [followLabel] adds a first choice meaning "none of my own" - a track's
 * "song", following the song's tuning.
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
        stringResource(R.string.tempo_tuning), index, 0 until entries.size, entries[index]?.name ?: followLabel!!, width = 108.dp,
        choices = entries.map { it?.name ?: followLabel!! },
    ) { onTuning(entries[it]) }
}

private const val BPM_MIN = 20f
private const val BPM_MAX = 300f

private fun formatBpm(bpm: Float): String =
    if (kotlin.math.abs(bpm - bpm.toInt()) < 0.05f) "%.0f".format(bpm) else "%.1f".format(bpm)

@Composable
private fun ClickPage() {
    // Every one of these applies as it is touched - they are the device's,
    // not the song's - so Cancel leaves them as they are.
    WindowCards {
        WindowCard(stringResource(R.string.click_card)) {
            SwitchGrid(stringResource(R.string.click_sound), stringArrayResource(R.array.click_voices).toList(), UiPrefs.clickVoice, columns = 3) { UiPrefs.chooseClickVoice(it) }
            SwitchGrid(stringResource(R.string.click_ticks_on), stringArrayResource(R.array.click_divisions).toList(), UiPrefs.clickDivision, columns = 3) { UiPrefs.chooseClickDivision(it) }
            SwitchGrid(stringResource(R.string.click_plays), stringArrayResource(R.array.click_when).toList(), UiPrefs.clickWhen, columns = 1) { UiPrefs.chooseClickWhen(it) }
            CountKnob(stringResource(R.string.click_level), (UiPrefs.clickVolume * 100f).roundToInt(), 0..100, "%.0f%%".format(UiPrefs.clickVolume * 100f)) {
                UiPrefs.chooseClickVolume(it / 100f)
            }
        }
        // Counted only when armed, which the title says so no line has to.
        WindowCard(stringResource(R.string.click_count_in)) {
            SwitchGrid(stringResource(R.string.click_bars), listOf(stringResource(R.string.none), "1", "2", "3", "4"), UiPrefs.countInBars, columns = 5) { UiPrefs.chooseCountInBars(it) }
        }
    }
}

internal val SIGNATURES = listOf(
    Signature(4, 4), Signature(3, 4), Signature(2, 4), Signature(5, 4),
    Signature(6, 8), Signature(7, 8), Signature(9, 8), Signature(12, 8),
)

private val GRIDS = listOf("1/4" to PPQN, "1/8" to PPQN / 2, "1/16" to PPQN / 4, "1/32" to PPQN / 8, "1/8T" to PPQN / 3, "1/16T" to PPQN / 6)

/**
 * The shape every window with tabs takes, so that none of them can drift
 * from the others: a card the width of the screen, a title, a row of chips,
 * a body, and one button on the right.
 *
 * **The body is as tall as the tallest page, not as tall as the page you are
 * looking at.** Every page is composed and measured; only the chosen one is
 * placed. A window that resizes when you change tab moves its own button out
 * from under your thumb, and you have to find it again - and a settings
 * window is exactly where that is most annoying, because you are usually
 * changing one thing and leaving.
 */
@Composable
fun TabbedDialog(
    title: String,
    selected: Int,
    pages: List<@Composable () -> Unit>,
    onDismiss: () -> Unit,
    dismissLabel: String = stringResource(R.string.done),
    maxBodyHeight: Dp = 560.dp,
    /** Between whatever a page puts in itself. */
    spacing: Dp = 6.dp,
    confirmLabel: String = "",
    onConfirm: (() -> Unit)? = null,
    chips: @Composable () -> Unit,
) {
    DialogShell(
        title, onDismiss, dismissLabel, maxBodyHeight,
        confirmLabel = confirmLabel, onConfirm = onConfirm, chips = chips,
    ) {
        TallestOf(selected, pages, spacing)
    }
}

/**
 * The same window without tabs, for the ones that are a single page.
 *
 * It exists so that a one-page window cannot drift from a tabbed one by
 * being written out again from scratch - they are the same `DialogShell`,
 * and the only difference is whether there is a row of chips in it.
 * [confirm] adds an action to the left of the dismiss button, for a window
 * that *does* something rather than just changing settings as you touch them.
 */
@Composable
fun PlainDialog(
    title: String,
    onDismiss: () -> Unit,
    dismissLabel: String = stringResource(R.string.cancel),
    confirmLabel: String = "",
    confirmEnabled: Boolean = true,
    onConfirm: (() -> Unit)? = null,
    maxBodyHeight: Dp = 560.dp,
    spacing: Dp = 16.dp,
    /**
     * Something that belongs in the header row when the window is turned,
     * between the title and the buttons - a unit's bypass. Upright there is
     * no header row, and the window draws it in its body instead.
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
    body: @Composable () -> Unit,
) {
    val c = com.rm.acidulous.ui.theme.Acid.colors
    // **The cap is the smaller of what was asked for and what there is.**
    //
    // `maxBodyHeight` is a statement about how tall a window should be
    // allowed to get, and five hundred and sixty dp is a fair answer on a
    // phone held upright, where there are eight hundred and fifty. Turned,
    // there are three hundred and ninety-three - so the cap never bound, the
    // body took its full natural height, and the window came out taller than
    // the screen with its Done button below the bottom edge. The body
    // scrolls, so nothing was unreachable; the button that closes it was.
    //
    // The pieces are counted rather than guessed at because they are known
    // here: whether there is a chip row, and whether there is a footer, are
    // both arguments to this function.
    val windowHeight = with(androidx.compose.ui.platform.LocalDensity.current) {
        androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp()
    }
    // **The card is capped, and the body is what gives.** Counting the chrome
    // and subtracting it was the first answer and it was arithmetic about a
    // layout rather than the layout itself - it came out short by a footer,
    // which is the one piece that had to survive. So the *card* is told how
    // tall it may be, the title, the chips and the footer take what they
    // need, and the body takes what is left by weight. Nothing has to be
    // counted, and nothing can be pushed off the bottom.
    // **And the floor may not be taller than the window either.** Two hundred
    // dp is a fair smallest useful window, but a floor that outranks the cap
    // re-creates the very fault the cap was written for: below about 224 dp -
    // a turned phone at the largest interface scale - the card was allowed to
    // be taller than the screen again, with its Done button off the bottom.
    val cardMax = (windowHeight - DialogEdgeH).coerceAtLeast(minOf(200.dp, windowHeight))
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        ScaledWindow {
            // **Turned, the header is one row**: the title, the tabs and the
            // buttons across the top, and the body the rest of the height.
            // Upright the three are stacked and the footer is its own row;
            // turned that cost a window of four hundred dp over half its
            // height, and every window of cards scrolled by its second card.
            // Square too: a square phone is as short as a turned one.
            val wide = screenShape() != ScreenShape.Tall
            // **Side by side only where two cards fit side by side.** A square
            // phone is short like a turned one but narrow like an upright one,
            // and cards laid in rows there could never pair: each stood alone
            // and a busy one was squeezed rather than wrapped. Below this
            // width the cards stack and wrap as they do upright, and the
            // tabs, which would not fit beside the title and the buttons,
            // have a row of their own.
            val roomy = with(androidx.compose.ui.platform.LocalDensity.current) {
                androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toDp()
            } >= WideCardsMinW
            androidx.compose.material3.Surface(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp).widthIn(max = if (wide) 1100.dp else 720.dp)
                    .heightIn(max = cardMax),
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
                Column(Modifier.padding(horizontal = 16.dp, vertical = if (wide) 10.dp else 14.dp)) {
                    if (wide) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                title, color = c.text, fontSize = 20.sp, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 280.dp).padding(end = 12.dp),
                            )
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                // The unit's own row comes up here only when its
                                // body is laid wide and leaves it out - see SlotRow.
                                if (roomy) { if (chips != null) chips() else wideHeader?.invoke() }
                            }
                            if (footer) {
                                Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) { Buttons() }
                            }
                        }
                        if (chips != null && !roomy) Box(Modifier.padding(top = 6.dp)) { chips() }
                        Box(Modifier.padding(top = 8.dp))
                    } else {
                        Text(title, color = c.text, fontSize = 20.sp)
                        if (chips != null) {
                            Box(Modifier.padding(top = 12.dp, bottom = 6.dp)) { chips() }
                        } else {
                            Box(Modifier.padding(top = 10.dp))
                        }
                    }
                    Box(
                        // The position bar is drawn on the outer edge of this
                        // box, so the content is inset to leave it a gutter -
                        // without it a chip that reaches the full width has the
                        // bar drawn straight through it.
                        // `fill = false` so it is only ever *smaller* than its
                        // content, never stretched to fill a tall window: a
                        // short page in a tabbed dialog must not push the button
                        // to the bottom of the screen.
                        Modifier.weight(1f, fill = false)
                            .heightIn(max = maxBodyHeight)
                            .verticalScrollWithBar(rememberScrollState())
                            .padding(end = 10.dp),
                    ) {
                        androidx.compose.runtime.CompositionLocalProvider(LocalDialogWide provides (wide && roomy)) { body() }
                    }
                    // An empty dismiss label and no action means no footer at
                    // all - for a window that is reporting rather than asking,
                    // and that must not be dismissed while it works.
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

/**
 * How much of the screen's height a window leaves alone.
 *
 * Twenty-four dp, so the card reads as a card rather than as the screen: an
 * edge flush against the top and bottom of the display is how a dialog stops
 * looking like one.
 */
private val DialogEdgeH = 24.dp

/** The narrowest window that lays a window's cards side by side; see [DialogShell]. */
private val WideCardsMinW = 600.dp

/** Measures every page, shows one, and takes the height of the biggest. */
@Composable
private fun TallestOf(selected: Int, pages: List<@Composable () -> Unit>, spacing: Dp) {
    androidx.compose.ui.layout.SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val loose = constraints.copy(minHeight = 0)
        // Each page is wrapped in a column *here* rather than being trusted
        // to be one box. A page that emits three sections as siblings would
        // otherwise be measured as three children and placed at the same
        // spot, one on top of another - which is exactly what happened the
        // first time this was written.
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
 * and one line saying what the current choice actually *means*.
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
 * One of a set: filled when it is the one in force.
 *
 * [enabled] is for the chips that are *actions* rather than choices - pasting
 * with nothing on the clipboard, clearing a clip with nothing in it - where
 * the honest thing is to show the control and say it has nothing to do, rather
 * than to hide it and leave somebody hunting for where it went.
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
 * on the right saying what tapping it would do. `on` fills it, the way a
 * chosen machine is filled in the picker.
 */
@Composable
internal fun DialogRow(
    mark: String,
    name: String,
    under: String = "",
    trailing: String = "",
    on: Boolean = false,
    monoUnder: Boolean = false,
    /** A second action at the end of the row - deleting, usually. */
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
            val said = stringResource(R.string.a11y_delete, name)
            Text(
                "✕", color = c.red, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onRemove)
                    .button(said, onClick = onRemove)
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            )
        }
    }
}

/** A line of numbers: the readouts that say whether something is working. */
@Composable
internal fun Readout(text: String, good: Boolean = false) {
    val c = com.rm.acidulous.ui.theme.Acid.colors
    Text(text, color = if (good) c.teal else c.textDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
}
