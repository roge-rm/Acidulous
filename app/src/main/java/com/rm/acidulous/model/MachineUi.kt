package com.rm.acidulous.model

/**
 * What the editor needs to know about a machine type beyond its parameters:
 * whether it is played from a keyboard or pads, and what the pads are called.
 * Mirrors the voice order and base note in engine/machine/hexbeat/Hexbeat.h.
 */
enum class MachineKind { Keyboard, Drums }

data class DrumVoice(val note: Int, val name: String, val short: String)

object MachineUi {
    fun kindOf(type: String): MachineKind = if (type == "Hexbeat" || type == "Forage") MachineKind.Drums else MachineKind.Keyboard
    fun acceptsSamples(type: String): Boolean = type == "Forage"

    /**
     * Whether the machine answers the performance controllers, and so whether
     * the Edit screen shows the strip. A machine that ignores mod wheel and
     * pressure gets no strip rather than a dead one.
     */
    fun usesPerformance(type: String): Boolean = type == "Trinity" || type == "Ratio"

    val hexbeatVoices: List<DrumVoice> = listOf(
        DrumVoice(36, "Kick", "BD"), DrumVoice(37, "Rim", "RS"), DrumVoice(38, "Snare", "SD"), DrumVoice(39, "Clap", "CP"),
        DrumVoice(40, "Low Tom", "LT"), DrumVoice(41, "Mid Tom", "MT"), DrumVoice(42, "Hi Tom", "HT"),
        DrumVoice(43, "Closed Hat", "CH"), DrumVoice(44, "Open Hat", "OH"), DrumVoice(45, "Crash", "CY"),
        DrumVoice(46, "Ride", "RD"), DrumVoice(47, "Cowbell", "CB"), DrumVoice(48, "Clave", "CL"),
    )

    /** Forage pads are named after their samples; unloaded pads by number. */
    fun voicesOf(type: String, settings: Map<String, String> = emptyMap()): List<DrumVoice> = when (type) {
        "Hexbeat" -> hexbeatVoices
        "Forage" -> (0 until 13).map { pad ->
            val file = settings["p%02d_sample".format(pad)]
            val name = file?.substringAfterLast('/')?.substringBeforeLast('.') ?: ""
            DrumVoice(36 + pad, if (name.isEmpty()) "Pad ${pad + 1}" else name, if (name.isEmpty()) "${pad + 1}" else name.take(4))
        }
        else -> emptyList()
    }
}

object HexbeatPresets {
    private fun p(name: String, vararg kv: Pair<String, Float>) = Patch("Hexbeat", name, kv.toMap())
    val all: List<Patch> = listOf(
        p("Init"),
        p("Tight", "kick_decay" to 0.35f, "kick_punch" to 0.7f, "snare_decay" to 0.3f, "snare_snappy" to 0.7f,
            "hat_closed_decay" to 0.25f, "hat_open_decay" to 0.35f, "accent" to 0.7f),
        p("Boomy", "kick_tune" to 0.3f, "kick_decay" to 0.8f, "kick_punch" to 0.35f, "tom_decay" to 0.75f,
            "snare_decay" to 0.6f, "snare_snappy" to 0.4f, "hat_open_decay" to 0.6f),
        p("Trashy", "snare_tone" to 0.8f, "snare_snappy" to 0.9f, "hat_tune" to 0.8f, "hat_tone" to 0.3f,
            "cym_tone" to 0.3f, "clap_tone" to 0.7f, "accent" to 0.9f),
    )
}
