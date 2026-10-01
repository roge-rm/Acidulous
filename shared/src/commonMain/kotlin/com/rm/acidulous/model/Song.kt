package com.rm.acidulous.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement

/**
 * The song.
 *
 * Everything here is immutable, so an edit makes a new value. That makes undo
 * and redo a list of old values, and lets [com.rm.acidulous.engine.EngineSync]
 * hand the engine a snapshot that can't change underneath it.
 *
 * Time is in ticks at [PPQN] per quarter note, the engine's resolution.
 */

const val PPQN = 240

/** Swing where nothing moves. A straight song uses this. */
const val SWING_STRAIGHT = 50f
/** The most swing: the offbeat three quarters of the way through the pair. */
const val SWING_MAX = 75f
/** Two against three, the usual shuffle. */
const val SWING_TRIPLET = 66.667f

/**
 * The engine's sample rate. Every decoded file is resampled to it.
 *
 * The song needs it to turn frames into musical time, like a take's length
 * in ticks or a freeze's in seconds.
 */
const val ENGINE_RATE = 48000

/**
 * The key and scale a song is in.
 *
 * [root] is a pitch class, 0 being C. [scale] indexes `Scales.names`, the same
 * order the Scale modifier uses, so no translation is needed between them.
 */
@Serializable
data class SongKey(val root: Int = 0, val scale: Int = 0)

/** The tempos a song or scene can have. */
const val BPM_MIN = 20f
const val BPM_MAX = 300f

@Serializable
data class Signature(val beats: Int = 4, val unit: Int = 4) {
    // Never less than a tick, whatever a file says: this is divided by
    // everywhere a bar is counted.
    val ticksPerBar: Int get() = (beats * 4 * PPQN / unit.coerceAtLeast(1)).coerceAtLeast(1)

    /** A signature a bar can be counted in: some beats of a whole, half, quarter... note. */
    val sensible: Boolean get() = beats in 1..32 && unit in listOf(1, 2, 4, 8, 16, 32)
}

@Serializable
enum class PlayMode { Loop, OneShot }

