package com.rm.acidulous.model

import com.rm.acidulous.io.*

import com.rm.acidulous.util.format

import com.rm.acidulous.res.*
import org.jetbrains.compose.resources.StringResource


/**
 * What a track is played with, which decides what the editor shows.
 *
 * It's an enum rather than a boolean because of [Audio]: a tape has no
 * keyboard, no pads and no notes at all.
 */
enum class MachineKind { Keyboard, Drums, Audio }

/**
 * One pad, as the pads and the grid draw it.
 *
 * [loaded] is only false where a pad can be empty, which is currently Forage.
 * Its thirteen pads hold whatever the player imported, and an empty pad needs
 * to look empty rather than like a sample named with a number.
 */
data class DrumVoice(val note: Int, val name: String, val short: String, val loaded: Boolean = true)

/**
 * What the editor needs to know about a machine type beyond its parameters:
 * whether it's played from a keyboard or pads, and what the pads are called.
 * Matches the voice order and base note in engine/machine/hexbeat/Hexbeat.h.
 */
object MachineUi {
    fun kindOf(type: String): MachineKind = when {
        type == "Bias" -> MachineKind.Audio
        type == "Hexbeat" || type == "Forage" || type == "Resonance" || type == "Dice" ||
            type == "Genesis" -> MachineKind.Drums
        else -> MachineKind.Keyboard
    }
    fun acceptsSamples(type: String): Boolean = type == "Forage"

    /** Machines whose notes are pitches and can be transposed: not drums or tape. */
    fun takesTranspose(type: String): Boolean = kindOf(type) == MachineKind.Keyboard

    /**
     * Machines that play a tuning: every melodic one. Drums and the audio
     * track have no scale to tune. The organ tunes each wheel as a note, and
     * Nexus tunes the pitch its blocks are given.
     */
    fun takesTuning(type: String): Boolean = kindOf(type) == MachineKind.Keyboard

    /**
     * Machines that hold one sample under the plain key "sample", unlike
     * Forage where each of the thirteen pads has its own.
     */
    fun acceptsOneSample(type: String): Boolean =
        type == "Pollen" || type == "Dice" || type == "Molt"

    /**
     * The machines in groups, each with a line saying what it is, since a
     * name like "Cipher" doesn't tell you it's a vocoder. Within a group
     * they're in the order they were built, which is roughly simplest first.
     */
    data class MachineGroup(val label: StringResource, val machines: List<String>)

    val machineGroups: List<MachineGroup> = listOf(
        MachineGroup(Res.string.machines_synths, listOf("Reflux", "Trinity", "Ratio", "Cumulus", "Formulate")),
        MachineGroup(Res.string.machines_drums, listOf("Hexbeat", "Genesis", "Resonance", "Forage", "Dice")),
        MachineGroup(Res.string.machines_realish, listOf("Manual", "Filament", "Brazen", "Timber", "Mosaic", "Pollen", "Molt")),
        MachineGroup(Res.string.machines_beyond, listOf("Cipher", "Nexus", "Diction", "Bias")),
    )

    /** One line per machine saying what it is. */
    fun describe(type: String): StringResource? = when (type) {
        "Reflux" -> Res.string.machine_about_reflux
        "Trinity" -> Res.string.machine_about_trinity
        "Ratio" -> Res.string.machine_about_ratio
        "Cumulus" -> Res.string.machine_about_cumulus
        "Formulate" -> Res.string.machine_about_formulate
        "Hexbeat" -> Res.string.machine_about_hexbeat
        "Genesis" -> Res.string.machine_about_genesis
        "Resonance" -> Res.string.machine_about_resonance
        "Dice" -> Res.string.machine_about_dice
        "Forage" -> Res.string.machine_about_forage
        "Manual" -> Res.string.machine_about_manual
        "Filament" -> Res.string.machine_about_filament
        "Brazen" -> Res.string.machine_about_brazen
        "Timber" -> Res.string.machine_about_timber
        "Mosaic" -> Res.string.machine_about_mosaic
        "Pollen" -> Res.string.machine_about_pollen
        "Molt" -> Res.string.machine_about_molt
        "Diction" -> Res.string.machine_about_diction
        "Cipher" -> Res.string.machine_about_cipher
        "Nexus" -> Res.string.machine_about_nexus
        "Bias" -> Res.string.machine_about_bias
        else -> null
    }

    /**
     * Parameters a patch doesn't own, by machine.
     *
     * Loading a patch normally replaces every parameter. Bias is the
     * exception: its four lane levels and mutes are how your takes are mixed,
     * and trying a different tape shouldn't wipe that.
     */
    fun patchKeeps(type: String): Set<String> =
        if (type != "Bias") emptySet()
        else (1..4).flatMap { listOf("lane$it", "mute$it") }.toSet()

