package com.rm.acidulous.ui

import com.rm.acidulous.model.Clip
import com.rm.acidulous.model.Quantise
import com.rm.acidulous.model.QuantiseSpec
import com.rm.acidulous.model.Song

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
