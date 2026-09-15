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
    fun acceptsOneSample(type: String): Boolean =
        type == "Pollen" || type == "Dice" || type == "Molt"

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
        MachineGroup("realish", listOf("Manual", "Filament", "Brazen", "Timber", "Mosaic", "Pollen", "Molt")),
        MachineGroup("beyond", listOf("Cipher", "Nexus")),
    )

    /** One line per machine: what it is, not what it has. */
    fun describe(type: String): String = when (type) {
        "Subvert" -> "acid bass - one oscillator, one filter that screams"
        "Trinity" -> "three oscillators, wavetables, dual filters, a mod matrix"
        "Ratio" -> "six-operator FM, with the algorithm itself on a knob"
        "Cumulus" -> "pads by spectrum - bands of partials, morphed"
        "Formulate" -> "the chip, and an equation you can type into it"
        "Hexbeat" -> "drums by synthesis, in the small-box vocabulary"
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
        "Molt" -> "a sung take, tuned by the notes you draw"
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
            type == "Brazen" || type == "Timber" || type == "Molt"

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

    /**
     * The order the *pads* are laid out in, which is deliberately not the order
     * the grid lists them in.
     *
     * Two rows of an odd count give the *shorter* row the wider cells, and
     * `DrumPads` fills the rows top-first - so whatever is put last ends up
     * both wider and nearest the thumb. Left alone, Hexbeat's thirteen split
     * seven and six and handed that row to `CH OH CY RD CB CL`: the hats and
     * cymbals were 18% wider than the kick and the snare, which is the wrong
     * way round for every piece of music anybody plays.
     *
     * The grid keeps ascending note order, because a drum grid is read with
     * the kick at the top and that convention is older than this app.
     *
     * Looked up by short code rather than by index, because Genesis and
     * Hexbeat do not agree on note order (Genesis is BD SD CP RS, Hexbeat is
     * BD RS SD CP) and neither should break if a voice is ever added.
     */
    fun padOrder(type: String, voices: List<DrumVoice>): List<DrumVoice> {
        // Numbered slices, objects and samples have no pecking order; moving
        // pad 5 somewhere else would only make pad 5 hard to find.
        if (type != "Hexbeat" && type != "Genesis") return voices
        val core = listOf("BD", "RS", "SD", "CP", "CH", "OH")
        val rest = voices.filter { it.short !in core }
        val hand = core.mapNotNull { code -> voices.firstOrNull { it.short == code } }
        return rest + hand
    }

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
