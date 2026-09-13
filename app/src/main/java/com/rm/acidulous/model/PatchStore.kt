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
     * Nexus is the exception and stays in code: its patches are built from
     * [NexusPalette], which asks the engine for the module list over JNI, so
     * they cannot be enumerated anywhere the engine is not - which is every
     * tool that would generate them.
     */
    fun factory(machine: String): List<Patch> =
        if (machine == "Nexus") NexusPresets.all else FactoryBanks.of(machine)

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
object NexusPresets {
    private fun graph(
        name: String,
        modules: List<Triple<Int, String, Boolean>>,
        cables: List<List<Int>>,
        knobs: Map<String, Float> = emptyMap(),
    ): Patch {
        val patch = NexusPatch(
            modules = modules.mapIndexed { i, (slot, type, poly) ->
                NexusModule(slot, type, poly, 60f + (i % 4) * 190f, 60f + (i / 4) * 130f)
            },
            cables = cables.map { NexusCable(it[0], it[1], it[2], it[3]) },
        )
        // Every module starts at its own defaults; the patch then says what
        // it wants different. The defaults come from the engine, so there is
        // no second table of numbers here to drift out of step.
        val params = mutableMapOf<String, Float>()
        for (m in patch.modules) {
            NexusPalette.of(m.type)?.defaults?.forEachIndexed { i, d ->
                if (i < NEXUS_KNOBS) params[nexusKnob(m.slot, i)] = d
            }
        }
        params.putAll(knobs)
        return Patch("Nexus", name, params, mapOf("nexus" to patch.encode()))
    }

    val all: List<Patch> by lazy {
        listOf(
            graph("Init",
                listOf(Triple(0, "voice", true), Triple(1, "osc", true), Triple(2, "out", false)),
                listOf(listOf(1, 0, 2, 0)),
            ),
            // The patch everybody builds first, so it is here already.
            graph("Subtractive",
                listOf(Triple(0, "voice", true), Triple(1, "osc", true), Triple(2, "filter", true),
                       Triple(3, "env", true), Triple(4, "vca", true), Triple(5, "out", false)),
                listOf(listOf(1, 0, 2, 0), listOf(2, 0, 4, 0), listOf(0, 1, 3, 0),
                       listOf(3, 0, 4, 1), listOf(4, 0, 5, 0)),
                mapOf(nexusKnob(2, 1) to 0.45f, nexusKnob(2, 2) to 0.35f,
                      nexusKnob(3, 1) to 0.05f, nexusKnob(3, 3) to 0.3f, nexusKnob(5, 0) to 0.35f),
            ),
            // Filament's string, plucked by a burst of noise. No other
            // modular has this block because no other app has Filament.
            graph("Plucked",
                listOf(Triple(0, "voice", true), Triple(1, "noise", true), Triple(2, "env", true),
                       Triple(3, "vca", true), Triple(4, "string", true), Triple(5, "out", false)),
                listOf(listOf(1, 0, 3, 0), listOf(0, 1, 2, 0), listOf(2, 0, 3, 1),
                       listOf(3, 0, 4, 0), listOf(4, 0, 5, 0)),
                mapOf(nexusKnob(2, 1) to 0.0f, nexusKnob(2, 2) to 0.02f, nexusKnob(2, 3) to 0.0f,
                      nexusKnob(4, 1) to 0.94f, nexusKnob(5, 0) to 0.4f),
            ),
            // And through Manual's cabinet, which is the other block nothing
            // else can offer.
            graph("Leslie String",
                listOf(Triple(0, "voice", true), Triple(1, "noise", true), Triple(2, "env", true),
                       Triple(3, "vca", true), Triple(4, "string", true), Triple(5, "rotary", false),
                       Triple(6, "out", false)),
                listOf(listOf(1, 0, 3, 0), listOf(0, 1, 2, 0), listOf(2, 0, 3, 1),
                       listOf(3, 0, 4, 0), listOf(4, 0, 5, 0), listOf(5, 0, 6, 0)),
                mapOf(nexusKnob(2, 1) to 0.0f, nexusKnob(2, 2) to 0.02f, nexusKnob(2, 3) to 0.0f,
                      nexusKnob(4, 1) to 0.95f, nexusKnob(6, 0) to 0.4f),
            ),
            // Two operators and a wavetable: Ratio taken apart and patched.
            graph("Two Operators",
                listOf(Triple(0, "voice", true), Triple(1, "op", true), Triple(2, "op", true),
                       Triple(3, "env", true), Triple(4, "vca", true), Triple(5, "out", false)),
                listOf(listOf(1, 0, 2, 0), listOf(2, 0, 4, 0), listOf(0, 1, 3, 0),
                       listOf(3, 0, 4, 1), listOf(4, 0, 5, 0)),
                mapOf(nexusKnob(1, 1) to 0.1f, nexusKnob(3, 2) to 0.5f, nexusKnob(5, 0) to 0.35f),
            ),
            // A patch that plays itself: the transport drives it, nothing else.
            graph("Runs Itself",
                listOf(Triple(0, "clock", false), Triple(1, "euclid", false), Triple(2, "rand", false),
                       Triple(3, "quant", false), Triple(4, "osc", true), Triple(5, "env", true),
                       Triple(6, "vca", true), Triple(7, "out", false)),
                listOf(listOf(0, 0, 1, 0), listOf(1, 0, 2, 0), listOf(2, 0, 3, 0),
                       listOf(3, 0, 4, 0), listOf(1, 0, 5, 0), listOf(4, 0, 6, 0),
                       listOf(5, 0, 6, 1), listOf(6, 0, 7, 0)),
                mapOf(nexusKnob(1, 1) to 0.3f, nexusKnob(5, 1) to 0.05f, nexusKnob(5, 3) to 0.25f,
                      nexusKnob(7, 0) to 0.35f),
            ),
            // Whatever is plugged in, granulated and sent round the cabinet.
            graph("Listening",
                listOf(Triple(0, "audioin", false), Triple(1, "grain", false), Triple(2, "rotary", false),
                       Triple(3, "out", false)),
                listOf(listOf(0, 0, 1, 0), listOf(1, 0, 2, 0), listOf(2, 0, 3, 0)),
                mapOf(nexusKnob(1, 2) to 0.55f, nexusKnob(3, 0) to 0.4f),
            ),
        )
    }
}






