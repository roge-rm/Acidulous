package com.rm.acidulous

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import com.rm.acidulous.engine.EngineSync
import com.rm.acidulous.model.DemoSong
import com.rm.acidulous.model.Song
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.util.System

/**
 * What [App] works on, held once for the whole process so a second window
 * (the bottom screen of a two-screen handheld) plays the same song through
 * the same editor. Snapshot state, so both compositions see each change.
 * Main thread only.
 *
 * [App] still decides when the song is loaded: a new activity restores the
 * session over whatever is here, as before.
 */
object AppState {
    // Referential, not structural: Song equality is by value (rev is left out
    // of equals on purpose), so a value-equal load or edit would otherwise be
    // silently dropped.
    val song: MutableState<Song> = mutableStateOf(DemoSong.build(), referentialEqualityPolicy())

    private var lastPushMs = 0L

    val editor = SongEditor(song.value) { edited, pushNow ->
        song.value = edited
        // Taps and gesture ends push at once. Mid-gesture updates throttle to ~15 Hz.
        val now = System.nanoTime() / 1_000_000L
        if (pushNow || now - lastPushMs >= 66) {
            EngineSync.sync(edited)
            lastPushMs = now
        }
    }

    /**
     * The track hardware notes, typed notes and the second screen play: the
     * one of the last opened clip, whether or not its editor is still open.
     */
    val midiTrack: MutableState<Int> = mutableStateOf(0)

    /** What the main screen shows. [App] saves and restores it; the second screen follows it. */
    internal val screen: MutableState<Screen> = mutableStateOf(Screen.Main)

    /**
     * What [App] owns that the second screen's panels need: meters, the click,
     * and the machine panel's patch and file actions. Set while [App] is
     * composed, null otherwise.
     */
    val hooks: MutableState<TopHooks?> = mutableStateOf(null)

    /**
     * A second screen the app can use is attached right now. Set by the
     * platform; the settings show the second-screen switch only then.
     */
    val secondScreenPresent: MutableState<Boolean> = mutableStateOf(false)

    // What a track plays at, by track id, so the editor's keys and the second
    // screen's agree. Kept for the process, not saved with the song.
    private val octaves = HashMap<String, MutableState<Int>>()
    private val mods = HashMap<String, MutableState<Float>>()

    /** The keyboard's octave for a track: its first key is note 12 x (octave + 1). */
    fun octave(trackId: String): MutableState<Int> = octaves.getOrPut(trackId) { mutableStateOf(3) }

    /** Where a track's mod wheel was left, 0..1. */
    fun modWheel(trackId: String): MutableState<Float> = mods.getOrPut(trackId) { mutableStateOf(0f) }
}

/** See [AppState.hooks]. The functions read [App]'s state when they run. */
class TopHooks(
    /** Read by the meters, not where the mixer is built. */
    val rackPeaks: () -> FloatArray,
    val masterPeak: () -> Float,
    val clickOn: () -> Boolean,
    val onClick: (Boolean) -> Unit,
    val panel: com.rm.acidulous.ui.PanelActions,
)
