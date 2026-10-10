package com.rm.acidulous.res

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Every French string has an English one. A word only in French has no
 * default, and the web build, which loads every string at start, couldn't
 * start in any other language.
 */
class FrenchKeysTest {
    private fun names(dir: String): Set<String> =
        File("src/commonMain/strings/$dir").listFiles { f -> f.name.endsWith(".xml") }!!.flatMap { f ->
            Regex("""<(?:string|string-array|plurals) name="([^"]+)"""").findAll(f.readText()).map { it.groupValues[1] }
        }.toSet()

    @Test fun `every French string has an English one`() {
        assertEquals(emptySet<String>(), names("values-fr") - names("values"))
    }
}
