package com.rm.acidulous.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.AppState
import com.rm.acidulous.BottomPickers
import com.rm.acidulous.PresetChoice
import com.rm.acidulous.rememberOpenDocument
import com.rm.acidulous.util.format
import com.rm.acidulous.rememberOpenDocuments
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.model.withImpulseChanged
import com.rm.acidulous.model.withSetting
import com.rm.acidulous.ScaledTheme
import com.rm.acidulous.Screen
import com.rm.acidulous.model.MachineKind
import com.rm.acidulous.model.MachineUi
import com.rm.acidulous.res.*
import com.rm.acidulous.ui.theme.Acid

/**
 * Everything the second screen draws, with the same scale, theme and
 * language as the main one. It works on [AppState], so it plays the track
 * the main screen plays.
 */
@Composable
fun SecondScreenRoot() {
    // Set before anything here makes a file picker, so they know they are on the second screen.
    CompositionLocalProvider(LocalSecondScreen provides true) { SecondScreenContent() }
}

@Composable
private fun SecondScreenContent() {
    val song by AppState.song
    // The bottom's own pickers, so the system's file window opens on this display.
    val sample = rememberOpenDocument { AppState.pickerResults?.sample?.invoke(it) }
    val kit = rememberOpenDocuments { AppState.pickerResults?.kit?.invoke(it) }
    val soundFont = rememberOpenDocument { AppState.pickerResults?.soundFont?.invoke(it) }
    val zones = rememberOpenDocuments { AppState.pickerResults?.zones?.invoke(it) }
    DisposableEffect(Unit) {
        val mine = BottomPickers(sample, kit, soundFont, zones)
        AppState.bottomPickers = mine
        onDispose { if (AppState.bottomPickers === mine) AppState.bottomPickers = null }
    }
    // Windows asked for here go away with the screen.
    DisposableEffect(Unit) {
        onDispose {
            AppState.sampleEdit.value = AppState.sampleEdit.value?.takeIf { !it.third }
            AppState.presetChoice.value = AppState.presetChoice.value?.takeIf { !it.bottom }
            AppState.patchEdit.value = null
        }
    }
    ScaledTheme {
        CompositionLocalProvider(
            LocalSongMappings provides song.mappings,
            LocalRoomChanged provides { old, new -> AppState.editor.editSong { it.withImpulseChanged(old, new) } },
            LocalSecondScreen provides true,
        ) {
            SecondFocus()
            Box(
                Modifier.fillMaxSize().background(Acid.colors.bg)
                    .onFocusChanged { KeyHub.secondAnyFocused = it.hasFocus },
            ) {
                SecondScreen()
                BottomWindows()
            }
        }
    }
}

/** Where arrows start on this screen when the keys are moved here and nothing has focus. */
@Composable
private fun SecondFocus() {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
    DisposableEffect(focusManager) {
        val mine: () -> Boolean = {
            // Keyboard mode, so the focus ring shows after a touch.
            if (inputMode.inputMode != androidx.compose.ui.input.InputMode.Keyboard) {
                inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
            }
            focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Next)
        }
        KeyHub.secondFocusFirst = mine
        onDispose {
            if (KeyHub.secondFocusFirst === mine) KeyHub.secondFocusFirst = { false }
            KeyHub.secondAnyFocused = false
        }
    }
}

/** The windows a bottom panel opened: the sample editor, the SoundFont's presets and Nexus's graph. */
@Composable
private fun BottomWindows() {
    val song by AppState.song
    val editor = AppState.editor
    var sample by AppState.sampleEdit
    sample?.takeIf { it.third && it.first in song.tracks.indices }?.let { (track, pad) ->
        SampleDialog(song.tracks[track], track, pad, editor) { sample = null }
    }
    var preset by AppState.presetChoice
    preset?.takeIf { it.bottom }?.let { PresetChoiceWindow(it, editor) { preset = null } }
    var patch by AppState.patchEdit
    patch?.takeIf { it.first in song.tracks.indices }?.let { (track, _) ->
        PatchScreen(song.tracks[track], track, editor, { patch = null }, Modifier.fillMaxSize().background(Acid.colors.bg))
    }
}

/** Which SoundFont preset to play, as a list. */
@Composable
fun PresetChoiceWindow(choice: PresetChoice, editor: SongEditor, onDone: () -> Unit) {
    fun label(line: String): String {
        val f = line.split('|')
        return if (f.size >= 3) "%03d:%03d  %s".format(f[0].toIntOrNull() ?: 0, f[1].toIntOrNull() ?: 0, f[2]) else line
    }
    PickerDialog(
        title = stringResource(Res.string.app_soundfont_preset),
        options = choice.presets.map { label(it) },
        onDismiss = onDone,
    ) { chosen ->
        val index = choice.presets.indexOfFirst { label(it) == chosen }
        if (index >= 0) editor.edit(choice.track) { t -> t.withSetting("sf2preset", index.toString()) }
        onDone()
    }
}

/**
 * What goes with the main screen. Under the song screen: the mixer or the
 * effects of the track being played. Under a track's editor: its keys or pads,
 * or its machine's panel. The page last chosen for each is kept in [UiPrefs].
 * Windows opened here open on this screen.
 */
