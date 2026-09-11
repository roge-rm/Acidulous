package com.rm.acidulous.model

/**
 * What the editor needs to know about a machine type beyond its parameters:
 * whether it is played from a keyboard or pads, and what the pads are called.
 * Mirrors the voice order and base note in engine/machine/hexbeat/Hexbeat.h.
 */
enum class MachineKind { Keyboard, Drums }

data class DrumVoice(val note: Int, val name: String, val short: String)

object MachineUi {
    fun kindOf(type: String): MachineKind =
        if (type == "Hexbeat" || type == "Forage" || type == "Resonance" || type == "Dice" ||
            type == "Genesis") MachineKind.Drums
        else MachineKind.Keyboard
    fun acceptsSamples(type: String): Boolean = type == "Forage"

    /**
     * Machines that hold one sample of their own, under the plain key
     * "sample" - as against Forage, whose thirteen pads each have their own.
     */
    fun acceptsOneSample(type: String): Boolean = type == "Pollen" || type == "Dice"

    /**
     * The machines, in groups, with a line each saying what they are.
     *
     * The picker was a list of names, and a name is no help when there are
     * twelve of them and more coming: "Cipher" does not say vocoder. The
     * order inside a group is the order they were built, which is also
     * roughly simplest first.
     */
    data class MachineGroup(val label: String, val machines: List<String>)

    val machineGroups: List<MachineGroup> = listOf(
        MachineGroup("synths", listOf("Subvert", "Trinity", "Ratio", "Cumulus", "Formulate")),
        MachineGroup("drums", listOf("Hexbeat", "Genesis", "Resonance", "Forage", "Dice")),
        MachineGroup("realish", listOf("Manual", "Filament", "Brazen", "Timber", "Mosaic", "Pollen")),
        MachineGroup("beyond", listOf("Cipher", "Nexus")),
    )

    /** One line per machine: what it is, not what it has. */
    fun describe(type: String): String = when (type) {
        "Subvert" -> "acid bass - one oscillator, one filter that screams"
        "Trinity" -> "three oscillators, wavetables, dual filters, a mod matrix"
        "Ratio" -> "six-operator FM, with the algorithm itself on a knob"
        "Cumulus" -> "pads by spectrum - bands of partials, morphed"
        "Formulate" -> "the chip, and an equation you can type into it"
        "Hexbeat" -> "drums by synthesis, in the 606's vocabulary"
        "Genesis" -> "the big box: a kick you feel, and a bus the kick ducks"
        "Resonance" -> "eight struck objects that ring, and hear each other"
        "Dice" -> "a loop cut into slices, and rolled: swap, stutter, drop"
        "Forage" -> "sampled drums, with a filter and envelope per pad"
        "Manual" -> "tonewheel organ, two manuals and a spinning cabinet"
        "Filament" -> "strings by modelling - pluck, bow or breathe at them"
        "Brazen" -> "brass by modelling - one player, or a section that listens"
        "Timber" -> "woodwinds by modelling - reed, double reed or air, and the holes"
        "Mosaic" -> "multisamples: zones, SoundFonts, grain clouds"
        "Pollen" -> "granular clouds that seed their own, from a file or live"
        "Cipher" -> "a vocoder whose band map is the instrument"
        "Nexus" -> "a modular whose blocks are the other machines"
        else -> ""
    }

    /** Machines that play a whole multisample map rather than one-shot pads. */
    fun acceptsSampleMap(type: String): Boolean = type == "Mosaic"

    /**
     * Whether the machine answers the performance controllers, and so whether
     * the Edit screen shows the strip. A machine that ignores mod wheel and
     * pressure gets no strip rather than a dead one.
     */
    fun usesPerformance(type: String): Boolean =
        type == "Trinity" || type == "Ratio" || type == "Mosaic" || type == "Manual" ||
            type == "Cipher" || type == "Filament" || type == "Cumulus" || type == "Pollen" ||
            type == "Brazen" || type == "Timber"

