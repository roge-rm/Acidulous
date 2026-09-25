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
    fun `the note grid is a piano, two rows to an octave, from the octave's C`() {
        val s = LpState()
        assertEquals(48, Surface.noteAt(cMajor, s, 0, 0)) // C3
        assertEquals(50, Surface.noteAt(cMajor, s, 0, 1)) // D
        assertEquals(59, Surface.noteAt(cMajor, s, 0, 6)) // B
        assertEquals(60, Surface.noteAt(cMajor, s, 0, 7)) // the C above
        assertEquals(49, Surface.noteAt(cMajor, s, 1, 1)) // C sharp, over D
        assertEquals(54, Surface.noteAt(cMajor, s, 1, 4)) // F sharp, over G
        assertNull(Surface.noteAt(cMajor, s, 1, 0)) // the gaps where a piano has no black key
        assertNull(Surface.noteAt(cMajor, s, 1, 3))
        assertNull(Surface.noteAt(cMajor, s, 1, 7))
        assertEquals(60, Surface.noteAt(cMajor, s, 2, 0)) // the next octave up
        assertEquals(94, Surface.noteAt(cMajor, s, 7, 6)) // A sharp, four octaves in
    }

    @Test
    fun `every note is there, and the scale is what is lit`() {
        val leds = Surface.render(cMajor, LpState())
        assertEquals(red, leds[LaunchpadPro.ledOf(Control.Pad(0, 0))]) // C, the root
        assertEquals(Rgb.scale(red, 0.3f), leds[LaunchpadPro.ledOf(Control.Pad(0, 1))]) // D, in the scale
        assertEquals(Rgb.scale(Rgb.WHITE, 0.03f), leds[LaunchpadPro.ledOf(Control.Pad(1, 1))]) // C sharp, not
        assertEquals(Rgb.OFF, leds[LaunchpadPro.ledOf(Control.Pad(1, 0))])
        // In A minor, A is the root and C sharp is still out.
        val aMinor = cMajor.copy(root = 9, intervals = listOf(0, 2, 3, 5, 7, 8, 10))
        val am = Surface.render(aMinor, LpState())
        assertEquals(red, am[LaunchpadPro.ledOf(Control.Pad(0, 5))])
        assertEquals(Rgb.scale(red, 0.3f), am[LaunchpadPro.ledOf(Control.Pad(0, 0))])
    }

    @Test
    fun `with the track's own scale, only its notes, an octave a row`() {
        val locked = cMajor.copy(scaleLocked = true)
        val s = LpState()
        assertEquals(36, Surface.noteAt(locked, s, 0, 0)) // C, an octave under the piano's
        assertEquals(38, Surface.noteAt(locked, s, 0, 1)) // D: no C sharp between
        assertEquals(47, Surface.noteAt(locked, s, 0, 6)) // B
        assertEquals(48, Surface.noteAt(locked, s, 0, 7)) // the C above ends the row
        assertEquals(48, Surface.noteAt(locked, s, 1, 0)) // and starts the next
        assertEquals(120, Surface.noteAt(locked, s, 7, 0)) // eight octaves up the grid
        // A pentatonic row: five notes and the root above, then nothing.
        val pent = locked.copy(root = 9, intervals = listOf(0, 3, 5, 7, 10))
        assertEquals(45, Surface.noteAt(pent, s, 0, 0))
        assertEquals(57, Surface.noteAt(pent, s, 0, 5))
        assertNull(Surface.noteAt(pent, s, 0, 6))
        val leds = Surface.render(locked, s)
        assertEquals(red, leds[LaunchpadPro.ledOf(Control.Pad(0, 0))])
        assertEquals(Rgb.scale(red, 0.3f), leds[LaunchpadPro.ledOf(Control.Pad(0, 1))])
    }

    @Test
    fun `up and down move the octave`() {
        val s = Surface.press(cMajor, LpState(), Control.Key(Button.Up), 127).first
        assertEquals(60, Surface.noteAt(cMajor, s, 0, 0))
    }

    @Test
    fun `a drum machine's pads are laid out as the app lays them`() {
        // Thirteen: the smaller half, six, along the bottom from pad one, and seven above.
        val drums = cMajor.copy(tracks = listOf(LpTrack(red, drums = (36..48).toList())))
        assertEquals(36, Surface.noteAt(drums, LpState(), 0, 0))
        assertEquals(41, Surface.noteAt(drums, LpState(), 0, 5))
        assertNull(Surface.noteAt(drums, LpState(), 0, 6))
        assertEquals(42, Surface.noteAt(drums, LpState(), 1, 0))
        assertEquals(48, Surface.noteAt(drums, LpState(), 1, 6))
        assertNull(Surface.noteAt(drums, LpState(), 2, 0))
        // In the app's pad order where it has one.
        val ordered = cMajor.copy(tracks = listOf(LpTrack(red, drums = (36..39).toList(), pads = listOf(36, 38, 37, 39))))
        assertEquals(38, Surface.noteAt(ordered, LpState(), 0, 1))
        assertEquals(37, Surface.noteAt(ordered, LpState(), 1, 0))
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
        assertEquals(listOf(LpAction.SelectTrack(0)), Surface.press(cMajor, LpState(), Control.Scene(0), 127).second)
        assertTrue(Surface.press(cMajor, LpState(), Control.Scene(1), 127).second.isEmpty())
        assertEquals(listOf(LpAction.PlayScene(2)), Surface.press(cMajor, LpState(), Control.Track(2), 127).second)
        assertTrue(Surface.press(cMajor, LpState(), Control.Track(3), 127).second.isEmpty())
    }

    @Test
    fun `the root is the track's colour, a held pad white, the note page lit`() {
        val leds = Surface.render(cMajor, LpState())
        assertEquals(red, leds[LaunchpadPro.ledOf(Control.Pad(0, 0))])
        assertTrue(leds[LaunchpadPro.ledOf(Control.Pad(0, 1))] != red)
        val held = Surface.press(cMajor, LpState(), Control.Pad(0, 1), 100).first
        assertEquals(Rgb.WHITE, Surface.render(cMajor, held)[LaunchpadPro.ledOf(Control.Pad(0, 1))])
        assertEquals(Rgb.WHITE, leds[Button.Note.cc])
        assertEquals(Rgb.DIM, leds[Button.Chord.cc]) // another page, not this one
    }

    // --- The session page -------------------------------------------------------

    private val blue = Rgb.of(0, 0, 127)
    private val session = LpView(
        tracks = listOf(
            LpTrack(red, clips = setOf(0, 1), playingScene = 1),
            LpTrack(blue, clips = setOf(0), queuedScene = 0),
        ),
        played = 0, scenes = 12, clipMode = true,
    )
    private val onSession = LpState(page = LpPage.Session)
    private fun hold(b: Button, s: LpState = onSession) = Surface.press(session, s, Control.Key(b), 127).first

    @Test
    fun `the session grid is the app's grid - tracks down, scenes across`() {
        assertEquals(0 to 0, Surface.sessionCell(onSession, 7, 0))
        assertEquals(1 to 7, Surface.sessionCell(onSession, 6, 7))
        assertEquals(1 to 3, Surface.sessionCell(onSession.copy(trackOffset = 1, sceneOffset = 3), 7, 0))
    }

    @Test
    fun `in clip mode a pad launches its clip, in song mode plays its scene`() {
        assertEquals(
            listOf(LpAction.SelectTrack(0), LpAction.LaunchClip(0, 1)),
            Surface.press(session, onSession, Control.Pad(7, 1), 100).second,
        )
        // An empty cell only chooses the track.
        assertEquals(listOf(LpAction.SelectTrack(1)), Surface.press(session, onSession, Control.Pad(6, 1), 100).second)
        val song = session.copy(clipMode = false)
        assertEquals(
            listOf(LpAction.SelectTrack(1), LpAction.PlayScene(0)),
            Surface.press(song, onSession, Control.Pad(6, 0), 100).second,
        )
    }

    @Test
    fun `clear, duplicate, mute and solo are held for the next press`() {
        assertEquals(listOf(LpAction.ClearClip(0, 0)), Surface.press(session, hold(Button.Clear), Control.Pad(7, 0), 100).second)
        // On into an empty scene, and not over a clip that is there.
        assertEquals(listOf(LpAction.CopyClipDown(1, 0)), Surface.press(session, hold(Button.Duplicate), Control.Pad(6, 0), 100).second)
        assertTrue(Surface.press(session, hold(Button.Duplicate), Control.Pad(7, 0), 100).second.isEmpty())
        assertEquals(listOf(LpAction.StopClips), Surface.press(session, onSession, Control.Key(Button.StopClip), 127).second)
    }

    @Test
    fun `the row under the grid is the scenes and the column beside it the tracks`() {
        assertEquals(listOf(LpAction.PlayScene(3)), Surface.press(session, onSession, Control.Track(3), 127).second)
        assertEquals(listOf(LpAction.DuplicateScene(3)), Surface.press(session, hold(Button.Duplicate), Control.Track(3), 127).second)
        assertEquals(listOf(LpAction.SelectTrack(1)), Surface.press(session, onSession, Control.Scene(1), 127).second)
        assertEquals(listOf(LpAction.ToggleMute(1)), Surface.press(session, hold(Button.Mute), Control.Scene(1), 127).second)
        assertEquals(listOf(LpAction.ToggleSolo(0)), Surface.press(session, hold(Button.Solo), Control.Scene(0), 127).second)
        val released = Surface.release(hold(Button.Mute), Control.Key(Button.Mute)).first
        assertEquals(listOf(LpAction.SelectTrack(1)), Surface.press(session, released, Control.Scene(1), 127).second)
        // On every page, not only this one.
        val onNote = LpState()
        assertEquals(listOf(LpAction.SelectTrack(1)), Surface.press(session, onNote, Control.Scene(1), 127).second)
        assertEquals(listOf(LpAction.PlayScene(3)), Surface.press(session, onNote, Control.Track(3), 127).second)
    }

    @Test
    fun `on the session page the arrows move the view a row or a column at a time`() {
        var st = Surface.press(session, onSession, Control.Key(Button.Right), 127).first
        assertEquals(1, st.sceneOffset)
        repeat(10) { st = Surface.press(session, st, Control.Key(Button.Right), 127).first }
        assertEquals(4, st.sceneOffset) // twelve scenes: the last eight in view, and no further
        assertEquals(0, Surface.press(session, onSession, Control.Key(Button.Down), 127).first.trackOffset) // two tracks: nowhere to go
        assertEquals(3, st.octave) // and the note page's octave is untouched
        val many = session.copy(tracks = List(10) { LpTrack(red) })
        assertEquals(1, Surface.press(many, onSession, Control.Key(Button.Down), 127).first.trackOffset)
        // An arrow is lit when there is more that way.
        val leds = Surface.render(session, onSession)
        assertEquals(Rgb.OFF, leds[Button.Left.cc])
        assertEquals(Rgb.WHITE, leds[Button.Right.cc])
        assertEquals(Rgb.OFF, leds[Button.Down.cc])
    }

    @Test
    fun `playing clips pulse, queued ones flash, empty cells are dark`() {
        val onBeat = Surface.render(session.copy(beat = 0f), onSession)
        assertEquals(red, onBeat[LaunchpadPro.ledOf(Control.Pad(7, 1))])
        assertEquals(blue, onBeat[LaunchpadPro.ledOf(Control.Pad(6, 0))])
        val offBeat = Surface.render(session.copy(beat = 0.75f), onSession)
        assertTrue(offBeat[LaunchpadPro.ledOf(Control.Pad(6, 0))] != blue)
        assertEquals(Rgb.OFF, onBeat[LaunchpadPro.ledOf(Control.Pad(6, 1))])
        assertEquals(Rgb.WHITE, onBeat[Button.Session.cc])
    }

    // --- The sequencer page ---------------------------------------------------

    /** One bar of sixteenths in scene 2, with a C3 on the first step and the song on the third. */
    private val seqView = cMajor.copy(seq = LpSeq(scene = 2, grid = 60, length = 960, notes = listOf(0 to 48), playhead = 130))
    private val onSeq = LpState(page = LpPage.Sequencer)

    @Test
    fun `sequencer rows are the scale up from the octave, or the drum voices`() {
        assertEquals(48, Surface.seqPitch(seqView, onSeq, 0))
        assertEquals(50, Surface.seqPitch(seqView, onSeq, 1))
        assertEquals(60, Surface.seqPitch(seqView, onSeq, 7))
        // A drum machine reads down from the kick, as its grid on screen does.
        val drums = seqView.copy(tracks = listOf(LpTrack(red, drums = (36..47).toList())))
        assertEquals(36, Surface.seqPitch(drums, onSeq, 7))
        assertEquals(43, Surface.seqPitch(drums, onSeq, 0))
        assertEquals(44, Surface.seqPitch(drums, onSeq.copy(seqRow = 3), 2))
        assertEquals(0, Surface.press(drums, onSeq, Control.Key(Button.Up), 127).first.seqRow) // at the kick already
        assertEquals(1, Surface.press(drums, onSeq, Control.Key(Button.Down), 127).first.seqRow)
    }

    @Test
    fun `sequencer columns are steps, a step at a time, and stop at the clip's end`() {
        assertEquals(0, Surface.seqTick(seqView, onSeq, 0))
        assertEquals(420, Surface.seqTick(seqView, onSeq, 7))
        assertEquals(60, Surface.seqTick(seqView, onSeq.copy(stepOffset = 1), 0))
        assertNull(Surface.seqTick(seqView, onSeq.copy(stepOffset = 9), 7))
        var st = Surface.press(seqView, onSeq, Control.Key(Button.Right), 127).first
        assertEquals(1, st.stepOffset)
        repeat(20) { st = Surface.press(seqView, st, Control.Key(Button.Right), 127).first }
        assertEquals(8, st.stepOffset) // sixteen steps: the last eight in view, and no further
    }

    @Test
    fun `a sequencer pad toggles a step in the played track's clip`() {
        assertEquals(
            listOf(LpAction.ToggleStep(track = 0, scene = 2, tick = 120, pitch = 52, length = 60)),
            Surface.press(seqView, onSeq, Control.Pad(2, 2), 127).second,
        )
        assertEquals(listOf(LpAction.QuantiseClip(0, 2)), Surface.press(seqView, onSeq, Control.Key(Button.Quantise), 127).second)
    }

    @Test
    fun `steps with notes are lit, and the playhead's column`() {
        val leds = Surface.render(seqView, onSeq)
        assertEquals(red, leds[LaunchpadPro.ledOf(Control.Pad(0, 0))])
        val head = leds[LaunchpadPro.ledOf(Control.Pad(1, 2))]
        val elsewhere = leds[LaunchpadPro.ledOf(Control.Pad(1, 3))]
        assertTrue(head != elsewhere)
        assertEquals(Rgb.WHITE, leds[Button.Sequencer.cc])
    }

    // --- Mixer, perform, chords ---------------------------------------------------

    @Test
    fun `the fader buttons choose the mixer and what its faders are`() {
        var st = Surface.press(session, LpState(), Control.Key(Button.Pan), 127).first
        assertEquals(LpPage.Mixer, st.page)
        assertEquals(LpFader.Pan, st.fader)
        st = Surface.press(session, st, Control.Key(Button.Sends), 127).first
        assertEquals(LpFader.SendA, st.fader)
        st = Surface.press(session, st, Control.Key(Button.Sends), 127).first
        assertEquals(LpFader.SendB, st.fader)
        assertEquals(LpFader.Device, Surface.press(session, st, Control.Key(Button.Device), 127).first.fader)
    }

    @Test
    fun `the mixer is a row for each track, top first, its value across`() {
        val mixer = LpState(page = LpPage.Mixer, fader = LpFader.Level)
        assertEquals(listOf(LpAction.SetMix(0, LpFader.Level, 1f)), Surface.press(session, mixer, Control.Pad(7, 7), 100).second)
        assertEquals(listOf(LpAction.SetMix(1, LpFader.Level, 0f)), Surface.press(session, mixer, Control.Pad(6, 0), 100).second)
        assertTrue(Surface.press(session, mixer, Control.Pad(3, 5), 100).second.isEmpty()) // no fifth track
        val device = session.copy(device = listOf(0.2f, 0.9f))
        assertEquals(
            listOf(LpAction.SetDevice(1, Surface.faderValue(3))),
            Surface.press(device, mixer.copy(fader = LpFader.Device), Control.Pad(6, 3), 100).second,
        )
        // Full on the top track fills its row from the left.
        val loud = session.copy(tracks = listOf(LpTrack(red, level = 1f), LpTrack(blue)))
        val leds = Surface.render(loud, mixer)
        assertEquals(red, leds[LaunchpadPro.ledOf(Control.Pad(7, 7))])
        assertEquals(Rgb.scale(red, 0.3f), leds[LaunchpadPro.ledOf(Control.Pad(7, 0))])
        // Up and down move through the tracks here too.
        val many = session.copy(tracks = List(10) { LpTrack(red) })
        assertEquals(1, Surface.press(many, mixer, Control.Key(Button.Down), 127).first.trackOffset)
    }

    @Test
    fun `perform pads are held, on when pressed and off when let go`() {
        val perform = LpState(page = LpPage.Perform)
        val (a, on) = Surface.press(session, perform, Control.Pad(7, 2), 100)
        assertEquals(listOf(LpAction.PerformParam("repeat", 3 / 5f)), on)
        // A second repeat held takes over; letting it go hands back to the first.
        val (b, _) = Surface.press(session, a, Control.Pad(7, 4), 100)
        assertEquals(listOf(LpAction.PerformParam("repeat", 3 / 5f)), Surface.release(b, Control.Pad(7, 4)).second)
        assertEquals(listOf(LpAction.PerformParam("repeat", 0f)), Surface.release(a, Control.Pad(7, 2)).second)
        val (k, killOn) = Surface.press(session, perform, Control.Pad(4, 1), 100)
        assertEquals(listOf(LpAction.PerformParam("killlow", 1f)), killOn)
        assertEquals(listOf(LpAction.PerformParam("killlow", 0f)), Surface.release(k, Control.Pad(4, 1)).second)
        val (x, xy) = Surface.press(session, perform, Control.Pad(3, 7), 100)
        assertEquals(listOf(LpAction.PerformParam("x", 1f), LpAction.PerformParam("y", 1f)), xy)
        assertEquals(listOf(LpAction.PerformParam("x", 0.5f), LpAction.PerformParam("y", 0f)), Surface.release(x, Control.Pad(3, 7)).second)
    }

    @Test
    fun `chords are stacked degrees of the scale, and a pad plays all of it`() {
        val chords = LpState(page = LpPage.Chord)
        assertEquals(listOf(48, 52, 55), Surface.chordAt(cMajor, chords, 0, 0))          // C
        assertEquals(listOf(50, 53, 57), Surface.chordAt(cMajor, chords, 0, 1))          // Dm
        assertEquals(listOf(55, 59, 62, 65), Surface.chordAt(cMajor, chords, 1, 4))      // G7
        assertEquals(listOf(48, 55, 60), Surface.chordAt(cMajor, chords, 6, 0))          // C5
        val (held, on) = Surface.press(cMajor, chords, Control.Pad(0, 0), 90)
        assertEquals(listOf(48, 52, 55).map { LpAction.NoteOn(it, 90) }, on)
        assertEquals(listOf(48, 52, 55).map { LpAction.NoteOff(it) }, Surface.release(held, Control.Pad(0, 0)).second)
        assertEquals(listOf(48, 52, 55).map { LpAction.Pressure(it, 70) }, Surface.pressure(held, Control.Pad(0, 0), 70))
    }
}
