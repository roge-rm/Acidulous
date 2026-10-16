package com.rm.acidulous.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A patch's inserts and sends, and which slots it owns (PatchChain.kt; the C++ twin is tools/patch_chain_test.cpp). */
class PatchChainTest {
    private val defaults: (String) -> Map<String, Float> = { type ->
        when (type) {
            "Chorus" -> mapOf("rate" to 0.3f, "mix" to 0.5f)
            "Delay" -> mapOf("time" to 0.4f, "mix" to 0.3f)
            else -> emptyMap()
        }
    }

    private val known: (String) -> Boolean = { it in setOf("Chorus", "Delay", "Distortion") }

    private fun track(vararg slots: UnitSlot) = Track(id = "t", name = "t", machine = Machine("Trinity"), effects = slots.toList())
    private fun patch(vararg fx: UnitSlot, reverb: Float? = null, delay: Float? = null) =
        Patch("Trinity", "P", emptyMap(), inserts = fx.toList(), sendReverb = reverb, sendDelay = delay)

    @Test
    fun `the plan takes the last patch's slots first, then free ones, in slot order`() {
        val p = PatchChain.plan(listOf(false, true), setOf(1), 2)
        assertEquals(listOf(0, 1), p.slotOf)
        assertEquals(setOf(1), p.clear)
        assertEquals(setOf(0, 1), p.owned)
        assertEquals(0, p.skipped)

        val full = PatchChain.plan(listOf(true, true), emptySet(), 1)
        assertEquals(listOf(-1), full.slotOf)
        assertEquals(1, full.skipped)
        assertTrue(full.owned.isEmpty())

        val one = PatchChain.plan(listOf(true, true), setOf(0, 1), 1)
        assertEquals(listOf(0), one.slotOf)
        assertEquals(setOf(0, 1), one.clear)
        assertEquals(setOf(0), one.owned)
    }

    @Test
    fun `a patch's inserts land in free slots with every knob, and the player's stay`() {
        val mine = UnitSlot("Delay", mapOf("time" to 0.9f))
        val t = track(mine)
        val a = PatchChain.apply(t, patch(UnitSlot("Chorus", mapOf("rate" to 0.7f))), known, defaults)
        assertEquals(mine, a.track.effectAt(0))
        assertEquals(UnitSlot("Chorus", mapOf("rate" to 0.7f, "mix" to 0.5f), fromPatch = true), a.track.effectAt(1))
        assertTrue(a.skipped.isEmpty())
    }

    @Test
    fun `the next patch replaces only what the last one put there`() {
        val mine = UnitSlot("Delay")
        val first = PatchChain.apply(track(UnitSlot(), mine), patch(UnitSlot("Chorus")), known, defaults).track
        assertEquals("Chorus", first.effectAt(0).type)
        val second = PatchChain.apply(first, patch(UnitSlot("Distortion"), UnitSlot("Delay")), known, defaults)
        assertEquals("Distortion", second.track.effectAt(0).type)
        assertTrue(second.track.effectAt(0).fromPatch)
        assertSame(mine, second.track.effectAt(1))
        assertEquals(listOf("Delay"), second.skipped)

        val none = PatchChain.apply(second.track, patch(), known, defaults).track
        assertTrue(none.effectAt(0).isEmpty)
        assertSame(mine, none.effectAt(1))
    }

    @Test
    fun `a patch keeps its order whichever slots it gets`() {
        val t = track(UnitSlot(), UnitSlot("Chorus", fromPatch = true))
        val a = PatchChain.apply(t, patch(UnitSlot("Distortion"), UnitSlot("Delay")), known, defaults).track
        assertEquals(listOf("Distortion", "Delay"), a.effects.map { it.type })
    }

    @Test
    fun `turning a knob keeps the insert the patch's, choosing another or a preset makes it the player's`() {
        val t = PatchChain.apply(track(), patch(UnitSlot("Chorus")), known, defaults).track
        assertTrue(t.withEffectParam(0, "rate", 0.1f).effectAt(0).fromPatch)
        assertTrue(t.withEffectBypass(0, true).effectAt(0).fromPatch)
        assertFalse(t.withEffect(0, "Delay").effectAt(0).fromPatch)
        assertFalse(t.withEffectPatch(0, mapOf("rate" to 0.2f)).effectAt(0).fromPatch)
        // Replaced by hand, the next patch leaves it.
        val handed = t.withEffect(0, "Delay")
        assertEquals("Delay", PatchChain.apply(handed, patch(), known, defaults).track.effectAt(0).type)
    }

