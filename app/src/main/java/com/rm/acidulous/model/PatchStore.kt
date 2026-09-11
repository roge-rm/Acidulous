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
        "Mosaic" -> MosaicPresets.all
        "Hexbeat" -> HexbeatPresets.all
        "Resonance" -> ResonancePresets.all
        "Manual" -> ManualPresets.all
        "Pollen" -> PollenPresets.all
        "Cumulus" -> CumulusPresets.all
        "Formulate" -> FormulatePresets.all
        "Cipher" -> CipherPresets.all
        "Filament" -> FilamentPresets.all
        "Nexus" -> NexusPresets.all
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

/**
 * Mosaic's factory patches. They set how the instrument is *played* - the
 * envelope, the filter, the blend, the grain cloud - and never which
 * instrument is loaded, because the map lives in the track's settings rather
 * than in its parameters. So any patch works with any SoundFont or zone set.
 */
object MosaicPresets {
    private fun lin(v: Float, min: Float, max: Float) = ((v - min) / (max - min)).coerceIn(0f, 1f)
    private fun exp(v: Float, min: Float, max: Float) =
        (kotlin.math.ln(v / min) / kotlin.math.ln(max / min)).toFloat().coerceIn(0f, 1f)
    private fun step(i: Int, count: Int) = (i.toFloat() / (count - 1)).coerceIn(0f, 1f)
    private fun attack(sec: Float) = exp(sec, 0.001f, 10f)
    private fun decay(sec: Float) = exp(sec, 0.002f, 15f)
    private fun freq(hz: Float) = exp(hz, 20f, 20000f)
    private fun size(ms: Float) = exp(ms, 5f, 500f)
    private fun density(n: Float) = exp(n, 1f, 120f)
    private fun rate(v: Float) = lin(v, -2f, 2f)
    private fun src(i: Int) = step(i, 13)
    private fun dest(i: Int) = step(i, 16)
    private fun depth(v: Float) = lin(v, -1f, 1f)

    private const val SRC_MOD = 2; private const val SRC_LFO1 = 11; private const val SRC_PRESSURE = 3
    private const val DST_SCAN = 2; private const val DST_GPOS = 4; private const val DST_GPITCH = 9

    private fun p(name: String, vararg kv: Pair<String, Float>) = Patch("Mosaic", name, kv.toMap())

    val all: List<Patch> = listOf(
        p("Init"),
        p("Soft Pad",
            "a_attack" to attack(0.6f), "a_decay" to decay(3f), "a_sustain" to 0.9f, "a_release" to decay(1.8f),
            "f_type" to step(3, 12), "f_freq" to freq(3500f), "f_env" to depth(0.2f),
            "keyfade" to lin(6f, 0f, 24f), "velfade" to lin(20f, 0f, 64f), "loop" to step(2, 3)),
        p("Cloud",
            "grain" to 1f, "gsize" to size(180f), "gdensity" to density(18f), "grate" to rate(0.15f),
            "gspray" to 0.25f, "gpitch" to lin(0.2f, 0f, 24f),
            "a_attack" to attack(0.35f), "a_sustain" to 1f, "a_release" to decay(2.5f),
            "m01_src" to src(SRC_MOD), "m01_dest" to dest(DST_GPOS), "m01_depth" to depth(0.6f)),
        p("Shimmer",
            "grain" to 1f, "gsize" to size(35f), "gdensity" to density(70f), "grate" to rate(0.05f),
            "gspray" to 0.5f, "gpitch" to lin(12f, 0f, 24f),
            "a_attack" to attack(0.2f), "a_sustain" to 1f, "a_release" to decay(3f),
            "l1_rate" to exp(0.2f, 0.01f, 40f),
            "m01_src" to src(SRC_LFO1), "m01_dest" to dest(DST_GPOS), "m01_depth" to depth(0.25f),
            "m02_src" to src(SRC_PRESSURE), "m02_dest" to dest(DST_GPITCH), "m02_depth" to depth(0.5f)),
        p("Scan Layers",
            "scanamt" to 1f, "scan" to 0.2f, "velfade" to lin(30f, 0f, 64f),
            "a_sustain" to 1f, "a_release" to decay(0.5f),
            "m01_src" to src(SRC_MOD), "m01_dest" to dest(DST_SCAN), "m01_depth" to depth(0.8f)),
        p("Backwards", "reverse" to 1f, "start" to 0.99f, "a_attack" to attack(0.15f), "a_sustain" to 1f),
    )
}

/**
 * Manual's factory registrations. A drawbar is quoted the way an organist
 * quotes one - 88 8000 000, eight being all the way out - so the helper
 * takes those digits and the rest is normalised the usual way.
 *
 * The first five are tonewheel registrations anybody would recognise. The
 * last four are the other three instruments and one that only this organ
 * can do.
 */
object ManualPresets {
    private fun p(name: String, vararg kv: Pair<String, Float>) = Patch("Manual", name, kv.toMap())
    private fun st(index: Int, steps: Int) = index.toFloat() / (steps - 1).toFloat()
    private val barNames = listOf("16", "513", "8", "4", "223", "2", "135", "113", "1")

    /** "888000000" as the nine drawbars of one manual. */
    private fun bars(prefix: String, digits: String): List<Pair<String, Float>> =
        digits.mapIndexed { i, c -> "$prefix${barNames[i]}" to (c - '0') / 8f }

    private fun reg(name: String, upper: String, lower: String, vararg kv: Pair<String, Float>) =
        Patch("Manual", name, (bars("ua_", upper) + bars("la_", lower) + kv.toList()).toMap())

