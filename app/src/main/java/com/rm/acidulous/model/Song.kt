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

@Serializable
data class LanePoint(val tick: Int, val value: Float)

/**
 * One parameter's movement over a clip. Values are the engine's normalised
 * 0..1 domain. Keyed in [Clip.automation] by [laneKey] ("machine:cutoff",
 * "channel:sendreverb"), so a lane follows its clip wherever it is copied.
 */
@Serializable
data class Lane(val points: List<LanePoint> = emptyList(), val linear: Boolean = true) {
    /** Adds or replaces the point at [tick]; keeps the list sorted. */
    fun withPoint(tick: Int, value: Float): Lane {
        val kept = points.filter { it.tick != tick }
        return copy(points = (kept + LanePoint(tick, value.coerceIn(0f, 1f))).sortedBy { it.tick })
    }

    fun withoutPointsIn(from: Int, to: Int): Lane = copy(points = points.filter { it.tick < from || it.tick > to })

    /** Mirrors seq::Lane::valueAt so the UI draws what the engine plays. */
    fun valueAt(tick: Int): Float {
        if (points.isEmpty()) return 0f
        if (tick <= points.first().tick) return points.first().value
        if (tick >= points.last().tick) return points.last().value
        val i = points.indexOfLast { it.tick <= tick }
        val a = points[i]
        val b = points[i + 1]
        if (!linear || b.tick == a.tick) return a.value
        return a.value + (b.value - a.value) * (tick - a.tick).toFloat() / (b.tick - a.tick)
    }
}

fun laneKey(unit: String, name: String): String = "$unit:$name"
fun laneUnit(key: String): String = key.substringBefore(':')
fun laneParam(key: String): String = key.substringAfter(':')

/** One track's material for one scene. */
@Serializable
data class Clip(
    val bars: Int = 1,
    val playMode: PlayMode = PlayMode.Loop,
    val mute: Boolean = false,
    /** Quantise and display grid, in ticks. Default is a sixteenth. */
    val grid: Int = PPQN / 4,
    val notes: List<Note> = emptyList(),
    /** Parameter movement, keyed by [laneKey]. */
    val automation: Map<String, Lane> = emptyMap(),
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

/** A track's channel strip. Units are musical (gain 0..1.5, pan -1..1, sends 0..1). */
@Serializable
data class Mixer(
    val volume: Float = 1f,
    val pan: Float = 0f,
    val sendReverb: Float = 0f,
    val sendDelay: Float = 0f,
    val mute: Boolean = false,
    val solo: Boolean = false,
)

@Serializable
data class Track(
    val id: String,
    val name: String,
    val machine: Machine,
    /** Keyed by [Scene.id]. A missing entry is silence in that scene. */
    val clips: Map<String, Clip> = emptyMap(),
    val mixer: Mixer = Mixer(),
)

@Serializable data class ReverbSettings(val on: Boolean = true, val size: Float = 0.5f, val damp: Float = 0.5f, val tone: Float = 0.6f)

/** [time] indexes [EngineParams.DELAY_TIME_NAMES]. */
@Serializable data class DelaySettings(val on: Boolean = true, val time: Int = 3, val feedback: Float = 0.4f, val tone: Float = 0.5f, val pingPong: Boolean = true)

@Serializable data class LimiterSettings(val on: Boolean = true, val drive: Float = 0.2f)

/** The master section: fader, send returns, limiter. The metronome is a transport setting, not part of the song. */
@Serializable
data class Master(
    val volume: Float = 0.8f,
    val reverb: ReverbSettings = ReverbSettings(),
    val delay: DelaySettings = DelaySettings(),
    val limiter: LimiterSettings = LimiterSettings(),
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
    val master: Master = Master(),
) {
    fun signatureOf(scene: Scene): Signature = scene.signature ?: signature

    /** Derived, exactly as the engine derives it: the longest clip, at least one bar. */
    fun barsOf(scene: Scene): Int = tracks.mapNotNull { it.clips[scene.id]?.bars }.maxOrNull() ?: 1
}