@Serializable
data class Note(
    val tick: Int,
    val length: Int,
    val pitch: Int,
    val velocity: Int,
    /** Where it was actually played, if it was quantised on input. */
    val rawTick: Int? = null,
    /**
     * What a finger did to this note while it was held: bend, pressure and
     * slide, each a sparse curve or null.
     *
     * Their ticks count from the note's start, not the clip's, so a note can
     * be dragged, quantised, copied or resized and keep its expression.
     * Values are normalised 0..1 like an automation [Lane]. Bend is signed
     * semitones scaled to +/-[BEND_SEMIS] with the centre at 0.5, so it
     * doesn't depend on what resolution the controller sent.
     *
     * `@EncodeDefault(NEVER)` because the song writes defaults, and otherwise
     * every note would get three empty lines.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val bend: Lane? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val pressure: Lane? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val timbre: Lane? = null,
    /**
     * The trig settings for this note.
     *
     * [chance] is a percentage, [trig] a condition, and [ratchet] how many
     * times the note is struck within its length. The defaults are a normal
     * note, and `@EncodeDefault(NEVER)` keeps plain notes from gaining four
     * empty lines each.
     *
     * [nudge] never reaches the engine as its own field. It's added to [tick]
     * when the note is sent. Signed ticks, with half a sixteenth either way
     * being the useful range. It's not [rawTick], which records where a
     * finger landed before quantising. A nudge is deliberate and survives a
     * later quantise.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val chance: Int = 100,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val trig: Trig = Trig.Always,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val ratchet: Int = 1,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val nudge: Int = 0,
    /**
     * The words this note sings, on a singer's track: a syllable or a word as
     * typed ("hel-", "lo"), or sounds in brackets ("[hh ax]"). A syllable
     * ending in "-" is joined to the next note's into one word, and "-" alone
     * holds the last vowel over another note. Empty for a plain note.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val lyric: String = "",
) {
    val hasExpression: Boolean get() = bend != null || pressure != null || timbre != null

    /** Whether anything here isn't the default, so a mark can be drawn. */
    val hasTrig: Boolean get() = chance < 100 || trig != Trig.Always || ratchet > 1 || nudge != 0

    /** The engine's packed word: chance | cond << 7 | (ratchet - 1) << 13. */
    val trigWord: Int
        get() = chance.coerceIn(0, 100) or
            ((Trig.codeOf(trig) and 0x3f) shl 7) or
            ((ratchet.coerceIn(1, 8) - 1) shl 13)

    /** The three curves in the engine's order; see `Expr` in Expression.h. */
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
 * One parameter's movement over a clip, in the engine's normalised 0..1.
 * Keyed in [Clip.automation] by [laneKey] ("machine:cutoff",
 * "channel:sendreverb"), so a lane goes wherever its clip is copied.
 */
@Serializable
data class Lane(val points: List<LanePoint> = emptyList(), val linear: Boolean = true) {
    /** Adds or replaces the point at [tick] and keeps the list sorted. */
    fun withPoint(tick: Int, value: Float): Lane {
        val kept = points.filter { it.tick != tick }
        return copy(points = (kept + LanePoint(tick, value.coerceIn(0f, 1f))).sortedBy { it.tick })
    }

    fun withoutPointsIn(from: Int, to: Int): Lane = copy(points = points.filter { it.tick < from || it.tick > to })

    /** Matches seq::Lane::valueAt so the UI draws what the engine plays. */
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
 * A note's curve cut to the note's length.
 *
 * A finger keeps moving for a few milliseconds after the key is released,
 * and the player stops reading the curve when the note ends. Anything past
 * the end becomes one point at the end, holding the value it had reached,
 * so the shape isn't cut off mid-glide.
 */
fun Lane.trimmedTo(length: Int): Lane? {
    if (points.isEmpty()) return null
    val end = length.coerceAtLeast(1)
    if (points.last().tick <= end) return this
    val inside = points.filter { it.tick < end }
    return Lane(points = inside + LanePoint(end, valueAt(end)), linear = linear)
}

fun laneKey(unit: String, name: String): String = "$unit:$name"

/**
 * The pedal lanes on the performance pseudo-unit, next to the wheel and
 * pressure: sustain, sostenuto and soft. They're switches, so they step, and
 * they rest up so a pedal pressed on beat three doesn't hold beat one.
 */
val PEDAL_LANES = listOf("sustain", "sostenuto", "soft")
fun isPedalLane(key: String): Boolean = laneUnit(key) == "performance" && laneParam(key) in PEDAL_LANES

/** A new empty lane for [key]: stepped and resting up for a pedal, a plain glide otherwise. */
fun newLaneFor(key: String, firstTick: Int): Lane =
    if (isPedalLane(key)) Lane(if (firstTick > 0) listOf(LanePoint(0, 0f)) else emptyList(), linear = false)
    else Lane()
fun laneUnit(key: String): String = key.substringBefore(':')
fun laneParam(key: String): String = key.substringAfter(':')

/**
 * A clip rendered to audio, so the rack can play the file instead of running
 * the machine. [file] is relative to the freeze directory. [bpm] is the tempo
 * it was rendered at. At any other tempo it's out of date and the machine
 * plays live instead (a freeze only stretches through a scene's tempo ramp).
 */
@Serializable
data class Frozen(
    val file: String,
    val bpm: Float,
    val ticks: Int,
    val frames: Int,
    val peak: Float = 0f,
    /**
     * A hash of how the track sounded when this was rendered.
     *
     * A freeze bakes in the machine and both insert effects, since the frozen
     * path in `Rack::render` skips them. Changing any of them afterwards
     * leaves the freeze out of date, so this is used to spot that. 0 means a
     * freeze from before this field and counts as matching, so old songs
     * don't open with every frozen clip marked stale.
     */
    val voice: Int = 0,
    /**
     * Frames of ring-out stored after the clip in the same file.
     *
     * [frames] is the loop. The tail plays over the next pass and whatever
     * follows the clip, so a frozen track rings on like the live one.
     *
     * 0 means an old freeze whose tail was folded into its start, so the
     * whole file is the loop. See `Freeze.stale`.
     */
    val tail: Int = 0,
)

/**
 * One lane's recording in one cell: a window into a file.
 *
 * [offset] and [frames] let a take recorded across four scenes be four clips
 * and one file. Splitting at scene lines copies no audio, each cell just
 * points further into the same recording.
 *
 * [bpm] is the tempo it was recorded at. When the track follows the tempo
 * the take is time-stretched to the scene's. When it doesn't, the take plays
 * anyway (there's no machine to fall back to, unlike [Frozen]): it starts on
 * the bar, runs at its own speed, and the cell shows it.
 *
 * [startTick] is where in the cycle it begins, which is 0 unless you punched
 * in part way through.
 */
@Serializable
data class TakeRef(
    /** Relative to the user root, like every recording: "samples/take 3.wav". */
    val file: String,
    val offset: Int,
    val frames: Int,
    val bpm: Float,
    /** The cycle it was recorded against, bars times repeat, in ticks. */
    val ticks: Int,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val startTick: Int = 0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val loop: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val label: String = "",
    /**
     * Fade in and fade out lengths, in frames.
     *
     * A crossfade between two takes is just two of these overlapping, since
     * the four lanes are summed. The fades are equal-power so the level stays
     * steady through the middle.
     *
     * They also stop clicks when a take is trimmed mid-word.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val fadeIn: Int = 0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val fadeOut: Int = 0,
    /** A rough shape for the grid to draw, so a cell doesn't need the disk. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val peaks: List<Float> = emptyList(),
)

/**
 * What a tape holds in one cell: four lanes that play together.
 *
 * The index is the lane, and null means nothing in that lane, which is
 * normal. Level and mute aren't here because they're machine parameters, so
 * they can be automated, mapped and recorded like any other.
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
     * [seed] makes probability repeatable: the same seed plays the same bar
     * every time, so exports repeat and takes can be recorded. Change it for a
     * different variation. It's stored in the song and isn't [rev], which
     * changes on every edit and would reroll the pattern as you work.
     *
     * [freeRoll] rerolls every pass instead. Renders still repeat, because a
     * render panics first and the player rewinds its dice then.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val seed: Int = 0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val freeRoll: Boolean = false,
    /** Parameter movement, keyed by [laneKey]. */
    val automation: Map<String, Lane> = emptyMap(),
    /** Set while this clip plays as audio instead of notes. */
    val frozen: Frozen? = null,
    /** What a Bias track recorded here. Null on every other kind of track. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val audio: ClipAudio? = null,
) {
    /**
     * Instance identity as a number. It's outside the constructor on purpose,
     * so it isn't serialised or part of equals/hashCode. Every new instance,
     * whether a [copy] for an edit or a load from JSON, gets a fresh one, and
     * a clip that's just carried along keeps its rev. The engine-side builder
     * uses it to reuse unchanged clips, so editing one clip only re-sends that
     * clip.
     *
     * `@Transient` is needed because kotlinx.serialization would otherwise
     * write body properties too, and a rev read from a file could clash with
     * a live one.
     */
    @Transient
    val rev: Long = ClipRev.next()
}

/**
 * The clip emptied of its contents, keeping its settings.
 *
 * Bars, play mode, mute and grid are settings and stay. Notes, automation and
 * audio are contents and go. The freeze goes too, since it's a render of the
 * notes and would otherwise keep playing from an empty-looking clip.
 */
fun Clip.cleared(): Clip =
    copy(notes = emptyList(), automation = emptyMap(), frozen = null, audio = null)

/**
 * The clip ready to paste somewhere else: everything except the freeze, with
 * a new identity.
 *
 * The freeze can't come. A render is named after its track and scene
 * (`Freeze.fileFor` builds `"${'$'}{trackId}__${'$'}{sceneId}.wav"`) and
 * `Freeze.discard` deletes that file, so two clips sharing one render would
 * both go silent when either thawed. On another track it would also be the
 * wrong machine.
 *
 * The audio does come. A `TakeRef` is a window into a shared take file that
 * never changes, so another clip pointing at it is fine.
 *
 * Everything else comes too. Lanes are addressed by name, so a name the new
 * machine doesn't have just doesn't resolve. The `rev` lives outside the
 * constructor, so `copy` gives a new one.
 */
fun Clip.asCopy(): Clip = copy(frozen = null)

/** Whether this clip has anything to clear. */
fun Clip.hasContent(): Boolean =
    notes.isNotEmpty() || automation.isNotEmpty() || frozen != null || audio?.isEmpty == false

@OptIn(ExperimentalAtomicApi::class)
object ClipRev {
    private val counter = AtomicLong(1)
    fun next(): Long = counter.fetchAndIncrement()
}

/** A scene's own tempo. Null means it follows the song tempo. */
@Serializable
data class SceneTempo(val bpm: Float, val smooth: Boolean = false)

/**
 * A tempo change within a scene: to [toBpm] over the scene's last [bars] bars,
 * on its last pass. Used for a ritardando into the next scene or an
 * accelerando across the whole scene. The next scene sets its own tempo when
 * it starts.
 */
@Serializable
data class TempoRamp(val toBpm: Float, val bars: Int = 1)

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
    @EncodeDefault(EncodeDefault.Mode.NEVER) val ramp: TempoRamp? = null,
) {
    /** The id as the engine sees it: a 64-bit FNV-1a of [id]. */
    val engineId: Long get() = fnv1a64(id)
}

/** 64-bit FNV-1a. Stable across processes, which String.hashCode isn't guaranteed to be. */
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
 * One of a track's slots: an insert effect after the machine, or a modifier
 * (Scale, Chord, Arp) before it. An empty [type] is an empty slot. Params are
 * normalised 0..1 like a machine's. [bypass] keeps the unit and its state but
 * takes it out of the signal path.
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

/** How many inserts the master has, before its fader and limiter. */
const val MASTER_INSERT_SLOTS = 2

/** How many groups the mixer can have, and how many inserts each has. */
const val MAX_GROUPS = 4
const val GROUP_INSERT_SLOTS = 2

/**
 * A mixer group: tracks route into it, and it has two inserts and its own
 * fader. It isn't a track, so it has no machine or clips.
 */
@Serializable
data class MixGroup(
    val name: String = "Group",
    val volume: Float = 1f,
    val mute: Boolean = false,
    val solo: Boolean = false,
    val inserts: List<UnitSlot> = emptyList(),
    val pan: Float = 0f,
) {
    fun insertAt(slot: Int): UnitSlot = inserts.getOrNull(slot) ?: UnitSlot()
}

/**
 * Input effects, applied before anything hears the input.
 *
 * Two, like a track's inserts. The difference is that these are recorded
 * into the take, because they run before the recorder. Put an amp here if
 * you want it on the take, or on the track if you want to keep changing it.
 */
const val INPUT_SLOTS = 2
/** One per modifier (chord, scale, arp), since the keyboard strip has a
 *  control for each and all three can run together. */
const val MODIFIER_SLOTS = 3

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
     * Where this track's notes go: 0 the machine, 1 the machine and external
     * hardware, 2 only the hardware. At 2 the machine isn't played at all.
     *
     * It's on the mixer because it's a routing choice, and it reaches the
     * engine as a "channel" parameter next to the fader. Has a default so
     * older songs still open.
     */
    val midiMode: Int = 0,
    val midiChannel: Int = 0,
    /**
     * Where this track's sound goes: 0 the master, 1..4 one of the mixer's
     * groups ([Master.groups]). Deleting a group remaps it, see [deleteGroup].
     */
    val output: Int = 0,
)

