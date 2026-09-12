package com.rm.acidulous.ui

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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.model.Clip
import com.rm.acidulous.model.PPQN
import com.rm.acidulous.model.PlayMode
import com.rm.acidulous.model.Scene
import com.rm.acidulous.model.SceneTempo
import com.rm.acidulous.model.Signature

/**
 * The reference sequencer's "4/4 × 1" chip, expanded: name, signature, repeat, tempo, fades.
 *
 * Rebuilt in the same vocabulary as every other window here. It used to be
 * a Material `AlertDialog` full of switches and outlined buttons, and its
 * signatures sat in two fixed `Row`s - which do not wrap, so 2/4 and 5/4
 * were crushed to slivers and 12/8 came out stacked vertically as "1 2 / 8".
 * A `FlowRow` of chips fits them at any width.
 */
@Composable
fun SceneSettingsDialog(scene: Scene, songSignature: Signature, onDismiss: () -> Unit, onConfirm: (Scene) -> Unit) {
    var name by remember { mutableStateOf(scene.name) }
    var signature by remember { mutableStateOf(scene.signature) } // null = song default
    var repeat by remember { mutableStateOf(scene.repeat) }
    var ownTempo by remember { mutableStateOf(scene.tempo != null) }
    var bpm by remember { mutableStateOf(scene.tempo?.bpm ?: 120f) }
    var smooth by remember { mutableStateOf(scene.tempo?.smooth ?: false) }
    var fadeIn by remember { mutableStateOf(scene.fadeIn) }
    var fadeOut by remember { mutableStateOf(scene.fadeOut) }

    PlainDialog(
        title = "Scene",
        onDismiss = onDismiss,
        confirmLabel = "OK",
        onConfirm = {
            onConfirm(
                scene.copy(
                    name = name.ifBlank { scene.name },
                    signature = signature,
                    repeat = repeat,
                    tempo = if (ownTempo) SceneTempo(bpm = bpm, smooth = smooth) else null,
                    fadeIn = fadeIn,
                    fadeOut = fadeOut,
                ),
            )
        },
    ) {
        ListSection("name") {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Nine of these, and as chips they wrapped onto a second row and
        // pushed everything under them down the screen. Ordered, discrete
        // and too many for a row: a slider with a stop on each.
        val sigIndex = signature?.let { SIGNATURES.indexOf(it) + 1 } ?: 0
        SliderSection(
            "signature",
            if (sigIndex == 0) "song (${songSignature.beats}/${songSignature.unit})"
            else SIGNATURES[sigIndex - 1].let { "${it.beats}/${it.unit}" },
            "",
            sigIndex.toFloat(), 0f..SIGNATURES.size.toFloat(), SIGNATURES.size - 1,
        ) { v ->
            val i = v.toInt().coerceIn(0, SIGNATURES.size)
            signature = if (i == 0) null else SIGNATURES[i - 1]
        }

        SliderSection(
            "repeat", "$repeat", "",
            repeat.toFloat(), 1f..32f,
        ) { repeat = it.toInt().coerceIn(1, 32) }

        Section("tempo") {
            Choice("song", !ownTempo) { ownTempo = false }
            Choice("own", ownTempo) { ownTempo = true }
            if (ownTempo) {
                Choice("jump", !smooth) { smooth = false }
                Choice("glide", smooth) { smooth = true }
            }
        }
        if (ownTempo) {
            SliderSection("beats a minute", "%.0f".format(bpm), "", bpm, 40f..240f) { bpm = it }
        }

        Section("fades") {
            Choice("in", fadeIn) { fadeIn = !fadeIn }
            Choice("out", fadeOut) { fadeOut = !fadeOut }
        }
    }
}

/** The reference sequencer's "1 Bar" chip, expanded: bars, play mode, mute, grid. */
@Composable
fun ClipSettingsDialog(
    clip: Clip,
    onDismiss: () -> Unit,
    /** The song's tempo here, to say whether a freeze can still be used. */
    tempo: Float = 0f,
    /** Render this clip to audio, or throw the render away. Both dismiss. */
    onFreeze: () -> Unit = {},
    onThaw: () -> Unit = {},
    onConfirm: (Clip) -> Unit,
) {
    var bars by remember { mutableStateOf(clip.bars) }
    var mode by remember { mutableStateOf(clip.playMode) }
    var mute by remember { mutableStateOf(clip.mute) }
    var grid by remember { mutableStateOf(clip.grid) }

    PlainDialog(
        title = "Clip",
        onDismiss = onDismiss,
        confirmLabel = "OK",
        onConfirm = { onConfirm(clip.copy(bars = bars, playMode = mode, mute = mute, grid = grid)) },
    ) {
        SliderSection("bars", "$bars", "", bars.toFloat(), 1f..16f, 14) { bars = it.toInt().coerceIn(1, 16) }

        Section("plays") {
            Choice("loop", mode == PlayMode.Loop) { mode = PlayMode.Loop }
            Choice("once", mode == PlayMode.OneShot) { mode = PlayMode.OneShot }
            Choice("mute", mute) { mute = !mute }
        }

        Section("grid") {
            for ((label, ticks) in GRIDS) {
                Choice(label, grid == ticks) { grid = ticks }
            }
        }

        // Freeze is an action rather than a setting, so it does its own
        // thing and closes; everything above waits for OK.
        val frozen = clip.frozen
        if (frozen != null) {
            val stale = tempo > 0f && kotlin.math.abs(frozen.bpm - tempo) >= 0.01f
            ListSection(
                "audio",
                "%.1f s of audio at %.0f bpm, peak %.2f.".format(frozen.frames / 48000f, frozen.bpm, frozen.peak) +
                    if (stale) " The song is at %.0f now, so the machine is playing instead - freeze it again.".format(tempo) else "",
            ) {
                Choice("thaw", false, onPick = onThaw)
            }
        } else if (clip.notes.isNotEmpty()) {
            ListSection("audio", "Freezing renders this clip down: the track stops running its machine.") {
                Choice("freeze", false, onPick = onFreeze)
            }
        }
    }
}

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
        title = "Machine",
        selected = tab,
        dismissLabel = "Cancel",
        onDismiss = onDismiss,
        chips = { SectionChipsStyled(groups.map { chipLabel(it.label) }, tab) { tab = it } },
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
                                com.rm.acidulous.model.MachineUi.describe(type),
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
            Text("Nothing here yet.", color = com.rm.acidulous.ui.theme.Acid.colors.textDim, fontSize = 12.sp)
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
        confirmLabel = "OK",
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

private val CLICK_VOICES = listOf("blip", "stick", "cowbell")
private val CLICK_DIVISIONS = listOf("bar", "beat", "1/8", "1/16", "1/8T")
private val TEMPO_TABS = listOf("tempo", "click")

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
fun TempoDialog(tempo: Float, onDismiss: () -> Unit, onConfirm: (Float) -> Unit) {
    var bpm by remember { mutableStateOf(tempo) }
    var tab by rememberSaveable { mutableStateOf(0) }
    TabbedDialog(
        title = "Tempo",
        selected = tab,
        onDismiss = onDismiss,
        dismissLabel = "Cancel",
        confirmLabel = "OK",
        onConfirm = { onConfirm(bpm) },
        spacing = 16.dp,
        chips = { SectionChips(TEMPO_TABS, tab) { tab = it } },
        pages = listOf({ TempoPage(bpm) { bpm = it } }, { ClickPage() }),
    )
}

@Composable
private fun TempoPage(bpm: Float, onBpm: (Float) -> Unit) {
    SliderSection(
        "beats a minute", "%.0f".format(bpm), "",
        bpm, 40f..240f,
    ) { onBpm(it) }
    // The tempos people actually count in, so an exact 128 is one tap
    // rather than a careful drag.
    Section("") {
        for (preset in listOf(80f, 90f, 100f, 110f, 120f, 128f, 140f, 174f)) {
            Choice("%.0f".format(preset), kotlin.math.abs(bpm - preset) < 0.5f) { onBpm(preset) }
        }
    }
}

@Composable
private fun ClickPage() {
    Section(
        "sound",
        when (UiPrefs.clickVoice) {
            1 -> "Filtered noise. It sits away from anything tuned, so it stays audible over a busy mix without being loud."
            2 -> "The 808's two detuned squares. For when the drums are loud enough to hide the other two."
            else -> "A short decaying sine, higher on the downbeat. The plain one."
        },
    ) {
        CLICK_VOICES.forEachIndexed { i, name ->
            Choice(name, UiPrefs.clickVoice == i) { UiPrefs.chooseClickVoice(i) }
        }
    }
    Section("ticks on") {
        CLICK_DIVISIONS.forEachIndexed { i, name ->
            Choice(name, UiPrefs.clickDivision == i) { UiPrefs.chooseClickDivision(i) }
        }
    }
    Section(
        "sounds",
        when (UiPrefs.clickWhen) {
            1 -> "Only while the transport is armed."
            2 -> "Never during the song - only to count you in."
            else -> ""
        },
    ) {
        Choice("always", UiPrefs.clickWhen == 0) { UiPrefs.chooseClickWhen(0) }
        Choice("recording", UiPrefs.clickWhen == 1) { UiPrefs.chooseClickWhen(1) }
        Choice("count-in only", UiPrefs.clickWhen == 2) { UiPrefs.chooseClickWhen(2) }
    }
    SliderSection(
        "level", "%.0f%%".format(UiPrefs.clickVolume * 100f),
        "The master gives up this much headroom while the click is on.",
        UiPrefs.clickVolume, 0f..1f,
    ) { UiPrefs.chooseClickVolume(it) }
    Section("count-in", "Bars of clicks before anything moves - the song's own, so 7/8 counts seven.") {
        for (bars in 0..4) {
            Choice(if (bars == 0) "none" else "$bars", UiPrefs.countInBars == bars) {
                UiPrefs.chooseCountInBars(bars)
            }
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
    dismissLabel: String = "Done",
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
    dismissLabel: String = "Cancel",
    confirmLabel: String = "",
    confirmEnabled: Boolean = true,
    onConfirm: (() -> Unit)? = null,
    maxBodyHeight: Dp = 560.dp,
    spacing: Dp = 16.dp,
    content: @Composable () -> Unit,
) {
    DialogShell(
        title, onDismiss, dismissLabel, maxBodyHeight,
        confirmLabel = confirmLabel, confirmEnabled = confirmEnabled, onConfirm = onConfirm, chips = null,
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
    body: @Composable () -> Unit,
) {
    val c = com.rm.acidulous.ui.theme.Acid.colors
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.material3.Surface(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp).widthIn(max = 720.dp),
            shape = RoundedCornerShape(16.dp),
            color = c.card,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(title, color = c.text, fontSize = 20.sp)
                if (chips != null) {
                    Box(Modifier.padding(top = 12.dp, bottom = 6.dp)) { chips() }
                } else {
                    Box(Modifier.padding(top = 10.dp))
                }
                Box(
                    // The position bar is drawn on the outer edge of this
                    // box, so the content is inset to leave it a gutter -
                    // without it a chip that reaches the full width has the
                    // bar drawn straight through it.
                    Modifier.heightIn(max = maxBodyHeight)
                        .verticalScrollWithBar(rememberScrollState())
                        .padding(end = 10.dp),
                ) {
                    body()
                }
                // An empty dismiss label and no action means no footer at
                // all - for a window that is reporting rather than asking,
                // and that must not be dismissed while it works.
                if (dismissLabel.isNotEmpty() || onConfirm != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (dismissLabel.isNotEmpty()) {
                            TextButton(onClick = onDismiss) { Text(dismissLabel) }
                        }
                        if (onConfirm != null) {
                            Button(onClick = onConfirm, enabled = confirmEnabled) { Text(confirmLabel) }
                        }
                    }
                }
            }
        }
    }
}

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

/** One of a set: filled when it is the one in force. */
@Composable
internal fun Choice(label: String, on: Boolean, modifier: Modifier = Modifier, onPick: () -> Unit) {
    val c = com.rm.acidulous.ui.theme.Acid.colors
    Box(
        modifier.clip(RoundedCornerShape(4.dp))
            .background(if (on) c.accent else c.control)
            .clickable(onClick = onPick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (on) c.onAccent else c.textMid, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
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
        Text(mark, color = c.accent, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
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
            Text(
                "✕", color = c.red, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onRemove)
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
