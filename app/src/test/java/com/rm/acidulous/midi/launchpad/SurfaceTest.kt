package com.rm.acidulous.midi.launchpad

import com.rm.acidulous.midi.launchpad.LaunchpadPro.Button
import com.rm.acidulous.midi.launchpad.LaunchpadPro.Control
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Launchpad Pro [MK3] as the app lays it out, without one plugged in. */
class SurfaceTest {
    private val major = listOf(0, 2, 4, 5, 7, 9, 11)
    private val red = Rgb.of(127, 0, 0)
    private val cMajor = LpView(tracks = listOf(LpTrack(red)), played = 0, root = 0, intervals = major, scenes = 3)

    @Test
    fun `the surface is numbered as the reference numbers it`() {
        assertEquals(11, LaunchpadPro.ledOf(Control.Pad(0, 0)))
        assertEquals(88, LaunchpadPro.ledOf(Control.Pad(7, 7)))
        assertEquals(Control.Pad(2, 4), LaunchpadPro.padOf(35))
        assertNull(LaunchpadPro.padOf(19)) // the scene column, not a pad
        assertEquals(89, LaunchpadPro.ledOf(Control.Scene(0)))
        assertEquals(Control.Scene(7), LaunchpadPro.controlOfCc(19))
        assertEquals(Control.Track(0), LaunchpadPro.controlOfCc(101))
        assertEquals(Control.Key(Button.Note), LaunchpadPro.controlOfCc(94))
        assertEquals(Control.Key(Button.Record), LaunchpadPro.controlOfCc(10))
        assertEquals(Control.Key(Button.StopClip), LaunchpadPro.controlOfCc(8))
    }

    @Test
    fun `programmer mode on and off are the reference's bytes`() {
        val on = intArrayOf(0xF0, 0x00, 0x20, 0x29, 0x02, 0x0E, 0x0E, 0x01, 0xF7)
        assertArrayEquals(ByteArray(on.size) { on[it].toByte() }, LaunchpadPro.programmer(true))
        assertEquals(0x00, LaunchpadPro.programmer(false)[7].toInt())
    }

    @Test
    fun `lights go out as RGB, sixty-four to a message`() {
        val msgs = LaunchpadPro.lights((0 until 100).map { (11 + it) to Rgb.of(1, 2, 3) })
        assertEquals(2, msgs.size)
        val first = msgs[0]
        assertEquals(0x03, first[6].toInt()) // the lighting command
        assertEquals(0x03, first[7].toInt()) // RGB
        assertEquals(11, first[8].toInt())
        assertEquals(listOf(1, 2, 3), listOf(first[9], first[10], first[11]).map { it.toInt() })
        assertEquals(0xF7, first.last().toInt() and 0xff)
        assertEquals(7 + 64 * 5 + 1, first.size)
    }

    @Test
    fun `the note grid is in the key, each row a fourth up`() {
        val s = LpState()
        assertEquals(48, Surface.noteAt(cMajor, s, 0, 0)) // C3
        assertEquals(50, Surface.noteAt(cMajor, s, 0, 1)) // D
        assertEquals(53, Surface.noteAt(cMajor, s, 1, 0)) // F: three degrees up
        assertEquals(59, Surface.noteAt(cMajor, s, 0, 6)) // B
        assertEquals(60, Surface.noteAt(cMajor, s, 0, 7)) // C again
        // In A minor the bottom left is A.
        val aMinor = cMajor.copy(root = 9, intervals = listOf(0, 2, 3, 5, 7, 8, 10))
        assertEquals(57, Surface.noteAt(aMinor, s, 0, 0))
    }

    @Test
    fun `without a key it is chromatic, rows a fourth apart`() {
        val none = cMajor.copy(root = null, intervals = null)
        assertEquals(48, Surface.noteAt(none, LpState(), 0, 0))
        assertEquals(49, Surface.noteAt(none, LpState(), 0, 1))
        assertEquals(53, Surface.noteAt(none, LpState(), 1, 0))
    }

