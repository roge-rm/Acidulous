package com.rm.acidulous.model

import kotlinx.serialization.Serializable
import kotlin.math.pow

/**
 * A tuning: the steps of a scale above its root, in cents, the last of them
 * the interval it repeats at - 1200 for an octave. It is Scala's idea of a
 * scale, and a `.scl` file reads straight into one.
 *
 * Kept whole in the song rather than by name, so a song plays the same on a
 * phone that has never seen the file it came from.
 */
@Serializable
data class Tuning(val name: String, val cents: List<Float>) {
    /** Twelve notes a period of 1200 each a hundred apart: nothing to retune. */
    val isEqual: Boolean
        get() = cents.size == 12 && cents.withIndex().all { (i, c) -> kotlin.math.abs(c - (i + 1) * 100f) < 0.01f }
}

object Tunings {

    /** Equal temperament, which is the same as having no tuning at all. */
    val EQUAL = Tuning("equal", (1..12).map { it * 100f })

    /**
     * The ones worth having without a file. Twelve-note tunings keep the
     * keyboard's octave; 19 and 24 steps put the octave 19 and 24 keys up.
     */
    val builtIn: List<Tuning> = listOf(
        EQUAL,
        Tuning("just", listOf(111.73f, 203.91f, 315.64f, 386.31f, 498.04f, 590.22f, 701.96f, 813.69f, 884.36f, 1017.60f, 1088.27f, 1200f)),
        Tuning("pythagorean", listOf(90.22f, 203.91f, 294.13f, 407.82f, 498.04f, 611.73f, 701.96f, 792.18f, 905.87f, 996.09f, 1109.78f, 1200f)),
        Tuning("meantone", listOf(76.05f, 193.16f, 310.26f, 386.31f, 503.42f, 579.47f, 696.58f, 772.63f, 889.74f, 1006.84f, 1082.89f, 1200f)),
        Tuning("werckmeister", listOf(90.22f, 192.18f, 294.13f, 390.22f, 498.04f, 588.27f, 696.09f, 792.18f, 888.27f, 996.09f, 1092.18f, 1200f)),
        Tuning("19 equal", (1..19).map { it * 1200f / 19f }),
        Tuning("24 equal", (1..24).map { it * 50f }),
    )

    /**
     * For each MIDI note, its ratio to equal temperament: what the engine is
     * handed. The root's own note in the middle octave keeps its pitch -
     * a tuning in A leaves A at 440 - and the rest are counted from it, one
     * step of the tuning per key, so a 19-step tuning puts the octave 19 keys
     * up the keyboard.
     */
    fun ratios(tuning: Tuning, root: Int): FloatArray {
        val size = tuning.cents.size.coerceAtLeast(1)
        val period = tuning.cents.last()
        val ref = 60 + Math.floorMod(root, 12)
        return FloatArray(128) { n ->
            val steps = n - ref
            val degree = Math.floorMod(steps, size)
            val octaves = Math.floorDiv(steps, size)
            val tuned = octaves * period + (if (degree == 0) 0f else tuning.cents[degree - 1])
            2.0.pow((tuned - steps * 100.0) / 1200.0).toFloat()
        }
    }

    /**
     * A Scala `.scl` file. Lines starting `!` are comments; the first other
     * line is a description, the next the number of notes, and then one pitch
     * a line - cents if it has a decimal point, a ratio such as 3/2 or a
     * whole number such as 2 if not. Anything after the pitch is ignored.
     */
    fun parseScl(text: String, fallbackName: String): Tuning {
        val lines = text.lineSequence().map { it.trim() }.filter { !it.startsWith("!") }.toList()
        require(lines.size >= 2) { "not a Scala file" }
        val description = lines[0]
        val count = lines[1].split(Regex("\\s+")).first().toIntOrNull()
        require(count != null && count in 1..128) { "not a Scala file: no note count" }
        val pitches = lines.drop(2).filter { it.isNotEmpty() }.take(count).map { line ->
            val token = line.split(Regex("\\s+")).first()
            when {
                token.contains('.') -> token.toFloat()
                token.contains('/') -> {
                    val (a, b) = token.split('/').map { it.toDouble() }
                    require(a > 0 && b > 0) { "a ratio of nothing: $token" }
                    (1200.0 * kotlin.math.ln(a / b) / kotlin.math.ln(2.0)).toFloat()
                }
                else -> {
                    val v = token.toDouble()
                    require(v > 0) { "a ratio of nothing: $token" }
                    (1200.0 * kotlin.math.ln(v) / kotlin.math.ln(2.0)).toFloat()
                }
            }
        }
        require(pitches.size == count) { "the file says $count notes and has ${pitches.size}" }
        require(pitches.last() > 0f) { "a scale that does not rise" }
        return Tuning(description.ifBlank { fallbackName }.take(40), pitches)
    }
}

/** The `.scl` files brought in with Import, kept under the user folder. */
object TuningStore {
    fun directory(userRoot: java.io.File) = java.io.File(userRoot, "tunings").apply { mkdirs() }

    /** The built-in tunings, then every imported one that reads. */
    fun all(userRoot: java.io.File): List<Tuning> =
        Tunings.builtIn + (directory(userRoot).listFiles { f -> f.extension.equals("scl", true) } ?: emptyArray())
            .sortedBy { it.name.lowercase() }
            .mapNotNull { f -> runCatching { Tunings.parseScl(f.readText(), f.nameWithoutExtension) }.getOrNull() }
}