@Serializable
data class Track(
    val id: String,
    val name: String,
    val machine: Machine,
    /** This track's own tuning, or null to follow the song's. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val tuning: Tuning? = null,
    /**
     * Semitones added to every note as it plays, from a clip or a finger.
     * The clip keeps what was written. Drum machines ignore it, since their
     * notes pick sounds (see [MachineUi.takesTranspose]).
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val transpose: Int = 0,
    /** Plays every note at this velocity, 1..127, or null for as played. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val velocity: Int? = null,
    /** The track's colour as a palette index, or null for its position's colour. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val colour: Int? = null,
    /**
     * This track's own swing, or null to follow the song's.
     *
     * Null rather than a special number, because a track that follows the
     * song has to change when the song does. The engine never sees the null,
     * it's resolved before sending, so the audio thread doesn't need to know
     * about following.
     */
    val swing: Float? = null,
    /** Keyed by [Scene.id]. No entry means silence in that scene. */
    val clips: Map<String, Clip> = emptyMap(),
    val mixer: Mixer = Mixer(),
    /** Insert effects, in signal order after the machine. Always [EFFECT_SLOTS] long when read through [effectAt]. */
    val effects: List<UnitSlot> = emptyList(),
    /** Modifiers, in order before the machine: notes pass modifier 1, then 2, then 3. */
    val modifiers: List<UnitSlot> = emptyList(),
) {
    fun effectAt(slot: Int): UnitSlot = effects.getOrNull(slot) ?: UnitSlot()
    fun modifierAt(slot: Int): UnitSlot = modifiers.getOrNull(slot) ?: UnitSlot()
}

