package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InsertModTest {
    private fun modded(): Song = Fixtures.song().updateTrack(0) { t ->
        t.withEffect(2, "Filter")
            .withEffectParam(2, "cutoff", 0.4f)
            .withEffectParam(2, InsertMod.SOURCE, 0.25f)
            .withEffectParam(2, InsertMod.target(1), InsertMod.targetValue(0))
            .withEffectParam(2, InsertMod.depth(1), 0.8f)
    }

    @Test
    fun `a target names an effect parameter by index and back`() {
        for (i in -1 until InsertMod.TARGETS) assertEquals(i, InsertMod.targetIndex(InsertMod.targetValue(i)))
    }

    @Test
    fun `the mod saves with the song`() {
        val song = modded()
        val back = SongStore.decode(SongStore.encode(song))
        assertEquals(song, back)
        assertTrue(InsertMod.isOn(back.tracks[0].effectAt(2)))
    }

    @Test
    fun `a song from before has its mods off`() {
        val back = SongStore.decode(SongStore.encode(Fixtures.song().updateTrack(0) { it.withEffect(0, "Delay") }))
        assertFalse(InsertMod.isOn(back.tracks[0].effectAt(0)))
    }

    @Test
    fun `an effect preset keeps the slot's mod`() {
        val t = modded().tracks[0].withEffectPatch(2, mapOf("cutoff" to 0.9f, "reso" to 0.2f))
        val fx = t.effectAt(2)
        assertEquals(0.9f, fx.params["cutoff"])
        assertEquals(0.25f, fx.params[InsertMod.SOURCE])
        assertEquals(0.8f, fx.params[InsertMod.depth(1)])
    }

    @Test
    fun `choosing another effect drops the mod`() {
        val t = modded().tracks[0].withEffect(2, "Delay")
        assertFalse(InsertMod.isOn(t.effectAt(2)))
    }

    @Test
    fun `a patch carries the mod on its own inserts`() {
        val track = modded().tracks[0].withEffect(0, "Filter").withEffectParam(0, InsertMod.SOURCE, 0.5f)
        val patch = PatchChain.withEffectsOf(Patch("machine:Reflux", "p", emptyMap()), track)
        val applied = PatchChain.apply(Fixtures.song().tracks[0], patch, { true }, { emptyMap() }).track
        assertEquals(0.5f, applied.effectAt(0).params[InsertMod.SOURCE])
    }

    @Test
    fun `an engine index names a mod setting`() {
        val names = listOf("mod_source", "mod_shape")
        assertEquals("mod_shape", InsertMod.nameOfIndex(InsertMod.INDEX_BASE - 1, names))
        assertEquals(null, InsertMod.nameOfIndex(-2, names))
    }
}
