package com.rm.acidulous.model

import kotlinx.serialization.Serializable

/**
 * A thing on a controller, pointed at a thing in the app.
 *
 * Both halves come in two kinds, and that is the whole of the feature: a
 * knob or a pad on one side, a parameter or a transport action on the other.
 * A knob to a knob is what anyone expects; a pad to *play* is what makes
 * mapping reach past the things that have values.
 *
 * A parameter target is the same address an automation lane uses -
 * `machine:cutoff`, `channel:gain` - which is why nothing here has to know
 * about recording. Whatever moves a parameter records it, so a mapped knob
 * records for the same reason a real one does. An action has no value and no
 * lane, and firing one is never recorded: a "play" written into a lane would
 * play the song from inside the song.
 */
@Serializable
data class Mapping(
    /** What arrives. Exactly one of these is set. */
    val cc: Int? = null,
    val note: Int? = null,
    /** A parameter's address, as a lane names it. Null when this is an action. */
    val unit: String? = null,
    val name: String? = null,
    /** An [Action] by name, when this fires something instead of setting it. */
    val action: String? = null,
    /** The track it acts on; null follows whatever MIDI routing says. */
    val rack: Int? = null,
) {
    val isAction: Boolean get() = action != null
    /** The lane this would write into, or null for an action. */
    val laneKey: String? get() = if (unit != null && name != null) laneKey(unit, name) else null

    /** How the MIDI window and the mapping list name the source. */
    fun sourceLabel(): String = when {
        cc != null -> "CC $cc"
        note != null -> "note ${Scales.keyNames[((note % 12) + 12) % 12]}${note / 12 - 1}"
        else -> "?"
    }

    /** And the target. [track] only to name the unit it belongs to. */
    fun targetLabel(track: Track?): String = when {
        action != null -> action.lowercase()
        unit != null && name != null && track != null -> laneLabel(track, laneKey(unit, name))
        unit != null && name != null -> "$unit · $name"
        else -> "?"
    }
}

/**
 * The transport's verbs: things that happen rather than things with a value.
 *
 * Stored by name, not ordinal, because this list will grow and a song is not
 * going to be re-saved to keep up with it.
 */
enum class Action { PlayStop, Play, Stop, RecordArm, LoopScene, ClipMode, Panic, Fill }

object Mappings {

    /**
     * Anything at or above this is a press; below it, a release.
     *
     * The MIDI convention, and it makes a momentary footswitch and a
     * latching one behave the same - both send something over 64 when they
     * go down, and only one of them sends anything when it comes up.
     */
    const val PRESS = 64

    /**
     * The mapping for an arriving controller, or null.
     *
     * The song wins over the device. Device mappings are the ones for the
     * hardware sitting in front of you; a song's are the ones that belong to
     * the music, and if a song has an opinion about CC 74 it is the one that
     * knows what CC 74 is for here.
     */
    fun find(song: Song, device: List<Mapping>, cc: Int? = null, note: Int? = null): Mapping? {
        val matches: (Mapping) -> Boolean = { m ->
            (cc != null && m.cc == cc) || (note != null && m.note == note)
        }
        return song.mappings.firstOrNull(matches) ?: device.firstOrNull(matches)
    }

    /** Every note a mapping has claimed, so the UI can say which are spoken for. */
    fun claimedNotes(song: Song, device: List<Mapping>): List<Int> =
        (song.mappings + device).mapNotNull { it.note }.distinct().sorted()

    /** Replaces any mapping on the same source, so a source drives one thing. */
    fun set(existing: List<Mapping>, mapping: Mapping): List<Mapping> =
        existing.filterNot { (mapping.cc != null && it.cc == mapping.cc) ||
            (mapping.note != null && it.note == mapping.note) } + mapping

    /** Forgets whatever drives this target. */
    fun clearTarget(existing: List<Mapping>, unit: String?, name: String?, action: String?): List<Mapping> =
        existing.filterNot {
            if (action != null) it.action == action else it.unit == unit && it.name == name
        }
}

/**
 * What the engine says about a mapped parameter, or null when it has no
 * table entry - a bypass pseudo-parameter, or a name from a machine that is
 * no longer mounted.
 */
fun mappedParamInfo(track: Track, unit: String, name: String): com.rm.acidulous.engine.ParamInfo? {
    val engine = com.rm.acidulous.engine.NativeEngine
    val list = when {
        unit == "machine" -> engine.machineParamInfo(track.machine.type)
        unit.startsWith("effect") -> effectSlotOf(unit)?.let { engine.effectParamInfo(track.effectAt(it).type) }
        unit.startsWith("eventor") -> eventorSlotOf(unit)?.let { engine.eventorParamInfo(track.eventorAt(it).type) }
        else -> null
    } ?: return null
    return list.firstOrNull { it.name == name }
}

/**
 * What that parameter is set to now, normalised - so a note can toggle a
 * switch rather than only ever turning it on.
 */
fun currentMapped(track: Track, unit: String, name: String): Float = when {
    unit == "machine" -> track.machine.params[name]
    unit.startsWith("effect") -> effectSlotOf(unit)?.let { track.effectAt(it).params[name] }
    unit.startsWith("eventor") -> eventorSlotOf(unit)?.let { track.eventorAt(it).params[name] }
    unit == "channel" -> when (name) {
        "gain" -> EngineParams.volume01(track.mixer.volume)
        "pan" -> EngineParams.pan01(track.mixer.pan)
        "sendreverb" -> track.mixer.sendReverb
        "senddelay" -> track.mixer.sendDelay
        "mute" -> if (track.mixer.mute) 1f else 0f
        "solo" -> if (track.mixer.solo) 1f else 0f
        else -> null
    }
    else -> null
} ?: mappedParamInfo(track, unit, name)?.defaultNormalized ?: 0f

/**
 * The mixer's two-step parameters, by name.
 *
 * A machine's parameter table comes back over the bridge and says whether a
 * parameter is a switch; the channel and master tables do not, because they
 * are fixed rather than per-machine and nothing has needed to ask before.
 * A note mapped to one of these has to toggle rather than set from velocity,
 * and that is the only thing the mapping needs to know about them. The names
 * are the engine's own, from Rack.cpp and MasterBus.cpp.
 */
private val mixerSwitches = setOf(
    "mute", "solo",                                   // channel
    "reverbon", "delayon", "limiteron", "delaypingpong", // master
)

/** Would a note on this target toggle it, rather than set it from velocity? */
fun mappedIsSwitch(track: Track?, unit: String, name: String): Boolean = when {
    unit == "channel" || unit == "master" -> name in mixerSwitches
    track != null -> mappedParamInfo(track, unit, name)?.let { it.curve == 2 && it.steps == 2 } ?: false
    else -> false
}

/** What a master parameter is set to now, normalised. */
fun currentMaster(master: Master, name: String): Float = when (name) {
    "volume" -> EngineParams.volume01(master.volume)
    "reverbon" -> if (master.reverb.on) 1f else 0f
    "reverbsize" -> master.reverb.size
    "reverbdamp" -> master.reverb.damp
    "reverbtone" -> master.reverb.tone
    "delayon" -> if (master.delay.on) 1f else 0f
    "delaytime" -> master.delay.time.toFloat() / (EngineParams.DELAY_TIMES - 1)
    "delayfeedback" -> master.delay.feedback
    "delaytone" -> master.delay.tone
    "delaypingpong" -> if (master.delay.pingPong) 1f else 0f
    "limiteron" -> if (master.limiter.on) 1f else 0f
    "limiterdrive" -> master.limiter.drive
    else -> 0f
}
