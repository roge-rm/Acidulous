package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin swing map, against the numbers the C++ harness prints.
 *
 * The two copies have to agree exactly. The engine uses its copy to decide
 * where a note *sounds*; this one uses the inverse to decide where a note
 * somebody played should be *written*. If they ever drifted apart, a part
 * played in would be stored a little away from where it was heard, and the
 * error would be silent, small, and permanent.
 */
class SwingTest {

    @Test
    fun straightIsTheIdentity() {
        for (t in 0 until PPQN * 8) {
            assertEquals(t, Swing.at(t, SWING_STRAIGHT, Swing.SIXTEENTHS))
            assertEquals(t, Swing.from(t, SWING_STRAIGHT, Swing.SIXTEENTHS))
        }
    }

    @Test
    fun itAgreesWithTheEngine() {
        // The same values tools/swing_test.cpp asserts: a pair of sixteenths
        // is 120 ticks, and at triplet the offbeat sits at 80 of them.
        assertEquals(120, Swing.SIXTEENTHS)
        assertEquals(240, Swing.EIGHTHS)
        assertEquals(0, Swing.at(0, SWING_TRIPLET, Swing.SIXTEENTHS))
        assertEquals(120, Swing.at(120, SWING_TRIPLET, Swing.SIXTEENTHS))
        assertTrue(Math.abs(Swing.at(60, SWING_TRIPLET, Swing.SIXTEENTHS) - 80) <= 1)
        assertTrue(Swing.at(60, SWING_MAX, Swing.SIXTEENTHS) > Swing.at(60, SWING_TRIPLET, Swing.SIXTEENTHS))
    }

    @Test
    fun itNeverGoesBackwards() {
        for (pct in listOf(55f, 60f, SWING_TRIPLET, 70f, SWING_MAX)) {
            for (pair in listOf(Swing.SIXTEENTHS, Swing.EIGHTHS)) {
                var prev = Swing.at(0, pct, pair)
                for (t in 1 until PPQN * 8) {
                    val now = Swing.at(t, pct, pair)
                    assertTrue("fell at $t, $pct% over $pair", now >= prev)
                    prev = now
                }
            }
        }
    }

    @Test
    fun aPairIsMappedOntoItself() {
        for (pct in listOf(55f, SWING_TRIPLET, SWING_MAX)) {
            for (t in 0 until PPQN * 8) {
                val out = Swing.at(t, pct, Swing.SIXTEENTHS)
                val base = t - t % Swing.SIXTEENTHS
                assertTrue(out >= base && out < base + Swing.SIXTEENTHS)
            }
        }
    }

    @Test
    fun theInverseRoundTrips() {
        for (pct in listOf(55f, 60f, SWING_TRIPLET, 70f, SWING_MAX)) {
            for (t in 0 until PPQN * 4) {
                val round = Swing.from(Swing.at(t, pct, Swing.SIXTEENTHS), pct, Swing.SIXTEENTHS)
                // Not exact and cannot be: the compressed half of the pair has
                // fewer ticks to land on, so two straight ticks can share a
                // swung one. Three ticks at 240 PPQN is under a millisecond.
                assertTrue("off by ${Math.abs(round - t)} at $t, $pct%", Math.abs(round - t) <= 3)
            }
        }
    }

    @Test
    fun aTrackFollowsTheSongUnlessItSaysOtherwise() {
        val song = Fixtures.song().copy(swing = SWING_TRIPLET)
        val follower = song.tracks[0]
        assertEquals(SWING_TRIPLET, song.swingOf(follower))
        val straight = follower.copy(swing = SWING_STRAIGHT)
        assertEquals(SWING_STRAIGHT, song.swingOf(straight))
        // And a track that follows moves when the song does, which is the
        // whole reason it holds null rather than a copy of the number.
        assertEquals(60f, song.copy(swing = 60f).swingOf(follower))
    }

    @Test
    fun anOldSongOpensStraight() {
        // Nought was the dead field's default, and nought is not a swing.
        val old = """{"name":"Old","swing":0.0,"tracks":[],"scenes":[{"id":"s","name":"S"}]}"""
        assertEquals(SWING_STRAIGHT, SongStore.decode(old).swing)
    }
}
