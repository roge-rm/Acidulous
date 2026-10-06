package com.rm.acidulous.model

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Where everything sits on a module's faceplate, in patch units from its top
 * left corner.
 *
 * Modules are drawn as Eurorack panels: all the same height, as wide as they
 * need to be, like a module's HP. The first knob is the big one at the top,
 * the rest sit in a grid under it, and the jacks are at the bottom with the
 * outputs last, in the dark box real modules mark outputs with.
 *
 * The width is the fewest columns that fit everything in the fixed height,
 * so a VCA is a narrow strip and a filter a wide panel.
 *
 * This is plain arithmetic on how many knobs and jacks a module has, so the
 * layout can use it without the engine and the tests can check it.
 */
class NexusFace(
    val w: Float,
    val h: Float,
    val knobs: List<FaceKnob>,
    val inputs: List<FacePoint>,
    val outputs: List<FacePoint>,
    /** The dark box behind the outputs, or null with none. */
    val outBox: FaceRect?,
    /** The scope's screen, or null for anything else. */
    val screen: FaceRect?,
    /** How far apart a row of knobs or jacks is spaced, which their labels must fit in. */
    val span: Float,
)

data class FacePoint(val x: Float, val y: Float)
data class FaceRect(val x: Float, val y: Float, val w: Float, val h: Float)

/**
 * One knob: [r] is the cap, [arc] the value arc round it and [ring] the
 * mapping ring outside that.
 *
 * The ring is cut down to half the distance to the nearest other knob, so
 * two rings never overlap however the knobs are packed.
 */
data class FaceKnob(val x: Float, val y: Float, val r: Float, val arc: Float, val ring: Float)

/** A faceplate's height, the same for every module so they line up in a row like a rack. */
const val FACE_H = 280f

/** The stripe across the top with the module's name. */
const val FACE_HEADER = 24f

private const val FACE_MIN_W = 56f
private const val FACE_MARGIN = 18f
private const val FACE_COL = 34f
private const val MAX_COLS = 4

/** The big knob's centre and the top row of small ones, from the top. */
private const val BIG_Y = 64f
private const val BIG_R = 13f
private const val SMALL_Y = 108f
private const val SMALL_R = 8.5f
private const val KNOB_ROW = 40f
/** Room under a knob's arc for its label, which starts clear of the mapping ring. */
private const val KNOB_LABEL = 23f
/** Where a knob's label starts, under its arc. Past the mapping ring, so the ring never crosses it. */
const val FACE_LABEL_GAP = 4.5f

private const val JACK_ROW = 32f
/** Room under the jacks for the screws. */
private const val FACE_FOOT = 18f

private const val SCREEN_H = 50f
/** How far the scope's screen pushes its knobs down. */
private const val SCREEN_SHIFT = 48f

/** The value arc sits this far outside the cap, and the mapping ring this far outside the arc. */
private const val ARC_GAP = 3.2f
private const val RING_GAP = 3f
/** At least this much clear between two mapping rings. */
private const val RING_CLEAR = 2f

/**
 * The faceplate for a module with these knobs and jacks. [knobs] counts
 * named knobs only. [scope] puts a screen where the big knob would go.
 */
