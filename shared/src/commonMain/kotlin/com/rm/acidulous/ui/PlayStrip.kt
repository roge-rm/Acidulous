package com.rm.acidulous.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.AppState
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.model.MachineKind
import com.rm.acidulous.model.MachineUi
import com.rm.acidulous.model.Scales
import com.rm.acidulous.model.Song
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.model.Track
import com.rm.acidulous.model.modifierUnit
import com.rm.acidulous.model.withModifier
import com.rm.acidulous.model.withModifierBypass
import com.rm.acidulous.model.withModifierParam
import com.rm.acidulous.res.*
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.util.Math

// Each modifier has a fixed slot, left to right like the chips: chord builds
// the notes, scale corrects them, arp sequences the result.
internal const val EV_CHORD = 0
internal const val EV_SCALE = 1
internal const val EV_ARP = 2

/** The track's Scale modifier as the scale chip and its dialog see it. */
internal fun scaleSettingOf(track: Track): ScaleSetting {
    val ev = track.modifierAt(EV_SCALE)
    return ScaleSetting(
        on = ev.type == "Scale" && !ev.bypass,
        key = Math.round((ev.params["key"] ?: 0f) * 11f),
        scale = Math.round((ev.params["scale"] ?: 0f) * 32f),
        degree = (ev.params["mode"] ?: 0f) >= 0.5f,
        snap = Math.round((ev.params["snap"] ?: 0f) * 2f),
    )
}

/** Writes [s] into the track's Scale slot, filling it if it's empty. One edit. */
internal fun applyScaleTo(editor: SongEditor, trackIndex: Int, s: ScaleSetting) {
    val slot = EV_SCALE
    editor.edit(trackIndex) { t ->
        (if (t.modifierAt(slot).type == "Scale") t else t.withModifier(slot, "Scale"))
            .withModifierParam(slot, "key", s.key / 11f)
            .withModifierParam(slot, "scale", s.scale / 32f)
            .withModifierParam(slot, "mode", if (s.degree) 1f else 0f)
            .withModifierParam(slot, "snap", s.snap / 2f)
            .withModifierBypass(slot, !s.on)
    }
}

/**
 * The playing area: keys with the performance row and wheels, or the drum
 * pads. The editor puts it under the roll, the second screen fills itself
 * with it. Octave and mod wheel are per track in [AppState], so both screens
 * show the same; pressure and bend spring back, so each strip keeps its own.
 *
 * [height] is the whole strip's. [grip] is the drag that moves the divider
 * above it (the blank gaps in the performance row are the handle). [bar] is
 * the patch bar when it rides in the strip on a desktop. Without [foldable]
 * there's no fold mark and the strip never folds. [onOpenModifier] and
 * [onOpenScale] are a held chip, so the caller opens the window on its own
 * screen.
 *
 * With [roomy] (the second screen) keys keep a fixed size: two keyboards an
 * octave apart, or the note grid, chosen per track by a chip in the row.
 */
