package com.rm.acidulous.engine

/**
 * A check that the engine's bridge carries each kind of value in a browser:
 * a string in and a Boolean out, notes, a Float back, a String back, arrays
 * of strings back, and an array the engine fills. For the web build's own
 * test page; it goes when the app itself runs there.
 */
object EngineProbe {
    fun machines(): List<String> = EngineNative.nativeMachineTypes().toList()
    fun params(type: String): List<String> = EngineNative.nativeMachineParamNames(type).toList()
    fun mount(rack: Int, type: String): Boolean = EngineNative.nativeMountMachine(rack, type)
    fun noteOn(rack: Int, note: Int) = EngineNative.nativeNoteOn(rack, note, 110)
    fun noteOff(rack: Int, note: Int) = EngineNative.nativeNoteOff(rack, note)
    fun peak(): Float = EngineNative.nativeReadPeakLevel()
    fun palette(): String = EngineNative.nativeNexusPalette()
    fun loudness(): FloatArray = EngineNative.nativeLoudness()
}