fun nexusFace(knobs: Int, inputs: Int, outputs: Int, scope: Boolean = false): NexusFace {
    val cols = (1..MAX_COLS).firstOrNull { fits(it, knobs, inputs, outputs, scope) } ?: MAX_COLS
    val w = max(FACE_MIN_W, FACE_MARGIN + cols * FACE_COL)
    val span = (w - FACE_MARGIN) / cols
    fun rowX(index: Int, count: Int): Float {
        val row = index / cols
        val inRow = min(cols, count - row * cols)
        return w / 2f + (index % cols - (inRow - 1) / 2f) * span
    }

    // Knobs. The scope's screen takes the big knob's place and pushes its
    // knobs down.
    val shift = if (scope) SCREEN_SHIFT else 0f
    val placed = ArrayList<Triple<Float, Float, Float>>()
    for (i in 0 until knobs) {
        if (i == 0) {
            placed += Triple(w / 2f, BIG_Y + shift, if (scope) SMALL_R else BIG_R)
        } else {
            val k = i - 1
            placed += Triple(rowX(k, knobs - 1), SMALL_Y + shift + (k / cols) * KNOB_ROW, SMALL_R)
        }
    }
    val faceKnobs = placed.mapIndexed { i, (x, y, r) ->
        val arc = r + ARC_GAP
        // Half the way to the nearest neighbour's own ring.
        var ring = arc + RING_GAP
        placed.forEachIndexed { j, (ox, oy, or) ->
            if (j == i) return@forEachIndexed
            val d = hypot(ox - x, oy - y)
            val theirs = or + ARC_GAP + RING_GAP
            // Share the gap in proportion to each knob's size.
            val mine = (d - RING_CLEAR) * (r + ARC_GAP + RING_GAP) / (r + ARC_GAP + RING_GAP + theirs)
            ring = min(ring, mine)
        }
        FaceKnob(x, y, r, arc, max(ring, arc + 1f))
    }

    // Jacks, from the bottom up: outputs in the last rows, inputs over them.
    val inRows = rows(inputs, cols)
    val outRows = rows(outputs, cols)
    val top = FACE_H - FACE_FOOT - (inRows + outRows) * JACK_ROW
    fun jack(index: Int, count: Int, firstRow: Int) =
        FacePoint(rowX(index, count), top + (firstRow + index / cols) * JACK_ROW + JACK_ROW / 2f - 4f)
    val ins = (0 until inputs).map { jack(it, inputs, 0) }
    val outs = (0 until outputs).map { jack(it, outputs, inRows) }
    val outBox = if (outputs == 0) null else
        FaceRect(5f, top + inRows * JACK_ROW - 3f, w - 10f, outRows * JACK_ROW + 2f)
    val screen = if (scope) FaceRect(7f, FACE_HEADER + 18f, w - 14f, SCREEN_H) else null
    return NexusFace(w, FACE_H, faceKnobs, ins, outs, outBox, screen, span)
}

private fun rows(n: Int, cols: Int) = (n + cols - 1) / cols

/** Whether [cols] columns fit everything in the faceplate's height. */
private fun fits(cols: Int, knobs: Int, inputs: Int, outputs: Int, scope: Boolean): Boolean {
    val shift = if (scope) SCREEN_SHIFT else 0f
    val knobEnd = when {
        knobs == 0 -> FACE_HEADER + 16f + shift
        knobs == 1 -> BIG_Y + shift + BIG_R + KNOB_LABEL
        else -> SMALL_Y + shift + (rows(knobs - 1, cols) - 1) * KNOB_ROW + KNOB_LABEL
    }
    val jacks = (rows(inputs, cols) + rows(outputs, cols)) * JACK_ROW
    return knobEnd + 10f + jacks + FACE_FOOT <= FACE_H
}

/** A face for a module type the palette describes, or a plain one for a type it doesn't. */
fun nexusFaceOf(info: NexusModuleInfo?, type: String): NexusFace =
    if (info == null) nexusFace(2, 2, 1) else nexusFace(
        info.knobs.count { it.isNotEmpty() }.coerceAtMost(NEXUS_KNOBS),
        info.inputs.size, info.outputs.size, scope = type == "scope",
    )

/**
 * The faces, one per module type, worked out once.
 *
 * Falls back to a plain face when the engine can't be asked, as in the unit
 * tests, which don't load it.
 */
object NexusFaces {
    private val cache = HashMap<String, NexusFace>()
    fun of(type: String): NexusFace = cache[type] ?: run {
        val info = NexusPalette.ofOrNull(type)
        nexusFaceOf(info, type).also { if (info != null) cache[type] = it }
    }
    fun size(m: NexusModule): Pair<Float, Float> = of(m.type).let { it.w to it.h }
}
