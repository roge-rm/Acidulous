package com.rm.acidulous.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import com.rm.acidulous.R
import com.rm.acidulous.model.Clip
import com.rm.acidulous.model.Quantise
import com.rm.acidulous.model.QuantiseSpec
import com.rm.acidulous.model.Song
import com.rm.acidulous.model.SongEditor
import androidx.compose.ui.unit.dp

/**
 * What the Quantise window was last set to, for this run of the app: the
 * window opens where it was left, and a Launchpad's Quantise button uses it
 * too, so the two mean the same thing.
 */
internal object QuantiseMemory {
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

    /** [clip]'s notes - those in [which], or all - quantised, or put back, as these settings say. */
    fun applyTo(song: Song, clip: Clip, clipTicks: Int, which: Set<Int>? = null) =
        if (asPlayed) Quantise.asPlayed(clip.notes, which, clipTicks)
        else Quantise.apply(clip.notes, which, spec(song, clip), clipTicks)
}

/**
 * The Quantise window: the selection, or the whole clip, onto a grid - all
 * the way or part of it, starts or ends too, straight or to another clip's
 * groove - or back to where it was played; and humanised on top.
 *
 * Heard as it is set, like Generate: a gesture is opened as the window is,
 * each change redraws the clip from how it was when the window opened, OK
 * keeps it as one undo step, and anything else throws it away.
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
    /** The roll of the dice for humanise, or null for none; a press of the button rolls again. */
    var seed by remember { mutableStateOf<Long?>(null) }
    // Every clip in the song with notes, as a groove to lean on.
    val grooves = remember(song) {
        song.tracks.flatMap { t ->
            song.scenes.mapNotNull { sc -> t.clips[sc.id]?.takeIf { it.notes.isNotEmpty() }?.let { Triple(t.id, sc.id, "${t.name} · ${sc.name}") } }
        }
    }
    var grooveAt by remember { mutableStateOf(m.groove?.let { g -> grooves.indexOfFirst { it.first == g.first && it.second == g.second } + 1 } ?: 0) }

    DisposableEffect(Unit) {
        editor.beginGesture(trackIndex)
        // Leaving any other way than OK - back, a tap outside - is Cancel.
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
    // Heard from the moment it opens, as it was last set.
    LaunchedEffect(Unit) { apply() }

    PlainDialog(
        title = stringResource(R.string.quantise_window_title),
        onDismiss = onDismiss,
        dismissLabel = stringResource(R.string.cancel),
        confirmLabel = stringResource(R.string.ok),
        onConfirm = {
            editor.endGesture()
            onDismiss()
        },
        spacing = 6.dp,
    ) {
        WindowCards {
            WindowCard(stringResource(R.string.quantise_card)) {
                SwitchGrid(
                    stringResource(R.string.quantise_grid),
                    listOf(stringResource(R.string.quantise_clip_grid)) + GRIDS.map { it.first },
                    gridAt, columns = 4, enabled = List(GRIDS.size + 1) { !asPlayed },
                ) { gridAt = it; apply() }
                CountKnob(stringResource(R.string.quantise_amount), strength, 0..100, "$strength%") { strength = it; apply() }
                SwitchGrid(stringResource(R.string.quantise_move), stringArrayResource(R.array.quantise_move_choices).toList(), if (ends) 1 else 0, enabled = listOf(!asPlayed, !asPlayed)) {
                    ends = it == 1; apply()
                }
                SwitchGrid(stringResource(R.string.quantise_as_played), stringArrayResource(R.array.off_on).toList(), if (asPlayed) 1 else 0) {
                    asPlayed = it == 1; apply()
                }
            }
            // How it feels: another clip's timing, and a player's unevenness.
            WindowCard(stringResource(R.string.quantise_feel)) {
                val names = listOf(stringResource(R.string.quantise_straight)) + grooves.map { it.third }
                CountKnob(
                    stringResource(R.string.quantise_groove), grooveAt, 0..grooves.size, names[grooveAt],
                    width = 140.dp, choices = names,
                ) { grooveAt = it; apply() }
                CountKnob(stringResource(R.string.quantise_humanise), human, 0..100, "$human%") { human = it; if (seed != null) apply() }
                // A press rolls the dice again; none takes it away.
                SwitchGrid("", stringArrayResource(R.array.quantise_humanise_choices).toList(), if (seed == null) 1 else -1) {
                    seed = if (it == 0) System.nanoTime() else null
                    apply()
                }
            }
        }
    }
}
