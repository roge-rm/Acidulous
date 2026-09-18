package com.rm.acidulous.model

import android.content.Context
import com.rm.acidulous.engine.EngineAssets
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A machine's parameters, normalised 0..1, by name - and, where a machine
 * has one, the variable-length part of its state. A Nexus patch without its
 * graph would be a bag of knob values wired to nothing; the same is true of
 * a Mosaic patch without its zones.
 */
@Serializable
data class Patch(
    val machine: String,
    val name: String,
    val params: Map<String, Float>,
    val settings: Map<String, String> = emptyMap(),
    /**
     * The notes the patch is played in, or -1 for no opinion. A bassoon
     * has a bottom and an oboe has a top, and a patch that knows its range
     * can put the keyboard there when it is loaded - so the first note
     * pressed is a note the instrument has, rather than middle C on a
     * tuba. Factory patches carry the bank's `range=`; a saved patch
     * carries where the keyboard was.
     */
    val low: Int = -1,
    val high: Int = -1,
    /**
     * Which shelf of the bank this sits on - Mosaic's keys/pad/grain, Pollen's
     * cloud/bloom/rhythm, Hexbeat's classic/room/metal. Empty for a patch the
     * user saved, which belongs on its own shelf and nowhere else.
     *
     * The banks have carried `family=` since M45; until now it was read by the
     * audition harness, used to name the demo wavs, and dropped on the floor
     * on the way to the app. A bank of fifty-one in one list is a list nobody
     * reads to the end of.
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
     * The prefix that says a patch belongs to an effect rather than a machine.
     *
     * `Patch.machine` is only a string, and none of the nine effect names
     * collides with any of the nineteen machine names today - `Filter` is not
     * `Filament`. But the folder is built from that string, so a future
     * machine sharing a name with an effect would silently share its user
     * patches. One prefix now costs nothing and makes that impossible.
     */
    const val FX = "fx."

    fun directory(context: Context, machine: String): File =
        File(EngineAssets.userRoot(context), path(machine)).apply { mkdirs() }

    private fun path(machine: String): String =
        if (machine.startsWith(FX)) "patches/fx/${machine.removePrefix(FX)}" else "patches/$machine"

    fun save(context: Context, patch: Patch): File =
        File(directory(context, patch.machine), "${safe(patch.name)}.json").also { it.writeText(json.encodeToString(Patch.serializer(), patch)) }

    fun load(context: Context, machine: String, name: String): Patch? =
        factory(machine).firstOrNull { it.name == name }
            ?: File(directory(context, machine), "${safe(name)}.json").takeIf { it.isFile }?.let { json.decodeFromString(Patch.serializer(), it.readText()) }

    fun list(context: Context, machine: String): List<String> = factoryNames(machine) + userList(context, machine)

    fun factoryNames(machine: String): List<String> = factory(machine).map { it.name }

    fun userList(context: Context, machine: String): List<String> =
        directory(context, machine).listFiles { f -> f.extension == "json" }?.map { it.nameWithoutExtension }?.sorted() ?: emptyList()

    /** User patches only; factory ones are code. */
    fun delete(context: Context, machine: String, name: String): Boolean =
        File(directory(context, machine), "${safe(name)}.json").delete()

    /**
     * A unit's factory patches.
     *
     * All but one come from [FactoryBanks], which `tools/gen_patches.sh`
     * writes from the text in `tools/banks/`. They used to be written here by
     * hand as normalised floats, with each object carrying its own copy of
     * the engine's parameter ranges to convert with - copies that could drift
     * from the engine with nothing to notice. A bank file says `cutoff 620 Hz`
     * and the same ParamDef converts it for the audition harness and for
     * this, so what somebody listened to is what plays.
     *
     * Nexus used to be the exception, on the grounds that its patches needed
     * [NexusPalette] and so the engine over JNI. They do not: a Nexus patch is
     * a *graph*, and a graph is text. It lives in `tools/banks/Nexus.bank`
     * with every other machine's, and the knobs a patch does not name take the
     * module's own defaults rather than zero - which is what the code here was
     * really for.
     */
    fun factory(machine: String): List<Patch> =
        if (machine == "Nexus") FactoryBanks.of(machine).map { seedNexusKnobs(it) }
        else FactoryBanks.of(machine)

    /**
     * Fill in the knobs a Nexus patch did not name, from the modules it uses.
     *
     * Every slot knob defaults to zero, so a graph that states only what it
     * changes has an oscillator at zero level and makes no sound at all. The
     * audition harness seeds these when it mounts a graph; the app has to do
     * the same, and until it did every factory Nexus patch was silent on the
     * device while measuring correctly on the desk - which is as clear a
     * demonstration as one could want that a harness is not the product.
     *
     * The defaults come from the engine over JNI, which is exactly what the
     * bank generator cannot do and why this is here rather than in the file.
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
        } catch (e: LinkageError) {
            // No engine here - a unit test, or a tool reading the banks. The
            // patch is still a patch; it simply cannot be told what a module's
            // knobs want until something can ask.
            //
            // `LinkageError` rather than `UnsatisfiedLinkError`, because the
            // palette asks the engine from a lazy initialiser: the first
            // attempt throws the unsatisfied link, and every one after that
            // throws NoClassDefFoundError for a class that failed to load.
            return patch
        }
        // What the patch said for itself wins over the module's default.
        params.putAll(patch.params)
        return patch.copy(params = params)
    }

    /** An effect type as a patch key: "Delay" becomes "fx.Delay". */
    fun effectKey(type: String): String = FX + type

    private fun safe(name: String) = name.trim().replace(Regex("[^A-Za-z0-9 _-]"), "_").ifEmpty { "patch" }
}










/**
 * Nexus's factory patches. Each is a whole graph, which is why a patch had
 * to learn to carry settings: knob values alone would be a bag of numbers
 * wired to nothing.
 *
 * They are also the argument for the machine. Two of them are patches no
 * other modular could make, because two of the blocks are Filament's string
 * and Manual's cabinet.
 */