@Composable
private fun SecondScreen() {
    val song by AppState.song
    val hooks by AppState.hooks
    val top by AppState.screen
    val rack by AppState.midiTrack
    // The editor's track, or the one notes go to.
    val (trackIndex, sceneId) = when (val s = top) {
        is Screen.Edit -> s.track to s.sceneId
        is Screen.Patch -> s.track to s.sceneId
        Screen.Main -> rack to ""
    }
    val home = top is Screen.Main
    // Without App's hooks there's nothing but the keys to show.
    val page = if (hooks == null) 0 else if (home) UiPrefs.secondPageHome else UiPrefs.secondPageTrack
    // A song with no tracks still has a mixer.
    // A stale index falls back to the first track, and its index goes on with it.
    val index = if (trackIndex in song.tracks.indices) trackIndex else 0
    val track = song.tracks.getOrNull(index) ?: if (home && page == 0) null else return
    var selectedPad by AppState.selectedPad(track?.id ?: "")
    // The fx page: 0 the sends, 1 the master's inserts, 2 a track's. A tap on the track's chip
    // again goes to the next track.
    var fxChip by remember { mutableStateOf(0) }
    var fxTrack by remember { mutableStateOf("") }
    val fxIndex = song.tracks.indexOfFirst { it.id == fxTrack }.takeIf { it >= 0 } ?: index
    val c = Acid.colors
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(
            Modifier.fillMaxWidth().height(28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (home && page == 0 || track == null) song.name else track.name,
                color = c.textHi, fontSize = 15.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (hooks != null) {
                val names = if (home) listOf(stringResource(Res.string.second_mix), stringResource(Res.string.edit_fx))
                else listOf(
                    stringResource(if (track != null && MachineUi.kindOf(track.machine) == MachineKind.Drums) Res.string.edit_pads else Res.string.edit_keys),
                    stringResource(Res.string.second_panel),
                )
                names.forEachIndexed { i, name -> PageTab(name, page == i) { UiPrefs.chooseSecondPage(home, i) } }
            }
        }
        val body = Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp)
        val h = hooks
        when {
            track == null || h != null && home && page == 0 -> if (h != null) MixerPanel(
                song, AppState.editor, h.rackPeaks, h.masterPeak, h.clickOn(), h.onClick, body, fill = true,
            )
            h != null && home -> Column(body) {
                FxChips(
                    fxChip, song.tracks.getOrNull(fxIndex)?.name.orEmpty(),
                    onChip = { fxChip = it },
                    onTrack = {
                        if (fxChip == 2 && song.tracks.size > 1) fxTrack = song.tracks[(fxIndex + 1) % song.tracks.size].id
                        fxChip = 2
                    },
                )
                SecondFxPage(
                    fxChip, song, song.tracks.getOrNull(fxIndex), fxIndex, AppState.editor,
                    Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp),
                )
            }
            h != null && page == 1 -> Box(body) {
                CompositionLocalProvider(LocalPanelGrid provides true) {
                    TrackMachinePanel(
                        track, index, AppState.editor, remember(h.panel) { h.panel.forSecondScreen() }, selectedPad, sceneId,
                        foldable = false, modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            else -> PlayArea(song, track, index, selectedPad, { selectedPad = it }, body)
        }
    }
}

/** The editor's playing strip with the keys or pads filling the room. */
@Composable
private fun PlayArea(
    song: com.rm.acidulous.model.Song, track: com.rm.acidulous.model.Track, rack: Int,
    selectedPad: Int, onSelectPad: (Int) -> Unit, modifier: Modifier,
) {
    var modifierSlot by remember(rack) { mutableStateOf(-1) }
    var scaleDialog by remember(rack) { mutableStateOf(false) }
    BoxWithConstraints(modifier) {
        PlayStrip(
            song, track, rack, AppState.editor, maxHeight,
            selectedPad, onSelectPad,
            onOpenModifier = { modifierSlot = it },
            onOpenScale = { scaleDialog = true },
            foldable = false,
            roomy = true,
        )
    }
    if (modifierSlot >= 0) {
        SlotDialog(
            SlotKind.Modifiers, track, rack, modifierSlot, AppState.editor,
            fixedType = if (modifierSlot == EV_CHORD) "Chord" else "Arp",
        ) { modifierSlot = -1 }
    }
    if (scaleDialog) {
        ScaleDialog(
            current = scaleSettingOf(track),
            onDismiss = { scaleDialog = false },
            onApply = { applyScaleTo(AppState.editor, rack, it); scaleDialog = false },
        )
    }
}

/** The fx page's chips: the sends, the master, and a track by name. */
@Composable
private fun FxChips(chip: Int, trackName: String, onChip: (Int) -> Unit, onTrack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(28.dp).padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PageTab(stringResource(Res.string.second_sends), chip == 0) { onChip(0) }
        PageTab(stringResource(Res.string.mixer_master), chip == 1) { onChip(1) }
        PageTab(trackName + if (chip == 2) " \u25B8" else "", chip == 2, onTrack)
    }
}

/** One of the header's two pages. */
@Composable
private fun PageTab(label: String, on: Boolean, onClick: () -> Unit) {
    val c = Acid.colors
    Box(
        Modifier.fillMaxHeight().clip(RoundedCornerShape(4.dp))
            .background(if (on) c.accent.copy(alpha = 0.25f) else c.raised)
            .clickable(onClick = onClick)
            .choice(label, on, tab = true)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (on) c.accent else c.textMid, fontSize = 13.sp, maxLines = 1) }
}
