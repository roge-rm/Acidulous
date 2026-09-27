package com.rm.acidulous.model

/**
 * The readable names of automation lanes.
 *
 * Lane keys use the engine's short names like "machine:gpos" or "effect1:fb",
 * which mean nothing to the user. The machine panels already spell out their
 * parameters, so [PANEL_LABELS] reuses that wording (generated from the panels
 * by tools/gen_param_labels.py). Everything else, like effects, modifiers and
 * parameters no panel shows, goes through [humanise], which expands the
 * engine's abbreviations.
 *
 * There are two names. The long one includes the unit, so a long list can
 * tell the machine's "cutoff" from the filter effect's. The short one is just
 * the parameter, for the strip's narrow gutter.
 *
 * Every word goes through a `word` function, which the app uses to translate
 * it (ui/PanelText.kt). Left as is it's English, which is what tests read.
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

/** The engine's name for a parameter, spelled out as far as possible. */
fun humanise(name: String, word: (String) -> String = { it }): String =
    name.split('_', '.', '-')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { part ->
            WORDS[part.lowercase()]?.let(word) ?: run {
                // "f2freq" and the like: a word with a digit on the end is a numbered one.
                val m = Regex("^([a-zA-Z]+)(\\d+)$").find(part)
                if (m != null) "${word(WORDS[m.groupValues[1].lowercase()] ?: m.groupValues[1])} ${m.groupValues[2]}"
                else word(part)
            }
        }

/** The unit a lane belongs to, named the way the track shows it. */
fun laneUnitLabel(track: Track, key: String, word: (String) -> String = { it }): String = when (val unit = laneUnit(key)) {
    "machine" -> track.machine.type
    "channel" -> word("mixer")
    "performance" -> word("perform")
    else -> {
        val slot = unit.takeLast(1).toIntOrNull()?.minus(1) ?: 0
        when {
            unit.startsWith("effect") -> track.effectAt(slot).type.ifEmpty { word("effect") } + " " + word("fx${slot + 1}")
            unit.startsWith("mod") -> track.modifierAt(slot).type.ifEmpty { word("modifier") } + " " + word("mod${slot + 1}")
            else -> unit
        }
    }
}

/** Just the parameter: the panel's word for it, or the name spelled out. */
fun laneShortLabel(track: Track, key: String, word: (String) -> String = { it }): String {
    val param = laneParam(key)
    if (laneUnit(key) == "machine") {
        PANEL_SHORT["${track.machine.type}:$param"]?.let { return word(it) }
        PANEL_LABELS["${track.machine.type}:$param"]?.let { return it.split('|').joinToString(" ", transform = word) }
    }
    return humanise(param, word)
}

/** Unit and parameter, for a list where one "cutoff" needs telling from another. */
fun laneLabel(track: Track, key: String, word: (String) -> String = { it }): String {
    val param = laneParam(key)
    val named = if (laneUnit(key) == "machine") PANEL_LABELS["${track.machine.type}:$param"] else null
    return "${laneUnitLabel(track, key, word)} · ${named?.split('|')?.joinToString(" ", transform = word) ?: humanise(param, word)}"
}
