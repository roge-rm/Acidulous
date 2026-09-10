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
        "Ratio" -> RatioPresets.all
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
    private const val SRC_PRESSURE = 3
    private const val DST_POS1 = 5; private const val DST_F1FREQ = 23; private const val DST_PITCH = 1
    private const val DST_FM21 = 21; private const val DST_SYNC1 = 14; private const val DST_DETUNE = 17

    private fun p(name: String, vararg kv: Pair<String, Float>) = Patch("Trinity", name, kv.toMap())

    val all: List<Patch> = listOf(
        p("Init"),
        p("Wide Saws",
            "o1_wave" to wave(0), "o1_level" to 0.7f, "o1_density" to density(6), "o1_detune" to 0.45f,
            "o2_wave" to wave(0), "o2_level" to 0.6f, "o2_density" to density(4), "o2_detune" to 0.6f, "o2_fine" to fine(-9f),
            "o3_wave" to wave(0), "o3_level" to 0.4f, "o3_coarse" to coarse(-12), "o3_density" to density(2),
            "f1_type" to ftype(3), "f1_freq" to freq(6000f), "f1_res" to 0.1f, "f1_env" to depth(0.25f),
            "a_attack" to attack(0.02f), "a_decay" to decay(1.2f), "a_sustain" to 0.8f, "a_release" to release(0.5f),
            "o1_drift" to 0.3f, "o2_drift" to 0.3f, "o3_drift" to 0.2f,
            "m01_src" to src(SRC_MOD), "m01_dest" to dest(DST_F1FREQ), "m01_depth" to depth(0.4f),
            "m02_src" to src(SRC_PRESSURE), "m02_dest" to dest(DST_DETUNE), "m02_depth" to depth(0.4f)),
        p("Glass Pad",
            "o1_wave" to wave(5), "o1_level" to 0.75f, "o1_pos" to 0.2f,
            "o2_wave" to wave(4), "o2_level" to 0.5f, "o2_pos" to 0.6f, "o2_fine" to fine(6f),
            "o3_level" to 0f,
            "f1_type" to ftype(1), "f1_freq" to freq(4000f), "f1_res" to 0.2f,
            "a_attack" to attack(0.8f), "a_decay" to decay(2f), "a_sustain" to 0.75f, "a_release" to release(2.5f),
            "l1_rate" to rate(0.15f), "l1_wave" to step(0, 9),
            "m01_src" to src(SRC_LFO1), "m01_dest" to dest(DST_POS1), "m01_depth" to depth(0.4f),
            "m02_src" to src(SRC_LFO2), "m02_dest" to dest(DST_F1FREQ), "m02_depth" to depth(0.15f),
            "l2_rate" to rate(0.07f),
            // The performance strip: the wheel opens it up, pressure leans on the filter.
            "m03_src" to src(SRC_MOD), "m03_dest" to dest(DST_POS1), "m03_depth" to depth(0.5f),
            "m04_src" to src(SRC_PRESSURE), "m04_dest" to dest(DST_F1FREQ), "m04_depth" to depth(0.45f)),
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

/**
 * Ratio's factory patches: one per corner of the architecture, so the six
 * operators, the eight operator modes, the algorithm morph and the ratio
 * snap each have a starting point.
 */
object RatioPresets {
    private fun lin(v: Float, min: Float, max: Float) = ((v - min) / (max - min)).coerceIn(0f, 1f)
    private fun exp(v: Float, min: Float, max: Float) =
        (kotlin.math.ln(v / min) / kotlin.math.ln(max / min)).toFloat().coerceIn(0f, 1f)
    private fun step(i: Int, count: Int) = (i.toFloat() / (count - 1)).coerceIn(0f, 1f)

    private fun algo(i: Int) = step(i, 32)
    private fun wave(i: Int) = step(i, 16)
    private fun mode(i: Int) = step(i, 8)
    private fun snap(i: Int) = step(i, 6)
    private fun ratio(v: Float) = exp(v, 0.25f, 64f)
    private fun freq(hz: Float) = exp(hz, 20f, 20000f)
    private fun attack(sec: Float) = exp(sec, 0.001f, 10f)
    private fun decay(sec: Float) = exp(sec, 0.002f, 15f)
    private fun skew(v: Float) = lin(v, -0.5f, 0.5f)
    private fun ftype(i: Int) = step(i, 12)
    private fun src(i: Int) = step(i, 14)
    private fun dest(i: Int) = step(i, 24)
    private fun depth(v: Float) = lin(v, -1f, 1f)

    // Mirrors Ratio.h.
    private const val SRC_EG1 = 7; private const val SRC_MOD = 2; private const val SRC_PRESSURE = 3
    private const val SRC_LFO1 = 11; private const val SRC_VEL = 4
    private const val DST_MORPH = 2; private const val DST_SKEW = 3; private const val DST_LEVEL2 = 5

    /** Silences the operators a patch does not use, so nothing leaks from the defaults. */
    private fun ops(vararg used: Int): List<Pair<String, Float>> =
        (1..6).filter { it !in used }.map { "o${it}_level" to 0f }

    private fun p(name: String, vararg kv: Pair<String, Float>) = Patch("Ratio", name, kv.toMap())
    private fun p(name: String, extra: List<Pair<String, Float>>, vararg kv: Pair<String, Float>) =
        Patch("Ratio", name, (extra + kv).toMap())

    val all: List<Patch> = listOf(
        p("Init"),
        p("Tine", ops(1, 2, 3, 4),
            "algoa" to algo(6), "snap" to snap(1),
            "o1_level" to 0.8f, "o1_ratio" to ratio(1f), "o1_decay" to decay(2.5f), "o1_sustain" to 0.25f,
            "o2_level" to 0.55f, "o2_ratio" to ratio(14f), "o2_decay" to decay(0.35f), "o2_sustain" to 0f, "o2_vel" to 0.9f,
            "o3_level" to 0.5f, "o3_ratio" to ratio(1f), "o3_decay" to decay(2f), "o3_sustain" to 0.2f,
            "o4_level" to 0.35f, "o4_ratio" to ratio(3f), "o4_decay" to decay(0.8f), "o4_sustain" to 0f),
        p("Bell", ops(1, 2, 3),
            "algoa" to algo(6), "snap" to snap(5),           // bell partials
            "o1_level" to 0.8f, "o1_ratio" to ratio(1f), "o1_decay" to decay(6f), "o1_sustain" to 0f,
            "o2_level" to 0.6f, "o2_ratio" to ratio(2.76f), "o2_decay" to decay(3f), "o2_sustain" to 0f,
            "o3_level" to 0.45f, "o3_ratio" to ratio(5.4f), "o3_decay" to decay(4f), "o3_sustain" to 0f,
            "o1_release" to decay(6f), "o2_release" to decay(3f), "o3_release" to decay(4f)),
        p("Grit Bass", ops(1, 2, 3),
            "algoa" to algo(0), "snap" to snap(1), "voicemode" to step(1, 3),
            "o1_level" to 0.9f, "o1_ratio" to ratio(1f), "o1_decay" to decay(0.5f), "o1_sustain" to 0.6f,
            "o2_level" to 0.65f, "o2_ratio" to ratio(1f), "o2_decay" to decay(0.25f), "o2_sustain" to 0.15f, "o2_fb" to 0.55f,
            "o3_level" to 0.4f, "o3_mode" to mode(7), "o3_fb" to 0.4f,   // crush in the chain
            "f_type" to ftype(3), "f_freq" to freq(2200f), "f_res" to 0.2f,
            "m01_src" to src(SRC_VEL), "m01_dest" to dest(DST_LEVEL2), "m01_depth" to depth(0.35f)),
        p("Morph Sweep", ops(1, 2, 3, 4, 5, 6),
            "algoa" to algo(0), "algob" to algo(22), "morph" to 0f, "snap" to snap(1),
            "o1_level" to 0.7f, "o2_level" to 0.6f, "o3_level" to 0.5f,
            "o4_level" to 0.45f, "o5_level" to 0.4f, "o6_level" to 0.35f,
            "o2_ratio" to ratio(2f), "o3_ratio" to ratio(3f), "o4_ratio" to ratio(4f),
            "o5_ratio" to ratio(6f), "o6_ratio" to ratio(8f),
            "o1_sustain" to 0.8f, "o2_sustain" to 0.7f, "o3_sustain" to 0.6f,
            "o1_attack" to attack(0.4f), "o1_release" to decay(1.5f),
            // the headline: an envelope and the wheel both walk the routing
            "m01_src" to src(SRC_EG1), "m01_dest" to dest(DST_MORPH), "m01_depth" to depth(0.7f),
            "m02_src" to src(SRC_MOD), "m02_dest" to dest(DST_MORPH), "m02_depth" to depth(0.6f),
            "e1_attack" to attack(1.2f), "e1_decay" to decay(3f), "e1_sustain" to 0.4f),
        p("Fold Lead", ops(1, 2),
            "algoa" to algo(3), "snap" to snap(1), "voicemode" to step(2, 3), "glide" to 0.05f,
            "o1_level" to 0.85f, "o1_mode" to mode(4), "o1_fb" to 0.45f,   // wave folder
            "o2_level" to 0.8f, "o2_wave" to wave(7), "o2_ratio" to ratio(1f), "o2_sustain" to 0.9f,
            "o1_sustain" to 0.9f,
            "m01_src" to src(SRC_PRESSURE), "m01_dest" to dest(DST_SKEW), "m01_depth" to depth(0.3f)),
        p("Sync Stab", ops(1, 2),
            "algoa" to algo(3), "snap" to snap(0),
            "o1_level" to 0.85f, "o1_mode" to mode(5), "o1_ratio" to ratio(4.5f),  // synced to op2
            "o2_level" to 0.9f, "o2_wave" to wave(7), "o2_ratio" to ratio(1f), "o2_sustain" to 1f,
            "o1_decay" to decay(0.6f), "o1_sustain" to 0.3f,
            "m01_src" to src(SRC_LFO1), "m01_dest" to dest(10), "m01_depth" to depth(0.25f), // ratio1
            "l1_rate" to exp(0.4f, 0.01f, 40f)),
        p("Stretched", ops(1, 2, 3),
            "algoa" to algo(6), "snap" to snap(1), "skew" to skew(0.22f),
            "o1_level" to 0.75f, "o2_level" to 0.55f, "o2_ratio" to ratio(3f),
            "o3_level" to 0.4f, "o3_ratio" to ratio(7f),
            "o1_decay" to decay(3f), "o1_sustain" to 0.3f, "o1_release" to decay(2f),
            "m01_src" to src(SRC_MOD), "m01_dest" to dest(DST_SKEW), "m01_depth" to depth(0.5f)),
    )
}