    @Test
    fun `sends are set only by a patch that has them, the missing one to zero`() {
        val t = track().copy(mixer = Mixer(sendReverb = 0.4f, sendDelay = 0.6f))
        assertEquals(t.mixer, PatchChain.apply(t, patch(), known, defaults).track.mixer)
        val set = PatchChain.apply(t, patch(reverb = 0.25f), known, defaults).track.mixer
        assertEquals(0.25f, set.sendReverb)
        assertEquals(0f, set.sendDelay)
    }

    @Test
    fun `a patch owns its sends and the player's come back after`() {
        val t = track().copy(mixer = Mixer(sendReverb = 0.4f, sendDelay = 0.6f))
        val a = PatchChain.apply(t, patch(reverb = 0.2f), known, defaults).track
        assertEquals(PatchSends(0.4f, 0.6f, 0.2f, 0f), a.patchSends)
        // A second patch with sends keeps the first saved levels.
        val b = PatchChain.apply(a, patch(reverb = 0.1f, delay = 0.3f), known, defaults).track
        assertEquals(0.4f, b.patchSends!!.reverb)
        assertEquals(0.3f, b.mixer.sendDelay)
        val c = PatchChain.apply(b, patch(), known, defaults).track
        assertEquals(0.4f, c.mixer.sendReverb)
        assertEquals(0.6f, c.mixer.sendDelay)
        assertNull(c.patchSends)
    }

    @Test
    fun `a send moved by hand is the player's, and nothing is put back`() {
        val t = track().copy(mixer = Mixer(sendReverb = 0.4f))
        val a = PatchChain.apply(t, patch(reverb = 0.2f), known, defaults).track
        val moved = a.withMixerParam("senddelay", 0.5f)
        assertNull(PatchChain.heldSends(moved))
        val b = PatchChain.apply(moved, patch(), known, defaults).track
        assertEquals(0.2f, b.mixer.sendReverb)
        assertEquals(0.5f, b.mixer.sendDelay)
        assertNull(b.patchSends)
        // The next patch keeps what the player set.
        assertEquals(PatchSends(0.2f, 0.5f, 0.3f, 0f), PatchChain.apply(moved, patch(reverb = 0.3f), known, defaults).track.patchSends)
    }

    @Test
    fun `saving with effects carries sends the patch owns, even at zero, and the track keeps its saved levels`() {
        val t = PatchChain.apply(track().copy(mixer = Mixer(sendReverb = 0.4f)), patch(reverb = 0f, delay = 0f), known, defaults).track
        val p = PatchChain.withEffectsOf(Patch("Trinity", "Mine", emptyMap()), t)
        assertEquals(0f, p.sendReverb)
        assertEquals(0f, p.sendDelay)
        assertEquals(t.patchSends, PatchChain.ownedBySaved(t).patchSends)
    }

    @Test
    fun `saving with effects takes every insert and the sends, and the track's inserts become the patch's`() {
        val t = track(UnitSlot("Chorus", fromPatch = true), UnitSlot("Delay", bypass = true))
            .copy(mixer = Mixer(sendReverb = 0.3f))
        val p = PatchChain.withEffectsOf(Patch("Trinity", "Mine", emptyMap()), t)
        assertEquals(listOf(UnitSlot("Chorus"), UnitSlot("Delay", bypass = true)), p.inserts)
        assertEquals(0.3f, p.sendReverb)
        assertEquals(0f, p.sendDelay)

        val saved = PatchChain.ownedBySaved(t)
        assertTrue(saved.effects.all { it.fromPatch })
        // Loading it again replaces them rather than adding a second set.
        val again = PatchChain.apply(saved, p, known, defaults)
        assertTrue(again.skipped.isEmpty())
        assertEquals(listOf("Chorus", "Delay"), again.track.effects.map { it.type })
        assertEquals(setOf(0, 1), again.placed)
    }

