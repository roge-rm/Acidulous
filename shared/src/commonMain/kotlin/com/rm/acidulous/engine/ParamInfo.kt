package com.rm.acidulous.engine

import com.rm.acidulous.util.format

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.ln
import kotlin.math.pow

/** A unit's parameter as the engine describes it; mirrors ParamDef. */
data class ParamInfo(
    val name: String,
    val min: Float,
    val max: Float,
    val def: Float,
    val curve: Int,   // 0 linear, 1 exponential, 2 stepped
    val steps: Int,
    val unit: String,
) {
    /** Normalised 0..1 -> unit range, as the engine maps it. */
    fun map(v01: Float): Float {
        val v = v01.coerceIn(0f, 1f)
        return when (curve) {
            1 -> min * (max / min).pow(v)
            2 -> min + floor(v * (steps - 1) + 0.5f) * ((max - min) / (steps - 1))
            else -> min + (max - min) * v
        }
    }

    fun unmap(value: Float): Float = when (curve) {
        1 -> (ln(value / min) / ln(max / min)).coerceIn(0f, 1f)
        else -> ((value - min) / (max - min)).coerceIn(0f, 1f)
    }

    val defaultNormalized: Float get() = unmap(def)

    /**
     * A level knob on a dB dial: 0 dB is the default, the top is the max, the
     * bottom is off and the rest of the travel runs down to [DB_FLOOR]. Only the
     * dial and its text use it; the stored value is still [map]'s.
     */
    val dbDial: Boolean get() = curve == 0 && def > 0f && max > def && min >= 0f

    private val dbTop: Float get() = 20f * log10(max / def)

    /** The stored 0..1 value -> where the dial points. */
    fun toDial(v01: Float): Float {
        val g = map(v01)
        if (g <= 0f) return 0f
        val db = (20f * log10(g / def)).coerceIn(DB_FLOOR, dbTop)
        return ((db - DB_FLOOR) / (dbTop - DB_FLOOR)).coerceIn(0f, 1f)
    }

    /** Where the dial points -> the stored 0..1 value. */
    fun fromDial(p: Float): Float {
        if (p <= DB_OFF) return unmap(0f)
        if (p >= 1f) return 1f
        val db = DB_FLOOR + p * (dbTop - DB_FLOOR)
        return unmap(def * 10f.pow(db / 20f))
    }

    /** "+6.0 dB", "0.0 dB", "-12.0 dB", or "-inf" at silence. */
    fun formatDb(v01: Float): String {
        val g = map(v01)
        if (g <= 0f) return "-inf"
        val db = 20f * log10(g / def)
        if (db < -99.95f) return "-inf"
        return (if (abs(db) < 0.05f) "0.0" else "%+.1f".format(db)) + " dB"
    }

    fun format(v01: Float): String {
        val v = map(v01)
        return when {
            curve == 2 -> v.toInt().toString()
            unit == "Hz" -> when {
                v >= 1000f -> "%.1fk".format(v / 1000f)
                // Slow LFOs need the decimals: 0.12Hz, not 0Hz.
                v < 1f -> "%.2fHz".format(v)
                v < 10f -> "%.1fHz".format(v)
                else -> "%.0fHz".format(v)
            }
            unit == "ms" -> if (v >= 1000f) "%.2fs".format(v / 1000f) else "%.0fms".format(v)
            // Under a second reads as ms, like a time given in ms.
            unit == "s" -> when {
                v < 1f -> "%.0fms".format(v * 1000f)
                v < 10f -> "%.2fs".format(v)
                else -> "%.1fs".format(v)
            }
            unit == "x" -> "%.2fx".format(v)
            unit == "st" -> "%+.1f".format(v)
            unit == "m" -> "%.1fm".format(v)
            // A level shows it's a level: a bare 0.00 read as nothing at all.
            unit == "dB" -> "%+.1fdB".format(v)
            unit == "dB/oct" -> "%.0fdB/oct".format(v)
            else -> "%.2f".format(v)
        }
    }

    companion object {
        const val DB_FLOOR = -60f
        private const val DB_OFF = 0.004f

        fun parse(s: String): ParamInfo {
            val f = s.split('|')
            return ParamInfo(f[0], f[1].toFloat(), f[2].toFloat(), f[3].toFloat(), f[4].toInt(), f[5].toInt(), f.getOrElse(6) { "" })
        }
    }
}