    val all: List<Patch> = listOf(
        p("Init"),
        reg("Gospel", "888000000", "008800000",
            "perc" to 1f, "percharm" to 1f, "percfast" to 1f, "perclvl" to 0.7f,
            "rotary" to 1f, "rotspeed" to st(2, 3), "drive" to 0.3f, "vibtype" to st(3, 6), "vibdepth" to 0.5f),
        reg("Smoke", "888800000", "888000000",
            "perc" to 0f, "rotary" to 1f, "rotspeed" to st(1, 3), "drive" to 0.45f, "click" to 0.5f,
            "age" to 0.4f, "leakage" to 0.3f),
        reg("Booker", "868868868", "848000000",
            "perc" to 1f, "percharm" to 0f, "percfast" to 0f, "perclvl" to 0.5f,
            "rotary" to 1f, "rotspeed" to st(2, 3), "drive" to 0.35f),
        reg("Full Draw", "888888888", "888888888",
            "perc" to 0f, "rotary" to 1f, "rotspeed" to st(2, 3), "drive" to 0.55f, "vibtype" to st(5, 6),
            "vibdepth" to 0.8f, "click" to 0.6f),
        reg("Combo", "800000000", "000000000",
            "model" to st(1, 4), "combowave" to st(0, 3), "tab16" to 0.8f, "tab8" to 1f, "tab4" to 0.7f,
            "tab2r" to 0.5f, "reedy" to 0.6f, "vibtype" to st(1, 6), "vibdepth" to 0.7f, "vibrate" to 0.75f,
            "rotary" to 0f, "drive" to 0.2f),
        reg("Cathedral", "888000000", "808000000",
            "model" to st(2, 4), "principal" to 1f, "flute" to 0.7f, "string" to 0.45f, "reed" to 0.3f,
            "mixture" to 0.6f, "chiff" to 0.45f, "tracker" to 0.3f, "windsag" to 0.35f, "windnoise" to 0.15f,
            "rotary" to 0f, "perc" to 0f, "attack" to 0.35f, "release" to 0.3f),
        reg("Harmonium", "880000000", "800000000",
            "model" to st(3, 4), "pressure" to 0.75f, "buzz" to 0.45f, "reedtrem" to 0.35f,
            "windsag" to 0.5f, "tremrate" to 0.4f, "rotary" to 0f, "perc" to 0f),
        // The one no organ does: two registrations, morphed by an LFO, over a
        // generator that has been sprayed apart.
        Patch("Manual", "Drift", (bars("ua_", "888000000") + bars("ub_", "004568888") +
            bars("la_", "808000000") + listOf(
            "morph" to 0f, "morphsrc" to st(9, 15), "morphamt" to 1f,
            "lfo1wave" to st(0, 9), "lfo1rate" to 0.2f, "lfo1depth" to 1f,
            "m1_src" to st(9, 15), "m1_dst" to st(1, 25), "m1_amt" to 0.75f,
            "m2_src" to st(11, 15), "m2_dst" to st(10, 25), "m2_amt" to 0.3f,
            "spray" to 0.45f, "spraywide" to 0.8f, "sprayrate" to 0.3f,
            "rotary" to 1f, "rotspeed" to st(1, 3), "drive" to 0.25f)).toMap()),
    )
}

/**
 * Cipher's factory patches. The first is a vocoder anybody would recognise;
 * the rest are the reasons this one is not that.
 */
object CipherPresets {
    private fun p(name: String, vararg kv: Pair<String, Float>) = Patch("Cipher", name, kv.toMap())
    private fun st(index: Int, steps: Int) = index.toFloat() / (steps - 1).toFloat()

    val all: List<Patch> = listOf(
        p("Init"),
        p("Classic", "bands" to st(12, 37), "q" to 0.55f, "wave a" to st(0, 5), "wave b" to st(2, 5),
            "mix" to 0.4f, "detune" to 0.25f, "sub" to 0.25f, "sibilance" to 0.5f, "siblevel" to 0.5f,
            "drive" to 0.15f),
        p("Choir", "bands" to st(24, 37), "attack" to 0.35f, "release" to 0.5f, "wave a" to st(2, 5),
            "wave b" to st(2, 5), "detune" to 0.5f, "mix" to 0.5f, "smear" to 0.62f),
        // Speech through a reversed bank: still speech-shaped, wholly
        // unintelligible, which is the point of it.
        p("Backwards", "bands" to st(20, 37), "remap" to st(1, 6), "remapamt" to 1f, "sibilance" to 0.2f,
            "wave a" to st(0, 5), "wave b" to st(1, 5), "mix" to 0.5f),
        p("Shuffled", "bands" to st(16, 37), "remap" to st(4, 6), "seed" to st(7, 32), "remapamt" to 0.8f,
            "smear" to 0.7f, "release" to 0.4f),
        p("Held Vowel", "freeze" to 1f, "frzmorph" to 1f, "frzdecay" to 0.9f, "bands" to st(24, 37),
            "wave a" to st(2, 5), "mix" to 0.3f, "detune" to 0.4f),
        p("Talkbox", "track" to 1f, "trackamt" to 1f, "trackglide" to 0.25f, "bands" to st(16, 37),
            "wave a" to st(1, 5), "pw" to 0.3f, "sub" to 0.35f, "sibilance" to 0.45f),
        // The input is the carrier and the synth does the talking, so a
        // chord chops whatever is plugged in.
        p("Inverted", "role" to 1f, "bands" to st(20, 37), "attack" to 0.1f, "release" to 0.12f,
            "wave a" to st(1, 5), "mix" to 0f, "wet" to 1f),
        p("Runaway", "feedback" to 0.62f, "fbtone" to 0.35f, "bands" to st(12, 37), "smear" to -0.5f,
            "gate" to 0.15f, "drive" to 0.35f),
    )
}

/**
 * Filament's factory patches. Each is a different way of disturbing the
 * same string, which is the whole argument for modelling one rather than
 * recording six.
 */
object FilamentPresets {
    private fun p(name: String, vararg kv: Pair<String, Float>) = Patch("Filament", name, kv.toMap())
    private fun st(index: Int, steps: Int) = index.toFloat() / (steps - 1).toFloat()

