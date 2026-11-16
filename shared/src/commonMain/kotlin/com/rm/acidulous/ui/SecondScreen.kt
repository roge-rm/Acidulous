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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.AppState
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
    ScaledTheme {
        Box(Modifier.fillMaxSize().background(Acid.colors.bg)) { SecondScreen() }
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
    var selectedPad by remember(index) { mutableStateOf(0) }
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
                song, AppState.editor, h.rackPeaks, h.masterPeak, h.clickOn(), h.onClick, body,
            )
            h != null && home -> SlotsPanel(
                SlotKind.Effects, track, index, AppState.editor, body, maxHeight = 2000.dp,
            )
            h != null && page == 1 -> Box(body) {
                TrackMachinePanel(
                    track, index, AppState.editor, h.panel, selectedPad, sceneId,
                    foldable = false, modifier = Modifier.fillMaxSize(),
                )
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
