package com.rm.acidulous.engine

import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

// The engine module, as the page's script put it: globalThis.acid - the
// Emscripten build of app/src/main/cpp on the memory the audio worklet shares.
// These carry the JNI objects its bridge takes and returns (platform/web/jni.h)
// across, for EngineNative's generated wasmJs half.

private fun jniEnv(): Int = js("globalThis.acid._acid_jni_env()")
private fun jniRelease(): Unit = js("globalThis.acid._acid_jni_release()")
private fun jniString(s: String): Int = js(
    "(() => { const m = globalThis.acid; const n = m.lengthBytesUTF8(s) + 1; const p = m._malloc(n); " +
        "m.stringToUTF8(s, p, n); const r = m._acid_jni_string(p); m._free(p); return r; })()",
)
private fun jniChars(p: Int): String = js("globalThis.acid.UTF8ToString(globalThis.acid._acid_jni_chars(p))")
private fun jniLength(p: Int): Int = js("globalThis.acid._acid_jni_length(p)")
private fun jniObjectAt(p: Int, i: Int): Int = js("globalThis.acid._acid_jni_object_at(p, i)")
private fun jniObjects(n: Int): Int = js("globalThis.acid._acid_jni_objects(n)")
private fun jniSetObject(p: Int, i: Int, v: Int): Unit = js("globalThis.acid._acid_jni_set_object(p, i, v)")
private fun jniFloats(n: Int): Int = js("globalThis.acid._acid_jni_floats(n)")
private fun jniFloatAt(p: Int, i: Int): Float = js("globalThis.acid._acid_jni_float_at(p, i)")
private fun jniSetFloat(p: Int, i: Int, v: Float): Unit = js("globalThis.acid._acid_jni_set_float(p, i, v)")
private fun jniInts(n: Int): Int = js("globalThis.acid._acid_jni_ints(n)")
private fun jniIntAt(p: Int, i: Int): Int = js("globalThis.acid._acid_jni_int_at(p, i)")
private fun jniSetInt(p: Int, i: Int, v: Int): Unit = js("globalThis.acid._acid_jni_set_int(p, i, v)")
private fun jniLongs(n: Int): Int = js("globalThis.acid._acid_jni_longs(n)")
private fun jniLongAt(p: Int, i: Int): Long = js("globalThis.acid._acid_jni_long_at(p, i)")
private fun jniSetLong(p: Int, i: Int, v: Long): Unit = js("globalThis.acid._acid_jni_set_long(p, i, v)")
private fun jniArenaOpen(): Int = js("globalThis.acid._acid_jni_arena_open()")
private fun asyncInt(t: Int): Int = js("globalThis.acid._acid_async_i(t)")
private fun asyncFloat(t: Int): Float = js("globalThis.acid._acid_async_f(t)")
private fun asyncLong(t: Int): Long = js("globalThis.acid._acid_async_j(t)")
private fun asyncDouble(t: Int): Double = js("globalThis.acid._acid_async_d(t)")
private fun asyncRelease(t: Int): Unit = js("globalThis.acid._acid_async_release(t)")
private fun onAsyncDone(done: (Int) -> Unit): Unit = js("globalThis.acidAsyncDone = done")

/**
 * JNI's objects on the engine's heap, from Kotlin: made to pass in, read when
 * they come back, and freed together once a call is done with them ([release]).
 * Arrays go across a value at a time - they are knob readings and waveform
 * columns, a few hundred at most, not audio.
 */
internal object Jni {
    val env: Int by lazy { jniEnv() }
    fun release() = jniRelease()

    // --- Calls handed to an engine thread: see web_async.cpp -----------------

    /** What each handed-over call does when its thread is done, by ticket. */
    private val waiting = HashMap<Int, () -> Unit>()
    private val hooked by lazy { onAsyncDone { ticket -> waiting.remove(ticket)?.invoke() } }

    /** A fresh arena, for the arguments of a call about to be handed over. */
    fun openArena(): Int {
        hooked
        return jniArenaOpen()
    }

    /**
     * Until the call's thread is done. Given up on, the call still finishes -
     * a thread cannot be stopped halfway through a file - and is freed then.
     */
    suspend fun await(ticket: Int) = suspendCancellableCoroutine { c ->
        waiting[ticket] = { c.resume(Unit) }
        c.invokeOnCancellation { waiting[ticket] = { asyncRelease(ticket) } }
    }
    fun resultInt(ticket: Int): Int = asyncInt(ticket)
    fun resultFloat(ticket: Int): Float = asyncFloat(ticket)
    fun resultLong(ticket: Int): Long = asyncLong(ticket)
    fun resultDouble(ticket: Int): Double = asyncDouble(ticket)
    fun releaseAsync(ticket: Int) = asyncRelease(ticket)

    fun string(s: String?): Int = if (s == null) 0 else jniString(s)
    fun readString(p: Int): String = if (p == 0) "" else jniChars(p)
    fun readStringOrNull(p: Int): String? = if (p == 0) null else jniChars(p)
    fun strings(a: Array<String>?): Int {
        if (a == null) return 0
        val p = jniObjects(a.size)
        for (i in a.indices) jniSetObject(p, i, jniString(a[i]))
        return p
    }
    fun readStrings(p: Int): Array<String> = if (p == 0) emptyArray() else Array(jniLength(p)) { readString(jniObjectAt(p, it)) }

    fun floats(a: FloatArray?): Int {
        if (a == null) return 0
        val p = jniFloats(a.size)
        for (i in a.indices) jniSetFloat(p, i, a[i])
        return p
    }
    fun copyBack(p: Int, a: FloatArray?) { if (a != null && p != 0) for (i in a.indices) a[i] = jniFloatAt(p, i) }
    fun readFloats(p: Int): FloatArray? = if (p == 0) null else FloatArray(jniLength(p)) { jniFloatAt(p, it) }

    fun ints(a: IntArray?): Int {
        if (a == null) return 0
        val p = jniInts(a.size)
        for (i in a.indices) jniSetInt(p, i, a[i])
        return p
    }
    fun copyBack(p: Int, a: IntArray?) { if (a != null && p != 0) for (i in a.indices) a[i] = jniIntAt(p, i) }
    fun readInts(p: Int): IntArray? = if (p == 0) null else IntArray(jniLength(p)) { jniIntAt(p, it) }

    fun longs(a: LongArray?): Int {
        if (a == null) return 0
        val p = jniLongs(a.size)
        for (i in a.indices) jniSetLong(p, i, a[i])
        return p
    }
    fun copyBack(p: Int, a: LongArray?) { if (a != null && p != 0) for (i in a.indices) a[i] = jniLongAt(p, i) }
    fun readLongs(p: Int): LongArray? = if (p == 0) null else LongArray(jniLength(p)) { jniLongAt(p, it) }
}
