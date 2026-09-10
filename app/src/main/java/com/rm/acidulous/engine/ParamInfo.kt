package com.rm.acidulous.engine

import kotlin.math.floor
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

    fun format(v01: Float): String {
        val v = map(v01)
        return when {
            curve == 2 -> v.toInt().toString()
            unit == "Hz" -> if (v >= 1000f) "%.1fk".format(v / 1000f) else "%.0f".format(v)
            unit == "ms" -> if (v >= 1000f) "%.2fs".format(v / 1000f) else "%.0fms".format(v)
            unit == "st" -> "%+.1f".format(v)
            else -> "%.2f".format(v)
        }
    }

    companion object {
        fun parse(s: String): ParamInfo {
            val f = s.split('|')
            return ParamInfo(f[0], f[1].toFloat(), f[2].toFloat(), f[3].toFloat(), f[4].toInt(), f[5].toInt(), f.getOrElse(6) { "" })
        }
    }
}
