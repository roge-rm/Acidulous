package com.rm.acidulous.model

import com.rm.acidulous.io.*

import com.rm.acidulous.engine.EngineAssets
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A machine's parameters, normalised 0..1, by name, plus the variable-length
 * part of its state if it has one. A Nexus patch needs its graph and a
 * Mosaic patch needs its zones, or the knob values are wired to nothing.
 */
@Serializable
data class Patch(
    val machine: String,
    val name: String,
    val params: Map<String, Float>,
    val settings: Map<String, String> = emptyMap(),
    /**
     * The note range the patch is played in, or -1 for none. Loading a patch
     * with a range moves the keyboard there, so the first note you press is
     * one the instrument has. Factory patches use the bank's `range=`, and
     * saved patches store where the keyboard was.
     */
    val low: Int = -1,
    val high: Int = -1,
    /**
     * Which group of the bank this patch is in, like Mosaic's keys/pad/grain
     * or Hexbeat's classic/room/metal. Empty for patches the user saved, which
     * go in their own group. Comes from the bank's `family=`.
     */
    val family: String = "",
)

/**
 * One JSON per patch under `user/patches/<machine>/`.
 *
 * Factory patches are generated into [FactoryBanks] from the text in
 * `tools/banks/`; see [factory].
 */
object PatchStore {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /**
     * The prefix that marks a patch as an effect's rather than a machine's.
     *
     * `Patch.machine` is just a string and the folder is built from it. No
     * effect and machine share a name today, but if one ever did they'd share
     * user patches. The prefix rules that out.
     */
    const val FX = "fx."

    fun directory(machine: String): File =
        File(EngineAssets.userRoot(), path(machine)).apply { mkdirs() }

    private fun path(machine: String): String =
        if (machine.startsWith(FX)) "patches/fx/${machine.removePrefix(FX)}" else "patches/$machine"

    fun save(patch: Patch): File =
        File(directory(patch.machine), "${safe(patch.name)}.json").also { it.writeTextSafely(json.encodeToString(Patch.serializer(), patch)) }

    fun load(machine: String, name: String): Patch? =
        factory(machine).firstOrNull { it.name == name }
            ?: File(directory(machine), "${safe(name)}.json").takeIf { it.isFile }?.let { json.decodeFromString(Patch.serializer(), it.readText()) }

    fun list(machine: String): List<String> = factoryNames(machine) + userList(machine)

    fun factoryNames(machine: String): List<String> = factory(machine).map { it.name }

    fun userList(machine: String): List<String> =
        directory(machine).listFiles { f -> f.extension == "json" }?.map { it.nameWithoutExtension }?.sorted() ?: emptyList()

    /** User patches only. Factory ones are built in. */
    fun delete(machine: String, name: String): Boolean =
        File(directory(machine), "${safe(name)}.json").delete()

    /**
     * A unit's factory patches.
     *
     * They come from [FactoryBanks], which `tools/gen_patches.sh` writes from
     * the text in `tools/banks/`. A bank file says `cutoff 620 Hz` and the
     * engine's own ParamDef converts it, for both the audition harness and
     * the app, so what was auditioned is what plays.
     *
     * Nexus patches are graphs stored as text in `tools/banks/Nexus.bank`
     * like the rest. The knobs a patch doesn't set get the module's defaults
     * rather than zero, see [seedNexusKnobs].
     */
    fun factory(machine: String): List<Patch> =
        if (machine == "Nexus") FactoryBanks.of(machine).map { seedNexusKnobs(it) }
        else FactoryBanks.of(machine)

    /**
     * Fills in the knobs a Nexus patch didn't set, from the modules it uses.
     *
     * Every slot knob defaults to zero, so a graph that only states what it
     * changes would have an oscillator at zero level and make no sound. The
     * audition harness does the same when it loads a graph.
     *
     * The defaults come from the engine over JNI, which the bank generator
     * can't do, so it happens here instead of in the generated file.
     */
    private fun seedNexusKnobs(patch: Patch): Patch {
        val graph = NexusPatch.decode(patch.settings["nexus"])
        if (graph.modules.isEmpty()) return patch
        val params = mutableMapOf<String, Float>()
        try {
            for (m in graph.modules) {
                NexusPalette.of(m.type)?.defaults?.forEachIndexed { i, d ->
                    if (i < NEXUS_KNOBS) params[nexusKnob(m.slot, i)] = d
                }
            }
        } catch (e: Throwable) {
            if (!com.rm.acidulous.util.isEngineMissing(e)) throw e
            // No engine here, e.g. a unit test or a tool reading the banks.
            // Return the patch without module defaults.
            //
            // It's a `LinkageError` rather than `UnsatisfiedLinkError` because
            // the palette asks the engine from a lazy initialiser: the first
            // try throws the unsatisfied link, and later ones throw
            // NoClassDefFoundError for the class that failed to load.
            return patch
        }
        // Values the patch set itself win over the module's defaults.
        params.putAll(patch.params)
        return patch.copy(params = params)
    }

    /** An effect type as a patch key: "Delay" becomes "fx.Delay". */
    fun effectKey(type: String): String = FX + type

    private fun safe(name: String) = name.trim().replace(Regex("[^A-Za-z0-9 _-]"), "_").ifEmpty { "patch" }
}
/** How a machine remembers which patch it is on, in its settings. */
object PatchMark {
    /** Which patch a machine is showing. Just a name, not a reference. */
    const val NAME = "patch_name"

    /**
     * A stamp of that patch's knob values, so the name can show when it's
     * out of date.
     *
     * Storing every value would add tens of kilobytes to each song, so it's a
     * 64-bit FNV hash of the same numbers in a fixed order.
     */
    const val STAMP = "patch_stamp"

    /** The parameters as one comparable value. Sorted so map order can't matter. */
    fun stampOf(params: Map<String, Float>): String =
        fnv1a64(params.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }).toString(16)
}