@Composable
fun PlayStrip(
    song: Song,
    track: Track,
    trackIndex: Int,
    editor: SongEditor,
    height: Dp,
    selectedPad: Int,
    onSelectPad: (Int) -> Unit,
    onOpenModifier: (Int) -> Unit,
    onOpenScale: () -> Unit,
    grip: Modifier = Modifier,
    foldable: Boolean = true,
    bar: (@Composable () -> Unit)? = null,
    onEmptyPad: (Int) -> Unit = {},
    roomy: Boolean = false,
) {
    val kind = MachineUi.kindOf(track.machine)
    val barInStrip = bar != null
    var octave by AppState.octave(track.id)
    var mod by AppState.modWheel(track.id)
    var pressure by remember(trackIndex) { mutableStateOf(0f) }
    var bend by remember(trackIndex) { mutableStateOf(0.5f) }
    val touchable = MachineUi.usesPerformance(track.machine.type)
    val keysFolded = foldable && UiPrefs.keysFolded
    val padsGrip = if (barInStrip) KeysStripH else PadsGrip
    // A tape has no instrument and no performance row, so the lanes get the
    // whole screen.
    if (kind == MachineKind.Audio) Unit
    else if (kind == MachineKind.Drums) Column(Modifier.fillMaxWidth().height(height)) {
        // The pads have no performance row, so the gap above them is the
        // handle.
        //
        // It has to be its own box, not padding on the pads. A pointerInput
        // after padding only covers the inside, so the drag would land on
        // the pads, which consume their own touches.
        //
        // It also holds the fold mark at the right-hand end, like the
        // keyboard's row.
        if (foldable || barInStrip) Box(
            Modifier.fillMaxWidth().height(padsGrip).then(grip),
            contentAlignment = Alignment.CenterEnd,
        ) {
            if (bar != null) Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                // The bar gives way to the fold mark, and a bare stretch
                // before the mark is always left to drag by.
                Box(Modifier.weight(1f, fill = false).padding(start = 6.dp)) { bar() }
                Spacer(Modifier.weight(1f).widthIn(min = PadsGrabMin))
                if (foldable) FoldMark(keysFolded, Modifier.fillMaxHeight()) { UiPrefs.foldKeys(!keysFolded) }
            } else FoldMark(keysFolded, Modifier.fillMaxHeight()) { UiPrefs.foldKeys(!keysFolded) }
        }
        if (!keysFolded) DrumPads(
            trackIndex, MachineUi.voicesOf(track.machine), selectedPad, onSelectPad,
            Modifier.fillMaxWidth().weight(1f),
            track.machine.type,
            onEmpty = onEmptyPad,
        )
    }
    else Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        // The whole row is the handle. Upright there's no blank space left
        // in it, so the drag is watched underneath the row and the controls
        // in it win where they are: the wheel and stepper consume at the
        // slop and cancel this, and a chip's tap detector never sees a
        // drag. See keysGrip.
        BoxWithConstraints(Modifier.fillMaxWidth().height(KeysStripH).then(grip)) {
        // The chip labels switch to glyphs when the row gets too narrow
        // (see PerfWideMin). At larger interface scales the chips at their
        // fixed widths squeezed the pressure wheel and octave stepper to
        // nothing. Glyphs give about 120 dp back to them.
        // The patch bar on a desktop takes its share first.
        val icons = maxWidth < PerfWideMin + (if (barInStrip) StripBarW else 0.dp) + (if (roomy) LayoutChipW else 0.dp) +
            (if (roomy && Scales.activeFor(song, track) != null) LayoutChipW else 0.dp)
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Equal weights either side so the scale chip sits on the row's
            // centre line. The chip is measured first as the one unweighted
            // child, so it only takes what its text needs.
            //
            // The wheel is weighted for position and capped for size
            // (PressureW), so it doesn't grow to fill a landscape row.
            Row(
                Modifier.weight(1f).fillMaxHeight().then(grip),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The machine and patch on a desktop, see barInStrip. It
                // gives way before the wheel does.
                if (bar != null) Box(Modifier.weight(1f, fill = false).padding(start = 6.dp), contentAlignment = Alignment.CenterStart) { bar() }
                if (touchable) TouchWheel(
                    value = pressure, accent = Acid.colors.pink, vertical = false,
                    // No label, the ridges and its place in the row say
                    // what it is.
                    springBackTo = 0f, label = null, said = stringResource(Res.string.a11y_pressure),
                    modifier = Modifier.widthIn(max = PressureW).fillMaxHeight(),
                ) { v -> pressure = v; NativeEngine.channelPressure(trackIndex, (v * 127f).toInt()) }
            }
            // The modifiers sit either side of the scale chip since they
            // all change notes between playing and hearing them. A tap
            // toggles one, holding opens it.
            ModifierChip(
                "Chord", EV_CHORD, track, trackIndex, editor,
                icon = if (icons) "≡" else null,
            ) { onOpenModifier(EV_CHORD) }
            ScaleChip(
                label = Scales.labelFor(track),
                onToggle = { applyScaleTo(editor, trackIndex, scaleSettingOf(track).let { it.copy(on = !it.on) }) },
                onOpen = onOpenScale,
                vertical = false,
                // A fixed width, not a minimum, since the label changes
                // from "scale" to "C Ionian (Major)" and would push the
                // other chips around.
                modifier = Modifier.width(if (icons) PerfIconW else 112.dp).fillMaxHeight(),
                icon = if (icons) "♯" else null,
            )
            ModifierChip(
                "Arp", EV_ARP, track, trackIndex, editor,
                icon = if (icons) "♫" else null,
            ) { onOpenModifier(EV_ARP) }
            // A fixed width (see OctaveW). The wheel at the other end is
            // the one that stretches. This puts the scale chip slightly off
            // centre, which is the lesser problem.
            Box(
                Modifier.width(OctaveW).fillMaxHeight().then(grip),
                contentAlignment = Alignment.CenterEnd,
            ) {
                OctaveStepper(octave, { octave = it }, Modifier.fillMaxHeight())
            }
            // The fold mark, last in the row.
            //
            // It has its own slot. Inside the octave's weighted box it had
            // no width and overflowed the screen margin upright.
            //
            // There's no balancing spacer at the start of the row, so the
            // scale chip is about 11 dp left of centre. A spacer would cost
            // the octave stepper its ▶ arrow.
            if (foldable) FoldMark(keysFolded, Modifier.fillMaxHeight()) { UiPrefs.foldKeys(!keysFolded) }
            if (roomy && Scales.activeFor(song, track) != null) {
                KeyChip(UiPrefs.gridAllNotes, Modifier.fillMaxHeight()) { UiPrefs.chooseGridAllNotes(it) }
            }
            if (roomy) LayoutChip(UiPrefs.usesGrid(track.id), Modifier.fillMaxHeight()) { UiPrefs.chooseGrid(track.id, it) }
        }
        }
        if (keysFolded) return@Column
        Row(
            // 30 dp is the performance row above. Clamp at 0 since a narrow
            // pane (landscape at the largest scale, or split screen) can
            // make this negative.
            Modifier.fillMaxWidth().height((height - 30.dp).coerceAtLeast(0.dp)).padding(top = 4.dp),
            // No spacing. The keyboard draws its own 3 dp either side and
            // takes touches there, so a finger just missing the outermost
            // key still plays it instead of grabbing the wheel. See
            // EdgeGrab in ui/PianoKeys.kt.
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            TouchWheel(
                value = mod, accent = Acid.colors.accent, vertical = true, label = null,
                said = stringResource(Res.string.a11y_mod_wheel),
                modifier = Modifier.width(26.dp).fillMaxHeight(),
            ) { v -> mod = v; NativeEngine.controlChange(trackIndex, 1, (v * 127f).toInt()) }
            val scale = Scales.activeFor(song, track)
            val root = Scales.rootFor(song, track)
            val spelling = Scales.spellingFor(song, track)
            val allNotes = roomy && UiPrefs.gridAllNotes
            if (!roomy) PianoKeys(
                trackIndex, scale, root, octave,
                Modifier.weight(1f).fillMaxHeight(),
                noteSpelling = spelling,
            )
            else if (UiPrefs.usesGrid(track.id)) NoteGrid(
                trackIndex, scale, root, octave,
                Modifier.weight(1f).fillMaxHeight().padding(horizontal = 3.dp),
                noteSpelling = spelling, allNotes = UiPrefs.gridAllNotes,
            )
            else Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // The upper keyboard an octave above the lower, which the stepper sets.
                PianoKeys(
                    trackIndex, scale, root, octave + 1,
                    Modifier.fillMaxWidth().weight(1f), noteSpelling = spelling, keyWidth = RoomyKeyW,
                    allNotes = allNotes,
                )
                PianoKeys(
                    trackIndex, scale, root, octave,
                    Modifier.fillMaxWidth().weight(1f), noteSpelling = spelling, keyWidth = RoomyKeyW,
                    allNotes = allNotes,
                )
            }
            // Bend springs back, so it's the wheel you can let go of
            // quickly.
            TouchWheel(
                value = bend, accent = Acid.colors.teal, vertical = true,
                springBackTo = 0.5f, centreMark = true, label = null,
                said = stringResource(Res.string.a11y_bend_wheel),
                state = kotlin.math.round((bend * 2f - 1f) * 100f).toInt().let { amount ->
                    when {
                        kotlin.math.abs(amount) < 2 -> stringResource(Res.string.a11y_centre)
                        amount > 0 -> stringResource(Res.string.a11y_bend_up, amount)
                        else -> stringResource(Res.string.a11y_bend_down, -amount)
                    }
                },
                modifier = Modifier.width(26.dp).fillMaxHeight(),
            ) { v ->
                bend = v
                val value14 = ((v * 2f - 1f) * 8192f + 8192f).toInt().coerceIn(0, 16383)
                NativeEngine.midiEvent(trackIndex, 0xE0, value14 and 0x7f, (value14 shr 7) and 0x7f)
            }
        }
    }
}

