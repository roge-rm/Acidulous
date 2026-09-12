package com.rm.acidulous.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.concurrent.atomic.AtomicLong

/**
 * The song document.
 *
 * Everything here is immutable: an edit produces a new value. That is what
 * makes undo/redo a list of old values, and what lets [com.rm.acidulous.engine.EngineSync]
 * hand the engine a snapshot without wondering whether it changes underneath.
 *
 * Time is in ticks at [PPQN] per quarter note - the engine's native resolution.
 */

const val PPQN = 240

@Serializable
data class Signature(val beats: Int = 4, val unit: Int = 4) {
    val ticksPerBar: Int get() = beats * 4 * PPQN / unit
}

@Serializable
enum class PlayMode { Loop, OneShot }

@Serializable
data class Note(
    val tick: Int,
    val length: Int,
    val pitch: Int,
    val velocity: Int,
    /** Where it was actually played, if it was quantised on the way in. */
    val rawTick: Int? = null,
)

/** One track's material for one scene. Automation lanes arrive in M6. */
@Serializable
data class Clip(
    val bars: Int = 1,
    val playMode: PlayMode = PlayMode.Loop,
    val mute: Boolean = false,
    /** Quantise and display grid, in ticks. Default is a sixteenth. */
    val grid: Int = PPQN / 4,
    val notes: List<Note> = emptyList(),
) {
    /**
     * Instance identity as a number. Lives outside the constructor on purpose:
     * not serialised, not part of equals/hashCode. Every construction - a
     * [copy] for an edit, a load from JSON - gets a fresh one, while a clip that
     * is simply carried along keeps its rev. The engine-side builder uses it to
     * reuse unchanged clips across snapshots, so an edit to one clip costs one
     * clip's marshalling rather than the whole song's.
     *
     * `@Transient` matters: kotlinx.serialization would otherwise write body
     * properties too, and a rev read back from a file could collide with a live one.
     */
    @Transient
    val rev: Long = ClipRev.next()
}

object ClipRev {
    private val counter = AtomicLong(1)
    fun next(): Long = counter.getAndIncrement()
}

/** A scene-level tempo override. Absent means "follow the song tempo". */
@Serializable
data class SceneTempo(val bpm: Float, val smooth: Boolean = false)

@Serializable
data class Scene(
    val id: String,
    val name: String,
    /** Null means the song's signature. */
    val signature: Signature? = null,
    val repeat: Int = 1,
    val tempo: SceneTempo? = null,
    val fadeIn: Boolean = false,
    val fadeOut: Boolean = false,
) {
    /** The id as the engine sees it: a 64-bit FNV-1a of [id]. */
    val engineId: Long get() = fnv1a64(id)
}

/** FNV-1a, 64-bit. Stable across processes, which a String.hashCode is not guaranteed to be. */
fun fnv1a64(s: String): Long {
    var h = -3750763034362895579L // 0xcbf29ce484222325
    for (b in s.encodeToByteArray()) {
        h = h xor (b.toLong() and 0xff)
        h *= 1099511628211L
    }
    return h
}

@Serializable
data class Machine(
    val type: String,
    val params: Map<String, Float> = emptyMap(),
    val settings: Map<String, String> = emptyMap(),
)

@Serializable
data class Track(
    val id: String,
    val name: String,
    val machine: Machine,
    /** Keyed by [Scene.id]. A missing entry is silence in that scene. */
    val clips: Map<String, Clip> = emptyMap(),
)

@Serializable
data class Song(
    val version: Int = 1,
    val name: String,
    val tempo: Float = 120f,
    val swing: Float = 0f,
    val signature: Signature = Signature(),
    val loopSong: Boolean = true,
    /** Index is the rack id. */
    val tracks: List<Track> = emptyList(),
    /** Order is the arrangement. */
    val scenes: List<Scene> = emptyList(),
) {
    fun signatureOf(scene: Scene): Signature = scene.signature ?: signature

    /** Derived, exactly as the engine derives it: the longest clip, at least one bar. */
    fun barsOf(scene: Scene): Int = tracks.mapNotNull { it.clips[scene.id]?.bars }.maxOrNull() ?: 1
}