    val all: List<Patch> = listOf(
        p("Init"),
        p("Nylon", "exciter" to st(0, 6), "position" to 0.35f, "sustain" to 0.78f, "tone" to 0.4f,
            "grit" to 0.6f, "length" to 0.12f, "body" to 1f, "size" to 0.55f, "bodymix" to 0.45f,
            "detune" to 0.1f, "couple" to 0.3f),
        p("Steel", "exciter" to st(1, 6), "position" to 0.12f, "sustain" to 0.88f, "tone" to 0.62f,
            "stiffness" to 0.18f, "stages" to st(2, 5), "detune" to 0.12f, "couple" to 0.4f,
            "body" to 1f, "size" to 0.4f, "bodymix" to 0.4f, "drive" to 0.12f),
        // Stiffness is the difference between a guitar and a piano, so this
        // is the same string with more of it.
        p("Hammered", "exciter" to st(2, 6), "hardness" to 0.55f, "length" to 0.05f, "sustain" to 0.93f,
            "tone" to 0.5f, "stiffness" to 0.55f, "stages" to st(4, 5), "tension" to 0.3f,
            "detune" to 0.06f, "couple" to 0.5f, "sympathy" to 1f, "symtune" to st(5, 6),
            "symlevel" to 0.25f, "body" to 1f, "size" to 0.3f, "bodymix" to 0.3f),
        p("Bowed", "exciter" to st(3, 6), "pressure" to 0.55f, "speed" to 0.45f, "grit" to 0.35f,
            "sustain" to 0.9f, "tone" to 0.4f, "position" to 0.18f, "body" to 1f, "bodymix" to 0.4f,
            "velocity" to 0.4f),
        p("Blown", "exciter" to st(4, 6), "pressure" to 0.6f, "grit" to 0.7f, "sustain" to 0.86f,
            "tone" to 0.3f, "stiffness" to 0.1f, "body" to 1f, "size" to 0.7f, "bodymix" to 0.5f),
        // Sympathetic strings and a pedal that never lifts.
        p("Sympathy", "exciter" to st(0, 6), "sustain" to 0.8f, "on release" to 0f, "sympathy" to 1f,
            "symtune" to st(4, 6), "symlevel" to 0.7f, "symsustain" to 0.97f, "symwide" to 0.8f,
            "tone" to 0.5f, "body" to 1f, "bodymix" to 0.35f),
        p("Prepared", "exciter" to st(2, 6), "damper at" to 0.33f, "damper" to 0.4f, "rattle" to 0.55f,
            "rattle at" to 0.2f, "sustain" to 0.9f, "stiffness" to 0.3f, "stages" to st(3, 5),
            "tone" to 0.6f, "drive" to 0.2f),
        // The string is played by whatever is plugged in, which is the one
        // thing a sampled string cannot be.
        p("Spoken To", "exciter" to st(5, 6), "in gain" to 0.5f, "sustain" to 0.92f, "tone" to 0.55f,
            "sympathy" to 1f, "symtune" to st(2, 6), "symlevel" to 0.4f, "body" to 1f, "bodymix" to 0.3f),
        p("Wire", "exciter" to st(1, 6), "position" to 0.05f, "sustain" to 0.99f, "tone" to 0.85f,
            "stiffness" to 0.85f, "stages" to st(4, 5), "tension" to 0.8f, "detune" to 0.6f,
            "couple" to 0.8f, "rattle" to 0.3f, "drive" to 0.3f),
    )
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

/**
 * Cumulus's factory clouds: one per corner of the idea - plain, vowelled,
 * stretched into a bell, spread into a wash, shimmering, and hollowed out.
 *
 * Each one spells out the whole spectrum, defaults included, because a patch
 * here is a recipe for a table rather than a set of knob positions: leaving
 * one out would inherit it from whatever was loaded before, and the cloud
 * would be neither patch.
 */
object CumulusPresets {
    private fun p(name: String, vararg kv: Pair<String, Float>) = Patch("Cumulus", name, kv.toMap())