private val FoldMarkW = 22.dp

/** A key's width on the second screen's keyboards. */
private val RoomyKeyW = 52.dp
/** The keys/grid chip, two words side by side. */
private val LayoutChipW = 100.dp

/** Keys or the note grid, for the second screen. */
@Composable
private fun LayoutChip(grid: Boolean, modifier: Modifier = Modifier, onGrid: (Boolean) -> Unit) {
    Row(modifier.width(LayoutChipW), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        LayoutHalf(stringResource(Res.string.edit_keys), !grid, Modifier.weight(1f)) { onGrid(false) }
        LayoutHalf(stringResource(Res.string.second_grid), grid, Modifier.weight(1f)) { onGrid(true) }
    }
}

/** The grid's scale notes only, or every note. */
@Composable
private fun KeyChip(all: Boolean, modifier: Modifier = Modifier, onAll: (Boolean) -> Unit) {
    Row(modifier.width(LayoutChipW), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        LayoutHalf(stringResource(Res.string.second_in_key), !all, Modifier.weight(1f)) { onAll(false) }
        LayoutHalf(stringResource(Res.string.second_all_notes), all, Modifier.weight(1f)) { onAll(true) }
    }
}

@Composable
private fun LayoutHalf(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Acid.colors
    Box(
        modifier.fillMaxHeight().clip(RoundedCornerShape(4.dp))
            .background(if (on) c.accentDim else c.card)
            .clickable(onClick = onClick)
            .choice(label, on, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, color = if (on) c.accent else c.textDim, fontSize = 9.sp,
            fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false,
        )
    }
}

