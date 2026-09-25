package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class MidiImportTest {

    // --- a small file writer, so the tests can say exactly what is in a file ---

    private fun varLen(v: Int): ByteArray {
        var buffer = v and 0x7f
        var x = v shr 7
        while (x > 0) { buffer = (buffer shl 8) or 0x80 or (x and 0x7f); x = x shr 7 }
        val out = ByteArrayOutputStream()
        while (true) { out.write(buffer and 0xff); if (buffer and 0x80 != 0) buffer = buffer shr 8 else break }
        return out.toByteArray()
    }

    private fun file(format: Int, division: Int, vararg tracks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("MThd".toByteArray()); out.write(byteArrayOf(0, 0, 0, 6, 0, format.toByte(), 0, tracks.size.toByte(), (division shr 8).toByte(), division.toByte()))
        for (t in tracks) {
            out.write("MTrk".toByteArray())
            out.write(byteArrayOf((t.size shr 24).toByte(), (t.size shr 16).toByte(), (t.size shr 8).toByte(), t.size.toByte()))
            out.write(t)
        }
        return out.toByteArray()
    }

    /** Events as (delta, bytes), with an end-of-track added. */
    private fun track(vararg events: Pair<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((delta, bytes) in events) { out.write(varLen(delta)); out.write(bytes) }
        out.write(byteArrayOf(0, 0xff.toByte(), 0x2f, 0))
        return out.toByteArray()
    }

    private fun b(vararg x: Int) = ByteArray(x.size) { x[it].toByte() }

    // --- reading -------------------------------------------------------------------

    @Test
    fun aSongWrittenOutReadsBackNoteForNote() {
        val scene = Scene(id = "s", name = "s")
        val bass = listOf(Note(0, 60, 36, 100), Note(240, 120, 43, 90), Note(720, 30, 38, 110))
        val keys = listOf(Note(0, 900, 60, 80), Note(0, 900, 64, 80))
        val song = Song(
            name = "round trip", tempo = 96f,
            tracks = listOf(
                Track(id = "a", name = "Bass", machine = Machine("Reflux"), clips = mapOf("s" to Clip(bars = 1, notes = bass))),
                Track(id = "b", name = "Keys", machine = Machine("Trinity"), clips = mapOf("s" to Clip(bars = 1, notes = keys))),
            ),
            scenes = listOf(scene),
        )
        val tmp = File.createTempFile("round", ".mid")
        try {
            MidiFile.write(song, tmp)
            val parsed = MidiFile.read(tmp.readBytes())
            assertEquals(96f, parsed.tempo!!, 0.01f)
            assertEquals(listOf("Bass", "Keys"), parsed.parts.map { it.name })
            assertEquals(bass, parsed.parts[0].notes)
            assertEquals(keys.sortedBy { it.pitch }, parsed.parts[1].notes)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun formatNoughtIsSplitByChannelAndRunningStatusAndSilentNoteOnsAreRead() {
        val bytes = file(0, PPQN, track(
            0 to b(0xff, 0x51, 3, 0x07, 0xa1, 0x20), // 500000 us: 120 bpm
            0 to b(0xff, 0x58, 4, 3, 2, 24, 8),     // 3/4
            0 to b(0x90, 60, 100),
            0 to b(62, 90),                          // running status: another note-on
            0 to b(0x99, 36, 120),                   // drums, channel 10
            PPQN to b(0x90, 60, 0),                  // velocity nought is a note-off
            0 to b(62, 0),
            0 to b(0x89, 36, 0),
        ))
        val parsed = MidiFile.read(bytes)
        assertEquals(120f, parsed.tempo!!, 0.01f)
        assertEquals(Signature(3, 4), parsed.signature)
        assertEquals(2, parsed.parts.size)
        val keys = parsed.parts.first { it.channel == 0 }
        val drums = parsed.parts.first { it.channel == MidiFile.DRUM_CHANNEL }
        assertEquals(listOf(Note(0, PPQN, 60, 100), Note(0, PPQN, 62, 90)), keys.notes)
        assertEquals("Drums", drums.name)
        assertEquals(listOf(Note(0, PPQN, 36, 120)), drums.notes)
    }

    @Test
    fun anotherResolutionIsScaledToOurs() {
        val bytes = file(1, 960, track(
            480 to b(0x90, 64, 100),
            960 to b(0x80, 64, 0),
        ))
        val note = MidiFile.read(bytes).parts.single().notes.single()
        assertEquals(PPQN / 2, note.tick)
        assertEquals(PPQN, note.length)
    }

    @Test
    fun anUnnamedPartIsCalledWhatItsInstrumentIsAndGoesToTheMachineThatPlaysIt() {
        val bytes = file(1, PPQN, track(
            0 to b(0xc0, 33),        // fingered bass
            0 to b(0xc1, 17),        // an organ
            0 to b(0x90, 36, 100), 0 to b(0x91, 60, 90),
            PPQN to b(0x80, 36, 0), 0 to b(0x81, 60, 0),
        ))
        val parts = MidiFile.read(bytes).parts
        assertEquals(listOf("Bass 1", "Organ 2"), parts.map { it.name })
        assertEquals(listOf("Trinity", "Manual"), parts.map { MidiImport.defaultMachine(it) })
    }

    @Test
    fun drumsGoOutOnChannelTenAsGeneralMidiAndComeBackAsTheyWere() {
        val scene = Scene(id = "s", name = "s")
        fun note(name: String) = MachineUi.voicesOf("Hexbeat").first { it.name == name }.note
        val beat = listOf(Note(0, 30, note("Kick"), 110), Note(0, 30, note("Closed Hat"), 80), Note(240, 30, note("Snare"), 100))
        val song = Song(
            name = "kit",
            tracks = listOf(
                Track(id = "b", name = "Bass", machine = Machine("Reflux"), clips = mapOf("s" to Clip(bars = 1, notes = listOf(Note(0, 60, 36, 100))))),
                Track(id = "d", name = "Drums", machine = Machine("Hexbeat"), clips = mapOf("s" to Clip(bars = 1, notes = beat))),
            ),
            scenes = listOf(scene),
        )
        val tmp = File.createTempFile("kit", ".mid")
        try {
            MidiFile.write(song, tmp)
            val parts = MidiFile.read(tmp.readBytes()).parts
            val drums = parts.first { it.name == "Drums" }
            assertEquals(MidiFile.DRUM_CHANNEL, drums.channel)
            // What another program sees: General MIDI's kick, closed hat, snare.
            assertEquals(setOf(36, 42, 38), drums.notes.map { it.pitch }.toSet())
            assertEquals(0, parts.first { it.name == "Bass" }.channel)
            // And what comes back in on the same machine is what went out.
            assertEquals(beat.sortedWith(compareBy({ it.tick }, { it.pitch })), MidiImport.notesFor(drums, "Hexbeat"))
        } finally {
            tmp.delete()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun somethingElseIsRefused() {
        MidiFile.read("RIFF not a midi file at all".toByteArray())
    }

    // --- building a song -------------------------------------------------------------

    private val bar = 4 * PPQN

    private fun loop(bars: Int, pitch: Int = 48, channel: Int = 0): MidiFile.Part =
        MidiFile.Part("part", channel, (0 until bars * 4).map { Note(it * PPQN, PPQN / 2, pitch + (it % 8), 100) })

    @Test
    fun aLoopRepeatedComesInAsOneSceneRepeated() {
        val parsed = MidiFile.Parsed(100f, null, listOf(loop(32)))
        val song = MidiImport.build("loop", parsed, listOf("Trinity"), 8)
        // The part is eight notes long in pitch, two bars in time, so every
        // eight-bar cut is the same as the last.
        assertEquals(1, song.scenes.size)
        assertEquals(4, song.scenes.single().repeat)
        assertEquals(8, song.tracks.single().clips.values.single().bars)
        assertEquals(100f, song.tempo)
    }

    @Test
    fun differentStretchesAreScenesOfTheirOwnAndTheLastIsWhatIsLeft() {
        val notes = (0 until 10 * 4).map { Note(it * PPQN, PPQN / 2, 40 + it, 100) }
        val song = MidiImport.build("ten", MidiFile.Parsed(null, null, listOf(MidiFile.Part("p", 0, notes))), listOf("Trinity"), 4)
        assertEquals(listOf("bar 1", "bar 5", "bar 9"), song.scenes.map { it.name })
        val clips = song.scenes.map { song.tracks.single().clips[it.id]!! }
        assertEquals(listOf(4, 4, 2), clips.map { it.bars })
        assertTrue(clips.all { c -> c.notes.all { it.tick < c.bars * bar } })
        assertEquals(40, song.tracks.single().notesIn(song.scenes[0]).first().pitch)
        assertEquals(56, song.tracks.single().notesIn(song.scenes[1]).first().pitch)
    }

    private fun Track.notesIn(scene: Scene) = clips[scene.id]!!.notes

    @Test
    fun aNoteHeldOverACutIsCutThere() {
        val part = MidiFile.Part("pad", 0, listOf(Note(3 * bar, 2 * bar, 60, 90), Note(5 * bar, PPQN, 62, 90)))
        val song = MidiImport.build("pad", MidiFile.Parsed(null, null, listOf(part)), listOf("Cumulus"), 4)
        val first = song.tracks.single().clips[song.scenes[0].id]!!.notes.single()
        assertEquals(3 * bar, first.tick)
        assertEquals(bar, first.length)
    }

    @Test
    fun aSilentStretchKeepsItsLength() {
        val part = MidiFile.Part("p", 0, listOf(Note(0, PPQN, 60, 90), Note(8 * bar, PPQN, 60, 90)))
        val song = MidiImport.build("gap", MidiFile.Parsed(null, null, listOf(part)), listOf("Trinity"), 4)
        assertEquals(3, song.scenes.size)
        assertEquals(4, song.barsOf(song.scenes[1]))
    }

    @Test
    fun drumsOnChannelTenGoToTheMachinesOwnSoundsByName() {
        val gm = MidiFile.Part("Drums", MidiFile.DRUM_CHANNEL, listOf(
            Note(0, 30, 36, 100),  // kick
            Note(0, 30, 42, 80),   // closed hat
            Note(240, 30, 38, 110), // snare
            Note(240, 30, 81, 70), // open triangle: nothing to go to
        ))
        fun pitchOf(type: String, name: String) = MachineUi.voicesOf(type).first { it.name == name }.note
        for (type in listOf("Hexbeat", "Genesis")) {
            val mapped = MidiImport.notesFor(gm, type).map { it.pitch }
            assertEquals(type, listOf(pitchOf(type, "Kick"), pitchOf(type, "Closed Hat"), pitchOf(type, "Snare")), mapped)
            assertEquals(1, MidiImport.unmatched(gm, type))
        }
        // A synth playing the drum part plays the notes as written.
        assertEquals(gm.notes, MidiImport.notesFor(gm, "Trinity"))
    }

    @Test
    fun partsLeftOutAreLeftOut() {
        val parsed = MidiFile.Parsed(null, null, listOf(loop(2, 40), loop(2, 60), loop(2, 36, MidiFile.DRUM_CHANNEL)))
        val song = MidiImport.build("some", parsed, listOf("Trinity", null, "Hexbeat"), 4)
        assertEquals(listOf("Trinity", "Hexbeat"), song.tracks.map { it.machine.type })
        assertEquals("Hexbeat", MidiImport.defaultMachine(parsed.parts[2]))
        assertEquals("Trinity", MidiImport.defaultMachine(parsed.parts[0]))
    }

    // --- controllers, pedals, bends and tempo -------------------------------------------

    @Test
    fun pedalsComeInAsSteppedLanesCutWithTheirScenes() {
        val bytes = file(1, PPQN, track(
            0 to b(0x90, 60, 100),
            PPQN to b(0xb0, 64, 127),          // sustain down on beat 2
            PPQN to b(0x80, 60, 0),
            (4 * 4 - 1) * PPQN to b(0xb0, 64, 0), // up again on beat 2 of bar 5
            0 to b(0xb0, 66, 100),              // sostenuto down there
            0 to b(0x90, 62, 90),
            PPQN to b(0x80, 62, 0),
        ))
        val parsed = MidiFile.read(bytes)
        val song = MidiImport.build("pedals", parsed, listOf("Trinity"), 4)
        val clips = song.scenes.map { song.tracks.single().clips[it.id]!! }
        val first = clips[0].automation[laneKey("performance", "sustain")]!!
        assertEquals(false, first.linear)
        // Up from the top of the clip, down on beat two, still down at the end.
        assertEquals(listOf(LanePoint(0, 0f), LanePoint(PPQN, 1f)), first.points)
        // The second clip begins with the pedal already down and lifts it.
        val second = clips[1].automation[laneKey("performance", "sustain")]!!
        assertEquals(LanePoint(0, 1f), second.points.first())
        assertEquals(0f, second.points.last().value)
        assertTrue(clips[1].automation.containsKey(laneKey("performance", "sostenuto")))
    }

    @Test
    fun aTempoChangeGivesTheScenesAfterItTheirOwnTempo() {
        val bytes = file(1, PPQN, track(
            0 to b(0xff, 0x51, 3, 0x07, 0xa1, 0x20),   // 120
            0 to b(0x90, 60, 100),
            PPQN to b(0x80, 60, 0),
            (4 * 4 - 1) * PPQN to b(0xff, 0x51, 3, 0x09, 0x27, 0xc0), // 100, at bar 5
            0 to b(0x90, 62, 100),
            PPQN to b(0x80, 62, 0),
        ))
        val song = MidiImport.build("tempo", MidiFile.read(bytes), listOf("Trinity"), 4)
        assertEquals(120f, song.tempo, 0.01f)
        assertEquals(null, song.scenes[0].tempo)
        assertEquals(100f, song.scenes[1].tempo!!.bpm, 0.01f)
    }

    @Test
    fun bendBecomesACurveOnTheNoteItBends() {
        val bytes = file(1, PPQN, track(
            0 to b(0xb0, 101, 0), 0 to b(0xb0, 100, 0), 0 to b(0xb0, 6, 12), // range: an octave
            0 to b(0x90, 60, 100),
            PPQN / 2 to b(0xe0, 0x00, 0x60),          // half way up: +6 semitones
            PPQN / 2 to b(0x80, 60, 0),
            0 to b(0xe0, 0x00, 0x40),                 // centre again
            0 to b(0x90, 64, 100),
            PPQN to b(0x80, 64, 0),
        ))
        val notes = MidiFile.read(bytes).parts.single().notes
        val bent = notes.first { it.pitch == 60 }.bend!!
        assertEquals(PPQN / 2, bent.points.last().tick)
        assertEquals(6f, Note.bendFrom01(bent.points.last().value), 0.05f)
        assertEquals(null, notes.first { it.pitch == 64 }.bend)
    }

    @Test
    fun aPedalLaneGoesOutAsTheControllerAndComesBack() {
        val scene = Scene(id = "s", name = "s")
        val pedal = Lane(listOf(LanePoint(0, 0f), LanePoint(PPQN, 1f), LanePoint(3 * PPQN, 0f)), linear = false)
        val song = Song(
            name = "ped",
            tracks = listOf(Track(id = "t", name = "Keys", machine = Machine("Trinity"), clips = mapOf("s" to Clip(
                bars = 1, notes = listOf(Note(0, PPQN, 60, 100)),
                automation = mapOf(laneKey("performance", "sustain") to pedal),
            )))),
            scenes = listOf(scene),
        )
        val tmp = File.createTempFile("ped", ".mid")
        try {
            MidiFile.write(song, tmp)
            val back = MidiFile.read(tmp.readBytes()).parts.single().lanes["sustain"]!!
            assertEquals(listOf(LanePoint(0, 0f), LanePoint(PPQN, 1f), LanePoint(3 * PPQN, 0f)), back.filter { it.tick < 4 * PPQN })
        } finally {
            tmp.delete()
        }
    }
}