    val all: List<Patch> = listOf(
        p("Init"),
        // The plain cloud: what the algorithm sounds like before anyone leans on it.
        p("Cirrus", "partials" to 0.4331f, "tilt" to 0.4667f, "odd" to 0.5000f, "comb" to 0.0000f,
            "combperiod" to 0.1818f, "vowel" to 0.0000f, "vowelamount" to 0.0000f, "bandwidth" to 0.5018f,
            "bwscale" to 0.2400f, "stretch" to 0.2500f, "seed" to 0.0667f, "btilt" to 0.6667f,
            "bbandwidth" to 0.4833f, "bstretch" to 0.5000f, "bcomb" to 0.5000f, "bvowel" to 0.5000f,
            "bodd" to 0.5000f, "drift" to 0.1200f, "spread" to 0.5000f, "detune" to 0.1400f, "width" to 0.7000f,
            "ampattack" to 0.7438f, "amprelease" to 0.7820f, "cutoff" to 0.9230f),
        // Three formants and a slow wander: a pad that says a vowel.
        p("Choir", "partials" to 0.3386f, "tilt" to 0.5333f, "odd" to 0.5000f, "comb" to 0.0000f,
            "combperiod" to 0.1818f, "vowel" to 0.2500f, "vowelamount" to 0.8500f, "bandwidth" to 0.4204f,
            "bwscale" to 0.2000f, "stretch" to 0.2500f, "seed" to 0.0667f, "btilt" to 0.6667f,
            "bbandwidth" to 0.4000f, "bstretch" to 0.5000f, "bcomb" to 0.5000f, "bvowel" to 0.7750f,
            "bodd" to 0.5000f, "drift" to 0.1800f, "driftrate" to 0.3843f, "spread" to 1.0000f,
            "detune" to 0.1800f, "width" to 0.8000f, "ampattack" to 0.6915f, "amprelease" to 0.7698f,
            "cutoff" to 0.8732f, "morphkey" to 0.6500f),
        // Stretch is what makes a bell a bell: partial n sits at n to the 1.03.
        p("Bell Cloud", "partials" to 0.2126f, "tilt" to 0.6000f, "odd" to 0.5000f, "comb" to 0.3500f,
            "combperiod" to 0.2727f, "vowel" to 0.0000f, "vowelamount" to 0.0000f, "bandwidth" to 0.2822f,
            "bwscale" to 0.2000f, "stretch" to 0.6500f, "seed" to 0.0667f, "btilt" to 0.6667f,
            "bbandwidth" to 0.5333f, "bstretch" to 0.7500f, "bcomb" to 0.5000f, "bvowel" to 0.5000f,
            "bodd" to 0.5000f, "spread" to 0.0000f, "ampattack" to 0.1543f, "ampdecay" to 0.9060f,
            "ampsustain" to 0.2500f, "amprelease" to 0.8875f, "filterenv" to 0.6500f, "cutoff" to 0.9607f,
            "shimmer" to 0.2000f),
        // Bandwidth past a semitone: the partials stop being partials.
        p("Deep Wash", "partials" to 0.4961f, "tilt" to 0.2667f, "odd" to 0.5000f, "comb" to 0.0000f,
            "combperiod" to 0.1818f, "vowel" to 0.0000f, "vowelamount" to 0.0000f, "bandwidth" to 0.7889f,
            "bwscale" to 0.3600f, "stretch" to 0.2500f, "seed" to 0.0667f, "btilt" to 0.3333f,
            "bbandwidth" to 0.1333f, "bstretch" to 0.5000f, "bcomb" to 0.5000f, "bvowel" to 0.5000f,
            "bodd" to 0.5000f, "spread" to 1.0000f, "detune" to 0.2800f, "width" to 1.0000f,
            "scatter" to 1.0000f, "drift" to 0.2800f, "driftrate" to 0.3248f, "ampattack" to 0.8706f,
            "amprelease" to 0.8875f, "cutoff" to 0.8283f),
        // Shimmer two octaves up, and an LFO walking the morph.
        p("Glass Rain", "partials" to 0.3701f, "tilt" to 0.4000f, "odd" to 0.7500f, "comb" to 0.0000f,
            "combperiod" to 0.1818f, "vowel" to 0.0000f, "vowelamount" to 0.0000f, "bandwidth" to 0.4627f,
            "bwscale" to 0.2000f, "stretch" to 0.2500f, "seed" to 0.0667f, "btilt" to 0.7222f,
            "bbandwidth" to 0.5833f, "bstretch" to 0.5000f, "bcomb" to 0.5000f, "bvowel" to 0.5000f,
            "bodd" to 0.2500f, "shimmer" to 0.5500f, "shimmerint" to 1.0000f, "lfo1rate" to 0.2736f,
            "lfo1morph" to 0.8000f, "spread" to 0.5000f, "detune" to 0.1200f, "width" to 0.9000f,
            "ampattack" to 0.6347f, "amprelease" to 0.8219f, "cutoff" to 0.9816f),
        // Odd partials and a scallop across them: a clarinet the size of a room.
        p("Hollow", "partials" to 0.3071f, "tilt" to 0.4333f, "odd" to 1.0000f, "comb" to 0.5500f,
            "combperiod" to 0.1818f, "vowel" to 0.0000f, "vowelamount" to 0.0000f, "bandwidth" to 0.4748f,
            "bwscale" to 0.2000f, "stretch" to 0.2500f, "seed" to 0.0667f, "btilt" to 0.6667f,
            "bbandwidth" to 0.4500f, "bstretch" to 0.5000f, "bcomb" to 0.2500f, "bvowel" to 0.5000f,
            "bodd" to 0.2000f, "spread" to 0.5000f, "detune" to 0.1000f, "ampattack" to 0.7569f,
            "amprelease" to 0.7932f, "cutoff" to 0.8524f),
    )
}

/**
 * Formulate's factory chips: four that are only hardware, three where the
 * expression does the work. The formula and the step tables travel in the
 * patch's settings, because they are text rather than knob positions.
 */
object FormulatePresets {
    private fun p(name: String, vararg kv: Pair<String, Float>, settings: Map<String, String> = emptyMap()) =
        Patch("Formulate", name, kv.toMap(), settings)

