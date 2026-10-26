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
        assertEquals(null, track("Bias", mapOf("macro1" to "x")).let { m ->
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

    @Test
    fun aviaryMacrosTakeThePatchNames() {
        val t = track("Aviary", mapOf("macro1" to "Harsh"))
        assertEquals("Harsh", laneShortLabel(t, laneKey("machine", "mac1")))
        assertEquals("macro 2", laneShortLabel(t, laneKey("machine", "mac2")))
    }

    @Test
    fun tongueMacrosTakeThePatchNames() {
        val t = track("Tongue", mapOf("macro1" to "Wah"))
        assertEquals("Wah", laneShortLabel(t, laneKey("machine", "mac1")))
        assertEquals("macro 2", laneShortLabel(t, laneKey("machine", "mac2")))
        assertEquals("wah rate", laneShortLabel(t, laneKey("machine", "wahrate")))
    }

    @Test
    fun tongueBankHasItsFamiliesInOrder() {
        val bank = FactoryBanks.of("Tongue")
        assertEquals(62, bank.size)
        val families = bank.map { it.family }.distinct()
        assertEquals(listOf("steel", "northern", "southern", "bamboo", "reeds", "rhythm", "strange"), families)
    }

    @Test
    fun sympathMacrosTakeThePatchNames() {
        val t = track("Sympath", mapOf("macro1" to "Buzz"))
        assertEquals("Buzz", laneShortLabel(t, laneKey("machine", "mac1")))
        assertEquals("macro 2", laneShortLabel(t, laneKey("machine", "mac2")))
        assertEquals("tarbs", laneShortLabel(t, laneKey("machine", "tarbs")))
    }

    @Test
    fun chanterMacrosTakeThePatchNames() {
        val t = track("Chanter", mapOf("macro1" to "Drones"))
        assertEquals("Drones", laneShortLabel(t, laneKey("machine", "mac1")))
        assertEquals("macro 2", laneShortLabel(t, laneKey("machine", "mac2")))
        assertEquals("dog", laneShortLabel(t, laneKey("machine", "dog")))
    }

    @Test
    fun sympathBankHasItsFamiliesInOrder() {
        val bank = FactoryBanks.of("Sympath")
        assertEquals(96, bank.size)
        assertEquals("Init", bank.first().name)
        val families = bank.map { it.family }.distinct()
        assertEquals(listOf("sitar", "sarod", "veena", "drone", "east", "bowed", "fiddle", "bridge"), families)
    }

    @Test
    fun chanterBankHasItsFamiliesInOrder() {
        val bank = FactoryBanks.of("Chanter")
        assertEquals(80, bank.size)
        assertEquals("Init", bank.first().name)
        val families = bank.map { it.family }.distinct()
        assertEquals(listOf("isles", "west", "east", "double", "gurdy", "drones", "odd"), families)
        assertEquals(listOf(15, 12, 12, 11, 12, 9, 9), families.map { f -> bank.count { it.family == f } })
    }

    @Test
    fun pollenMacrosTakeThePatchNames() {
        val t = track("Pollen", mapOf("macro1" to "Thicker"))
        assertEquals("Thicker", laneShortLabel(t, laneKey("machine", "mac1")))
        assertEquals("macro 2", laneShortLabel(t, laneKey("machine", "mac2")))
        assertEquals("density", laneShortLabel(t, laneKey("machine", "density")))
    }

    @Test
    fun pollenBankHasItsFamiliesInOrder() {
        val bank = FactoryBanks.of("Pollen")
        assertEquals(54, bank.size)
        assertEquals("Init", bank.first().name)
        val families = bank.map { it.family }.distinct()
        assertEquals(listOf("cloud", "pitch", "stretch", "bloom", "rhythm", "texture", "live"), families)
        assertEquals(listOf(8, 9, 6, 8, 8, 8, 7), families.map { f -> bank.count { it.family == f } })
    }

    @Test
    fun mosaicMacrosTakeThePatchNames() {
        val t = track("Mosaic", mapOf("macro1" to "Brighter"))
        assertEquals("Brighter", laneShortLabel(t, laneKey("machine", "mac1")))
        assertEquals("macro 2", laneShortLabel(t, laneKey("machine", "mac2")))
        assertEquals("freq", laneShortLabel(t, laneKey("machine", "f_freq")))
    }

    @Test
    fun mosaicBankHasItsFamiliesInOrder() {
        val bank = FactoryBanks.of("Mosaic")
        assertEquals(56, bank.size)
        assertEquals("Init", bank.first().name)
        val families = bank.map { it.family }.distinct()
        assertEquals(listOf("keys", "pad", "lead", "bass", "grain", "motion", "texture"), families)
        assertEquals(listOf(10, 9, 8, 6, 9, 8, 6), families.map { f -> bank.count { it.family == f } })
    }

    @Test
    fun dictionMacrosTakeThePatchNames() {
        val t = track("Diction", mapOf("macro1" to "Bigger"))
        assertEquals("Bigger", laneShortLabel(t, laneKey("machine", "mac1")))
        assertEquals("macro 2", laneShortLabel(t, laneKey("machine", "mac2")))
        assertEquals("formant", laneShortLabel(t, laneKey("machine", "formant")))
    }

    @Test
    fun dictionBankHasItsFamiliesInOrder() {
        val bank = FactoryBanks.of("Diction")
        assertEquals(45, bank.size)
        assertEquals("Init", bank.first().name)
        val families = bank.map { it.family }.distinct()
        assertEquals(listOf("voices", "colour", "style", "choir", "harmony", "strange"), families)
        assertEquals(listOf(8, 8, 9, 8, 5, 7), families.map { f -> bank.count { it.family == f } })
    }

    @Test
    fun moltMacrosTakeThePatchNames() {
        val t = track("Molt", mapOf("macro1" to "Looser"))
        assertEquals("Looser", laneShortLabel(t, laneKey("machine", "mac1")))
        assertEquals("macro 2", laneShortLabel(t, laneKey("machine", "mac2")))
        assertEquals("formant", laneShortLabel(t, laneKey("machine", "formant")))
    }

    @Test
    fun moltBankHasItsFamiliesInOrder() {
        val bank = FactoryBanks.of("Molt")
        assertEquals(38, bank.size)
        assertEquals("Init", bank.first().name)
        val families = bank.map { it.family }.distinct()
        assertEquals(listOf("tune", "robot", "size", "tone", "shape", "play"), families)
        assertEquals(listOf(7, 6, 6, 7, 6, 6), families.map { f -> bank.count { it.family == f } })
    }

    @Test
    fun cipherMacrosTakeThePatchNames() {
        val t = track("Cipher", mapOf("macro1" to "Untangle"))
        assertEquals("Untangle", laneShortLabel(t, laneKey("machine", "mac1")))
        assertEquals("macro 2", laneShortLabel(t, laneKey("machine", "mac2")))
    }

    @Test
    fun cipherBankHasItsFamiliesInOrder() {
        val bank = FactoryBanks.of("Cipher")
        assertEquals(40, bank.size)
        assertEquals("Init", bank.first().name)
        val families = bank.map { it.family }.distinct()
        assertEquals(listOf("classic", "remap", "formant", "hold", "carrier", "extreme"), families)
        assertEquals(listOf(7, 7, 6, 6, 7, 7), families.map { f -> bank.count { it.family == f } })
    }

    @Test
    fun diceMacrosTakeThePatchNames() {
        val t = track("Dice", mapOf("macro1" to "Swap"))
        assertEquals("Swap", laneShortLabel(t, laneKey("machine", "mac1")))
        assertEquals("macro 2", laneShortLabel(t, laneKey("machine", "mac2")))
    }

    @Test
    fun diceBankHasItsFamiliesInOrder() {
        val bank = FactoryBanks.of("Dice")
        assertEquals(26, bank.size)
        assertEquals("Init", bank.first().name)
        val families = bank.map { it.family }.distinct()
        assertEquals(listOf("straight", "dice", "perform", "tone", "slice"), families)
        assertEquals(listOf(6, 6, 6, 5, 3), families.map { f -> bank.count { it.family == f } })
    }
}
