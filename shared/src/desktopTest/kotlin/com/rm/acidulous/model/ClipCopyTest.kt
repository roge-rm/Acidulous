package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a copied clip carries, and the one thing it must not.
 *
 * A clip holds two references to files on disk and they are not alike. A
 * `TakeRef` is a window into a shared, immutable recording - one take across
 * four scenes is four clips and one file - so a second clip pointing at it is
 * what that design is for. A `Frozen` is a render named after the track and
 * scene it was made for, and `Freeze.discard` deletes that file: two clips
 * pointing at one render means thawing either one silences both, and pasted
 * onto another track it is a render of the wrong machine entirely.
 *
 * So the freeze is the one thing a copy drops, and that is worth a test rather
 * than a comment, because nothing about it is visible until somebody thaws a
 * clip and a different one goes quiet.
 */
class ClipCopyTest {

    private val take = TakeRef(file = "samples/take 1.wav", offset = 0, frames = 48000, bpm = 124f, ticks = 960)

    private fun full() = Clip(
        bars = 4,
        playMode = PlayMode.OneShot,
        mute = true,
        grid = PPQN / 3,
        notes = listOf(Note(tick = 0, pitch = 60, velocity = 100, length = 120)),
        automation = mapOf("channel:gain" to Lane(points = listOf(LanePoint(0, 0.2f), LanePoint(480, 0.9f)))),
        frozen = Frozen("t-bass__s-verse.wav", 124f, 960, 48000, 0.5f, 1234, tail = 48000),
        audio = ClipAudio(lanes = listOf(take, null, null, null)),
        seed = 7,
        freeRoll = true,
    )

    @Test
    fun theFreezeDoesNotTravel() {
        val source = full()
        val copy = source.asCopy()
        assertNull("a copy must not carry the freeze", copy.frozen)
        // And the source keeps its own, which is the half that would be found
        // late: a copy that stole the freeze would silence the clip it came from.
        assertEquals("the source kept its freeze", "t-bass__s-verse.wav", source.frozen?.file)
    }

    @Test
    fun theAudioDoesTravel() {
        val copy = full().asCopy()
        assertSame("a take is shared on purpose", take, copy.audio?.lanes?.get(0))
    }

    @Test
    fun everythingElseTravels() {
        val source = full()
        val copy = source.asCopy()
        assertEquals(source.bars, copy.bars)
        assertEquals(source.playMode, copy.playMode)
        assertEquals(source.mute, copy.mute)
        assertEquals(source.grid, copy.grid)
        assertEquals(source.seed, copy.seed)
        assertEquals(source.freeRoll, copy.freeRoll)
        assertEquals(source.notes, copy.notes)
        assertEquals(source.automation, copy.automation)
    }

    @Test
    fun aCopyHasItsOwnIdentity() {
        // The engine caches a marshalled clip by `rev` alone, so two cells
        // sharing one would share an engine clip. `rev` lives outside the
        // constructor for exactly this reason - every construction mints one -
        // and this is the test that says so out loud.
        val source = full()
        assertNotEquals("a copy is a different clip", source.rev, source.asCopy().rev)
        assertNotEquals("and so is the next one", source.asCopy().rev, source.asCopy().rev)
    }

    @Test
    fun theClipboardHandsOutAFreshOneEachTime() {
        ClipClipboard.put(full(), "Bass", "Verse")
        assertEquals("Bass · Verse", ClipClipboard.from)
        assertTrue(ClipClipboard.has)
        val first = ClipClipboard.take()
        val second = ClipClipboard.take()
        assertNotEquals("two pastes are two clips", first?.rev, second?.rev)
        assertNull("and neither carries the freeze", first?.frozen)
        assertEquals("but both are the same music", first?.notes, second?.notes)
    }

    @Test
    fun copyingAnEmptyClipIsHarmless() {
        // Holding an empty cell opens the same window, so this is reachable.
        val empty = Clip(bars = 2)
        val copy = empty.asCopy()
        assertTrue(copy.notes.isEmpty())
        assertEquals("its settings are worth copying on their own", 2, copy.bars)
    }
}
