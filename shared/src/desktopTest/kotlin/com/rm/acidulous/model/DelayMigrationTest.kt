package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DelayMigrationTest {
    private fun near(expected: Float, actual: Float?) = assertEquals(expected, actual ?: Float.NaN, 1e-6f)

    /** A song as 0.13 saved it, version 5: Delays with the pingpong switch. */
    private fun oldSong(): Song {
        val lane = Lane(listOf(LanePoint(0, 1f), LanePoint(48, 0f), LanePoint(96, LANE_BASE)), linear = false)
        val track = Track(
            id = "t1", name = "t",
            machine = Machine("Hexbeat"),
            effects = listOf(UnitSlot("Delay", mapOf("pingpong" to 1f, "feedback" to 0.5f)), UnitSlot("Delay", mapOf("pingpong" to 0f))),
            clips = mapOf("c1" to Clip(automation = mapOf("effect1:pingpong" to lane, "machine:cutoff" to lane))),
        )
        return Song(
            version = 5, name = "old", tracks = listOf(track),
            master = Master(sends = listOf(UnitSlot("Delay", mapOf("pingpong" to 1f)))),
            mappings = listOf(Mapping(cc = 1, unit = "effect1", name = "pingpong")),
        )
    }

    @Test
    fun `an old song's delay switch becomes a mode`() {
        val song = RateMigration.song(oldSong())
        assertEquals(RateMigration.SONG_VERSION, song.version)
        val fx = song.tracks[0].effects
        near(1f / 3f, fx[0].params["mode"])
        near(0.5f, fx[0].params["feedback"])
        assertFalse("pingpong" in fx[0].params)
        near(0f, fx[1].params["mode"])
        near(1f / 3f, song.master.sends[0].params["mode"])
        assertEquals("mode", song.mappings[0].name)
    }

    @Test
    fun `a lane on the switch moves to mode and its values with it`() {
        val auto = RateMigration.song(oldSong()).tracks[0].clips["c1"]!!.automation
        assertNull(auto["effect1:pingpong"])
        val pts = auto["effect1:mode"]!!.points
        near(1f / 3f, pts[0].value)
        near(0f, pts[1].value)
        assertEquals(LANE_BASE, pts[2].value, 0f)
        // Other lanes are left alone.
        near(1f, auto["machine:cutoff"]!!.points[0].value)
    }

    @Test
    fun `a current song and a delay patch`() {
        val current = oldSong().copy(version = RateMigration.SONG_VERSION)
        assertEquals(current, RateMigration.song(current))
        val p = RateMigration.patch(Patch("fx.Delay", "mine", mapOf("pingpong" to 1f, "feedback" to 0.4f), version = 5))
        assertEquals(RateMigration.PATCH_VERSION, p.version)
        near(1f / 3f, p.params["mode"])
        near(0.4f, p.params["feedback"])
        assertFalse("pingpong" in p.params)
        val now = RateMigration.patch(Patch("fx.Delay", "mine", mapOf("mode" to 1f), version = RateMigration.PATCH_VERSION))
        near(1f, now.params["mode"])
    }
}