    @Test
    fun `up and down move the octave, the arrows walk the scale`() {
        var s = LpState()
        s = Surface.press(cMajor, s, Control.Key(Button.Up), 127).first
        assertEquals(60, Surface.noteAt(cMajor, s, 0, 0))
        s = Surface.press(cMajor, s, Control.Key(Button.Right), 127).first
        assertEquals(62, Surface.noteAt(cMajor, s, 0, 0))
    }

    @Test
    fun `a drum machine's voices are a drum rack`() {
        val drums = cMajor.copy(tracks = listOf(LpTrack(red, drums = (36..51).toList())))
        assertEquals(36, Surface.noteAt(drums, LpState(), 0, 0))
        assertEquals(39, Surface.noteAt(drums, LpState(), 0, 3))
        assertEquals(40, Surface.noteAt(drums, LpState(), 1, 0))
        assertNull(Surface.noteAt(drums, LpState(), 0, 4)) // only sixteen voices
        assertNull(Surface.noteAt(drums, LpState(), 4, 0))
    }

    @Test
    fun `a held pad lets go of the note it started, whatever the grid says now`() {
        var s = LpState()
        val (s1, on) = Surface.press(cMajor, s, Control.Pad(0, 0), 90)
        assertEquals(listOf(LpAction.NoteOn(48, 90)), on)
        s = Surface.press(cMajor, s1, Control.Key(Button.Up), 127).first
        val (s2, off) = Surface.release(s, Control.Pad(0, 0))
        assertEquals(listOf(LpAction.NoteOff(48)), off)
        assertTrue(s2.sounding.isEmpty())
        assertEquals(listOf(LpAction.Pressure(48, 64)), Surface.pressure(s1, Control.Pad(0, 0), 64))
    }

    @Test
    fun `shift turns play into panic, clear into undo, duplicate into redo`() {
        val shifted = Surface.press(cMajor, LpState(), Control.Key(Button.Shift), 127).first
        assertEquals(listOf(LpAction.Play), Surface.press(cMajor, LpState(), Control.Key(Button.Play), 127).second)
        assertEquals(listOf(LpAction.Panic), Surface.press(cMajor, shifted, Control.Key(Button.Play), 127).second)
        assertEquals(listOf(LpAction.Undo), Surface.press(cMajor, shifted, Control.Key(Button.Clear), 127).second)
        assertEquals(listOf(LpAction.Redo), Surface.press(cMajor, shifted, Control.Key(Button.Duplicate), 127).second)
        assertTrue(Surface.press(cMajor, LpState(), Control.Key(Button.Clear), 127).second.isEmpty())
        assertTrue(!Surface.release(shifted, Control.Key(Button.Shift)).first.shift)
    }

    @Test
    fun `track and scene buttons reach only what exists`() {
        assertEquals(listOf(LpAction.SelectTrack(0)), Surface.press(cMajor, LpState(), Control.Track(0), 127).second)
        assertTrue(Surface.press(cMajor, LpState(), Control.Track(1), 127).second.isEmpty())
        assertEquals(listOf(LpAction.PlayScene(2)), Surface.press(cMajor, LpState(), Control.Scene(2), 127).second)
        assertTrue(Surface.press(cMajor, LpState(), Control.Scene(3), 127).second.isEmpty())
    }

    @Test
    fun `the root is the track's colour, a held pad white, the note page lit`() {
        val leds = Surface.render(cMajor, LpState())
        assertEquals(red, leds[LaunchpadPro.ledOf(Control.Pad(0, 0))])
        assertTrue(leds[LaunchpadPro.ledOf(Control.Pad(0, 1))] != red)
        val held = Surface.press(cMajor, LpState(), Control.Pad(0, 1), 100).first
        assertEquals(Rgb.WHITE, Surface.render(cMajor, held)[LaunchpadPro.ledOf(Control.Pad(0, 1))])
        assertEquals(Rgb.WHITE, leds[Button.Note.cc])
        assertEquals(Rgb.OFF, leds[Button.Chord.cc]) // not built yet
    }
}
