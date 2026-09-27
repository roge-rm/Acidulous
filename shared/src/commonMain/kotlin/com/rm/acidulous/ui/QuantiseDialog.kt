package com.rm.acidulous.ui

import com.rm.acidulous.util.System

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.rm.acidulous.model.Clip
import com.rm.acidulous.model.Quantise
import com.rm.acidulous.model.QuantiseSpec
import com.rm.acidulous.model.Song
import com.rm.acidulous.model.SongEditor
import androidx.compose.ui.unit.dp
import com.rm.acidulous.res.*

/**
 * What the Quantise window was last set to, for this run of the app. The
 * window opens with it and a Launchpad's Quantise button uses it too.
 */
object QuantiseMemory {
    /** The grid in ticks, or null for the clip's own. */
    var grid: Int? = null
    var strength: Float = 1f
    var ends: Boolean = false
    /** The clip whose timing is the groove, as (track id, scene id), or null for straight. */
    var groove: Pair<String, String>? = null
    /** Back to where the notes were played, instead of onto a grid. */
    var asPlayed: Boolean = false
    /** How much humanise wobbles, 0..1. */
    var human: Float = 0.5f

    /** The quantise these settings make for [clip]. */
    fun spec(song: Song, clip: Clip): QuantiseSpec {
        val g = (grid ?: clip.grid).coerceAtLeast(1)
        val groove = groove?.let { (trackId, sceneId) ->
            val ref = song.tracks.firstOrNull { it.id == trackId }?.clips?.get(sceneId)
            val scene = song.scenes.firstOrNull { it.id == sceneId }
            if (ref == null || scene == null || ref.notes.isEmpty()) null
            else Quantise.grooveFrom(ref.notes, g, song.signatureOf(scene).ticksPerBar)
        }
        return QuantiseSpec(grid = g, strength = strength, ends = ends, groove = groove)
    }

    /** Quantises [clip]'s notes (those in [which], or all), or puts them back, per these settings. */
    fun applyTo(song: Song, clip: Clip, clipTicks: Int, which: Set<Int>? = null) =
        if (asPlayed) Quantise.asPlayed(clip.notes, which, clipTicks)
        else Quantise.apply(clip.notes, which, spec(song, clip), clipTicks)
}

/**
 * The Quantise window: moves the selection, or the whole clip, onto a grid
 * (fully or partly, starts or ends too, straight or to another clip's
 * groove) or back to where it was played, with optional humanise.
 *
 * You hear it as you change it, like Generate: a gesture opens with the
 * window, each change redraws the clip from how it was when the window
 * opened, OK keeps it as one undo step, anything else throws it away.
 */
@Composable
fun QuantiseDialog(
    song: Song,
    editor: SongEditor,
    trackIndex: Int,
    sceneId: String,
    base: Clip,
    clipTicks: Int,
    /** The notes chosen in the roll, or null for all of them. */
    which: Set<Int>?,
    onDismiss: () -> Unit,
) {
    val m = QuantiseMemory
    var gridAt by remember { mutableStateOf(m.grid?.let { g -> GRIDS.indexOfFirst { it.second == g } + 1 } ?: 0) }
    var strength by remember { mutableStateOf((m.strength * 100).toInt()) }
    var ends by remember { mutableStateOf(m.ends) }
    var asPlayed by remember { mutableStateOf(m.asPlayed) }
    var human by remember { mutableStateOf((m.human * 100).toInt()) }
    /** The humanise seed, or null for none. Pressing the button rolls again. */
    var seed by remember { mutableStateOf<Long?>(null) }
    // Every clip in the song with notes, to use as a groove.
    val grooves = remember(song) {
        song.tracks.flatMap { t ->
            song.scenes.mapNotNull { sc -> t.clips[sc.id]?.takeIf { it.notes.isNotEmpty() }?.let { Triple(t.id, sc.id, "${t.name} · ${sc.name}") } }
        }
    }
    var grooveAt by remember { mutableStateOf(m.groove?.let { g -> grooves.indexOfFirst { it.first == g.first && it.second == g.second } + 1 } ?: 0) }

    DisposableEffect(Unit) {
        editor.beginGesture(trackIndex)
        // Closing any other way than OK (back, a tap outside) cancels.
        onDispose { editor.cancelGesture() }
    }

    fun apply() {
        m.grid = if (gridAt == 0) null else GRIDS[gridAt - 1].second
        m.strength = strength / 100f
        m.ends = ends
        m.asPlayed = asPlayed
        m.human = human / 100f
        m.groove = grooves.getOrNull(grooveAt - 1)?.let { it.first to it.second }
        var notes = m.applyTo(song, base, clipTicks, which)
        seed?.let { notes = Quantise.humanise(notes, which, m.human, it, clipTicks) }
        editor.updateGestureClip(sceneId, pushNow = true) { it.copy(notes = notes.sortedBy { n -> n.tick }) }
    }
    // Apply the last settings as soon as it opens.
    LaunchedEffect(Unit) { apply() }

    PlainDialog(
        title = stringResource(Res.string.quantise_window_title),
        onDismiss = onDismiss,
        dismissLabel = stringResource(Res.string.cancel),
        confirmLabel = stringResource(Res.string.ok),
        onConfirm = {
            editor.endGesture()
            onDismiss()
        },
        spacing = 6.dp,
    ) {
        WindowCards {
            WindowCard(stringResource(Res.string.quantise_card)) {
                SwitchGrid(
                    stringResource(Res.string.quantise_grid),
                    listOf(stringResource(Res.string.quantise_clip_grid)) + GRIDS.map { it.first },
                    gridAt, columns = 4, enabled = List(GRIDS.size + 1) { !asPlayed },
                ) { gridAt = it; apply() }
                CountKnob(stringResource(Res.string.quantise_amount), strength, 0..100, "$strength%") { strength = it; apply() }
                SwitchGrid(stringResource(Res.string.quantise_move), stringArrayResource(Res.array.quantise_move_choices).toList(), if (ends) 1 else 0, enabled = listOf(!asPlayed, !asPlayed)) {
                    ends = it == 1; apply()
                }
                SwitchGrid(stringResource(Res.string.quantise_as_played), stringArrayResource(Res.array.off_on).toList(), if (asPlayed) 1 else 0) {
                    asPlayed = it == 1; apply()
                }
            }
            // Feel: another clip's timing, and humanise.
            WindowCard(stringResource(Res.string.quantise_feel)) {
                val names = listOf(stringResource(Res.string.quantise_straight)) + grooves.map { it.third }
                CountKnob(
                    stringResource(Res.string.quantise_groove), grooveAt, 0..grooves.size, names[grooveAt],
                    width = 140.dp, choices = names,
                ) { grooveAt = it; apply() }
                CountKnob(stringResource(Res.string.quantise_humanise), human, 0..100, "$human%") { human = it; if (seed != null) apply() }
                // A press rolls again; none turns it off.
                SwitchGrid("", stringArrayResource(Res.array.quantise_humanise_choices).toList(), if (seed == null) 1 else -1) {
                    seed = if (it == 0) System.nanoTime() else null
                    apply()
                }
            }
        }
    }
}
