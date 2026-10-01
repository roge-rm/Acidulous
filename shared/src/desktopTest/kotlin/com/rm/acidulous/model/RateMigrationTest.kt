package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RateMigrationTest {
    private fun near(expected: Float, actual: Float?) = assertEquals(expected, actual ?: Float.NaN, 1e-6f)

    /** A song as 0.9.11 saved it: eight rates, version 1. */
    private fun oldSong(): Song {
        val filter = UnitSlot("Filter", mapOf("lforate" to 4f / 7f, "cutoff" to 0.3f)) // 1 bar
        val lane = Lane(listOf(LanePoint(0, 0f), LanePoint(240, LANE_BASE)), linear = false) // a 1/16 step lock
        val trinity = Track(
            id = "t1", name = "bass",
            machine = Machine("Trinity", mapOf("l1_sync" to 3f / 8f, "l2_sync" to 0f, "f1_res" to 0.5f)), // 1/4, free
            effects = listOf(filter),
            clips = mapOf("c1" to Clip(automation = mapOf(laneKey("effect1", "lforate") to lane, laneKey("effect1", "cutoff") to lane))),
        )
        return Song(version = 1, name = "old", tracks = listOf(trinity), master = Master(inserts = listOf(UnitSlot("Tremolo", mapOf("rate" to 2f / 7f)))))
    }

    @Test
    fun anOldSongKeepsItsRates() {
        val song = SongStore.decode(SongStore.encode(oldSong()))
        assertEquals(RateMigration.SONG_VERSION, song.version)
        val t = song.tracks[0]
        near(13f / 16f, t.effects[0].params["lforate"]) // still 1 bar
        near(0.3f, t.effects[0].params["cutoff"])
        near(9f / 17f, t.machine.params["l1_sync"]) // still 1/4
        near(0f, t.machine.params["l2_sync"]) // still free
        near(0.5f, t.machine.params["f1_res"])
        near(8f / 16f, song.master.inserts[0].params["rate"]) // still 1/4
        val locks = t.clips.getValue("c1").automation
        near(2f / 16f, locks.getValue(laneKey("effect1", "lforate")).points[0].value) // still 1/16
        near(LANE_BASE, locks.getValue(laneKey("effect1", "lforate")).points[1].value)
        near(0f, locks.getValue(laneKey("effect1", "cutoff")).points[0].value) // not a rate
    }

    @Test
    fun aNewSongIsLeftAlone() {
        val song = oldSong().copy(version = RateMigration.SONG_VERSION)
        near(4f / 7f, SongStore.decode(SongStore.encode(song)).tracks[0].effects[0].params["lforate"])
    }

    @Test
    fun anOldPatchKeepsItsRates() {
        val trinity = RateMigration.patch(Patch("Trinity", "mine", mapOf("l1_sync" to 1f / 8f))) // 1/16
        near(3f / 17f, trinity.params["l1_sync"])
        val chorus = RateMigration.patch(Patch("fx.Chorus", "mine", mapOf("rate" to 7f / 7f))) // 8 bars
        near(1f, chorus.params["rate"])
        assertEquals(RateMigration.PATCH_VERSION, chorus.version)
        // Saved since, it's left as it is.
        near(0.5f, RateMigration.patch(Patch("fx.Chorus", "mine", mapOf("rate" to 0.5f), version = 2)).params["rate"])
    }
}
