package com.rm.acidulous.model

import kotlinx.serialization.EncodeDefault
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

/**
 * The rate the engine runs at, and the rate every decoded file is resampled to.
 *
 * Here because it is the other fixed number the document needs to turn frames
 * into musical time - a take's length in ticks, a freeze's in seconds. It was
 * written as a bare 48000 in eight places before anything had to do arithmetic
 * with it.
 */
const val ENGINE_RATE = 48000

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
    /**
     * What a finger did to this note while it was held: bend, pressure and
     * slide, each a sparse curve or absent.
     *
     * Their ticks are the note's own, counted from its start rather than the
     * clip's, which is what lets a note be dragged, quantised, copied into
     * another clip or resized without its expression coming loose. Values are
     * the normalised 0..1 an automation [Lane] uses; bend is signed semitones
     * scaled to +/-[BEND_SEMIS] with the centre at a half, so what is written
     * down is the music and not the fourteen bits some controller happened to
     * send for it.
     *
     * `@EncodeDefault(NEVER)` because the document writes defaults: without it
     * every note in every song gains three lines saying nothing.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val bend: Lane? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val pressure: Lane? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val timbre: Lane? = null,
    /**
     * What this trig is allowed to decide, and where it sits.
     *
     * [chance] is a percentage; [trig] a condition; [ratchet] how many times
     * the note is struck inside its own length. The defaults are an ordinary
     * note - certain, unconditional, struck once - and the same
     * `@EncodeDefault(NEVER)` rule applies for the same reason: a song of
     * plain notes must not grow four lines a note saying nothing.
     *
     * [nudge] is the odd one out. It never reaches the engine as a property at
     * all: the marshalling adds it to [tick], because "when does this note
     * play" is a field the engine already has. Signed ticks, half a sixteenth
     * either way being the useful range. It is *not* [rawTick], which records
     * where a finger landed before quantising - a nudge is a deliberate
     * placement and survives a later quantise.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val chance: Int = 100,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val trig: Trig = Trig.Always,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val ratchet: Int = 1,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val nudge: Int = 0,
) {
    val hasExpression: Boolean get() = bend != null || pressure != null || timbre != null

    /** Anything here that is not the plain default, so a mark can be drawn. */
    val hasTrig: Boolean get() = chance < 100 || trig != Trig.Always || ratchet > 1 || nudge != 0

    /** The engine's packed word: chance | cond << 7 | (ratchet - 1) << 13. */
    val trigWord: Int
        get() = chance.coerceIn(0, 100) or
            ((Trig.codeOf(trig) and 0x3f) shl 7) or
            ((ratchet.coerceIn(1, 8) - 1) shl 13)

    /** The three in the order the engine indexes them; see `Expr` in Expression.h. */
    val curves: List<Lane?> get() = listOf(bend, pressure, timbre)

    companion object {
        /** Full-scale bend either way: MPE's default range, and its widest. */
        const val BEND_SEMIS = 48f
        /** Signed semitones to the stored 0..1, and back. */
        fun bendTo01(semitones: Float): Float = (0.5f + semitones / (2f * BEND_SEMIS)).coerceIn(0f, 1f)
        fun bendFrom01(v: Float): Float = (v - 0.5f) * 2f * BEND_SEMIS
    }
}

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

/**
 * A note's curve cut to the note's own length.
 *
 * A finger goes on moving for a few milliseconds after the key is released,
 * and a curve that ran past the note would be read by nothing - the player
 * stops asking when the note ends. Anything past the end becomes one point
 * *at* the end, holding the value the curve had got to, so the shape is not
 * cut off mid-glide.
 */
fun Lane.trimmedTo(length: Int): Lane? {
    if (points.isEmpty()) return null
    val end = length.coerceAtLeast(1)
    if (points.last().tick <= end) return this
    val inside = points.filter { it.tick < end }
    return Lane(points = inside + LanePoint(end, valueAt(end)), linear = linear)
}

fun laneKey(unit: String, name: String): String = "$unit:$name"
fun laneUnit(key: String): String = key.substringBefore(':')
fun laneParam(key: String): String = key.substringAfter(':')

/**
 * A clip rendered to audio, so the rack can play the file instead of running
 * the machine. [file] is relative to the freeze directory; [bpm] is the tempo
 * it was rendered at, and a song at any other tempo ignores it and plays live
 * - audio does not stretch.
 */
@Serializable
data class Frozen(
    val file: String,
    val bpm: Float,
    val ticks: Int,
    val frames: Int,
    val peak: Float = 0f,
)

/**
 * One lane's recording, as one cell refers to it: a window into a file.
 *
 * [offset] and [frames] are why a take sung across four scenes is four clips
 * and *one* file. The split at the scene lines costs no audio at all - each
 * cell points a little further into the same recording.
 *
 * [bpm] is the tempo it was sung at. Audio does not stretch, so a song at
 * another tempo plays it anyway rather than going silent: a take is a
 * performance and there is no live machine behind it to fall back to, which is
 * the one way this differs from [Frozen]. It enters on the bar and runs at its
 * own rate, and the cell says so.
 *
 * [startTick] is where in the cycle it begins, which is nought unless somebody
 * punched in part way through.
 */