/**
 * Width of a performance row chip when it shows a glyph. An anchor's width,
 * same as the footer, sized for a finger.
 */
private val PerfIconW = 44.dp

/**
 * The narrowest row that can still show the words "chord", "scale" and "arp".
 *
 * The three chips at 52, 112 and 52, the fold mark, the five gaps, the octave
 * stepper's width, and enough for the pressure wheel. A 393 dp phone just fits
 * at 1.0 and doesn't at any larger scale. Below this the chips show glyphs.
 */
private val PerfWideMin = 52.dp + 112.dp + 52.dp + FoldMarkW + 20.dp + OctaveW + 32.dp

/**
 * The blank above the drum pads, which is their drag handle. 18 dp is enough to
 * catch without looking like a band. The keyboard uses the gaps in its
 * performance row instead.
 */
internal val PadsGrip = 18.dp
/** The keyboard's strip of chips, and the pads' grip when it holds the patch. */
internal val KeysStripH = 26.dp
/** About what the patch bar takes in the strip with a typical name, see [PerfWideMin]. */
private val StripBarW = 300.dp
/** The bare part of the pads' grip left to drag by when it holds the patch bar. */
private val PadsGrabMin = 48.dp

/**
 * The widest the pressure wheel gets, in either orientation. It's rolled with
 * one thumb, and past this the extra width isn't reachable anyway.
 */
private val PressureW = 88.dp

/**
 * The mark that folds the instrument away and brings it back. The same as the
 * note lane's and automation strip's: an 11 sp chevron, ▾ to put it away and
 * ▴ to bring it back. It points down because the keyboard is on the bottom
 * edge.
 */
@Composable
private fun FoldMark(folded: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.width(FoldMarkW).clickable(onClick = onClick)
            .button(stringResource(if (folded) Res.string.a11y_unfold_keys else Res.string.a11y_fold_keys)),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (folded) "▴" else "▾", color = Acid.colors.textMid, fontSize = 11.sp)
    }
}

/**
 * One modifier, as a chip.
 *
 * There are three modifiers and three chips, so nothing is chosen from a list:
 * chord on the left, scale in the middle, arp on the right, each on its own
 * slot. A tap toggles it, a long press opens it.
 *
 * Toggling is a parameter: bypass is a pseudo-parameter on the modifier unit,
 * so a tap saves, undoes and automates like anything else, and goes straight to
 * the running modifier as well as the document. Note the field is bypass, so
 * lit means bypass == false. An empty slot is filled on the first tap.
 */
@Composable
private fun ModifierChip(
    type: String,
    slot: Int,
    track: Track,
    trackIndex: Int,
    editor: SongEditor,
    /** A glyph instead of the word, when the row is too narrow. */
    icon: String? = null,
    onOpen: () -> Unit,
) {
    val ev = track.modifierAt(slot)
    val loaded = ev.type == type
    SlotChip(
        text = icon ?: type.lowercase(),
        said = panelWord(type.lowercase()),
        on = loaded && !ev.bypass,
        onToggle = {
            if (!loaded) {
                // Nothing there yet, so the first tap adds it, switched on.
                editor.edit(trackIndex) { t -> t.withModifier(slot, type).withModifierBypass(slot, false) }
                NativeEngine.setParam(trackIndex, modifierUnit(slot), "bypass", 0f, record = true)
            } else {
                val bypass = !ev.bypass
                editor.edit(trackIndex) { t -> t.withModifierBypass(slot, bypass) }
                NativeEngine.setParam(trackIndex, modifierUnit(slot), "bypass", if (bypass) 1f else 0f, record = true)
            }
        },
        onOpen = {
            // Hold also fills an empty slot so there's something to open, but
            // bypassed, since holding means "let me look", not "turn it on".
            if (!loaded) {
                editor.edit(trackIndex) { t -> t.withModifier(slot, type).withModifierBypass(slot, true) }
            }
            onOpen()
        },
        vertical = false,
        icon = icon != null,
        modifier = (if (icon != null) Modifier.width(PerfIconW) else Modifier.widthIn(min = 52.dp))
            .fillMaxHeight(),
    )
}
