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

/** Swing at which nothing moves. The value a straight song holds. */
const val SWING_STRAIGHT = 50f
/** As far as swing goes: the offbeat three quarters of the way through the pair. */
const val SWING_MAX = 75f
/** Two against three, which is the shuffle everybody means. */
const val SWING_TRIPLET = 66.667f

/**
 * The rate the engine runs at, and the rate every decoded file is resampled to.
 *
 * Here because it is the other fixed number the document needs to turn frames
 * into musical time - a take's length in ticks, a freeze's in seconds. It was
 * written as a bare 48000 in eight places before anything had to do arithmetic
 * with it.
 */
const val ENGINE_RATE = 48000

/**
 * The key and scale a song is in.
 *
 * [root] is a pitch class, 0 being C. [scale] indexes `Scales.names`, which
 * is the same order the Scale modifier uses, so the two can be handed to each
 * other without a translation table.
 */
@Serializable
data class SongKey(val root: Int = 0, val scale: Int = 0)

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
    /**
     * What the track sounded like when this was rendered.
     *
     * A freeze bakes in the machine **and both insert effects** - the frozen
     * branch of `Rack::render` returns buffer audio and never reaches the
     * machine or the insert loop, which is the whole of the saving. So
     * changing any of them afterwards leaves the freeze silently out of date:
     * you turn a knob, hear nothing, and nothing says why.
     *
     * Only the tempo was checked, because at first the tempo was the only
     * thing that could invalidate one. Zero means a freeze written before this
     * existed, and is treated as matching rather than as stale - an old song
     * should not open with every frozen clip claiming to be wrong.
     */
    val voice: Int = 0,
    /**
     * Frames of ring-out stored after the clip in the same file.
     *
     * [frames] is the loop; this is what the clip goes on sounding after it,
     * played over the next pass and over whatever follows the clip - because a
     * frozen track that stopped dead at the bar line did not sound like the
     * live one, which rings on.
     *
     * Nought means a freeze written before this existed. Those had their tail
     * folded into their head instead, so the whole file is the loop and the
     * engine plays them the way it always did.
     */
    val tail: Int = 0,
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

/**
 * The clip as something to put somewhere else: everything it holds, except the
 * freeze, and a new identity.
 *
 * **The freeze cannot come.** A render is named after the track and scene it
 * was made for - `Freeze.fileFor` builds `"${'$'}{trackId}__${'$'}{sceneId}.wav"` - and
 * `Freeze.discard` deletes that file, so two clips pointing at one render means
 * thawing either one silences both. Pasted onto another track it would be a
 * render of a different machine as well. It is the same reasoning [cleared]
 * gives: a freeze belongs to the notes it was made from, in the place it was
 * made.
 *
 * **The audio does come.** A `TakeRef` is a window into a shared, immutable
 * take file - one take sung across four scenes is four clips and one file - so
 * a second clip referring to it is what that design is for.
 *
 * Everything else comes: the notes, the automation, and how the clip is set up.
 * A lane is addressed by name and resolved against whatever machine it lands
 * on, so a name the new one does not have simply does not resolve; and the
 * ordinary paste is the same track in the next scene, where the automation is
 * exactly what you wanted to bring. The `rev` looks after itself - it lives
 * outside the constructor, so every `copy` mints a new one.
 */
fun Clip.asCopy(): Clip = copy(frozen = null)

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
 * One of a track's slots - an insert effect after the machine, or an modifier
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

/** How many inserts the master has, before its fader and limiter. */
const val MASTER_INSERT_SLOTS = 2

/** How many groups the mixer can have, and how many inserts each has. */
const val MAX_GROUPS = 4
const val GROUP_INSERT_SLOTS = 2

/**
 * A group in the mixer: tracks route into it, and it has two inserts and a
 * fader of its own. Not a track - it has no machine and no clips.
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
 * Effects on the way *in*, before anything hears the input.
 *
 * Two, like a track's inserts, and the difference between them is the whole
 * reason these exist: **what is here is printed into the recording**, because
 * it runs before the recorder ever sees the audio. An amp you want on the take
 * goes here; an amp you want to keep deciding about goes on the track.
 */
const val INPUT_SLOTS = 2
/** One per modifier - chord, scale, arp - because the keyboard strip gives
 *  each of them a control and all three must be able to run together. */
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
    /**
     * Where this track's sound goes: 0 the master, 1..4 one of the mixer's
     * groups ([Master.groups]). Deleting a group remaps it - see [deleteGroup].
     */
    val output: Int = 0,
)