    /** Genesis's kit, mirroring engine/machine/genesis/Genesis.h's Voice order. */
    val genesisVoices: List<DrumVoice> = listOf(
        DrumVoice(36, "Kick", "BD"), DrumVoice(37, "Snare", "SD"), DrumVoice(38, "Clap", "CP"),
        DrumVoice(39, "Rim", "RS"), DrumVoice(40, "Low Tom", "LT"), DrumVoice(41, "Mid Tom", "MT"),
        DrumVoice(42, "Hi Tom", "HT"), DrumVoice(43, "Closed Hat", "CH"), DrumVoice(44, "Open Hat", "OH"),
        DrumVoice(45, "Crash", "CY"), DrumVoice(46, "Ride", "RD"), DrumVoice(47, "Cowbell", "CB"),
    )

    val hexbeatVoices: List<DrumVoice> = listOf(
        DrumVoice(36, "Kick", "BD"), DrumVoice(37, "Rim", "RS"), DrumVoice(38, "Snare", "SD"), DrumVoice(39, "Clap", "CP"),
        DrumVoice(40, "Low Tom", "LT"), DrumVoice(41, "Mid Tom", "MT"), DrumVoice(42, "Hi Tom", "HT"),
        DrumVoice(43, "Closed Hat", "CH"), DrumVoice(44, "Open Hat", "OH"), DrumVoice(45, "Crash", "CY"),
        DrumVoice(46, "Ride", "RD"), DrumVoice(47, "Cowbell", "CB"), DrumVoice(48, "Clave", "CL"),
    )

    /** Forage pads are named after their samples; unloaded pads by number. */
    fun voicesOf(type: String, settings: Map<String, String> = emptyMap()): List<DrumVoice> = when (type) {
        "Hexbeat" -> hexbeatVoices
        "Genesis" -> genesisVoices
        // Eight objects, and what each one is is a parameter rather than a
        // name - so they are numbered here and named on the panel.
        "Resonance" -> (0 until 8).map { DrumVoice(36 + it, "Object ${it + 1}", "${it + 1}") }
        "Dice" -> (0 until 16).map { DrumVoice(36 + it, "Slice ${it + 1}", "${it + 1}") }
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

/**
 * One entry of a Mosaic map, as the document stores it. Zones live in
 * `Machine.settings["zones"]`, one per line, because they are a variable
 * length list rather than parameters - the same reason samples do.
 */
data class Zone(
    val path: String = "",
    val lowKey: Int = 0, val highKey: Int = 127, val rootKey: Int = 60,
    val lowVel: Int = 1, val highVel: Int = 127,
    val tuneCents: Float = 0f, val gain: Float = 1f, val pan: Float = 0f,
    val loop: Boolean = false,
) {
    fun encode(root: java.io.File?): String {
        val abs = if (root != null && !path.startsWith("/")) java.io.File(root, path).absolutePath else path
        return listOf(abs, lowKey, highKey, rootKey, lowVel, highVel, tuneCents, gain, pan, if (loop) 1 else 0)
            .joinToString("|")
    }
    val name: String get() = path.substringAfterLast('/').substringBeforeLast('.')
}

object Zones {
    fun decode(text: String?): List<Zone> = (text ?: "").lineSequence().mapNotNull { line ->
        val f = line.split('|')
        if (f.size < 10) null else runCatching {
            Zone(f[0], f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5].toInt(),
                f[6].toFloat(), f[7].toFloat(), f[8].toFloat(), f[9] != "0")
        }.getOrNull()
    }.toList()

    fun encode(zones: List<Zone>): String = zones.joinToString("\n") {
        listOf(it.path, it.lowKey, it.highKey, it.rootKey, it.lowVel, it.highVel, it.tuneCents, it.gain, it.pan,
            if (it.loop) 1 else 0).joinToString("|")
    }

    /** What the engine is asked to build: absolute paths, one zone per line. */
    fun spec(zones: List<Zone>, root: java.io.File?): String = zones.joinToString("\n") { it.encode(root) }
}
