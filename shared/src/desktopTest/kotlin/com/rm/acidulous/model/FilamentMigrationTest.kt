package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.math.sqrt

class FilamentMigrationTest {
    private val k = sqrt(0.2f / 3f)
    private fun near(expected: Float, actual: Float?) = assertEquals(expected, actual ?: Float.NaN, 1e-6f)

    /** A depth as the knob reads it, -1..1, from its normalised value. */
    private fun depthOf(v: Float?) = (v ?: Float.NaN) * 2f - 1f

    /** A song as 0.12.1 saved it, version 4: one Filament with the bank on, one without a level. */
    private fun oldSong(): Song {
        val toBank = 12f / 17f
        val levelLane = Lane(listOf(LanePoint(0, 0.6f), LanePoint(96, LANE_BASE)), linear = false)
        val strings = Track(
            id = "t1", name = "strings",
            machine = Machine(
                "Filament",
                mapOf(
                    "sympathy" to 1f, "symlevel" to 0.7f,
                    "m1_dst" to toBank, "m1_amt" to 0.75f, // +0.5 into the bank
                    "m2_dst" to 2f / 17f, "m2_amt" to 0.75f, // +0.5 into sustain
                    "mac1_a1" to 0.9f,
                ),
            ),
            clips = mapOf("c1" to Clip(automation = mapOf(
                laneKey("machine", "symlevel") to levelLane,
                laneKey("machine", "m1_amt") to Lane(listOf(LanePoint(0, 1f))),
                laneKey("machine", "m2_amt") to Lane(listOf(LanePoint(0, 1f))),
            ))),
        )
        val bare = Track(id = "t2", name = "bare", machine = Machine("Filament", mapOf("tone" to 0.3f)))
        val other = Track(id = "t3", name = "other", machine = Machine("Trinity", mapOf("symlevel" to 0.7f)))
        return Song(version = 4, name = "old", tracks = listOf(strings, bare, other))
    }

    @Test
    fun anOldSongKeepsItsSympatheticLevel() {
        val song = SongStore.decode(SongStore.encode(oldSong()))
        assertEquals(RateMigration.SONG_VERSION, song.version)
        val p = song.tracks[0].machine.params
        near(0.7f * k, p["symlevel"])
        near(0.5f * k, depthOf(p["m1_amt"]))
        near(0.5f, depthOf(p["m2_amt"]))
        near(0.9f, p["mac1_a1"])
        val lanes = song.tracks[0].clips.getValue("c1").automation
        near(0.6f * k, lanes.getValue(laneKey("machine", "symlevel")).points[0].value)
        assertEquals(LANE_BASE, lanes.getValue(laneKey("machine", "symlevel")).points[1].value)
        near(k, depthOf(lanes.getValue(laneKey("machine", "m1_amt")).points[0].value))
        near(1f, lanes.getValue(laneKey("machine", "m2_amt")).points[0].value)
        // A Filament that never set its level gets the old default's.
        near(0.35f * k, song.tracks[1].machine.params["symlevel"])
        near(0.7f, song.tracks[2].machine.params["symlevel"])
    }

    @Test
    fun aCurrentSongIsLeftAlone() {
        val song = oldSong().copy(version = RateMigration.SONG_VERSION)
        assertEquals(song, RateMigration.song(song))
        assertFalse("symlevel" in RateMigration.song(song).tracks[1].machine.params)
    }

    @Test
    fun anOldPatchKeepsItsSympatheticLevel() {
        val old = Patch("Filament", "mine", mapOf("symlevel" to 0.4f, "m3_dst" to 12f / 17f, "m3_amt" to 0.25f), version = 4)
        val moved = RateMigration.patch(old)
        assertEquals(RateMigration.PATCH_VERSION, moved.version)
        near(0.4f * k, moved.params["symlevel"])
        near(-0.5f * k, depthOf(moved.params["m3_amt"]))
        val bare = RateMigration.patch(Patch("Filament", "bare", mapOf("tone" to 0.2f), version = 2))
        near(0.35f * k, bare.params["symlevel"])
        val current = Patch("Filament", "new", mapOf("symlevel" to 0.4f), version = RateMigration.PATCH_VERSION)
        assertEquals(current, RateMigration.patch(current))
    }
}
