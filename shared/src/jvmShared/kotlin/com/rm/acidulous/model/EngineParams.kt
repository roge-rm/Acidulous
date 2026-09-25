package com.rm.acidulous.model

/**
 * How document units become the engine's normalised 0..1 parameters. These
 * mirror the ParamDef tables in engine/rack/Rack.cpp and MasterBus.cpp; if a
 * range changes there it changes here.
 */
object EngineParams {
    const val VOLUME_MAX = 1.5f
    const val DELAY_TIMES = 7
    val DELAY_TIME_NAMES = listOf("1/16", "1/8T", "1/8", "1/8.", "1/4", "1/4.", "1/2")

    fun volume01(v: Float): Float = (v / VOLUME_MAX).coerceIn(0f, 1f)
    fun volumeFrom01(v01: Float): Float = v01.coerceIn(0f, 1f) * VOLUME_MAX
    fun pan01(p: Float): Float = ((p + 1f) / 2f).coerceIn(0f, 1f)
    fun panFrom01(v01: Float): Float = v01.coerceIn(0f, 1f) * 2f - 1f
    fun bool01(b: Boolean): Float = if (b) 1f else 0f
    fun delayTime01(index: Int): Float = index.coerceIn(0, DELAY_TIMES - 1).toFloat() / (DELAY_TIMES - 1)
    fun unit01(v: Float): Float = v.coerceIn(0f, 1f)
}