    val all: List<Patch> = listOf(
        p("Init"),
        // A quarter-duty pulse and a major arpeggio at 50 Hz: 1987, in one line.
        p("Pulse Lead", "wave" to 0.0000f, "duty" to 0.2500f, "ampattack" to 0.0771f, "ampdecay" to 0.5940f,
            "ampsustain" to 0.7000f, "amprelease" to 0.3758f, "framerate" to 0.8171f, "mono" to 1.0000f,
            "volume" to 0.4667f,
            settings = mapOf("arp" to "0 4 7", "duty" to "", "vol" to "", "formula" to "")),
        // Pulse, sub octave, and the octave jump every tracker bass had.
        p("Arcade Bass", "wave" to 0.0000f, "duty" to 0.5000f, "sub" to 0.6000f, "ampattack" to 0.0771f,
            "ampdecay" to 0.5302f, "ampsustain" to 0.5000f, "amprelease" to 0.3368f, "framerate" to 0.7705f,
            "mono" to 1.0000f, "bits" to 1.0000f, "volume" to 0.6000f,
            settings = mapOf("arp" to "0 0 0 12", "vol" to "255 200 | 160", "duty" to "", "formula" to "")),
        // The shift register, short tap, with a volume table for a tail.
        p("Noise Hit", "wave" to 0.7500f, "noiseshort" to 1.0000f, "ampattack" to 0.0000f,
            "ampdecay" to 0.5550f, "ampsustain" to 0.0000f, "amprelease" to 0.3121f, "framerate" to 0.8552f,
            "volume" to 0.5333f,
            settings = mapOf("vol" to "255 190 120 70 40 20 8 0 |", "arp" to "", "duty" to "", "formula" to "")),
        // Three bits and a slow clock: the hardware's own limits, on purpose.
        p("Buzzsaw", "wave" to 0.5000f, "bits" to 0.2857f, "crush" to 0.4308f, "cutoff" to 0.8785f,
            "ampattack" to 0.1543f, "ampdecay" to 0.7181f, "ampsustain" to 0.8000f, "amprelease" to 0.5000f,
            "volume" to 0.4667f,
            settings = mapOf("formula" to "", "arp" to "", "duty" to "", "vol" to "")),
        // The equation is the oscillator now, and knob a is in it.
        p("Formula Buzz", "wave" to 1.0000f, "formula" to 1.0000f, "formulamode" to 0.2500f,
            "timekeyed" to 1.0000f, "timescale" to 0.5000f, "a" to 0.6275f, "ampattack" to 0.1543f,
            "ampdecay" to 0.6489f, "ampsustain" to 0.8000f, "amprelease" to 0.4610f, "cutoff" to 0.9289f,
            "volume" to 0.4000f,
            settings = mapOf("formula" to "t * (t >> 5 & a >> 4)", "arp" to "", "duty" to "", "vol" to "")),
        // A pulse, ring-modulated by a sine the formula draws.
        p("Ring Chip", "wave" to 0.0000f, "duty" to 0.5000f, "formula" to 1.0000f, "formulamode" to 0.5000f,
            "timekeyed" to 1.0000f, "timescale" to 0.6667f, "framerate" to 0.6638f, "ampattack" to 0.1543f,
            "ampdecay" to 0.6879f, "ampsustain" to 0.6000f, "amprelease" to 0.5000f, "volume" to 0.4667f,
            settings = mapOf("formula" to "x * sin(t) >> 7", "arp" to "0 7", "duty" to "", "vol" to "")),
        // The formula decides when the chip is heard, sixteen times a bar.
        p("Gated Grit", "wave" to 0.0000f, "duty" to 0.1250f, "formula" to 1.0000f, "formulamode" to 0.7500f,
            "timekeyed" to 1.0000f, "timescale" to 0.5000f, "framerate" to 0.9534f, "bits" to 0.7143f,
            "ampattack" to 0.0771f, "ampdecay" to 0.6242f, "ampsustain" to 0.7000f, "amprelease" to 0.4060f,
            "volume" to 0.4667f,
            settings = mapOf("formula" to "t >> 9 & 1 ? 255 : 0", "arp" to "0 0 12 7", "duty" to "", "vol" to "")),
    )
}

/**
 * Pollen's factory clouds: a plain wash, a self-seeding one, a chord of
 * dust, a slicer that only lands on transients, the microphone held, and
 * the one that eats its own output. The last two read the live ring, which
 * is why they have no sample.
 */
object PollenPresets {
    private fun p(name: String, vararg kv: Pair<String, Float>) = Patch("Pollen", name, kv.toMap())

    val all: List<Patch> = listOf(
        p("Init"),
        // The plain cloud: a slow wash over whatever is loaded.
        p("Drift", "source" to 0.0000f, "size" to 0.7952f, "density" to 0.5981f, "jitter" to 0.3500f,
            "spray" to 0.2500f, "scan" to 0.5050f, "panspread" to 0.7000f, "ampattack" to 0.7118f,
            "amprelease" to 0.7985f, "window" to 0.0000f, "volume" to 0.5333f),
        // One seed and three generations: the cloud makes its own.
        p("Pollinate", "source" to 0.0000f, "size" to 0.7241f, "density" to 0.4628f, "bloom" to 1.0000f,
            "generations" to 0.4000f, "drift" to 0.3500f, "mutate" to 0.3500f, "spray" to 0.1000f,
            "panspread" to 0.9000f, "ampattack" to 0.6347f, "amprelease" to 0.8219f, "window" to 0.0000f,
            "volume" to 0.5333f),
        // Every grain lands on a fifth or an octave, so a spray is a chord.
        p("Chord Dust", "source" to 0.0000f, "size" to 0.6588f, "density" to 0.7510f, "spread" to 0.5000f,
            "scatter" to 0.5000f, "spray" to 0.4000f, "panspread" to 0.8000f, "ampattack" to 0.4353f,
            "amprelease" to 0.7328f, "volume" to 0.4667f),
        // Grains land on the transients and nowhere else.
        p("Slicer", "source" to 0.0000f, "size" to 0.7564f, "density" to 0.5562f, "snap" to 1.0000f,
            "spray" to 0.8000f, "jitter" to 0.2000f, "window" to 0.6667f, "ampattack" to 0.0771f,
            "ampdecay" to 0.7181f, "amprelease" to 0.5260f, "keytrack" to 0.0000f, "volume" to 0.6000f),
        // The microphone, held: freeze it and the last few seconds keep going.
        p("Live Hold", "source" to 1.0000f, "buffer" to 0.8000f, "size" to 0.8356f, "density" to 0.6316f,
            "spray" to 0.1500f, "position" to 0.1000f, "scan" to 0.5000f, "keytrack" to 0.0000f,
            "dry" to 0.3000f, "ampattack" to 0.5895f, "amprelease" to 0.7042f, "volume" to 0.5333f),
        // Live, with the output going back in: the texture feeds on what it made.
        p("Eat Itself", "source" to 1.0000f, "buffer" to 0.6000f, "feedback" to 0.7368f, "size" to 0.8679f,
            "density" to 0.6834f, "spray" to 0.5000f, "scan" to 0.6250f, "keytrack" to 0.0000f,
            "bloom" to 0.5000f, "generations" to 0.2000f, "mutate" to 0.5000f, "bits" to 0.6000f,
            "wobble" to 0.2400f, "ampattack" to 0.6915f, "amprelease" to 0.8219f, "volume" to 0.4667f),
    )
}

/**
 * Resonance's factory kits: skins, wood, metal and glass. A patch here is
 * eight objects and how much they listen to each other, which is why the
 * coupling knob is part of every one of them.
 */
object ResonancePresets {
    private fun p(name: String, vararg kv: Pair<String, Float>) = Patch("Resonance", name, kv.toMap())

