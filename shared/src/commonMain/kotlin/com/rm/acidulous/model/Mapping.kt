package com.rm.acidulous.model

import kotlinx.serialization.Serializable

/**
 * A control on a MIDI controller, pointed at something in the app.
 *
 * The source is a knob or a pad, and the target is a parameter or a transport
 * action, so a pad can be mapped to play as well as to a value.
 *
 * A parameter target uses the same address as an automation lane
 * (`machine:cutoff`, `channel:gain`), so a mapped knob records just like the
 * real one. Actions have no value or lane and are never recorded, since a
 * recorded "play" would start the song from inside the song.
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
    /** The track it acts on. Null follows the MIDI routing. */
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

    /** How the target is named. [track] is only used to name the unit. */
    fun targetLabel(track: Track?, word: (String) -> String = { it }): String = when {
        action != null -> action.lowercase()
        unit != null && name != null && track != null -> laneLabel(track, laneKey(unit, name), word)
        unit != null && name != null -> "$unit · $name"
        else -> "?"
    }
}

/**
 * Transport actions: things that happen rather than things with a value.
 *
 * Stored by name rather than ordinal, because this list will grow and old
 * songs won't be re-saved to keep up.
 */
enum class Action { PlayStop, Play, Stop, RecordArm, LoopScene, ClipMode, Panic, Fill }

object Mappings {

    /**
     * At or above this is a press, below it a release.
     *
     * This is the MIDI convention, and it makes momentary and latching
     * footswitches behave the same.
     */
    const val PRESS = 64

    /**
     * The mapping for an incoming controller, or null.
     *
     * Song mappings win over device mappings, since a song's mappings belong
     * to that music.
     */
    fun find(song: Song, device: List<Mapping>, cc: Int? = null, note: Int? = null): Mapping? {
        val matches: (Mapping) -> Boolean = { m ->
            (cc != null && m.cc == cc) || (note != null && m.note == note)
        }
        return song.mappings.firstOrNull(matches) ?: device.firstOrNull(matches)
    }

    /** Every note a mapping has claimed, so the UI can show which are taken. */
    fun claimedNotes(song: Song, device: List<Mapping>): List<Int> =
        (song.mappings + device).mapNotNull { it.note }.distinct().sorted()

    /** Replaces any mapping on the same source, so each source drives one thing. */
    fun set(existing: List<Mapping>, mapping: Mapping): List<Mapping> =
        existing.filterNot { (mapping.cc != null && it.cc == mapping.cc) ||
            (mapping.note != null && it.note == mapping.note) } + mapping

    /** Removes whatever drives this target. */
    fun clearTarget(existing: List<Mapping>, unit: String?, name: String?, action: String?): List<Mapping> =
        existing.filterNot {
            if (action != null) it.action == action else it.unit == unit && it.name == name
        }
}

/**
 * The engine's info for a mapped parameter, or null when it has none, like a
 * bypass pseudo-parameter or a machine that's no longer loaded.
 */
fun mappedParamInfo(track: Track, unit: String, name: String): com.rm.acidulous.engine.ParamInfo? {
    val engine = com.rm.acidulous.engine.NativeEngine
    val list = when {
        unit == "machine" -> engine.machineParamInfo(track.machine.type)
        unit.startsWith("effect") -> effectSlotOf(unit)?.let { engine.effectParamInfo(track.effectAt(it).type) }
        unit.startsWith("mod") -> modifierSlotOf(unit)?.let { engine.inputModParamInfo(track.modifierAt(it).type) }
        else -> null
    } ?: return null
    return list.firstOrNull { it.name == name }
}

/**
 * The parameter's current value, normalised, so a note can toggle a switch
 * off as well as on.
 */
fun currentMapped(track: Track, unit: String, name: String): Float = when {
    unit == "machine" -> track.machine.params[name]
    unit.startsWith("effect") -> effectSlotOf(unit)?.let { track.effectAt(it).params[name] }
    unit.startsWith("mod") -> modifierSlotOf(unit)?.let { track.modifierAt(it).params[name] }
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
 * The mixer's on/off parameters, by name.
 *
 * Machine parameter tables say whether a parameter is a switch, but the
 * channel and master tables don't. A note mapped to one of these toggles it
 * instead of setting it from velocity. The names come from Rack.cpp and
 * MasterBus.cpp.
 */
private val mixerSwitches = setOf(
    "mute", "solo",                                   // channel
    "limiteron", // master
)

/** Whether a note on this target toggles it instead of setting it from velocity. */
fun mappedIsSwitch(track: Track?, unit: String, name: String): Boolean = when {
    unit == "channel" || unit == "master" -> name in mixerSwitches
    track != null -> mappedParamInfo(track, unit, name)?.let { it.curve == 2 && it.steps == 2 } ?: false
    else -> false
}

/**
 * A send parameter's current value, normalised.
 *
 * A slot's map only holds parameters that have been touched, so a missing name
 * reads as 0 here, even where the effect's own default is something else.
 */
fun currentSend(master: Master, slot: Int, name: String): Float {
    val send = master.sendAt(slot)
    if (name == "bypass") return if (send.bypass) 1f else 0f
    return send.params[name] ?: 0f
}

/** A master parameter's current value, normalised. */
fun currentMaster(master: Master, name: String): Float = when (name) {
    "volume" -> EngineParams.volume01(master.volume)
    "limiteron" -> if (master.limiter.on) 1f else 0f
    "limiterdrive" -> master.limiter.drive
    else -> 0f
}