/**
 * The two fixed send effects stored by older songs.
 *
 * Only read, never written. Sends are slots now and [Master.migrated] turns
 * these into the first two slots when an old song is opened. They're kept so
 * old songs load without losing their settings.
 */
@Serializable data class ReverbSettings(val on: Boolean = true, val size: Float = 0.5f, val damp: Float = 0.5f, val tone: Float = 0.6f)

/** [time] indexes [EngineParams.DELAY_TIME_NAMES]. See [ReverbSettings]. */
@Serializable data class DelaySettings(val on: Boolean = true, val time: Int = 3, val feedback: Float = 0.4f, val tone: Float = 0.5f, val pingPong: Boolean = true)

@Serializable data class LimiterSettings(val on: Boolean = true, val drive: Float = 0.2f)

/**
 * Settings for the held performance effects. What's being held (repeat,
 * stop, the pad) isn't here, since that's a performance and is stored as
 * lanes in a clip.
 *
 * [stopLen] counts up to [STOP_LENGTHS], [throwTime] indexes [THROW_TIMES], and
 * [feedback] is the echo's, 0..0.9.
 */
@Serializable data class PerformSettings(
    val stopLen: Int = 2,
    val throwTime: Int = 2,
    val feedback: Float = 0.55f,
    /** Counts up to [RISER_LENGTHS]. */
    val riserLen: Int = 1,
    /** What the pad does across and up, below [PAD_X_MODES] and [PAD_Y_MODES]. */
    val xMode: Int = 0,
    val yMode: Int = 0,
    /** When a mute on the live page lands: [MUTE_ON_BAR], [MUTE_ON_BEAT] or [MUTE_ON_NOW]. */
    val muteOn: Int = 0,
    /** Where the held effects run: 0 the whole mix, 1..4 one of the groups. */
    val target: Int = 0,
)

