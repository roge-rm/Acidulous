package com.rm.acidulous.engine

import kotlin.concurrent.Volatile

/**
 * Engine lines: the engine calls that build a song's state, written as text
 * (engine.txt) so a host without Kotlin can build the same state by making
 * the same calls.
 *
 * The first line is `acidulous-engine 1`. Then one call per line: its name
 * without `native`, then its arguments, separated by spaces:
 *
 *     MountMachine 2 "Reflux"
 *     SetParam 2 "machine" "cutoff" 0.42 false 0
 *     SnapshotSetLane @0 2 1 "Reflux" "machine" "cutoff" false f[4: 0.0 0.2 1536.0 0.9]
 *
 * - Ints and longs as numbers, floats as the shortest text that reads back
 *   as the same float (NaN and Infinity as Kotlin writes them), booleans as
 *   true or false.
 * - Strings in UTF-8, in double quotes with \\, \", \n, \r and \t escaped,
 *   and other control characters as \xHH. A missing string or array is null.
 *   The replayer hands strings over as the JVM does, in modified UTF-8.
 * - Arrays as a type letter (i, l, f, s), then a count and the values:
 *   `i[3: 1 2 3]`, `f[0:]`.
 * - A snapshot handle is `@n`, the n-th SnapshotBegin in the file from 0.
 * - A media path the writer was told how to shorten is `zip:` and its path
 *   inside the zip, as an argument or as one of a spec's `|` fields marked as
 *   a path. The replayer puts its own folder in place of `zip:` there.
 * - Lines are in the order the calls started, so a call made while a load
 *   runs on a worker comes after the load.
 * - Lines starting with # are comments.
 *
 * Which calls are written is marked in EngineNative's list (`// line`).
 * Writing them (EngineNative.jvm.kt) and replaying them
 * (platform/lines/lines_dispatch.cpp) are both generated from that list by
 * tools/gen_engine_bridge.py.
 */
object EngineLines {
    const val HEADER = "acidulous-engine 1"

    /**
     * Where recorded calls go, or null when nothing is recording. Every
     * recorded call reads it once, which is all it costs when null.
     */
    @Volatile var writer: EngineLineWriter? = null
}

/**
 * Collects engine lines. [zipPath] turns an absolute media path into its
 * path inside the zip, or null to leave it as it is.
 *
 * Calls can come from more than one thread (loads run on a worker), so lines
 * are added under a lock, in the order the calls returned.
 *
 * An absolute path [zipPath] doesn't shorten is written as it is, and only
 * works on this machine, so it's also listed in [unportable].
 */
class EngineLineWriter(private val zipPath: (String) -> String? = { null }) {
    private val lock = Any()
    private val text = StringBuilder(EngineLines.HEADER).append('\n')
    /** Each live handle's position among the SnapshotBegin lines. */
    private val handles = HashMap<Long, Int>()
    private var begun = 0
    private val absolute = LinkedHashSet<String>()

    fun text(): String = com.rm.acidulous.util.locked(lock) { text.toString() }

    /** Absolute paths written as they are, which another machine won't have. */
    fun unportable(): List<String> = com.rm.acidulous.util.locked(lock) { absolute.toList() }

    /** Writes one call, before it's made. [args] writes its arguments in order. */
    inline fun call(name: String, args: EngineLine.() -> Unit) {
        val line = EngineLine(this, name)
        line.args()
        add(line)
    }

    @PublishedApi internal fun add(line: EngineLine) = com.rm.acidulous.util.locked(lock) {
        text.append(line.text).append('\n')
    }

    /**
     * Writes a call with no arguments that makes a handle, before it's made.
     * Returns its number among those calls, for [handleMade].
     */
    fun callMakingHandle(name: String): Int = com.rm.acidulous.util.locked(lock) {
        text.append(name).append('\n')
        begun++
    }

    /** The call numbered [slot] by [callMakingHandle] returned [handle]. */
    fun handleMade(slot: Int, handle: Long) = com.rm.acidulous.util.locked(lock) { handles[handle] = slot }

    @PublishedApi internal fun handleRef(handle: Long): String =
        com.rm.acidulous.util.locked(lock) { handles[handle]?.let { "@$it" } } ?: "@?"

    @PublishedApi internal fun shorten(path: String): String {
        if (path.isEmpty()) return path
        zipPath(path)?.let { return "zip:$it" }
        val isAbsolute = path.startsWith('/') || path.startsWith('\\') || (path.length > 2 && path[1] == ':' && path[2] == '\\')
        if (isAbsolute) com.rm.acidulous.util.locked(lock) { absolute += path }
        return path
    }
}

/** One line being written: its name, then each argument after a space. */
class EngineLine(@PublishedApi internal val writer: EngineLineWriter, name: String) {
    @PublishedApi internal val text = StringBuilder(name)

    private fun arg(s: String) { text.append(' ').append(s) }

    fun int(v: Int) = arg(v.toString())
    fun long(v: Long) = arg(v.toString())
    fun float(v: Float) = arg(v.toString())
    fun double(v: Double) = arg(v.toString())
    fun bool(v: Boolean) = arg(if (v) "true" else "false")
    fun handle(v: Long) = arg(writer.handleRef(v))
    fun str(v: String?) = arg(if (v == null) "null" else quote(v))
    /** A media path, shortened if the writer knows how. */
    fun path(v: String?) = str(v?.let { writer.shorten(it) })
    /** Lines of `|` fields, where the fields numbered in [paths] may be media paths. */
    fun spec(v: String?, paths: IntArray) = str(
        v?.split('\n')?.joinToString("\n") { l ->
            l.split('|').mapIndexed { i, f -> if (i in paths) writer.shorten(f) else f }.joinToString("|")
        },
    )
    fun ints(v: IntArray?) = array('i', v?.size) { i -> v!![i].toString() }
    fun longs(v: LongArray?) = array('l', v?.size) { i -> v!![i].toString() }
    fun floats(v: FloatArray?) = array('f', v?.size) { i -> v!![i].toString() }
    fun strings(v: Array<String>?) = array('s', v?.size) { i -> quote(v!![i]) }

    private inline fun array(kind: Char, size: Int?, item: (Int) -> String) {
        if (size == null) return arg("null")
        text.append(' ').append(kind).append('[').append(size).append(':')
        for (i in 0 until size) text.append(' ').append(item(i))
        text.append(']')
    }

    private fun quote(s: String): String {
        val b = StringBuilder(s.length + 2).append('"')
        for (c in s) when {
            c == '\\' -> b.append("\\\\")
            c == '"' -> b.append("\\\"")
            c == '\n' -> b.append("\\n")
            c == '\r' -> b.append("\\r")
            c == '\t' -> b.append("\\t")
            c < ' ' -> b.append("\\x").append(c.code.toString(16).padStart(2, '0'))
            else -> b.append(c)
        }
        return b.append('"').toString()
    }
}
