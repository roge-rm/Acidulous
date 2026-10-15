package com.rm.acidulous.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** The text engine lines are written as (EngineLines.kt). Replaying it is checked by tools/engine_replay.sh. */
class EngineLinesTest {

    private fun lines(w: EngineLineWriter) = w.text().lines().drop(1).filter { it.isNotEmpty() }

    @Test
    fun startsWithTheHeader() {
        assertEquals("acidulous-engine 1\n", EngineLineWriter().text())
    }

    @Test
    fun writesEachKindOfArgument() {
        val w = EngineLineWriter()
        w.call("Mixed") {
            int(-3); long(1L shl 40); float(0.1f); bool(true); str("a \"b\"\\c\n\t\u0001"); str(null)
            ints(intArrayOf(1, 2)); floats(FloatArray(0)); floats(null); strings(arrayOf("x", "y"))
        }
        assertEquals(
            listOf("Mixed -3 1099511627776 0.1 true \"a \\\"b\\\"\\\\c\\n\\t\\x01\" null i[2: 1 2] f[0:] null s[2: \"x\" \"y\"]"),
            lines(w),
        )
    }

    @Test
    fun floatsReadBackExactly() {
        val values = floatArrayOf(0.1f, 1f / 3f, 1e-30f, -7.006e-39f, Float.MAX_VALUE, 0.42f, Float.NaN)
        val w = EngineLineWriter()
        w.call("F") { floats(values) }
        val read = lines(w).single().substringAfter(": ").removeSuffix("]").split(' ').map { it.toFloat() }
        assertEquals(values.map { it.toRawBits() }, read.map { it.toRawBits() })
    }

    @Test
    fun handlesAreNumberedInOrder() {
        val w = EngineLineWriter()
        w.handleMade(w.callMakingHandle("SnapshotBegin"), 7777L)
        w.call("SnapshotCommit") { handle(7777L) }
        w.handleMade(w.callMakingHandle("SnapshotBegin"), 7777L)
        w.call("SnapshotCommit") { handle(7777L) }
        assertEquals(listOf("SnapshotBegin", "SnapshotCommit @0", "SnapshotBegin", "SnapshotCommit @1"), lines(w))
    }

    @Test
    fun mediaPathsBecomeZipPaths() {
        val w = EngineLineWriter { p -> if (p.startsWith("/user/")) p.removePrefix("/user/") else null }
        w.call("LoadSample") { path("/user/samples/k.wav"); path("/elsewhere/a.wav"); path("") }
        w.call("LoadZoneMap") { spec("/user/a.wav|/user/x|127\n/user/b c.wav|1|2", intArrayOf(0)) }
        assertEquals(
            listOf(
                "LoadSample \"zip:samples/k.wav\" \"/elsewhere/a.wav\" \"\"",
                "LoadZoneMap \"zip:a.wav|/user/x|127\\nzip:b c.wav|1|2\"",
            ),
            lines(w),
        )
        assertEquals(listOf("/elsewhere/a.wav"), w.unportable())
    }
}
