package com.rm.acidulous.model

/**
 * The mod slot on every insert (engine/effect/SlotMod.h). Its settings are
 * params of the slot, kept in [UnitSlot.params] beside the effect's under
 * these names, so they save, record and lock like knobs. A slot without them
 * has its mod off.
 */
object InsertMod {
    /** The engine's name for the mod's table, read like an effect's (NativeEngine.effectParamInfo). */
    const val TYPE = "~mod"
    const val SOURCE = "mod_source"
    const val SHAPE = "mod_shape"
    const val RATE = "mod_rate"
    const val LENGTH = "mod_length"
    const val ATTACK = "mod_attack"
    const val RELEASE = "mod_release"
    const val KEY = "mod_key"
    fun target(j: Int) = "mod_target$j"
    fun depth(j: Int) = "mod_depth$j"
    fun step(i: Int) = "mod_step${i + 1}"

    /** Sources by index: off, LFO, steps, a follower on the insert's input, one keyed from another track. */
    const val OFF = 0
    const val LFO = 1
    const val STEPS = 2
    const val FOLLOW = 3
    const val KEYED = 4
    const val SOURCES = 5
    const val SHAPES = 7

    /** [KEY] is a track, 0-based over this many steps. */
    const val KEYS = 16
    fun sourceValue(source: Int): Float = source.coerceIn(0, SOURCES - 1).toFloat() / (SOURCES - 1)

    /** A target is an effect parameter's index + 1 over this many steps; 0 is none. */
    const val TARGETS = 64
    const val MAX_STEPS = 16
    /** Slot parameter k is engine index INDEX_BASE - k (bypass is -2). */
    const val INDEX_BASE = -16

    fun isModParam(name: String) = name.startsWith("mod_")
    fun withoutMod(params: Map<String, Float>) = params.filterKeys { !isModParam(it) }
    fun modOf(params: Map<String, Float>) = params.filterKeys { isModParam(it) }
    fun isOn(slot: UnitSlot) = (slot.params[SOURCE] ?: 0f) > 0f

    /** The engine index a target's normalised value names, or -1 for none. */
    fun targetIndex(v01: Float): Int = kotlin.math.round(v01.coerceIn(0f, 1f) * TARGETS).toInt() - 1
    fun targetValue(index: Int): Float = ((index + 1).coerceIn(0, TARGETS)).toFloat() / TARGETS

    /** The mod parameter a negative engine index names, or null. */
    fun nameOfIndex(index: Int, names: List<String>): String? =
        if (index <= INDEX_BASE) names.getOrNull(INDEX_BASE - index) else null
}
