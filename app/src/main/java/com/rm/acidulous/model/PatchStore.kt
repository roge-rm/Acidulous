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

    fun list(context: Context, machine: String): List<String> = factoryNames(machine) + userList(context, machine)

    fun factoryNames(machine: String): List<String> = factory(machine).map { it.name }

    fun userList(context: Context, machine: String): List<String> =
        directory(context, machine).listFiles { f -> f.extension == "json" }?.map { it.nameWithoutExtension }?.sorted() ?: emptyList()

    /** User patches only; factory ones are code. */
    fun delete(context: Context, machine: String, name: String): Boolean =
        File(directory(context, machine), "${safe(name)}.json").delete()

    fun factory(machine: String): List<Patch> = when (machine) {
        "Subvert" -> SubvertPresets.all
        "Trinity" -> TrinityPresets.all
        "Hexbeat" -> HexbeatPresets.all
        else -> emptyList()
    }

    private fun safe(name: String) = name.trim().replace(Regex("[^A-Za-z0-9 _-]"), "_").ifEmpty { "patch" }
}

/**
 * Subvert's factory patches. Values are normalised; the names are ours.
 * Voiced on paper - Dan tunes by ear from the debug build.
 */
object SubvertPresets {
    private fun p(name: String, vararg kv: Pair<String, Float>) = Patch("Subvert", name, kv.toMap())

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

/**
 * Trinity's factory patches: one per corner of the architecture, so the
 * three oscillators, the wavetables, density, FM and the matrix each have a
 * starting point. Normalised values, computed from the engine's ranges by the
 * helpers below rather than guessed.
 */
object TrinityPresets {
    private fun lin(v: Float, min: Float, max: Float) = ((v - min) / (max - min)).coerceIn(0f, 1f)
    private fun exp(v: Float, min: Float, max: Float) =
        (kotlin.math.ln(v / min) / kotlin.math.ln(max / min)).toFloat().coerceIn(0f, 1f)
    private fun step(i: Int, count: Int) = (i.toFloat() / (count - 1)).coerceIn(0f, 1f)

    private fun wave(i: Int) = step(i, 12)
    private fun freq(hz: Float) = exp(hz, 20f, 20000f)
    private fun attack(sec: Float) = exp(sec, 0.001f, 10f)
    private fun decay(sec: Float) = exp(sec, 0.002f, 15f)
    private fun release(sec: Float) = decay(sec)
    private fun coarse(st: Int) = lin(st.toFloat(), -24f, 24f)
    private fun fine(cents: Float) = lin(cents, -50f, 50f)
    private fun density(n: Int) = step(n - 1, 8)
    private fun ftype(i: Int) = step(i, 12)
    private fun src(i: Int) = step(i, 16)
    private fun dest(i: Int) = step(i, 34)
    private fun depth(v: Float) = lin(v, -1f, 1f)
    private fun rate(hz: Float) = exp(hz, 0.01f, 40f)

    // Matrix source and destination indices, mirroring Trinity.h.
    private const val SRC_LFO1 = 13; private const val SRC_LFO2 = 14
    private const val SRC_VEL = 4; private const val SRC_ENV3 = 9; private const val SRC_MOD = 2
    private const val DST_POS1 = 5; private const val DST_F1FREQ = 23; private const val DST_PITCH = 1
    private const val DST_FM21 = 21; private const val DST_SYNC1 = 14

    private fun p(name: String, vararg kv: Pair<String, Float>) = Patch("Trinity", name, kv.toMap())