@Serializable
data class TakeRef(
    /** Relative to the user root, as every other recording is: "samples/take 3.wav". */
    val file: String,
    val offset: Int,
    val frames: Int,
    val bpm: Float,
    /** The cycle it was recorded against - bars x repeat, in ticks. */
    val ticks: Int,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val startTick: Int = 0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val loop: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val label: String = "",
    /**
     * How long the take takes to arrive and to go, in frames.
     *
     * **A crossfade between two takes is two of these overlapping**, which is
     * why there is no separate crossfade anywhere: four lanes already sum, so
     * one lane's fade-out across another's fade-in *is* the crossfade, and it
     * is equal-power so the two together hold a steady level rather than
     * dipping in the middle.
     *
     * They also do the ordinary job: a take trimmed mid-word clicks, and a few
     * milliseconds of fade is the difference between an edit and a fault.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val fadeIn: Int = 0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val fadeOut: Int = 0,
    /** A coarse shape for the grid to draw, so a cell costs no disk. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val peaks: List<Float> = emptyList(),
)

/**
 * What a tape holds in one cell: four lanes, and they sound together.
 *
 * The index is the lane and a null is a lane with nothing here, which is
 * ordinary - nobody sings every lane over every scene. Level and mute are not
 * in here: they are the machine's own parameters, which is what makes them
 * automatable, mappable and recordable without any of that being written
 * twice.
 */
@Serializable
data class ClipAudio(val lanes: List<TakeRef?> = emptyList()) {
    fun lane(i: Int): TakeRef? = lanes.getOrNull(i)
    val isEmpty: Boolean get() = lanes.all { it == null }
}

/** One track's material for one scene. */
@Serializable
data class Clip(
    val bars: Int = 1,
    val playMode: PlayMode = PlayMode.Loop,
    val mute: Boolean = false,
    /** Quantise and display grid, in ticks. Default is a sixteenth. */
    val grid: Int = PPQN / 4,
    val notes: List<Note> = emptyList(),
    /**
     * The clip's own dice.
     *
     * [seed] makes a probability repeatable: the same clip with the same seed
     * plays the same bar every time, which is what lets an export repeat and a
     * take be recorded. Changing it asks for a different variation. It is a
     * document value and deliberately not [rev], which is fresh on every edit
     * - a pattern built on that would reroll itself under your hand.
     *
     * [freeRoll] sets them loose: a new roll every pass, alive on stage. A
     * render still repeats, because a render panics first and the player
     * rewinds its dice there.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val seed: Int = 0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val freeRoll: Boolean = false,
    /** Parameter movement, keyed by [laneKey]. */
    val automation: Map<String, Lane> = emptyMap(),
    /** Set while this clip plays as audio rather than as notes. */
    val frozen: Frozen? = null,
    /** What a Bias track recorded here. Null on every other kind of track. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val audio: ClipAudio? = null,
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

/**
 * The clip emptied of what was played into it, keeping how it is set up.
 *
 * Bars, play mode, mute and grid are the clip's *settings* - how long it is
 * and how it behaves - and survive. Notes and automation are its contents and
 * do not. The freeze goes with them: it is a render of the notes, so a cleared
 * clip that kept its frozen audio would sit there silent-looking and still
 * making a sound, which is the one outcome nobody could explain.
 */
fun Clip.cleared(): Clip =
    copy(notes = emptyList(), automation = emptyMap(), frozen = null, audio = null)

/** Is there anything in this clip to clear? */
fun Clip.hasContent(): Boolean =
    notes.isNotEmpty() || automation.isNotEmpty() || frozen != null || audio?.isEmpty == false

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

/**
 * One of a track's slots - an insert effect after the machine, or an eventor
 * (Scale, Chord, Arp) ahead of it. An empty [type] is an empty slot. Params are
 * normalised 0..1 like a machine's; [bypass] keeps the unit and its state but
 * takes it out of the path.
 */
@Serializable
data class UnitSlot(
    val type: String = "",
    val params: Map<String, Float> = emptyMap(),
    val bypass: Boolean = false,
) {
    val isEmpty: Boolean get() = type.isEmpty()
}

typealias EffectSlot = UnitSlot

const val EFFECT_SLOTS = 2

/** How many send buses the master has; see [Master.sends]. */
const val SEND_SLOTS = 2
/** One per eventor - chord, scale, arp - because the keyboard strip gives
 *  each of them a control and all three must be able to run together. */
const val EVENTOR_SLOTS = 3

/** A track's channel strip. Units are musical (gain 0..1.5, pan -1..1, sends 0..1). */
@Serializable
data class Mixer(
    val volume: Float = 1f,
    val pan: Float = 0f,
    val sendReverb: Float = 0f,
    val sendDelay: Float = 0f,
    val mute: Boolean = false,
    val solo: Boolean = false,
    /**
     * Where this track's notes go. 0 the machine, 1 the machine and the
     * hardware, 2 the hardware alone - and at 2 the machine is not asked at
     * all, which is the point of driving something else.
     *
     * It lives on the mixer because it is a routing choice, and it reaches
     * the engine as a "channel" parameter beside the fader for the same
     * reason. Defaulted, so songs written before this still open.
     */
    val midiMode: Int = 0,
    val midiChannel: Int = 0,
)

@Serializable
data class Track(
    val id: String,
    val name: String,
    val machine: Machine,
    /** Keyed by [Scene.id]. A missing entry is silence in that scene. */
    val clips: Map<String, Clip> = emptyMap(),
    val mixer: Mixer = Mixer(),
    /** Insert effects, in signal order after the machine. Always [EFFECT_SLOTS] long when read through [effectAt]. */
    val effects: List<UnitSlot> = emptyList(),
    /** Eventors, in order ahead of the machine: notes pass eventor 1 then 2. */
    val eventors: List<UnitSlot> = emptyList(),
) {
    fun effectAt(slot: Int): UnitSlot = effects.getOrNull(slot) ?: UnitSlot()
    fun eventorAt(slot: Int): UnitSlot = eventors.getOrNull(slot) ?: UnitSlot()
}

/**
 * The two fixed send boxes, as songs written before M53 carry them.
 *
 * **Read, never written.** The sends are slots now - any of the fourteen
 * effects, with that effect's own parameters - and [Master.migrated] turns
 * these two into the first two slots when an old song is opened. They stay
 * declared so that reading one is a migration rather than a loss.
 */
@Serializable data class ReverbSettings(val on: Boolean = true, val size: Float = 0.5f, val damp: Float = 0.5f, val tone: Float = 0.6f)

/** [time] indexes [EngineParams.DELAY_TIME_NAMES]. See [ReverbSettings]. */
@Serializable data class DelaySettings(val on: Boolean = true, val time: Int = 3, val feedback: Float = 0.4f, val tone: Float = 0.5f, val pingPong: Boolean = true)

@Serializable data class LimiterSettings(val on: Boolean = true, val drive: Float = 0.2f)

/** The master section: fader, send returns, limiter. The metronome is a transport setting, not part of the song. */
@Serializable
data class Master(
    val volume: Float = 0.8f,
    /**
     * What is on each of the two send buses.
     *
     * The same [UnitSlot] an insert is, and for the same reason: a send was
     * two boxes nobody could change, while the rack next to it could put any
     * of fourteen effects in either of its own slots. A reverb and a delay are
     * what they start as, because that is what a send is *for* - but a song
     * that wants a send chorus or a send bitcrusher can have one.
     */
    val sends: List<UnitSlot> = listOf(UnitSlot("Reverb"), UnitSlot("Delay")),
    val limiter: LimiterSettings = LimiterSettings(),
    /** Only ever non-null in a song written before the sends were slots. */
    val reverb: ReverbSettings? = null,
    val delay: DelaySettings? = null,
) {
    fun sendAt(slot: Int): UnitSlot = sends.getOrNull(slot) ?: UnitSlot()

    /**
     * An old song's two fixed boxes, as the two slots.
     *
     * **The positions carry, not the sound.** The send reverb was a different
     * reverb from the insert one - a plainer Schroeder room against the insert's
     * eight combs with predelay, shimmer, bits, crush and wobble - so nothing
     * could make an old song sound identical, and the honest thing is to put
     * every control where it was and let the better room be better. `tone` is
     * the one that moves most: it was a plain 0..1 and is now a frequency, and
     * the same fraction of a different range is the closest statement of
     * "where the knob was" there is.
     */
    fun migrated(): Master {
        if (reverb == null && delay == null) return this
        val r = reverb ?: ReverbSettings()
        val d = delay ?: DelaySettings()
        return copy(
            sends = listOf(
                UnitSlot(
                    "Reverb",
                    mapOf("size" to r.size, "damp" to r.damp, "tone" to r.tone),
                    bypass = !r.on,
                ),
                UnitSlot(
                    "Delay",
                    mapOf(
                        "time" to (d.time.toFloat() / (EngineParams.DELAY_TIMES - 1).toFloat()).coerceIn(0f, 1f),
                        "feedback" to d.feedback,
                        "tone" to d.tone,
                        "pingpong" to if (d.pingPong) 1f else 0f,
                    ),
                    bypass = !d.on,
                ),
            ),
            reverb = null,
            delay = null,
        )
    }
}

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
    /**
     * Controller mappings that belong to this music rather than to the room
     * it is played in. These win over the device's own; see [Mappings.find].
     */
    val mappings: List<Mapping> = emptyList(),
) {
    fun signatureOf(scene: Scene): Signature = scene.signature ?: signature

    /** Derived, exactly as the engine derives it: the longest clip, at least one bar. */
    fun barsOf(scene: Scene): Int = tracks.mapNotNull { it.clips[scene.id]?.bars }.maxOrNull() ?: 1
}
