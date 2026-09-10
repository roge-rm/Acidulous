package com.rm.acidulous.model

import android.content.Context
import com.rm.acidulous.engine.EngineAssets
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** A machine's parameters, normalised 0..1, by name. */
@Serializable
data class Patch(val machine: String, val name: String, val params: Map<String, Float>)

/** One JSON per patch under user/patches/<machine>/. Factory patches live in code. */
object PatchStore {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun directory(context: Context, machine: String): File =
        File(EngineAssets.userRoot(context), "patches/$machine").apply { mkdirs() }

    fun save(context: Context, patch: Patch): File =
        File(directory(context, patch.machine), "${safe(patch.name)}.json").also { it.writeText(json.encodeToString(Patch.serializer(), patch)) }

    fun load(context: Context, machine: String, name: String): Patch? =
        factory(machine).firstOrNull { it.name == name }
            ?: File(directory(context, machine), "${safe(name)}.json").takeIf { it.isFile }?.let { json.decodeFromString(Patch.serializer(), it.readText()) }

    fun list(context: Context, machine: String): List<String> =
        factory(machine).map { it.name } +
            (directory(context, machine).listFiles { f -> f.extension == "json" }?.map { it.nameWithoutExtension }?.sorted() ?: emptyList())

    fun factory(machine: String): List<Patch> = when (machine) {
        "SubVert" -> SubVertPresets.all
        else -> emptyList()
    }

    private fun safe(name: String) = name.trim().replace(Regex("[^A-Za-z0-9 _-]"), "_").ifEmpty { "patch" }
}

/**
 * SubVert's factory patches. Values are normalised; the names are ours.
 * Voiced on paper - Dan tunes by ear from the debug build.
 */
object SubVertPresets {
    private fun p(name: String, vararg kv: Pair<String, Float>) = Patch("SubVert", name, kv.toMap())

    val all: List<Patch> = listOf(
        p("Init"),
        p("Classic", "wave" to 0f, "cutoff" to 0.45f, "resonance" to 0.62f, "envmod" to 0.55f, "decay" to 0.5f,
            "accent" to 0.7f, "slide" to 0.5f, "drive" to 0.12f),
        p("Rubber", "wave" to 1f, "pw" to 0.5f, "cutoff" to 0.35f, "resonance" to 0.75f, "envmod" to 0.7f, "decay" to 0.35f,
            "accent" to 0.8f, "slide" to 0.6f, "drive" to 0.25f),
        p("Sub Hollow", "wave" to 1f, "pw" to 0.25f, "sub" to 0.6f, "cutoff" to 0.3f, "resonance" to 0.4f, "envmod" to 0.35f,
            "decay" to 0.6f, "mode" to 0f, "drive" to 0.05f),
        p("Band Squelch", "wave" to 0f, "cutoff" to 0.5f, "resonance" to 0.85f, "envmod" to 0.8f, "decay" to 0.3f,
            "accent" to 0.9f, "mode" to 1f, "drive" to 0.4f),
    )
}
