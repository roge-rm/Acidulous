package com.rm.acidulous.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.rm.acidulous.ui.theme.Acid
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sqrt

/** The partials an Abacus note can have. */
private const val BEADS = 64
private val VOWEL_HZ = arrayOf(
    floatArrayOf(730f, 1090f), floatArrayOf(530f, 1840f), floatArrayOf(270f, 2290f),
    floatArrayOf(570f, 840f), floatArrayOf(300f, 870f),
)
private val BAR_ROOTS = doubleArrayOf(4.730040745, 7.853204624, 10.995607838, 14.137165491)
private val BELL = doubleArrayOf(1.0, 2.0, 2.4, 3.0, 4.0, 5.0, 5.33, 6.0, 8.0, 10.67, 12.0, 13.5)

/** The square plate's modes, m^2 + k^2 each once, sorted, against the first. */
private val PLATE: DoubleArray = run {
    val vals = HashSet<Int>()
    for (m in 1..19) for (k in m..19) vals.add(m * m + k * k)
    val sorted = vals.sorted()
    DoubleArray(BEADS) { sorted[it] / 2.0 }
}

/** Partial [n]'s ratio to the note under the law, as the engine works it out (Abacus::ratioOf). */
private fun ratioOf(law: Int, stretch: Float, n: Int): Double {
    val h = n + 1.0
    val r = when (law) {
        1 -> {
            val b = 0.0003 * 8.0.pow(stretch.toDouble())
            return h * sqrt((1.0 + b * h * h) / (1.0 + b))
        }
        2 -> {
            val b = if (n < 4) BAR_ROOTS[n] else (2.0 * n + 3.0) * 3.14159265358979 / 2.0
            (b / BAR_ROOTS[0]) * (b / BAR_ROOTS[0])
        }
        3 -> if (n < 12) BELL[n] else BELL[11] * (h / 12.0).pow(1.4)
        4 -> PLATE[n]
        else -> return h.pow(1.0 + 0.02 * stretch)
    }
    return exp(ln(h) + (1.0 + stretch) * (ln(r) - ln(h)))
}

/**
 * The partials as beads on rods: one rod a partial, the bead's height its
 * level. It is worked out here from the panel's knobs for a middle C, where
 * the engine works it out from the playing note, so the shape follows every
 * spectrum and law knob but not the envelope or the shimmer.
 */
@Composable
internal fun BeadFrame(b: ParamBinding, modifier: Modifier = Modifier) {
    val c = Acid.colors
    fun v(name: String): Float = b.infoOf(name)?.map(b.value(name)) ?: 0f
    val count = 16 shl v("partials").toInt().coerceIn(0, 2)
    val law = v("law").toInt().coerceIn(0, 4)
    val stretch = v("stretch")
    val morph = v("morph").coerceIn(0f, 1f)
    val slope = (v("slope") + morph * v("bslope")).coerceIn(-48f, 12f)
    val odd = (v("odd") + morph * v("bodd")).coerceIn(-1f, 1f)
    val comb = (v("comb") + morph * v("bcomb")).coerceIn(0f, 1f)
    val combN = v("combn").toInt().coerceIn(2, 16)
    val peakLog2 = log2(v("peak").coerceAtLeast(1f)) + morph * v("bpeak")
    val peakGain = v("peakgain")
    val peakInv = 1f / (v("peakwidth") * 0.5f).coerceAtLeast(0.05f)
    val vw = v("vowel").coerceIn(0f, 4f)
    val i0 = vw.toInt().coerceAtMost(3)
    val fr = vw - i0
    val f1 = log2(VOWEL_HZ[i0][0]) * (1 - fr) + log2(VOWEL_HZ[i0 + 1][0]) * fr
    val f2 = log2(VOWEL_HZ[i0][1]) * (1 - fr) + log2(VOWEL_HZ[i0 + 1][1]) * fr
    val vowelAmt = v("vowelamt").coerceIn(0f, 1f)
    val levels = FloatArray(BEADS)
    val sounding = BooleanArray(BEADS)
    val log2f0 = log2(261.63f)
    for (n in 0 until BEADS) {
        val ratio = ratioOf(law, stretch, n)
        val l2 = log2(ratio).toFloat()
        val lf = log2f0 + l2
        var lg = slope / 6.0206f * l2
        if (peakGain > 0f) {
            val d = (lf - peakLog2) * peakInv
            lg += peakGain / 6.0206f * exp(-d * d * ln(2f))
        }
        var g = 2f.pow(lg)
        g *= if (n % 2 == 0) (if (odd < 0f) 1f + odd else 1f) else (if (odd > 0f) 1f - odd else 1f)
        if ((n + 1) % combN == 0) g *= 1f - comb
        if (vowelAmt > 0f) {
            val d1 = (lf - f1) / 0.35f
            val d2 = (lf - f2) / 0.3f
            val resp = 0.06f + exp(-d1 * d1 * ln(2f)) + 0.7f * exp(-d2 * d2 * ln(2f))
            g *= 1f + vowelAmt * (resp - 1f)
        }
        levels[n] = (20f * log10(g.coerceAtLeast(1e-6f)))
        sounding[n] = n < count && 261.63 * ratio < 21600.0
    }
    Canvas(
        modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(6.dp)).background(c.bg)
            .clearAndSetSemantics { },
    ) {
        val pitch = size.width / BEADS
        val beadH = 3.dp.toPx()
        val beadW = pitch * 0.7f
        val top = 4.dp.toPx()
        val room = size.height - top * 2 - beadH
        for (n in 0 until BEADS) {
            val x = (n + 0.5f) * pitch
            drawLine(c.line, Offset(x, top), Offset(x, size.height - top), strokeWidth = 1f)
            // 6 dB over the first partial at the top, 54 dB under at the bottom.
            val up = ((levels[n] + 54f) / 60f).coerceIn(0f, 1f)
            val y = top + room * (1f - up)
            drawRoundRect(
                if (sounding[n]) (if (n % 4 == 0) c.accent else c.teal) else c.textFaint,
                Offset(x - beadW / 2f, y), Size(beadW, beadH), CornerRadius(beadH / 2f),
            )
        }
    }
}

private fun log10(x: Float): Float = (ln(x) / ln(10f))