    val all: List<Patch> = listOf(
        p("Init"),
        // A kit of heads and shells: membranes tuned down a room.
        p("Skins", "p00_kind" to 0.0000f, "p00_tune" to 0.1729f, "p00_decay" to 0.5197f, "p00_damp" to 0.5500f,
            "p00_hit" to 0.2000f, "p00_hard" to 0.3500f, "p00_noise" to 0.3500f, "p00_inharm" to 0.3000f,
            "p00_level" to 0.6667f, "p00_pan" to 0.5000f, "p00_couple" to 0.2000f, "p00_drive" to 0.0000f,
            "p00_bend" to 0.3333f, "p00_bendtime" to 0.5111f, "p01_kind" to 0.0000f, "p01_tune" to 0.4395f,
            "p01_decay" to 0.4777f, "p01_damp" to 0.6000f, "p01_hit" to 0.4500f, "p01_hard" to 0.6000f,
            "p01_noise" to 0.6000f, "p01_inharm" to 0.3000f, "p01_level" to 0.6000f, "p01_pan" to 0.4500f,
            "p01_couple" to 0.3000f, "p01_drive" to 0.0000f, "p01_bend" to 0.0000f, "p01_bendtime" to 0.5111f,
            "p02_kind" to 0.0000f, "p02_tune" to 0.3301f, "p02_decay" to 0.5677f, "p02_damp" to 0.4500f,
            "p02_hit" to 0.3000f, "p02_hard" to 0.5000f, "p02_noise" to 0.2000f, "p02_inharm" to 0.3000f,
            "p02_level" to 0.5333f, "p02_pan" to 0.3250f, "p02_couple" to 0.3000f, "p02_drive" to 0.0000f,
            "p02_bend" to 0.0000f, "p02_bendtime" to 0.5111f, "p03_kind" to 0.0000f, "p03_tune" to 0.3986f,
            "p03_decay" to 0.5532f, "p03_damp" to 0.4500f, "p03_hit" to 0.3000f, "p03_hard" to 0.5000f,
            "p03_noise" to 0.2000f, "p03_inharm" to 0.3000f, "p03_level" to 0.5333f, "p03_pan" to 0.5000f,
            "p03_couple" to 0.3000f, "p03_drive" to 0.0000f, "p03_bend" to 0.0000f, "p03_bendtime" to 0.5111f,
            "p04_kind" to 0.0000f, "p04_tune" to 0.4633f, "p04_decay" to 0.5372f, "p04_damp" to 0.4500f,
            "p04_hit" to 0.3000f, "p04_hard" to 0.5000f, "p04_noise" to 0.2000f, "p04_inharm" to 0.3000f,
            "p04_level" to 0.5333f, "p04_pan" to 0.6750f, "p04_couple" to 0.3000f, "p04_drive" to 0.0000f,
            "p04_bend" to 0.0000f, "p04_bendtime" to 0.5111f, "p05_kind" to 1.0000f, "p05_tune" to 0.8099f,
            "p05_decay" to 0.2991f, "p05_damp" to 0.8500f, "p05_hit" to 0.6000f, "p05_hard" to 0.9000f,
            "p05_noise" to 0.5000f, "p05_inharm" to 0.3000f, "p05_level" to 0.4000f, "p05_pan" to 0.6250f,
            "p05_couple" to 0.6000f, "p05_drive" to 0.0000f, "p05_bend" to 0.0000f, "p05_bendtime" to 0.5111f,
            "p06_kind" to 1.0000f, "p06_tune" to 0.7877f, "p06_decay" to 0.7314f, "p06_damp" to 0.5000f,
            "p06_hit" to 0.6000f, "p06_hard" to 0.8500f, "p06_noise" to 0.4000f, "p06_inharm" to 0.3000f,
            "p06_level" to 0.3333f, "p06_pan" to 0.3750f, "p06_couple" to 0.7000f, "p06_drive" to 0.0000f,
            "p06_bend" to 0.0000f, "p06_bendtime" to 0.5111f, "p07_kind" to 0.4000f, "p07_tune" to 0.6792f,
            "p07_decay" to 0.8620f, "p07_damp" to 0.3500f, "p07_hit" to 0.5000f, "p07_hard" to 0.7000f,
            "p07_noise" to 0.3000f, "p07_inharm" to 0.3000f, "p07_level" to 0.3000f, "p07_pan" to 0.7000f,
            "p07_couple" to 0.8000f, "p07_drive" to 0.0000f, "p07_bend" to 0.0000f, "p07_bendtime" to 0.5111f,
            "coupling" to 0.3500f, "modes" to 0.4000f, "humanise" to 0.2000f, "volume" to 0.6000f),
        // Bars and blocks: a marimba, a woodblock, a claves pair.
        p("Woodshop", "p00_kind" to 0.2000f, "p00_tune" to 0.2616f, "p00_decay" to 0.6834f,
            "p00_damp" to 0.2500f, "p00_hit" to 0.2800f, "p00_hard" to 0.6000f, "p00_noise" to 0.0500f,
            "p00_inharm" to 0.3000f, "p00_level" to 0.6667f, "p00_pan" to 0.5000f, "p00_couple" to 0.3000f,
            "p00_drive" to 0.0000f, "p00_bend" to 0.0000f, "p00_bendtime" to 0.5111f, "p01_kind" to 0.2000f,
            "p01_tune" to 0.4266f, "p01_decay" to 0.6353f, "p01_damp" to 0.3000f, "p01_hit" to 0.2800f,
            "p01_hard" to 0.7000f, "p01_noise" to 0.0500f, "p01_inharm" to 0.3000f, "p01_level" to 0.5333f,
            "p01_pan" to 0.4000f, "p01_couple" to 0.3000f, "p01_drive" to 0.0000f, "p01_bend" to 0.0000f,
            "p01_bendtime" to 0.5111f, "p02_kind" to 0.2000f, "p02_tune" to 0.5232f, "p02_decay" to 0.5934f,
            "p02_damp" to 0.3500f, "p02_hit" to 0.2800f, "p02_hard" to 0.7500f, "p02_noise" to 0.0500f,
            "p02_inharm" to 0.3000f, "p02_level" to 0.5333f, "p02_pan" to 0.6000f, "p02_couple" to 0.3000f,
            "p02_drive" to 0.0000f, "p02_bend" to 0.0000f, "p02_bendtime" to 0.5111f, "p03_kind" to 0.6000f,
            "p03_tune" to 0.6284f, "p03_decay" to 0.4216f, "p03_damp" to 0.5000f, "p03_hit" to 0.5000f,
            "p03_hard" to 0.9000f, "p03_noise" to 0.1000f, "p03_inharm" to 0.3000f, "p03_level" to 0.6000f,
            "p03_pan" to 0.5000f, "p03_couple" to 0.3000f, "p03_drive" to 0.0000f, "p03_bend" to 0.0000f,
            "p03_bendtime" to 0.5111f, "p04_kind" to 0.2000f, "p04_tune" to 0.7500f, "p04_decay" to 0.4520f,
            "p04_damp" to 0.4000f, "p04_hit" to 0.3000f, "p04_hard" to 0.9500f, "p04_noise" to 0.0500f,
            "p04_inharm" to 0.3000f, "p04_level" to 0.5333f, "p04_pan" to 0.3250f, "p04_couple" to 0.3000f,
            "p04_drive" to 0.0000f, "p04_bend" to 0.0000f, "p04_bendtime" to 0.5111f, "p05_kind" to 0.2000f,
            "p05_tune" to 0.8466f, "p05_decay" to 0.4216f, "p05_damp" to 0.4500f, "p05_hit" to 0.3000f,
            "p05_hard" to 0.9500f, "p05_noise" to 0.0500f, "p05_inharm" to 0.3000f, "p05_level" to 0.5333f,
            "p05_pan" to 0.6750f, "p05_couple" to 0.3000f, "p05_drive" to 0.0000f, "p05_bend" to 0.0000f,
            "p05_bendtime" to 0.5111f, "p06_kind" to 0.0000f, "p06_tune" to 0.3832f, "p06_decay" to 0.4520f,
            "p06_damp" to 0.7000f, "p06_hit" to 0.4000f, "p06_hard" to 0.5000f, "p06_noise" to 0.5000f,
            "p06_inharm" to 0.3000f, "p06_level" to 0.4667f, "p06_pan" to 0.5000f, "p06_couple" to 0.3000f,
            "p06_drive" to 0.0000f, "p06_bend" to 0.0000f, "p06_bendtime" to 0.5111f, "p07_kind" to 0.6000f,
            "p07_tune" to 0.5483f, "p07_decay" to 0.5677f, "p07_damp" to 0.3000f, "p07_hit" to 0.5000f,
            "p07_hard" to 0.8000f, "p07_noise" to 0.0500f, "p07_inharm" to 0.3000f, "p07_level" to 0.4667f,
            "p07_pan" to 0.5000f, "p07_couple" to 0.5000f, "p07_drive" to 0.0000f, "p07_bend" to 0.0000f,
            "p07_bendtime" to 0.5111f, "coupling" to 0.2000f, "modes" to 0.2000f, "humanise" to 0.2500f,
            "volume" to 0.6000f),
        // Metal, struck and left alone: anvils, pipes, a sheet.
        p("Foundry", "p00_kind" to 1.0000f, "p00_tune" to 0.3668f, "p00_decay" to 0.8059f,
            "p00_damp" to 0.2500f, "p00_hit" to 0.3300f, "p00_hard" to 0.8500f, "p00_noise" to 0.1500f,
            "p00_inharm" to 0.3000f, "p00_level" to 0.6667f, "p00_pan" to 0.5000f, "p00_couple" to 0.3000f,
            "p00_drive" to 0.2500f, "p00_bend" to 0.0000f, "p00_bendtime" to 0.5111f, "p01_kind" to 1.0000f,
            "p01_tune" to 0.5636f, "p01_decay" to 0.8363f, "p01_damp" to 0.2000f, "p01_hit" to 0.4000f,
            "p01_hard" to 0.9000f, "p01_noise" to 0.1000f, "p01_inharm" to 0.3000f, "p01_level" to 0.5333f,
            "p01_pan" to 0.3500f, "p01_couple" to 0.8000f, "p01_drive" to 0.0000f, "p01_bend" to 0.0000f,
            "p01_bendtime" to 0.5111f, "p02_kind" to 0.4000f, "p02_tune" to 0.5142f, "p02_decay" to 0.9040f,
            "p02_damp" to 0.1500f, "p02_hit" to 0.4500f, "p02_hard" to 0.8000f, "p02_noise" to 0.2000f,
            "p02_inharm" to 0.3000f, "p02_level" to 0.5333f, "p02_pan" to 0.6500f, "p02_couple" to 0.9000f,
            "p02_drive" to 0.0000f, "p02_bend" to 0.0000f, "p02_bendtime" to 0.5111f, "p03_kind" to 0.8000f,
            "p03_tune" to 0.4266f, "p03_decay" to 0.9520f, "p03_damp" to 0.1000f, "p03_hit" to 0.5000f,
            "p03_hard" to 0.7000f, "p03_noise" to 0.1000f, "p03_inharm" to 0.3000f, "p03_level" to 0.4667f,
            "p03_pan" to 0.5000f, "p03_couple" to 0.9000f, "p03_drive" to 0.0000f, "p03_bend" to 0.0000f,
            "p03_bendtime" to 0.5111f, "p04_kind" to 0.6000f, "p04_tune" to 0.4951f, "p04_decay" to 0.7686f,
            "p04_damp" to 0.3000f, "p04_hit" to 0.5000f, "p04_hard" to 0.9000f, "p04_noise" to 0.1000f,
            "p04_inharm" to 0.3000f, "p04_level" to 0.5333f, "p04_pan" to 0.4250f, "p04_couple" to 0.3000f,
            "p04_drive" to 0.0000f, "p04_bend" to 0.0000f, "p04_bendtime" to 0.5111f, "p05_kind" to 1.0000f,
            "p05_tune" to 0.7287f, "p05_decay" to 0.6834f, "p05_damp" to 0.3500f, "p05_hit" to 0.3500f,
            "p05_hard" to 0.9500f, "p05_noise" to 0.2000f, "p05_inharm" to 0.3000f, "p05_level" to 0.5333f,
            "p05_pan" to 0.5750f, "p05_couple" to 0.3000f, "p05_drive" to 0.0000f, "p05_bend" to 0.0000f,
            "p05_bendtime" to 0.5111f, "p06_kind" to 0.4000f, "p06_tune" to 0.8099f, "p06_decay" to 0.7845f,
            "p06_damp" to 0.3000f, "p06_hit" to 0.5000f, "p06_hard" to 0.9000f, "p06_noise" to 0.2500f,
            "p06_inharm" to 0.3000f, "p06_level" to 0.4000f, "p06_pan" to 0.7250f, "p06_couple" to 0.3000f,
            "p06_drive" to 0.0000f, "p06_bend" to 0.0000f, "p06_bendtime" to 0.5111f, "p07_kind" to 0.8000f,
            "p07_tune" to 0.6284f, "p07_decay" to 0.9777f, "p07_damp" to 0.0800f, "p07_hit" to 0.5500f,
            "p07_hard" to 0.6000f, "p07_noise" to 0.0500f, "p07_inharm" to 0.3000f, "p07_level" to 0.3333f,
            "p07_pan" to 0.5000f, "p07_couple" to 1.0000f, "p07_drive" to 0.0000f, "p07_bend" to 0.0000f,
            "p07_bendtime" to 0.5111f, "coupling" to 0.7500f, "modes" to 0.8000f, "humanise" to 0.1500f,
            "volume" to 0.5333f),
        // Bowls and bottles, barely damped: a kit that will not stop.
        p("Glassware", "p00_kind" to 0.8000f, "p00_tune" to 0.4744f, "p00_decay" to 0.9216f,
            "p00_damp" to 0.1000f, "p00_hit" to 0.5000f, "p00_hard" to 0.6000f, "p00_noise" to 0.0500f,
            "p00_inharm" to 0.3000f, "p00_level" to 0.6000f, "p00_pan" to 0.5000f, "p00_couple" to 0.8000f,
            "p00_drive" to 0.0000f, "p00_bend" to 0.0000f, "p00_bendtime" to 0.5111f, "p01_kind" to 0.8000f,
            "p01_tune" to 0.5710f, "p01_decay" to 0.9040f, "p01_damp" to 0.1200f, "p01_hit" to 0.5000f,
            "p01_hard" to 0.6500f, "p01_noise" to 0.0500f, "p01_inharm" to 0.3000f, "p01_level" to 0.5333f,
            "p01_pan" to 0.3750f, "p01_couple" to 0.8000f, "p01_drive" to 0.0000f, "p01_bend" to 0.0000f,
            "p01_bendtime" to 0.5111f, "p02_kind" to 0.8000f, "p02_tune" to 0.6395f, "p02_decay" to 0.8843f,
            "p02_damp" to 0.1400f, "p02_hit" to 0.5000f, "p02_hard" to 0.7000f, "p02_noise" to 0.0500f,
            "p02_inharm" to 0.3000f, "p02_level" to 0.5333f, "p02_pan" to 0.6250f, "p02_couple" to 0.8000f,
            "p02_drive" to 0.0000f, "p02_bend" to 0.0000f, "p02_bendtime" to 0.5111f, "p03_kind" to 0.6000f,
            "p03_tune" to 0.7093f, "p03_decay" to 0.8059f, "p03_damp" to 0.2000f, "p03_hit" to 0.5000f,
            "p03_hard" to 0.8000f, "p03_noise" to 0.1000f, "p03_inharm" to 0.3000f, "p03_level" to 0.5333f,
            "p03_pan" to 0.3000f, "p03_couple" to 0.7000f, "p03_drive" to 0.0000f, "p03_bend" to 0.0000f,
            "p03_bendtime" to 0.5111f, "p04_kind" to 0.6000f, "p04_tune" to 0.7788f, "p04_decay" to 0.7845f,
            "p04_damp" to 0.2200f, "p04_hit" to 0.5000f, "p04_hard" to 0.8500f, "p04_noise" to 0.1000f,
            "p04_inharm" to 0.3000f, "p04_level" to 0.5333f, "p04_pan" to 0.7000f, "p04_couple" to 0.7000f,
            "p04_drive" to 0.0000f, "p04_bend" to 0.0000f, "p04_bendtime" to 0.5111f, "p05_kind" to 0.4000f,
            "p05_tune" to 0.8576f, "p05_decay" to 0.7510f, "p05_damp" to 0.3000f, "p05_hit" to 0.4500f,
            "p05_hard" to 0.9000f, "p05_noise" to 0.1500f, "p05_inharm" to 0.3000f, "p05_level" to 0.4000f,
            "p05_pan" to 0.5000f, "p05_couple" to 0.6000f, "p05_drive" to 0.0000f, "p05_bend" to 0.0000f,
            "p05_bendtime" to 0.5111f, "p06_kind" to 0.0000f, "p06_tune" to 0.2745f, "p06_decay" to 0.5372f,
            "p06_damp" to 0.6000f, "p06_hit" to 0.2500f, "p06_hard" to 0.4000f, "p06_noise" to 0.4000f,
            "p06_inharm" to 0.3000f, "p06_level" to 0.5333f, "p06_pan" to 0.5000f, "p06_couple" to 0.3000f,
            "p06_drive" to 0.0000f, "p06_bend" to 0.0000f, "p06_bendtime" to 0.5111f, "p07_kind" to 1.0000f,
            "p07_tune" to 0.9315f, "p07_decay" to 0.6529f, "p07_damp" to 0.4000f, "p07_hit" to 0.4000f,
            "p07_hard" to 0.9500f, "p07_noise" to 0.2000f, "p07_inharm" to 0.3000f, "p07_level" to 0.3333f,
            "p07_pan" to 0.5000f, "p07_couple" to 0.9000f, "p07_drive" to 0.0000f, "p07_bend" to 0.0000f,
            "p07_bendtime" to 0.5111f, "coupling" to 0.9000f, "modes" to 1.0000f, "humanise" to 0.1000f,
            "volume" to 0.5000f),
    )
}
