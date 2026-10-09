package com.rm.acidulous.midi.exquis

import com.rm.acidulous.midi.PadLights
import com.rm.acidulous.midi.exquis.ExquisSurface.padOf
import com.rm.acidulous.midi.launchpad.LpAction
import com.rm.acidulous.midi.launchpad.LpFader
import com.rm.acidulous.midi.launchpad.LpSeq
import com.rm.acidulous.midi.launchpad.LpTrack
import com.rm.acidulous.midi.launchpad.LpView
import com.rm.acidulous.midi.launchpad.Rgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExquisSurfaceTest {
    private val red = Rgb.of(127, 0, 0)
    private val view = LpView(
        tracks = listOf(
            LpTrack(red, clips = setOf(0, 1), level = 0.5f),
            LpTrack(Rgb.of(0, 0, 127), clips = setOf(1), drums = listOf(36, 38, 42)),
        ),
        scenes = 3,
        seq = LpSeq(scene = 0, grid = 24, length = 24 * 32, notes = listOf(0 to 60, 48 to 62)),
    )

    @Test fun `the pads are eleven rows of 6 and 5 from the bottom left`() {
        assertEquals(0, padOf(0, 0))
        assertEquals(6, padOf(1, 0))
        assertEquals(60, padOf(10, 5))
        assertEquals(10, ExquisSurface.rowOf(60))
        assertEquals(4, ExquisSurface.colOf(padOf(3, 4)))
    }

    @Test fun `Play leaves the pads to the Exquis, other pages take them`() {
        val play = ExquisSurface.zones(XqState())
        assertEquals(0, play and ExquisSurface.ZONE_PADS)
        // The arrows are always the app's, so the Exquis's octave stays put.
        assertTrue(play and ExquisSurface.ZONE_UPDOWN != 0)
        val session = ExquisSurface.zones(XqState(page = XqPage.Session))
        assertTrue(session and ExquisSurface.ZONE_PADS != 0)
        // Settings and sound always stay the Exquis's own.
        assertEquals(0, session and 0x10)
    }

    @Test fun `the arrows on Play move the app's octave for the Exquis`() {
        val up = ExquisSurface.button(view, XqState(), ExquisSurface.UP, true).first
        assertEquals(1, up.playOctave)
        assertEquals(Rgb.WHITE, ExquisSurface.render(view, up)[ExquisSurface.UP])
        assertEquals(-1, ExquisSurface.button(view, XqState(), ExquisSurface.DOWN, true).first.playOctave)
    }

    @Test fun `the Exquis's middle pads play a drum machine, in a block, the rest are moved by octaves`() {
        assertEquals(27, PadLights.exquisPadNote(0, 0))
        assertEquals(47, PadLights.exquisPadNote(5, 2))
        val drums = listOf(36, 38, 42, 46)
        val notes = PadLights.exquisDrumNotes(4)
        // Two rows, centred: the kick at row 4's third pad, the fourth drum above it.
        assertEquals(listOf(43, 44, 45, 47), notes)
        assertEquals(36, PadLights.exquisNote(43, drums, 0))
        assertEquals(46, PadLights.exquisNote(47, drums, 0))
        assertEquals(null, PadLights.exquisNote(46, drums, 0))
        // No note repeats, however many drums.
        assertEquals(24, PadLights.exquisDrumNotes(24).toSet().size)
        assertEquals(2 to 2, PadLights.exquisDrumPads(16)[0])
        // Twelve drums start on a row of 5 to sit around the middle row.
        assertEquals(3 to 2, PadLights.exquisDrumPads(12)[0])
        assertEquals(7, PadLights.exquisDrumPads(12).last().first)
        assertEquals(72, PadLights.exquisNote(60, null, 12))
        assertEquals(null, PadLights.exquisNote(120, null, 12))
    }

    private fun tapClips(s: XqState): XqState {
        val held = ExquisSurface.button(view, s, ExquisSurface.CLIPS, true).first
        return ExquisSurface.button(view, held, ExquisSurface.CLIPS, false).first
    }

    @Test fun `tapping clips goes between Play and the last page`() {
        var s = tapClips(XqState())
        assertEquals(XqPage.Session, s.page)
        s = tapClips(s)
        assertEquals(XqPage.Play, s.page)
        s = tapClips(s.copy(lastPage = XqPage.Steps))
        assertEquals(XqPage.Steps, s.page)
    }

    @Test fun `holding clips and tapping a pad chooses the page`() {
        var s = ExquisSurface.button(view, XqState(), ExquisSurface.CLIPS, true).first
        // On Play the pads are taken over while clips is held, to choose with.
        assertTrue(ExquisSurface.zones(s) and ExquisSurface.ZONE_PADS != 0)
        val mixer = ExquisSurface.PAGE_CHOICE.entries.first { it.value == XqPage.Mixer }.key
        s = ExquisSurface.pad(view, s, mixer, true).first
        s = ExquisSurface.button(view, s, ExquisSurface.CLIPS, false).first
        // Letting go after choosing doesn't switch again.
        assertEquals(XqPage.Mixer, s.page)
        assertFalse(s.choosing)
        assertEquals(XqPage.Play, tapClips(s).page)
        assertEquals(XqPage.Mixer, tapClips(tapClips(s)).page)
    }

    @Test fun `Session's top row is the first track and its pads are scenes`() {
        val s = XqState(page = XqPage.Session)
        val (_, acts) = ExquisSurface.pad(view, s, padOf(10, 1), true)
        assertEquals(listOf(LpAction.SelectTrack(0), LpAction.PlayScene(1)), acts)
        val clip = ExquisSurface.pad(view.copy(clipMode = true), s, padOf(9, 1), true).second
        assertEquals(listOf(LpAction.SelectTrack(1), LpAction.LaunchClip(1, 1)), clip)
        // The bottom row launches scenes, and its sixth pad stops.
        assertEquals(listOf(LpAction.PlayScene(2)), ExquisSurface.pad(view, s, padOf(0, 2), true).second)
        assertEquals(listOf(LpAction.StopClips), ExquisSurface.pad(view, s, padOf(0, 5), true).second)
    }

    @Test fun `Session lights a track's clips in its colour`() {
        val leds = ExquisSurface.render(view, XqState(page = XqPage.Session))
        assertEquals(Rgb.scale(red, 0.3f), leds[padOf(10, 0)])
        assertEquals(Rgb.OFF, leds[padOf(10, 2)])
        assertEquals(Rgb.OFF, leds[padOf(10, 5)])
    }

    @Test fun `Mixer rows select, mute, solo and set the level`() {
        val s = XqState(page = XqPage.Mixer)
        assertEquals(listOf(LpAction.ToggleMute(0)), ExquisSurface.pad(view, s, padOf(10, 1), true).second)
        assertEquals(listOf(LpAction.ToggleSolo(1)), ExquisSurface.pad(view, s, padOf(9, 2), true).second)
        // A row of 6 has a level bar of 3: its top pad is full.
        assertEquals(listOf(LpAction.SetMix(0, LpFader.Level, 1f)), ExquisSurface.pad(view, s, padOf(10, 5), true).second)
    }

    @Test fun `Steps writes the chosen note on a step`() {
        val s = XqState(page = XqPage.Steps)
        // The first lane is C at the octave, and step 2 is the third pad of the top row.
        val acts = ExquisSurface.pad(view, s, padOf(10, 2), true).second
        assertEquals(listOf(LpAction.ToggleStep(0, 0, 48, 48, 24)), acts)
        // Choosing a lane plays it, and lets go when the pad does. With no
        // scale the lanes are every note, so the second is C sharp.
        val (held, on) = ExquisSurface.pad(view, s, padOf(6, 1), true)
        assertEquals(listOf(LpAction.NoteOn(49, 100)), on)
        assertEquals(49, held.lane)
        assertEquals(listOf(LpAction.NoteOff(49)), ExquisSurface.pad(view, held, padOf(6, 1), false).second)
    }

    @Test fun `Steps on a drum track picks drums`() {
        val drums = view.copy(played = 1)
        assertEquals(listOf(36, 38, 42), ExquisSurface.lanes(drums, XqState(page = XqPage.Steps)))
    }

    @Test fun `the slider moves the played track's level by how far it slides`() {
        var s = ExquisSurface.slide(view, XqState(), 2).first
        val (after, acts) = ExquisSurface.slide(view, s, 5)
        assertEquals(listOf(LpAction.SetMix(0, LpFader.Level, 1f)), acts)
        s = ExquisSurface.slide(view, after, 127).first
        assertEquals(null, s.slider)
    }

    @Test fun `a knob turns the played machine's knob a step a click`() {
        val v = view.copy(device = listOf(0.5f, 0.2f))
        assertEquals(listOf(LpAction.SetDevice(1, 0.25f)), ExquisSurface.turn(v, XqState(), 1, 5).second.map {
            (it as LpAction.SetDevice).copy(value = Math.round(it.value * 100) / 100f)
        })
    }

    @Test fun `lights go out in runs of neighbouring ids`() {
        val msgs = PadLights.exquisLeds(mapOf(3 to Rgb.of(1, 2, 3), 4 to Rgb.of(4, 5, 6), 9 to Rgb.WHITE))
        assertEquals(2, msgs.size)
        val first = msgs[0].map { it.toInt() and 0xff }
        assertEquals(listOf(0xF0, 0x00, 0x21, 0x7E, 0x7F, 0x04, 3, 1, 2, 3, 0, 4, 5, 6, 0, 0xF7), first)
    }

    @Test fun `the exact scale is the root and twelve degrees from it`() {
        val (root, scale) = PadLights.exquisExactScale(2, setOf(2, 4, 6, 7, 9, 11, 1))
        assertEquals(listOf(0xF0, 0x00, 0x21, 0x7E, 0x7F, 0x06, 2, 0xF7), root.map { it.toInt() and 0xff })
        assertEquals(listOf(1, 0, 1, 0, 1, 1, 0, 1, 0, 1, 0, 1), scale.map { it.toInt() and 0xff }.drop(6).dropLast(1))
    }

    @Test fun `only controls in held zones are the controller's`() {
        assertFalse(PadLights.isExquisControl(0x9F, 10, ExquisSurface.ZONE_BUTTONS))
        assertTrue(PadLights.isExquisControl(0x9F, 10, ExquisSurface.ZONE_PADS))
        assertTrue(PadLights.isExquisControl(0xBF, 112, ExquisSurface.ZONE_ENCODERS))
        // CC 74 on channel 16 is an MPE finger, not a control.
        assertFalse(PadLights.isExquisControl(0xBF, 74, 0x3F))
    }

    @Test fun `clicking knob 1 on Session jumps five scenes, then back to the start`() {
        val many = view.copy(scenes = 12)
        var s = XqState(page = XqPage.Session)
        val seen = (0..3).map { s.sceneOffset.also { s = ExquisSurface.button(many, s, ExquisSurface.ENCODER_PUSH_FIRST, true).first } }
        // The last jump stops where the last five scenes fill the row.
        assertEquals(listOf(0, 5, 7, 0), seen)
    }

    @Test fun `Session shows when there are more scenes than fit`() {
        val few = ExquisSurface.render(view, XqState(page = XqPage.Session))
        val many = ExquisSurface.render(view.copy(scenes = 12), XqState(page = XqPage.Session))
        assertTrue(Rgb.of(90, 90, 90) == many[ExquisSurface.ENCODER_FIRST])
        assertFalse(few[ExquisSurface.ENCODER_FIRST] == many[ExquisSurface.ENCODER_FIRST])
        // A row of 6's last pad, faintly, while more scenes are to the right.
        assertEquals(Rgb.OFF, few[padOf(10, 5)])
        assertEquals(Rgb.of(14, 14, 14), many[padOf(10, 5)])
        assertEquals(Rgb.OFF, ExquisSurface.render(view.copy(scenes = 12), XqState(page = XqPage.Session, sceneOffset = 7))[padOf(10, 5)])
    }
}
