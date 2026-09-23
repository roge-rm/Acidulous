package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocksTest {

    private val bar = 4 * PPQN
    private val step = PPQN / 4
    private fun span(i: Int) = (i * step) until ((i + 1) * step)

    @Test
    fun aLockIsItsValueForItsStepAndTheKnobEitherSide() {
        val lane = Locks.set(null, listOf(span(4)), 0.8f, bar)!!
        assertTrue(Locks.isLocks(lane))
        assertFalse(lane.linear)
        val knob = 0.3f
        val played = Locks.resolve(lane, knob)
        assertEquals(knob, played.valueAt(0))
        assertEquals(knob, played.valueAt(4 * step - 1))
        assertEquals(0.8f, played.valueAt(4 * step))
        assertEquals(0.8f, played.valueAt(5 * step - 1))
        assertEquals(knob, played.valueAt(5 * step))
        assertEquals(knob, played.valueAt(bar - 1))
    }

    @Test
    fun theKnobIsReadWhenSentSoTheStepsAroundFollowIt() {
        val lane = Locks.set(null, listOf(span(2)), 0.9f, bar)!!
        assertEquals(0.1f, Locks.resolve(lane, 0.1f).valueAt(0))
        assertEquals(0.6f, Locks.resolve(lane, 0.6f).valueAt(0))
        assertEquals(0.9f, Locks.resolve(lane, 0.6f).valueAt(2 * step))
    }

    @Test
    fun aLockOnTheFirstStepHoldsFromTheTop() {
        val played = Locks.resolve(Locks.set(null, listOf(span(0)), 0.7f, bar)!!, 0.2f)
        assertEquals(0.7f, played.valueAt(0))
        assertEquals(0.2f, played.valueAt(step))
    }

    @Test
    fun neighbouringLocksHandOverWithoutTheKnobBetween() {
        var lane = Locks.set(null, listOf(span(3)), 0.4f, bar)
        lane = Locks.set(lane, listOf(span(4)), 0.9f, bar)!!
        val played = Locks.resolve(lane, 0f)
        assertEquals(0.4f, played.valueAt(4 * step - 1))
        assertEquals(0.9f, played.valueAt(4 * step))
        assertEquals(0f, played.valueAt(5 * step))
        assertEquals(2, Locks.of(lane, bar).size)
    }

    @Test
    fun turningTheKnobAgainReplacesTheLockAndClearingTakesItAway() {
        var lane = Locks.set(null, listOf(span(1), span(5)), 0.5f, bar)
        lane = Locks.set(lane, listOf(span(5)), 0.25f, bar)
        assertEquals(listOf(0.5f, 0.25f), Locks.of(lane, bar).map { it.value })
        lane = Locks.clear(lane, listOf(span(1)), bar)
        assertEquals(listOf(5 * step), Locks.of(lane, bar).map { it.tick })
        assertNull(Locks.clear(lane, listOf(span(5)), bar))
    }

    @Test
    fun theLastStepRunsToTheEndOfTheClip() {
        val lane = Locks.set(null, listOf(span(15)), 0.6f, bar)!!
        assertEquals(listOf(Lock(15 * step, bar, 0.6f)), Locks.of(lane, bar))
        assertEquals(0.6f, Locks.resolve(lane, 0f).valueAt(bar - 1))
    }

    @Test
    fun aDrawnLaneIsNotLocksAndLocksAreNotDrawn() {
        val drawn = Lane(listOf(LanePoint(0, 0.2f), LanePoint(bar / 2, 0.8f)))
        assertTrue(Locks.isDrawn(drawn))
        assertFalse(Locks.isLocks(drawn))
        assertTrue(Locks.of(drawn, bar).isEmpty())
        assertFalse(Locks.isDrawn(Locks.set(null, listOf(span(1)), 0.5f, bar)))
        assertFalse(Locks.isDrawn(null))
    }

    @Test
    fun lockedStepsAreFoundForTheGrid() {
        val clip = Clip(bars = 1, automation = mapOf(
            laneKey("machine", "tune") to Locks.set(null, listOf(span(2), span(6)), 0.5f, bar)!!,
            laneKey("machine", "decay") to Lane(listOf(LanePoint(0, 0.3f))),
        ))
        assertEquals(setOf(2 * step, 6 * step), Locks.startTicks(clip, bar))
    }

    @Test
    fun locksSurviveBeingSavedAndOpened() {
        val clip = Clip(bars = 1, automation = mapOf(laneKey("machine", "tune") to Locks.set(null, listOf(span(3)), 0.5f, bar)!!))
        val song = Song(name = "t", tracks = listOf(Track(id = "t", name = "t", machine = Machine(type = "Hexbeat"), clips = mapOf("s" to clip))), scenes = listOf(Scene(id = "s", name = "s")))
        val back = SongStore.decode(SongStore.encode(song))
        assertEquals(clip.automation, back.tracks[0].clips["s"]!!.automation)
    }
}
