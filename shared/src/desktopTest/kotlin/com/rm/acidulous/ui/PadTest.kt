package com.rm.acidulous.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PadTest {
    private fun stick(x: Float = 0f, y: Float = 0f, rx: Float = 0f, ry: Float = 0f) = Pad.moved(
        "test", listOf(Pad.Axis("X", x, 0f), Pad.Axis("Y", y, 0f), Pad.Axis("Z", rx, 0f), Pad.Axis("RZ", ry, 0f)),
    )

    @After
    fun centre() {
        stick()
        Pad.tick(0f)
        Pad.turnable = null
    }

    @Test
    fun aStickAtRestIsAtRest() {
        // What the Retroid Pocket's sticks read at rest and when pushed across.
        assertEquals(0f, Pad.deadZoned(-0.04f))
        assertEquals(0f, Pad.deadZoned(0.08f))
        assertEquals(1f, Pad.deadZoned(1f))
        assertEquals(-1f, Pad.deadZoned(-1f))
        stick(y = -0.04f, x = 0.08f)
        assertTrue(!Pad.active)
        stick(y = -1f)
        assertTrue(Pad.active)
    }

    @Test
    fun theLeftStickTurnsAKnobInOneGesture() {
        var value = 0.5f
        var begins = 0
        var ends = 0
        Pad.turnable = Pad.Turnable({ value }, { 0 }, { begins++ }, { value = it }, { ends++ })
        stick(y = -1f) // fully up
        repeat(30) { Pad.tick(0.01f) } // 0.3 s
        stick()
        Pad.tick(0.01f)
        assertEquals(1, begins)
        assertEquals(1, ends)
        // A full push sweeps the whole range in 1.5 s, so 0.3 s is a fifth.
        assertEquals(0.7f, value, 0.01f)
        // Down turns it back.
        stick(y = 1f)
        repeat(30) { Pad.tick(0.01f) }
        assertEquals(0.5f, value, 0.01f)
    }

    @Test
    fun aHalfPushIsSlowerThanHalfSpeed() {
        var value = 0f
        Pad.turnable = Pad.Turnable({ value }, { 0 }, {}, { value = it }, {})
        stick(x = Pad.DEAD_ZONE + (1f - Pad.DEAD_ZONE) * 0.5f)
        repeat(100) { Pad.tick(0.01f) } // 1 s
        // Squared: half a push is a quarter of the speed.
        assertEquals(0.25f / 1.5f, value, 0.01f)
    }

    @Test
    fun aSteppedKnobMovesAStepAtATime() {
        var value = 0f
        val seen = mutableListOf<Float>()
        Pad.turnable = Pad.Turnable({ value }, { 3 }, {}, { value = it; seen += it }, {})
        stick(y = -1f)
        Pad.tick(0.01f)
        assertEquals("a step as soon as it's pushed", listOf(0.25f), seen)
        repeat(19) { Pad.tick(0.01f) } // to 0.2 s, at 8 steps a second
        assertEquals(listOf(0.25f, 0.5f), seen)
    }

    @Test
    fun theRightStickRepeatsArrowsFasterTheFurtherItsPushed() {
        val sent = mutableListOf<Int>()
        Pad.sendKey = { sent += it }
        stick(rx = 1f)
        repeat(100) { Pad.tick(0.01f) } // 1 s fully right
        val fast = sent.size
        assertTrue(sent.all { it == KeyCodes.KEYCODE_DPAD_RIGHT })
        sent.clear()
        stick()
        Pad.tick(0.01f)
        stick(ry = -(Pad.DEAD_ZONE + 0.1f))
        repeat(100) { Pad.tick(0.01f) } // 1 s barely up
        assertTrue(sent.all { it == KeyCodes.KEYCODE_DPAD_UP })
        assertTrue("fully pushed $fast, barely ${sent.size}", fast > 3 * sent.size && sent.size >= 2)
    }

    @Test
    fun theButtonsPlayAScaleUpFromItsRoot() {
        val major = setOf(0, 2, 4, 5, 7, 9, 11)
        // C major from middle C: C D E F G A B C.
        assertEquals(listOf(60, 62, 64, 65, 67, 69, 71, 72), (0..7).map { degreeNote(it, 4, 0, major) })
        // A minor pentatonic, five notes, so the sixth button is the next A.
        val pentatonic = setOf(9, 0, 2, 4, 7)
        assertEquals(listOf(69, 72, 74, 76, 79, 81), (0..5).map { degreeNote(it, 4, 9, pentatonic) })
    }

    @Test
    fun aButtonCanBeGivenAnotherJob() {
        for (job in Pad.CHOICES) assertEquals(job, Pad.decode(Pad.encode(job)))
        val y = KeyCodes.KEYCODE_BUTTON_Y
        assertEquals(Pad.Job.Action(KeyAction.PlayStop), Pad.jobOf(y))
        UiPrefs.choosePadJob(y, Pad.Job.Action(KeyAction.Record))
        assertEquals(Pad.Job.Action(KeyAction.Record), Pad.jobOf(y))
        // Its own job again is no change at all.
        UiPrefs.choosePadJob(y, Pad.Job.Action(KeyAction.PlayStop))
        assertTrue(y !in UiPrefs.padJobs)
        // A button with no job of its own can be given one, and reset takes it away.
        val l3 = KeyCodes.KEYCODE_BUTTON_THUMBL
        UiPrefs.choosePadJob(l3, Pad.Job.Action(KeyAction.Undo))
        assertEquals(Pad.Job.Action(KeyAction.Undo), Pad.jobOf(l3))
        UiPrefs.resetKeys()
        assertEquals(Pad.Job.Nothing, Pad.jobOf(l3))
    }
}