@Serializable
data class Track(
    val id: String,
    val name: String,
    val machine: Machine,
    /**
     * This track's own swing, or null to follow the song's.
     *
     * Null rather than a sentinel percentage, because "the same as the song"
     * is not a number: a track that follows a song at sixty per cent has to
     * change when the song does, and one holding sixty would not. The engine
     * never sees the null - the push resolves it - so nothing on the audio
     * thread has to know what following means.
     */
    val swing: Float? = null,
    /** Keyed by [Scene.id]. A missing entry is silence in that scene. */
    val clips: Map<String, Clip> = emptyMap(),
    val mixer: Mixer = Mixer(),
    /** Insert effects, in signal order after the machine. Always [EFFECT_SLOTS] long when read through [effectAt]. */
    val effects: List<UnitSlot> = emptyList(),
    /** Modifiers, in order ahead of the machine: notes pass modifier 1, then 2, then 3. */
    val modifiers: List<UnitSlot> = emptyList(),
) {
    fun effectAt(slot: Int): UnitSlot = effects.getOrNull(slot) ?: UnitSlot()
    fun modifierAt(slot: Int): UnitSlot = modifiers.getOrNull(slot) ?: UnitSlot()
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

/**
 * The held effects' settings. What is held (repeat, stop, the pad) is not
 * here: it is a performance, and it is kept as lanes in a clip.
 *
 * [stopLen] indexes [STOP_LENGTHS], [throwTime] indexes [THROW_TIMES], and
 * [feedback] is the echo's, 0..0.9.
 */
@Serializable data class PerformSettings(
    val stopLen: Int = 2,
    val throwTime: Int = 2,
    val feedback: Float = 0.55f,
    /** Indexes [RISER_LENGTHS]. */
    val riserLen: Int = 1,
    /** What the pad does across and up: index [PAD_X_MODES] and [PAD_Y_MODES]. */
    val xMode: Int = 0,
    val yMode: Int = 0,
    /** When a mute on the live page lands: indexes [MUTE_ON]. */
    val muteOn: Int = 0,
)

/** How long a tape stop takes, as labels; mirrors `Perform::StopLen`. */
val STOP_LENGTHS = listOf("1/4", "1/2", "1 beat", "2 beats")
/** The echo's time, as labels; mirrors `Perform::ThrowTime`. */
val THROW_TIMES = listOf("1/16", "1/8", "3/16", "1/4", "3/8")
/** The repeat's slice lengths, 1..5 in `Perform::Repeat`; 0 is off. */
val REPEAT_LENGTHS = listOf("1", "1/2", "1/4", "1/8", "1/16")
/** When a mute tapped on the live page lands, while the song plays. */
val MUTE_ON = listOf("bar", "beat", "now")
/** What the pad does across: a filter, or a crush. Mirrors `Perform::XMode`. */
val PAD_X_MODES = listOf("filter", "crush")
/** What the pad does up: throws into an echo, or into a wash. Mirrors `Perform::YMode`. */
val PAD_Y_MODES = listOf("echo", "wash")
/** How long the riser takes to climb; mirrors `Perform::RiserLen`. */
val RISER_LENGTHS = listOf("1 bar", "2 bars", "4 bars")
/** The gate's rates, 1..5 in `Perform::Gate`; 0 is off. */
val GATE_LENGTHS = listOf("1/8", "1/16", "1/32", "1/8T", "1/16T")

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
    /** Effects on the whole mix, after the sends and before the fader and limiter. */
    val inserts: List<UnitSlot> = emptyList(),
    /** The mixer's groups, up to [MAX_GROUPS]. A track's [Mixer.output] names one. */
    val groups: List<MixGroup> = emptyList(),
    val perform: PerformSettings = PerformSettings(),
    /** Only ever non-null in a song written before the sends were slots. */
    val reverb: ReverbSettings? = null,
    val delay: DelaySettings? = null,
) {
    fun sendAt(slot: Int): UnitSlot = sends.getOrNull(slot) ?: UnitSlot()
    fun insertAt(slot: Int): UnitSlot = inserts.getOrNull(slot) ?: UnitSlot()

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
    /**
     * How late the offbeats sit, as a percentage of the pair.
     *
     * Fifty is straight; two thirds of the way - 66.7 - is the shuffle
     * everybody means; seventy-five is as far as it goes. A track may
     * disagree, and says so in its own [Track.swing].
     *
     * **It was nought and dead for a long time.** The field was declared with
     * the rest of the song and read by nothing at all, so every saved song
     * carried a number that did nothing. Fifty is the value that means
     * straight, so the default moves with it - and a song written before this
     * arrives with nought, which `SongStore` reads as straight rather than as
     * a swing of minus fifty.
     */
    val swing: Float = SWING_STRAIGHT,
    /** Which pair the swing bends: 0 a pair of sixteenths, 1 a pair of eighths. */
    val swingUnit: Int = 0,
    /**
     * What key the song is in, or null for none.
     *
     * A fact about the song rather than an instruction to it: the roll shades
     * the rows that are not in it and a new track is fitted with a matching
     * Scale modifier, but nothing already written is moved and no track is
     * forced. A track that wants a different scale says so in its own Scale
     * modifier, which is where it always said it.
     */
    val key: SongKey? = null,
    val signature: Signature = Signature(),
    val loopSong: Boolean = true,
    /** Index is the rack id. */
    val tracks: List<Track> = emptyList(),
    /** Order is the arrangement. */
    val scenes: List<Scene> = emptyList(),
    val master: Master = Master(),
    /**
     * What the incoming audio goes through before anything hears it.
     *
     * On the song rather than on a track because there is one input, and
     * because **what is here is printed into a recording** - which makes it a
     * property of the session rather than of whichever track happens to be
     * armed. Empty in every song saved before this existed, which is what the
     * default is for.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val input: List<UnitSlot> = emptyList(),
    /**
     * Controller mappings that belong to this music rather than to the room
     * it is played in. These win over the device's own; see [Mappings.find].
     */
    val mappings: List<Mapping> = emptyList(),
) {
    /** Input slot [i], or an empty one: the shape [Master.sendAt] has. */
    fun inputAt(i: Int): UnitSlot = input.getOrNull(i) ?: UnitSlot("")

    fun signatureOf(scene: Scene): Signature = scene.signature ?: signature

    /** Derived, exactly as the engine derives it: the longest clip, at least one bar. */
    fun barsOf(scene: Scene): Int = tracks.mapNotNull { it.clips[scene.id]?.bars }.maxOrNull() ?: 1
}
