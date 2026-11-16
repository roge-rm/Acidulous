package com.rm.acidulous.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.rm.acidulous.AppState
import com.rm.acidulous.model.Patch
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.model.Track
import com.rm.acidulous.model.withSetting
import com.rm.acidulous.util.format

/**
 * What a machine panel asks of the app around it, by track index: patches,
 * imports and the windows and screens it opens. Built once by App and used by
 * the editor and the second screen alike.
 */
class PanelActions(
    val patchNames: (track: Int) -> List<String>,
    /** The name, the note range the keyboard was showing, which a saved patch remembers, and whether the inserts go too. */
    val savePatch: (track: Int, name: String, low: Int, high: Int, effects: Boolean) -> Unit,
    val loadPatch: (track: Int, name: String) -> Patch?,
    val factoryPatches: (track: Int) -> List<Patch>,
    val userPatchNames: (track: Int) -> List<String>,
    val deletePatch: (track: Int, name: String) -> Unit,
    val importSample: (track: Int, pad: Int) -> Unit,
    val importKit: (track: Int, pad: Int) -> Unit,
    val importSlice: (track: Int) -> Unit,
    /** One pad's sample, on its own page with a picture of it. */
    val openSample: (track: Int, pad: Int) -> Unit,
    /** Pollen's single sample, which is keyed by name instead of by pad. */
    val importOneSample: (track: Int) -> Unit,
    val importSoundFont: (track: Int) -> Unit,
    val pickPreset: (track: Int) -> Unit,
    val importZoneSamples: (track: Int) -> Unit,
    /** Nexus's graph, on a screen of its own. */
    val openPatch: (track: Int, sceneId: String) -> Unit,
)

/**
 * A track's machine panel wired to [actions]: the one the editor shows, and
 * the second screen's. The keyboard's octave is the track's in [AppState].
 */
@Composable
fun TrackMachinePanel(
    track: Track,
    trackIndex: Int,
    editor: SongEditor,
    actions: PanelActions,
    selectedPad: Int,
    sceneId: String,
    bar: Boolean = true,
    body: Boolean = true,
    vertical: Boolean = false,
    barInRow: Boolean = false,
    /** False where the panel has the screen to itself and has no fold. */
    foldable: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var octave by AppState.octave(track.id)
    MachinePanel(
        track, trackIndex, editor, { actions.patchNames(trackIndex) },
        // A patch knows the notes it's for. Loading one moves the
        // keyboard's bottom C to the bottom of its range so the first key
        // pressed is a note the instrument has. Saving one keeps where the
        // keyboard was.
        onSavePatch = { name, effects -> actions.savePatch(trackIndex, name, 12 * (octave + 1), 12 * (octave + 3), effects) },
        onLoadPatch = { name ->
            actions.loadPatch(trackIndex, name)?.also { p ->
                // The keyboard follows the patch, the roll doesn't. The
                // roll is where you were looking at your own music, and
                // auditioning patches shouldn't scroll it.
                if (p.low >= 0) octave = (p.low / 12 - 1).coerceIn(0, 8)
            }
        },
        factoryPatchNames = { actions.factoryPatches(trackIndex) },
        userPatchNames = { actions.userPatchNames(trackIndex) },
        onDeletePatch = { actions.deletePatch(trackIndex, it) },
        onImportSoundFont = { actions.importSoundFont(trackIndex) },
        onPickPreset = { actions.pickPreset(trackIndex) },
        onImportZoneSamples = { actions.importZoneSamples(trackIndex) },
        selectedPad = selectedPad,
        onImportSample = { pad -> actions.importSample(trackIndex, pad) },
        onImportKit = { pad -> actions.importKit(trackIndex, pad) },
        onImportSlice = { actions.importSlice(trackIndex) },
        onOpenSample = { pad -> actions.openSample(trackIndex, pad) },
        onImportOneSample = { actions.importOneSample(trackIndex) },
        onClearSample = { pad -> editor.edit(trackIndex) { t -> t.withSetting("p%02d_sample".format(pad), null) } },
        // The whole kit in one edit, so clearing it is one undo and a
        // half-cleared kit can't be autosaved. Only the settings here, the
        // pads' trim is parameters, which the panel resets itself.
        onClearKit = {
            editor.edit(trackIndex) { t ->
                var out = t
                for (pad in 0 until 13) out = out.withSetting("p%02d_sample".format(pad), null)
                out.withSetting("slice_sample", null).withSetting("slice_count", null)
            }
        },
        onAssignSample = { pad, rel ->
            editor.edit(trackIndex) { t -> t.withSetting("p%02d_sample".format(pad), rel) }
        },
        onOpenPatch = { actions.openPatch(trackIndex, sceneId) },
        sceneId = sceneId,
        bar = bar, body = body, vertical = vertical,
        barInRow = barInRow,
        foldable = foldable,
        modifier = modifier,
    )
}
