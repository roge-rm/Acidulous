package com.rm.acidulous.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * [RECORD_UNITS] against the C++ enum it mirrors, read from the header, so an
 * entry added to one and not the other fails here instead of silently
 * dropping whatever that unit records.
 */
class RecordUnitsTest {
    private fun enumNames(): List<String> {
        val text = File("src/main/cpp/engine/core/Messages.h").readText()
        val body = text.substringAfter("enum class Unit : uint8_t {").substringBefore("};")
        val code = body
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\n]*"), "")
        return code.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** The lane name each unit goes by; the master's inserts are `master1` and `master2`. */
    private fun laneName(enumName: String): String = enumName.lowercase().replace("masterfx", "master")

    @Test
    fun everyUnitIsNamedAtItsOrdinal() {
        assertEquals(enumNames().map(::laneName), RECORD_UNITS)
    }
}
