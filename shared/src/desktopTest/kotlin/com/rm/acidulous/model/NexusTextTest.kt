package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A module's text, like a formula, kept with the patch. */
class NexusTextTest {
    private val withFormula = NexusPatch(
        modules = listOf(NexusModule(0, "voice", x = 0f, y = 0f), NexusModule(3, "formula", x = 200f, y = 0f)),
        cables = listOf(NexusCable(0, 0, 3, 1)),
        texts = mapOf(3 to "t * (t >> 5 | t >> 8)"),
    )

    @Test
    fun aFormulaWithBarsInItSurvivesTheRoundTrip() {
        val back = NexusPatch.decode(withFormula.encode())
        assertEquals("t * (t >> 5 | t >> 8)", back.texts[3])
        assertEquals(withFormula.cables, back.cables)
    }

    @Test
    fun theTextIsPartOfWhatTheEngineBuilds() {
        // Changing a formula has to rebuild the module, so it's in the topology.
        val other = withFormula.copy(texts = mapOf(3 to "t"))
        assertTrue(withFormula.topology() != other.topology())
        assertTrue(withFormula.topology().contains("e|03|t * (t >> 5 | t >> 8)"))
    }

    @Test
    fun aTextForAModuleThatIsGoneIsNotWritten() {
        val orphan = withFormula.copy(modules = withFormula.modules.filter { it.slot != 3 })
        assertFalse(orphan.encode().lines().any { it.startsWith("e|") })
    }

    @Test
    fun aFormulaIsOneLine() {
        val twoLines = withFormula.copy(texts = mapOf(3 to "t >> 4\n| t >> 6"))
        val back = NexusPatch.decode(twoLines.encode())
        assertEquals("t >> 4 | t >> 6", back.texts[3])
    }
}