/** How many tape stop lengths there are; matches `Perform::StopLen`. Named in `perform_stop_lengths`. */
const val STOP_LENGTHS = 4
/** The echo's time, as labels; matches `Perform::ThrowTime`. */
val THROW_TIMES = listOf("1/16", "1/8", "3/16", "1/4", "3/8")
/** The repeat's slice lengths, 1..5 in `Perform::Repeat`; 0 is off. */
val REPEAT_LENGTHS = listOf("1", "1/2", "1/4", "1/8", "1/16")
/** When a mute tapped on the live page lands while playing: on the bar, the beat, or now. */
const val MUTE_ON_BAR = 0
const val MUTE_ON_BEAT = 1
const val MUTE_ON_NOW = 2
const val MUTE_ON = 3
/** What the pad does across: a filter or a crush. Matches `Perform::XMode`. */
const val PAD_X_MODES = 2
/** What the pad does up: throws into an echo or a wash. Matches `Perform::YMode`. */
const val PAD_Y_MODES = 2
/** How many riser lengths there are; matches `Perform::RiserLen`. Named in `perform_riser_lengths`. */
const val RISER_LENGTHS = 3
/** The gate's rates, 1..5 in `Perform::Gate`; 0 is off. */
val GATE_LENGTHS = listOf("1/8", "1/16", "1/32", "1/8T", "1/16T")