    @Test
    fun `sends at zero aren't saved, so loading leaves the track's alone`() {
        val p = PatchChain.withEffectsOf(Patch("Trinity", "Mine", emptyMap()), track(UnitSlot("Chorus")))
        assertNull(p.sendReverb)
        assertNull(p.sendDelay)
        val other = track().copy(mixer = Mixer(sendReverb = 0.5f))
        assertEquals(0.5f, PatchChain.apply(other, p, known, defaults).track.mixer.sendReverb)
    }

    @Test
    fun `an effect this build doesn't have is left out and never takes a slot`() {
        val mine = UnitSlot("Delay")
        val first = PatchChain.apply(track(UnitSlot(), mine), patch(UnitSlot("Chorus")), known, defaults).track
        val a = PatchChain.apply(first, patch(UnitSlot("FutureVerb"), UnitSlot("Distortion")), known, defaults)
        assertEquals(listOf("FutureVerb"), a.unknown)
        assertTrue(a.skipped.isEmpty())
        assertEquals(listOf("Distortion", "Delay"), a.track.effects.map { it.type })
        assertTrue(a.track.effects.none { it.type == "FutureVerb" })
        assertEquals(setOf(0), a.placed)
    }

    @Test
    fun `loading a patch with its inserts is one undo step`() {
        val editor = SongEditor(Fixtures.song()) { _, _ -> }
        val before = editor.song.tracks[0]
        editor.edit(0) { t -> PatchChain.apply(t, patch(UnitSlot("Chorus"), reverb = 0.2f), known, defaults).track.withPatch(mapOf("x" to 0.5f)) }
        assertEquals("Chorus", editor.song.tracks[0].effectAt(0).type)
        assertTrue(editor.song.tracks[0].patchSends != null)
        editor.undo(0)
        assertSame(before, editor.song.tracks[0])
    }

    @Test
    fun `songs keep which slots are the patch's, and old songs own none`() {
        val song = Fixtures.song()
        val owned = song.copy(tracks = song.tracks.mapIndexed { i, t ->
            if (i == 0) t.copy(effects = listOf(UnitSlot("Chorus", fromPatch = true), UnitSlot("Delay"))) else t
        })
        val text = SongStore.encode(owned)
        val back = SongStore.decode(text)
        assertTrue(back.tracks[0].effectAt(0).fromPatch)
        assertFalse(back.tracks[0].effectAt(1).fromPatch)
        // Not written when false, so an old song reads the same.
        assertEquals(1, Regex("fromPatch").findAll(text).count())
        val old = SongStore.decode(text.replace(Regex(",?\\s*\"fromPatch\":\\s*true"), ""))
        assertFalse(old.tracks[0].effectAt(0).fromPatch)
        assertNull(old.tracks[0].patchSends)
        assertFalse(text.contains("patchSends"))
        val held = song.copy(tracks = song.tracks.mapIndexed { i, t -> if (i == 0) t.copy(patchSends = PatchSends(0.4f, 0.1f, 0.2f, 0f)) else t })
        assertEquals(PatchSends(0.4f, 0.1f, 0.2f, 0f), SongStore.decode(SongStore.encode(held)).tracks[0].patchSends)
    }

    @Test
    fun `a patch with effects round-trips, and an old patch reads without any`() {
        val json = Json { ignoreUnknownKeys = true }
        val p = patch(UnitSlot("Chorus", mapOf("rate" to 0.7f)), delay = 0.1f)
        val back = json.decodeFromString(Patch.serializer(), json.encodeToString(Patch.serializer(), p))
        assertEquals(p, back)
        val old = json.decodeFromString(Patch.serializer(), """{"machine":"Trinity","name":"Old","params":{"cutoff":0.5}}""")
        assertTrue(old.inserts.isEmpty())
        assertNull(old.sendReverb)
        assertNull(old.sendDelay)
    }

    @Test
    fun `factory examples carry their effects`() {
        val bank = PatchStore.factory("Trinity")
        val withFx = bank.filter { it.inserts.isNotEmpty() }
        assertTrue("no Trinity patch carries an insert", withFx.isNotEmpty())
        for (p in withFx) assertTrue("${p.name} has more inserts than slots", p.inserts.size <= EFFECT_SLOTS)
        assertTrue(bank.any { it.sendReverb != null || it.sendDelay != null })
    }
}
