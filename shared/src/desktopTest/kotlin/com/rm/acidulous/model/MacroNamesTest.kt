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
}