/** The master section: fader, send returns, limiter. The metronome is a transport setting, not saved in the song. */
@Serializable
data class Master(
    val volume: Float = 0.8f,
    /**
     * What's on each of the two send buses.
     *
     * The same [UnitSlot] as an insert, so a send can hold any effect. They
     * start as a reverb and a delay, but a song can use a chorus or a
     * bitcrusher as a send instead.
     */
    val sends: List<UnitSlot> = listOf(UnitSlot("Reverb"), UnitSlot("Delay")),
    val limiter: LimiterSettings = LimiterSettings(),
    /** Effects on the whole mix, after the sends and before the fader and limiter. */
    val inserts: List<UnitSlot> = emptyList(),
    /** The mixer's groups, up to [MAX_GROUPS]. A track's [Mixer.output] names one. */
    val groups: List<MixGroup> = emptyList(),
    val perform: PerformSettings = PerformSettings(),
    /** Only set in songs saved before sends were slots. */
    val reverb: ReverbSettings? = null,
    val delay: DelaySettings? = null,
) {
    fun sendAt(slot: Int): UnitSlot = sends.getOrNull(slot) ?: UnitSlot()
    fun insertAt(slot: Int): UnitSlot = inserts.getOrNull(slot) ?: UnitSlot()

    /**
     * An old song's two fixed send effects, as the two slots.
     *
     * The knob positions carry over but the sound won't be identical, since
     * the old send reverb was a simpler design than the current one. `tone`
     * changes the most: it was a plain 0..1 and is now a frequency, so the
     * same fraction of the new range is used.
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
    /** 2 from 0.9.12: the LFOs' seventeen rates ([RateMigration]). */
    val version: Int = RateMigration.SONG_VERSION,
    val name: String,
    val tempo: Float = 120f,
    /**
     * How late the offbeats sit, as a percentage of the pair.
     *
     * 50 is straight, 66.7 is a triplet shuffle and 75 is the maximum. A track
     * can override it with [Track.swing].
     *
     * Older songs saved 0 here, which `SongStore` reads as straight rather
     * than as a swing of minus fifty.
     */
    val swing: Float = SWING_STRAIGHT,
    /** Which pair the swing moves: 0 a pair of sixteenths, 1 a pair of eighths. */
    val swingUnit: Int = 0,
    /**
     * The song's key, or null for none.
     *
     * The roll shades rows outside it and new tracks get a matching Scale
     * modifier, but existing notes aren't moved and no track is forced. A
     * track that wants a different scale sets its own Scale modifier.
     */
    val key: SongKey? = null,
    /** How the notes are tuned, from the key's root. Null is equal temperament. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val tuning: Tuning? = null,
    val signature: Signature = Signature(),
    val loopSong: Boolean = true,
    /** Index is the rack id. */
    val tracks: List<Track> = emptyList(),
    /** Order is the arrangement. */
    val scenes: List<Scene> = emptyList(),
    val master: Master = Master(),
    /**
     * Effects the incoming audio goes through before anything hears it.
     *
     * On the song rather than a track because there's one input, and because
     * these are recorded into the take, whichever track is armed. Empty in
     * older songs.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val input: List<UnitSlot> = emptyList(),
    /**
     * Controller mappings that belong to this song rather than the device.
     * These win over the device's own; see [Mappings.find].
     */
    val mappings: List<Mapping> = emptyList(),
) {
    /** Input slot [i], or an empty one, like [Master.sendAt]. */
    fun inputAt(i: Int): UnitSlot = input.getOrNull(i) ?: UnitSlot("")

    fun signatureOf(scene: Scene): Signature = scene.signature ?: signature

    /** Worked out the same way as the engine: the longest clip, at least one bar. */
    fun barsOf(scene: Scene): Int = tracks.mapNotNull { it.clips[scene.id]?.bars }.maxOrNull() ?: 1
}
