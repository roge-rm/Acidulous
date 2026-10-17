package com.rm.acidulous.model

import com.rm.acidulous.ui.TRINITY_WAVES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor

class TableMigrationTest {
    private fun near(expected: Float, actual: Float?) = assertEquals(expected, actual ?: Float.NaN, 1e-6f)

    /** The wave a Trinity stepped value picks, as ParamDef::map does. */
    private fun trinityStep(v: Float, steps: Int) = floor(v * (steps - 1) + 0.5f).toInt()

    /** The table a Nexus knob picks, as stepOf does. */
    private fun nexusStep(v: Float, tables: Int) = (v.coerceIn(0f, 0.9999f) * tables).toInt()

    private val nexusText = "v|1\nm|00|voice|poly\nm|01|wtosc|poly\nm|02|osc|poly"

    /** A song as 0.12.0 saved it: 12 Trinity waves, 8 Nexus tables, version 2. */
    private fun oldSong(): Song {
        val waveLock = Lane(listOf(LanePoint(0, 9f / 11f), LanePoint(240, LANE_BASE)), linear = false)
        val trinity = Track(
            id = "t1", name = "lead",
            machine = Machine("Trinity", mapOf("o1_wave" to 7f / 11f, "o2_wave" to 1f, "o3_wave" to 0f, "o1_pos" to 0.4f)),
            clips = mapOf("c1" to Clip(automation = mapOf(laneKey("machine", "o2_wave") to waveLock, laneKey("machine", "o1_pos") to waveLock))),
        )
        val nexus = Track(
            id = "t2", name = "mod",
            machine = Machine("Nexus", mapOf("s01_p1" to 0.8f, "s02_p1" to 0.8f, "s01_p2" to 0.8f), mapOf("nexus" to nexusText)),
            clips = mapOf("c1" to Clip(automation = mapOf(laneKey("machine", "s01_p1") to Lane(listOf(LanePoint(0, 0.3f)))))),
        )
        return Song(version = 2, name = "old", tracks = listOf(trinity, nexus))
    }

    @Test
    fun everyOldTrinityWaveKeepsItsWave() {
        for (step in 0 until 12) {
            val moved = TableMigration.trinityWave(step / 11f)
            assertEquals(step, trinityStep(moved, TableMigration.TRINITY_WAVES))
        }
    }

    @Test
    fun everyOldNexusTableKeepsItsTable() {
        for (k in 0..1000) {
            val v = k / 1000f
            assertEquals(nexusStep(v, 8), nexusStep(TableMigration.nexusTable(v), TableMigration.WAVETABLES))
        }
    }

    @Test
    fun anOldSongKeepsItsWavesAndTables() {
        val song = SongStore.decode(SongStore.encode(oldSong()))
        assertEquals(RateMigration.SONG_VERSION, song.version)
        val t = song.tracks[0].machine.params
        assertEquals(7, trinityStep(t.getValue("o1_wave"), 36))
        assertEquals(11, trinityStep(t.getValue("o2_wave"), 36))
        assertEquals(0, trinityStep(t.getValue("o3_wave"), 36))
        near(0.4f, t["o1_pos"])
        val lanes = song.tracks[0].clips.getValue("c1").automation
        assertEquals(9, trinityStep(lanes.getValue(laneKey("machine", "o2_wave")).points[0].value, 36))
        near(LANE_BASE, lanes.getValue(laneKey("machine", "o2_wave")).points[1].value)
        near(9f / 11f, lanes.getValue(laneKey("machine", "o1_pos")).points[0].value) // not a wave

        val n = song.tracks[1].machine
        assertEquals(6, nexusStep(n.params.getValue("s01_p1"), 32)) // the wtosc's table
        near(0.8f, n.params["s02_p1"]) // an osc's first knob
        near(0.8f, n.params["s01_p2"]) // the wtosc's frame
        assertEquals(2, nexusStep(song.tracks[1].clips.getValue("c1").automation.getValue(laneKey("machine", "s01_p1")).points[0].value, 32))
        assertTrue(n.settings.getValue("nexus").startsWith("v|2\n"))
    }

    @Test
    fun aFreezeThatMatchedStillMatches() {
        val old = oldSong()
        val t = old.tracks[0]
        val clip = t.clips.getValue("c1")
        val fresh = Frozen("a.wav", 120f, 1920, 96000, voice = Freeze.voiceOf(t), tail = 64, content = Freeze.contentOf(old, t, clip))
        val stale = Frozen("b.wav", 120f, 1920, 96000, voice = 12345, tail = 64, content = 678)
        val song = old.copy(tracks = listOf(
            t.copy(clips = mapOf("c1" to clip.copy(frozen = fresh))),
            old.tracks[1].copy(clips = old.tracks[1].clips.mapValues { (_, c) -> c.copy(frozen = stale) }),
        ))
        val moved = RateMigration.song(song)
        val mt = moved.tracks[0]
        val mc = mt.clips.getValue("c1")
        assertTrue(Freeze.voiceOf(mt) != Freeze.voiceOf(t)) // the waves did move
        assertEquals(Freeze.voiceOf(mt), mc.frozen!!.voice)
        assertEquals(Freeze.contentOf(moved, mt, mc), mc.frozen!!.content)
        assertTrue(!Freeze.outdated(moved, mt, mc, null))
        // One that was already out of date stays so.
        val nf = moved.tracks[1].clips.getValue("c1").frozen!!
        assertEquals(12345, nf.voice)
        assertEquals(678, nf.content)
    }

    @Test
    fun aNewSongIsLeftAlone() {
        val song = oldSong().copy(version = RateMigration.SONG_VERSION)
        near(7f / 11f, SongStore.decode(SongStore.encode(song)).tracks[0].machine.params["o1_wave"])
    }

    @Test
    fun oldPatchesKeepTheirWavesAndTables() {
        val trinity = RateMigration.patch(Patch("Trinity", "mine", mapOf("o1_wave" to 4f / 11f), version = 2))
        assertEquals(RateMigration.PATCH_VERSION, trinity.version)
        assertEquals(4, trinityStep(trinity.params.getValue("o1_wave"), 36))
        // From before 0.9.12: both migrations run.
        val older = RateMigration.patch(Patch("Trinity", "mine", mapOf("o2_wave" to 5f / 11f, "l1_sync" to 1f / 8f)))
        assertEquals(5, trinityStep(older.params.getValue("o2_wave"), 36))
        near(3f / 17f, older.params["l1_sync"]) // 1/16 on the seventeen
        val nexus = RateMigration.patch(Patch("Nexus", "mine", mapOf("s01_p1" to 0.99f), mapOf("nexus" to nexusText), version = 2))
        assertEquals(7, nexusStep(nexus.params.getValue("s01_p1"), 32))
        assertTrue(nexus.settings.getValue("nexus").startsWith("v|2\n"))
        val current = RateMigration.patch(Patch("Trinity", "mine", mapOf("o1_wave" to 4f / 11f), version = RateMigration.PATCH_VERSION))
        near(4f / 11f, current.params["o1_wave"])
    }

    @Test
    fun thePanelNamesEveryWave() {
        assertEquals(TableMigration.TRINITY_WAVES, TRINITY_WAVES.size)
    }

    @Test
    fun nexusWritesTheNewVersion() {
        assertTrue(NexusPatch.decode(nexusText).encode().startsWith("v|2\n"))
    }
}
