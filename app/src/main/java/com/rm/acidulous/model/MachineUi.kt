package com.rm.acidulous.model

/**
 * What the editor needs to know about a machine type beyond its parameters:
 * whether it is played from a keyboard or pads, and what the pads are called.
 * Mirrors the voice order and base note in engine/machine/hexbeat/Hexbeat.h.
 */
/**
 * What a track is played with, which decides what the editor shows.
 *
 * [Audio] is the odd one and the reason this is an enum rather than a boolean:
 * a tape has no keyboard, no pads and no notes at all. Every `== Drums` in
 * EditScreen used to mean "pads rather than keys", and each one had to be read
 * again as "pads, keys, or neither".
 */
enum class MachineKind { Keyboard, Drums, Audio }

/**
 * One pad, as the pads and the grid draw it.
 *
 * [loaded] is false only where a pad *can* be empty, which today is Forage:
 * its thirteen pads hold whatever the player imported, and an empty one used
 * to be labelled with its own number - indistinguishable from a pad holding a
 * sample whose name begins with a digit. A freshly added Forage track
 * therefore looked like a working drum machine and made no sound at all.
 */
data class DrumVoice(val note: Int, val name: String, val short: String, val loaded: Boolean = true)

object MachineUi {
    fun kindOf(type: String): MachineKind = when {
        type == "Bias" -> MachineKind.Audio
        type == "Hexbeat" || type == "Forage" || type == "Resonance" || type == "Dice" ||
            type == "Genesis" -> MachineKind.Drums
        else -> MachineKind.Keyboard
    }
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
        MachineGroup("synths", listOf("Reflux", "Trinity", "Ratio", "Cumulus", "Formulate")),
        MachineGroup("drums", listOf("Hexbeat", "Genesis", "Resonance", "Forage", "Dice")),
        MachineGroup("realish", listOf("Manual", "Filament", "Brazen", "Timber", "Mosaic", "Pollen", "Molt")),
        MachineGroup("beyond", listOf("Cipher", "Nexus", "Bias", "Bus")),
    )

    /** One line per machine: what it is, not what it has. */
    fun describe(type: String): String = when (type) {
        "Reflux" -> "acid bass - one oscillator, one filter that screams"
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
        "Bias" -> "a four-track: recordings arranged along the song"
        "Bus" -> "a group: other tracks routed through one fader and two inserts"
        else -> ""
    }

    /**
     * Parameters a patch does not own, by machine.
     *
     * Loading a patch replaces every parameter, which is right almost
     * everywhere: what a preset does not mention it wants at the machine's
     * default. Bias is the exception, because **a Bias patch is a medium and
     * not a mix** - its four lane levels and four mutes are where your take
     * sits against the others, and trying a different tape must not wipe that.
     */
    fun patchKeeps(type: String): Set<String> =
        if (type != "Bias") emptySet()
        else (1..4).flatMap { listOf("lane$it", "mute$it") }.toSet()

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
     * `DrumPads` puts the first half on the *bottom* row, where a hand rests,
     * and gives it the wider cells when the count is odd - so whatever comes
     * first here is both nearest the thumb and biggest. Left alone, Hexbeat's
     * thirteen handed that row to `CH OH CY RD CB CL`: the hats and cymbals
     * were wider than the kick and the snare, which is the wrong way round
     * for every piece of music anybody plays.
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
        val hand = core.mapNotNull { code -> voices.firstOrNull { it.short == code } }
        return hand + voices.filter { it.short !in core }
    }

    /** Forage pads are named after their samples; unloaded pads by number. */
    fun voicesOf(type: String, settings: Map<String, String> = emptyMap()): List<DrumVoice> = when (type) {
        "Hexbeat" -> hexbeatVoices
        "Genesis" -> genesisVoices
        // Eight objects, and what each one is is a parameter rather than a
        // name - so they are numbered here and named on the panel.
        "Resonance" -> (0 until 8).map { DrumVoice(36 + it, "Object ${it + 1}", "${it + 1}") }
        "Dice" -> (0 until 16).map { DrumVoice(36 + it, "Slice ${it + 1}", "${it + 1}") }
        // A Forage pad plays its own sample, or a piece of the file the whole
        // kit was sliced from, or nothing. All three have to be visible: the
        // first version of slicing wrote the shared file and the start and end
        // points and left every pad still drawn as empty, so the one thing the
        // player had asked for was the one thing nothing on screen said had
        // happened.
        "Forage" -> {
            val sliced = settings["slice_sample"]
            val sliceName = sliced?.substringAfterLast('/')?.substringBeforeLast('.').orEmpty()
            val sliceCount = settings["slice_count"]?.toIntOrNull() ?: 0
            (0 until 13).map { pad ->
                val file = settings["p%02d_sample".format(pad)]
                val own = file?.substringAfterLast('/')?.substringBeforeLast('.') ?: ""
                val isSlice = own.isEmpty() && sliced != null && pad < sliceCount
                DrumVoice(
                    note = 36 + pad,
                    name = when {
                        own.isNotEmpty() -> own
                        isSlice -> "$sliceName ${pad + 1}"
                        else -> "Pad ${pad + 1}"
                    },
                    short = when {
                        own.isNotEmpty() -> own.take(4)
                        isSlice -> "${pad + 1}"
                        else -> "+"
                    },
                    loaded = own.isNotEmpty() || isSlice,
                )
            }
        }
        else -> emptyList()
    }
}


/**
 * One entry of a Mosaic map, as the document stores it. Zones live in
 * `Machine.settings["zones"]`, one per line, because they are a variable
 * length list rather than parameters - the same reason samples do.
 */
/**
 * Every sample file the song refers to, relative to the user root.
 *
 * What it is for is deleting: the browser lets a player clear out the sample
 * folder, and a file that a track is playing must not go quietly. Machines
 * name their samples in four shapes and this knows all of them - one `sample`
 * (Dice, Pollen, Molt), thirteen `pNN_sample` (Forage), the `slice_sample`
 * behind Forage's pads, and Mosaic's zones, which keep their paths inside an
 * encoded string rather than in a setting of their own.
 *
 * Erring towards "in use": a path this misses is a file the player can delete
 * without being warned, which is the expensive direction to be wrong in.
 */
fun Song.samplesInUse(): Set<String> = buildSet {
    for (t in tracks) {
        for ((key, value) in t.machine.settings) {
            if (value.isEmpty()) continue
            if (key == "sample" || key == "slice_sample" || key.endsWith("_sample")) add(value)
            if (key == "zones") Zones.decode(value).forEach { if (it.path.isNotEmpty()) add(it.path) }
        }
        // **And the takes, which are not in settings.** Every other reference
        // to a recording is a machine setting; a tape's are on its clips, one
        // per lane per cell. Miss them and the library's delete page offers to
        // remove the vocal the song is playing - which is the exact failure
        // this set exists to prevent.
        for (clip in t.clips.values) {
            val audio = clip.audio ?: continue
            for (take in audio.lanes) {
                if (take != null && take.file.isNotEmpty()) add(take.file)
            }
        }
    }
}

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
