package com.rm.acidulous.model

/**
 * What an automation lane is called where a person reads it.
 *
 * A lane key is addressed by the engine's names - "machine:gpos",
 * "effect1:fb" - which are short because they are keys, and say nothing to
 * the person choosing one. The machine panels already spell their own
 * parameters out, so [PANEL_LABELS] carries that wording (generated from the
 * panels by tools/gen_param_labels.py); everything else - the effects and
 * eventors, which are drawn from the registry without a hand-made face, and
 * any parameter no panel shows - goes through [humanise], which expands the
 * abbreviations the engine uses.
 *
 * Two names come out of this. The long one names the unit as well, because a
 * list of sixty entries needs to say whether "cutoff" is the machine's or the
 * filter effect's. The short one is the parameter alone, for the strip's
 * gutter, where there is room for one word turned on its side.
 */

private val WORDS = mapOf(
    "amt" to "amount", "amnt" to "amount", "atk" to "attack", "rel" to "release",
    "sus" to "sustain", "dec" to "decay", "env" to "envelope", "envmod" to "envelope amount",
    "eg" to "envelope", "lfo" to "LFO", "osc" to "oscillator", "freq" to "frequency",
    "frq" to "frequency", "res" to "resonance", "reso" to "resonance", "cutoff" to "cutoff",
    "fb" to "feedback", "fdbk" to "feedback", "dly" to "delay", "wet" to "wet mix",
    "vol" to "volume", "vel" to "velocity", "pw" to "pulse width", "pos" to "position",
    "dens" to "density", "len" to "length", "xf" to "crossfade", "mod" to "modulation",
    "depth" to "depth", "thr" to "threshold", "thresh" to "threshold", "rat" to "ratio",
    "hpf" to "high pass", "lpf" to "low pass", "bpf" to "band pass", "hp" to "high pass",
    "lp" to "low pass", "src" to "source", "dest" to "destination", "sync" to "sync",
    "spd" to "speed", "wid" to "width", "num" to "number", "det" to "detune",
    "glide" to "glide", "prs" to "pressure", "trig" to "trigger", "bal" to "balance",
    "drv" to "drive", "sat" to "saturation", "q" to "Q", "pan" to "pan",
    "bypass" to "bypass", "gain" to "volume", "sendreverb" to "reverb send",
    "senddelay" to "delay send", "mix" to "mix", "rate" to "rate", "time" to "time",
)

/** The engine's name for a parameter, spelled out as far as it can be. */
fun humanise(name: String): String =
    name.split('_', '.', '-')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { part ->
            WORDS[part.lowercase()] ?: run {
                // "f2freq" and the like: a digit glued to a word is a numbered one.
                val m = Regex("^([a-zA-Z]+)(\\d+)$").find(part)
                if (m != null) "${WORDS[m.groupValues[1].lowercase()] ?: m.groupValues[1]} ${m.groupValues[2]}"
                else part
            }
        }

/** The unit a lane belongs to, named the way the track shows it. */
fun laneUnitLabel(track: Track, key: String): String = when (val unit = laneUnit(key)) {
    "machine" -> track.machine.type
    "channel" -> "mixer"
    "performance" -> "perform"
    else -> {
        val slot = unit.takeLast(1).toIntOrNull()?.minus(1) ?: 0
        when {
            unit.startsWith("effect") -> (track.effectAt(slot).type.ifEmpty { "effect" }) + " fx${slot + 1}"
            unit.startsWith("eventor") -> (track.eventorAt(slot).type.ifEmpty { "eventor" }) + " ev${slot + 1}"
            else -> unit
        }
    }
}

/** The parameter alone: the panel's word for it, else the name spelled out. */
fun laneShortLabel(track: Track, key: String): String {
    val param = laneParam(key)
    if (laneUnit(key) == "machine") {
        PANEL_SHORT["${track.machine.type}:$param"]?.let { return it }
        PANEL_LABELS["${track.machine.type}:$param"]?.let { return it }
    }
    return humanise(param)
}

/** Unit and parameter, for a list where one "cutoff" must be told from another. */
fun laneLabel(track: Track, key: String): String {
    val param = laneParam(key)
    val named = if (laneUnit(key) == "machine") PANEL_LABELS["${track.machine.type}:$param"] else null
    return "${laneUnitLabel(track, key)} · ${named ?: humanise(param)}"
}
