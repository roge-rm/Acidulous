package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** A named macro shows its name in the lane lists, on every machine with macros. */
class MacroNamesTest {

    private fun track(type: String, settings: Map<String, String>) =
        Track(id = "t", name = "t", machine = Machine(type, settings = settings))

    @Test
    fun nexusMacrosTakeThePatchNames() {
        val t = track("Nexus", mapOf("macro1" to "Cutoff"))
        assertEquals("Cutoff", laneShortLabel(t, laneKey("machine", "macro1")))
        assertEquals("Nexus · Cutoff", laneLabel(t, laneKey("machine", "macro1")))
        // An unnamed one falls back to the panel's word.
        assertEquals("macro 2", laneShortLabel(t, laneKey("machine", "macro2")))
    }

    @Test
    fun theOtherMachinesStillCallThemMac() {
        val t = track("Cumulus", mapOf("macro3" to "Bloom"))
        assertEquals("Bloom", laneShortLabel(t, laneKey("machine", "mac3")))
        // Only Nexus's knobs are called macro1..macro8.
        assertEquals(null, track("Mosaic", mapOf("macro1" to "x")).let { m ->
            laneShortLabel(m, laneKey("machine", "macro1")).takeIf { it == "x" }
        })
    }

    @Test
    fun filamentMacrosTakeThePatchNames() {
        val t = track("Filament", mapOf("macro1" to "Bow Harder"))
        assertEquals("Bow Harder", laneShortLabel(t, laneKey("machine", "mac1")))
        assertEquals("macro 2", laneShortLabel(t, laneKey("machine", "mac2")))
        // The vibrato's lanes take the panel's words.
        assertEquals("depth", laneShortLabel(t, laneKey("machine", "vibdepth")))
    }

    @Test
    fun filamentBankHasItsFamiliesInOrder() {
        val families = FactoryBanks.of("Filament").map { it.family }.distinct()
        assertEquals(listOf("plucked", "picked", "struck", "bowed", "blown", "sympathetic", "prepared"), families)
    }

    @Test
    fun timberMacrosTakeThePatchNames() {
        val t = track("Timber", mapOf("macro1" to "Blow"))
        assertEquals("Blow", laneShortLabel(t, laneKey("machine", "mac1")))
        assertEquals("macro 2", laneShortLabel(t, laneKey("machine", "mac2")))
        // The swell's lanes take the panel's words.
        assertEquals("amount", laneShortLabel(t, laneKey("machine", "swell")))
    }

    @Test
    fun timberBankHasItsFamiliesInOrder() {
        val families = FactoryBanks.of("Timber").map { it.family }.distinct()
        assertEquals(listOf("clarinet", "sax", "double", "flute", "pipe", "low", "odd"), families)
    }

    @Test
    fun brazenMacrosTakeThePatchNames() {
        val t = track("Brazen", mapOf("macro1" to "Blow"))
        assertEquals("Blow", laneShortLabel(t, laneKey("machine", "mac1")))
        assertEquals("macro 2", laneShortLabel(t, laneKey("machine", "mac2")))
        // The swell's and flutter's lanes take the panel's words.
        assertEquals("amount", laneShortLabel(t, laneKey("machine", "swell")))
        assertEquals("rate", laneShortLabel(t, laneKey("machine", "flutterrate")))
    }

    @Test
    fun brazenBankHasItsFamiliesInOrder() {
        val families = FactoryBanks.of("Brazen").map { it.family }.distinct()
        assertEquals(listOf("high", "horn", "low", "muted", "section", "natural", "odd"), families)
        assertEquals("Init", FactoryBanks.of("Brazen").first().name)
    }

    @Test
    fun manualMacrosTakeThePatchNames() {
        val t = track("Manual", mapOf("macro1" to "Pull"))
        assertEquals("Pull", laneShortLabel(t, laneKey("machine", "mac1")))
        assertEquals("macro 2", laneShortLabel(t, laneKey("machine", "mac2")))
        // The swell box's and bar glide's lanes take the panel's words.
        assertEquals("shutters", laneShortLabel(t, laneKey("machine", "swellbox")))
        assertEquals("bar glide", laneShortLabel(t, laneKey("machine", "barglide")))
    }

    @Test
    fun manualBankHasItsFamiliesInOrder() {
        val families = FactoryBanks.of("Manual").map { it.family }.distinct()
        assertEquals(listOf("tonewheel", "cabinet", "combo", "church", "theatre", "reed", "split", "odd"), families)
        assertEquals("Init", FactoryBanks.of("Manual").first().name)
    }

    @Test
    fun hammerDrawAndFretMacrosTakeThePatchNames() {
        for (type in listOf("Hammer", "Draw", "Fret")) {
            val t = track(type, mapOf("macro1" to "Lid"))
            assertEquals("Lid", laneShortLabel(t, laneKey("machine", "mac1")))
            assertEquals("macro 2", laneShortLabel(t, laneKey("machine", "mac2")))
        }
        // The new knobs' lanes take the panel's words.
        // Roll carries its sense, "roll~strike", which the word lookup drops in English.
        assertEquals("roll", laneShortLabel(track("Hammer", emptyMap()), laneKey("machine", "roll")) { it.substringBefore('~') })
        assertEquals("duplex", laneShortLabel(track("Hammer", emptyMap()), laneKey("machine", "duplex")))
        assertEquals("swell", laneShortLabel(track("Fret", emptyMap()), laneKey("machine", "swell")))
        assertEquals("level", laneShortLabel(track("Draw", emptyMap()), laneKey("machine", "bass level")))
    }

    @Test
    fun tineAndPalmMacrosTakeThePatchNames() {
        for (type in listOf("Tine", "Palm")) {
            val t = track(type, mapOf("macro1" to "Ring"))
            assertEquals("Ring", laneShortLabel(t, laneKey("machine", "mac1")))
            assertEquals("macro 2", laneShortLabel(t, laneKey("machine", "mac2")))
        }
        assertEquals("pair", laneShortLabel(track("Tine", emptyMap()), laneKey("machine", "pair")))
        assertEquals("flam", laneShortLabel(track("Palm", emptyMap()), laneKey("machine", "flam")))
    }

    @Test
    fun fathomMacrosTakeThePatchNames() {
        val t = track("Fathom", mapOf("macro1" to "Storm"))
        assertEquals("Storm", laneShortLabel(t, laneKey("machine", "mac1")))
        assertEquals("macro 2", laneShortLabel(t, laneKey("machine", "mac2")))
        assertEquals("waves", laneShortLabel(track("Fathom", emptyMap()), laneKey("machine", "swell")))
    }
}