    /** Machines that play a whole multisample map rather than one-shot pads. */
    fun acceptsSampleMap(type: String): Boolean = type == "Mosaic"

    /**
     * Whether the machine responds to the performance controllers, and so
     * whether the Edit screen shows the strip. Machines that ignore mod wheel
     * and pressure get no strip.
     */
    fun usesPerformance(type: String): Boolean =
        type == "Trinity" || type == "Ratio" || type == "Mosaic" || type == "Manual" ||
            type == "Cipher" || type == "Filament" || type == "Cumulus" || type == "Pollen" ||
            type == "Brazen" || type == "Timber" || type == "Molt" || type == "Diction"

    /** Genesis's kit, in the Voice order of engine/machine/genesis/Genesis.h. */
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
     * The order the pads are laid out in, which is intentionally different
     * from the grid's order.
     *
     * `DrumPads` puts the first half on the bottom row, where your hand rests,
     * and gives it the wider cells when the count is odd. So whatever comes
     * first here is nearest the thumb and biggest. Without this, Hexbeat's
     * bottom row would be the hats and cymbals instead of the kick and snare.
     *
     * The grid keeps ascending note order, with the kick at the top, as drum
     * grids usually do.
     *
     * Looked up by short code rather than index, because Genesis and Hexbeat
     * order their notes differently (BD SD CP RS vs BD RS SD CP), and adding a
     * voice shouldn't break either.
     */
    fun padOrder(type: String, voices: List<DrumVoice>): List<DrumVoice> {
        // Numbered slices, objects and samples have no natural order, and
        // moving pad 5 somewhere else would just make it hard to find.
        if (type != "Hexbeat" && type != "Genesis") return voices
        val core = listOf("BD", "RS", "SD", "CP", "CH", "OH")
        val hand = core.mapNotNull { code -> voices.firstOrNull { it.short == code } }
        return hand + voices.filter { it.short !in core }
    }

    /** Forage pads are named after their samples, and empty pads by number. */
    fun voicesOf(type: String, settings: Map<String, String> = emptyMap()): List<DrumVoice> = when (type) {
        "Hexbeat" -> hexbeatVoices
        "Genesis" -> genesisVoices
        // Eight objects whose sound is set by a parameter rather than a name,
        // so they're numbered here and named on the panel.
        "Resonance" -> (0 until 8).map { DrumVoice(36 + it, "Object ${it + 1}", "${it + 1}") }
        "Dice" -> (0 until 16).map { DrumVoice(36 + it, "Slice ${it + 1}", "${it + 1}") }
        // A Forage pad plays its own sample, a slice of the file the kit was
        // sliced from, or nothing. All three need to show on screen.
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
 * Every sample file the song uses, relative to the user root.
 *
 * Used when deleting from the sample browser, so a file a track is playing
 * isn't removed without a warning. Machines store samples in four ways and
 * this checks all of them: one `sample` (Dice, Pollen, Molt), thirteen
 * `pNN_sample` (Forage), Forage's `slice_sample`, and Mosaic's zones, which
 * keep their paths inside an encoded string.
 *
 * When unsure it errs towards "in use", since missing a path means a file can
 * be deleted without a warning.
 */
fun Song.samplesInUse(): Set<String> = buildSet {
    for (t in tracks) {
        for ((key, value) in t.machine.settings) {
            if (value.isEmpty()) continue
            if (key == "sample" || key == "slice_sample" || key.endsWith("_sample")) add(value)
            if (key == "zones") Zones.decode(value).forEach { if (it.path.isNotEmpty()) add(it.path) }
        }
        // Tape takes aren't in settings, they're on the clips, one per lane
        // per cell. Without this the delete page would offer to remove a
        // recording the song is playing.
        for (clip in t.clips.values) {
            val audio = clip.audio ?: continue
            for (take in audio.lanes) {
                if (take != null && take.file.isNotEmpty()) add(take.file)
            }
        }
    }
}

/**
 * One entry of a Mosaic map. Zones live in `Machine.settings["zones"]`, one
 * per line, because they're a variable length list rather than parameters.
 */
data class Zone(
    val path: String = "",
    val lowKey: Int = 0, val highKey: Int = 127, val rootKey: Int = 60,
    val lowVel: Int = 1, val highVel: Int = 127,
    val tuneCents: Float = 0f, val gain: Float = 1f, val pan: Float = 0f,
    val loop: Boolean = false,
) {
    fun encode(root: com.rm.acidulous.io.File?): String {
        val abs = if (root != null && !path.startsWith("/")) com.rm.acidulous.io.File(root, path).absolutePath else path
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
    fun spec(zones: List<Zone>, root: com.rm.acidulous.io.File?): String = zones.joinToString("\n") { it.encode(root) }
}
