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

    @Test
    fun aCablesTextDepthsSurviveTheRoundTrip() {
        // Past the 24 cables with parameters, the text depth is what plays.
        val text = "v|2\nm|00|voice|poly\nm|03|vca|poly\nc|00.0|03.0|0.35|-0.09449|99.0|0.0000\nc|00.0|03.1|0.3\n"
        val back = NexusPatch.decode(NexusPatch.decode(text).encode())
        assertEquals(0.35f, back.cables[0].depthA, 0f)
        assertEquals(-0.09449f, back.cables[0].depthB, 0f)
        // A missing B depth is the A depth, as the engine reads it.
        assertEquals(0.3f, back.cables[1].depthB, 0f)
        assertTrue(NexusPatch.decode(text).encode().contains("c|00.0|03.1|0.3|0.3|99.0|0.0000"))
    }

    @Test
    fun numbersAreWrittenWithAPointInFrench() {
        val before = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.CANADA_FRENCH)
            val p = withFormula.copy(cables = listOf(NexusCable(0, 0, 3, 1, modSlot = 0, modAmount = 0.5f, depthA = 0.25f)))
            assertTrue(p.encode(), p.encode().contains("c|00.0|03.1|0.25|1.0|00.0|0.5000"))
        } finally {
            java.util.Locale.setDefault(before)
        }
    }
}