    val all: List<Patch> = listOf(
        p("Init"),
        p("Wide Saws",
            "o1_wave" to wave(0), "o1_level" to 0.7f, "o1_density" to density(6), "o1_detune" to 0.45f,
            "o2_wave" to wave(0), "o2_level" to 0.6f, "o2_density" to density(4), "o2_detune" to 0.6f, "o2_fine" to fine(-9f),
            "o3_wave" to wave(0), "o3_level" to 0.4f, "o3_coarse" to coarse(-12), "o3_density" to density(2),
            "f1_type" to ftype(3), "f1_freq" to freq(6000f), "f1_res" to 0.1f, "f1_env" to depth(0.25f),
            "a_attack" to attack(0.02f), "a_decay" to decay(1.2f), "a_sustain" to 0.8f, "a_release" to release(0.5f),
            "o1_drift" to 0.3f, "o2_drift" to 0.3f, "o3_drift" to 0.2f),
        p("Glass Pad",
            "o1_wave" to wave(5), "o1_level" to 0.75f, "o1_pos" to 0.2f,
            "o2_wave" to wave(4), "o2_level" to 0.5f, "o2_pos" to 0.6f, "o2_fine" to fine(6f),
            "o3_level" to 0f,
            "f1_type" to ftype(1), "f1_freq" to freq(4000f), "f1_res" to 0.2f,
            "a_attack" to attack(0.8f), "a_decay" to decay(2f), "a_sustain" to 0.75f, "a_release" to release(2.5f),
            "l1_rate" to rate(0.15f), "l1_wave" to step(0, 9),
            "m01_src" to src(SRC_LFO1), "m01_dest" to dest(DST_POS1), "m01_depth" to depth(0.4f),
            "m02_src" to src(SRC_LFO2), "m02_dest" to dest(DST_F1FREQ), "m02_depth" to depth(0.15f),
            "l2_rate" to rate(0.07f)),
        p("Bell Keys",
            "o1_wave" to wave(7), "o1_level" to 0.8f, "o1_pos" to 0.7f,
            "o2_wave" to wave(3), "o2_level" to 0.35f, "o2_coarse" to coarse(12),
            "o3_level" to 0f, "ring12" to 0.35f,
            "f1_type" to ftype(3), "f1_freq" to freq(9000f), "f1_env" to depth(0.5f),
            "a_attack" to attack(0.002f), "a_decay" to decay(1.6f), "a_sustain" to 0.05f, "a_release" to release(1.2f),
            "f_decay" to decay(0.8f), "f_sustain" to 0f),
        p("FM Bass",
            "o1_wave" to wave(3), "o1_level" to 0.9f,
            "o2_wave" to wave(3), "o2_level" to 0f, "o2_coarse" to coarse(12),
            "o3_level" to 0f, "fm21" to 0.55f,
            "f1_type" to ftype(3), "f1_freq" to freq(900f), "f1_res" to 0.25f, "f1_env" to depth(0.55f),
            "a_attack" to attack(0.002f), "a_decay" to decay(0.35f), "a_sustain" to 0.35f, "a_release" to release(0.15f),
            "f_decay" to decay(0.2f), "f_sustain" to 0f,
            "voicemode" to step(1, 4), "glide" to 0.04f,
            "m01_src" to src(SRC_ENV3) , "m01_dest" to dest(DST_FM21), "m01_depth" to depth(0.3f),
            "e3_decay" to decay(0.25f), "e3_sustain" to 0f),
        p("Sync Lead",
            "o1_wave" to wave(0), "o1_level" to 0.85f, "o1_sync" to 0.25f,
            "o2_wave" to wave(1), "o2_level" to 0.3f, "o2_fine" to fine(5f),
            "o3_level" to 0f,
            "f1_type" to ftype(3), "f1_freq" to freq(7000f), "f1_res" to 0.15f,
            "a_attack" to attack(0.01f), "a_decay" to decay(0.5f), "a_sustain" to 0.85f, "a_release" to release(0.2f),
            "voicemode" to step(2, 4), "glide" to 0.06f,
            "m01_src" to src(SRC_MOD), "m01_dest" to dest(DST_SYNC1), "m01_depth" to depth(0.7f),
            "m02_src" to src(SRC_VEL), "m02_dest" to dest(DST_F1FREQ), "m02_depth" to depth(0.3f)),
        p("Drift Strings",
            "o1_wave" to wave(0), "o1_level" to 0.6f, "o1_density" to density(3), "o1_detune" to 0.3f, "o1_drift" to 0.8f,
            "o2_wave" to wave(0), "o2_level" to 0.55f, "o2_fine" to fine(-7f), "o2_drift" to 0.9f,
            "o3_wave" to wave(2), "o3_level" to 0.35f, "o3_coarse" to coarse(12), "o3_drift" to 0.7f,
            "f1_type" to ftype(2), "f1_freq" to freq(3500f), "f1_res" to 0.05f,
            "a_attack" to attack(0.35f), "a_decay" to decay(2f), "a_sustain" to 0.85f, "a_release" to release(1.4f),
            "l1_rate" to rate(4.5f), "l1_delay" to 0.4f,
            "m01_src" to src(SRC_LFO1), "m01_src2" to src(SRC_MOD), "m01_dest" to dest(DST_PITCH), "m01_depth" to depth(0.02f)),
    )
}
