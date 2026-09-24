package com.rm.acidulous.model

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The song as a standard MIDI file - the notes, not the sound.
 *
 * Format 1: a first track carrying tempo and time signature, then one track
 * per instrument. The division goes into the header as **240**, which is
 * `PPQN` unchanged, so nothing is re-quantised on the way out and a note
 * that sat exactly on a sixteenth still does in the other program. Getting
 * that for free is the whole reason the engine runs at 240 rather than the
 * more usual 96 or 480.
 *
 * The arrangement is *unrolled*: a scene that repeats four times writes its
 * notes four times, and a one-bar clip under a four-bar scene is written
 * four times too, because a MIDI file has no idea what a scene or a loop is.
 * What comes out is what you would hear playing the song from the top.
 *
 * **The modifiers need no applying.** What is written is what is in the clips,
 * and since M59 that is already what you hear: a part played through the
 * arpeggiator was written down as the run, not as the chord that made it. The
 * note reading "the export is the chord you drew rather than the run you hear"
 * belonged to the old arrangement and is gone with it.
 *
 * Entirely offline, entirely ours, and it shares nothing with the live paths
 * but the song itself.
 */
object MidiFile {

    /** Channel per track, which is the same rule the MIDI input routing uses. */
    private const val MAX_CHANNELS = 16

    fun write(song: Song, file: File) {
        val tracks = mutableListOf<ByteArray>()
        tracks += tempoTrack(song)
        // Drums on channel 10, where every other program looks for them, and
        // the rest in order around it.
        var next = 0
        song.tracks.forEach { track ->
            val channel = if (MachineUi.kindOf(track.machine.type) == MachineKind.Drums) {
                DRUM_CHANNEL
            } else {
                if (next == DRUM_CHANNEL) next++
                (next++ % MAX_CHANNELS)
            }
            noteTrack(song, track, channel)?.let { tracks += it }
        }

        file.outputStream().buffered().use { out ->
            out.write("MThd".toByteArray(Charsets.US_ASCII))
            out.write(int32(6))
            out.write(int16(1))             // format 1: several tracks, one timeline
            out.write(int16(tracks.size))
            out.write(int16(PPQN))          // division, in ticks per quarter note
            for (bytes in tracks) {
                out.write("MTrk".toByteArray(Charsets.US_ASCII))
                out.write(int32(bytes.size))
                out.write(bytes)
            }
        }
    }

    /** How long the whole arrangement is, unrolled, in ticks. */
    fun totalTicks(song: Song): Int {
        var total = 0
        for (scene in song.scenes) {
            total += song.barsOf(scene) * song.signatureOf(scene).ticksPerBar * scene.repeat
        }
        return total
    }

    // --- reading -------------------------------------------------------------------

    /**
     * A standard MIDI file's notes, ready to become a song: every track's
     * notes on every channel, in this engine's ticks, with the first tempo and
     * time signature the file states.
     *
     * Format 0 keeps everything on one track and tells instruments apart by
     * channel, and a format 1 file may do the same inside a track - so the
     * parts come out split by track *and* channel, which is what a person
     * means by "the bass". Tempo and signature changes after the first are
     * left out: a song here has one of each per scene, and the file has not
     * said where its scenes are.
     *
     * Throws [IllegalArgumentException] for anything that is not a MIDI file,
     * or is one timed in SMPTE frames rather than beats, which no song is.
     */
    fun read(bytes: ByteArray): Parsed {
        val r = Reader(bytes)
        require(r.ascii(4) == "MThd") { "not a MIDI file" }
        val headerLength = r.int32()
        val format = r.int16()
        val trackCount = r.int16()
        val division = r.int16()
        r.skip(headerLength - 6)
        require(format in 0..2) { "MIDI format $format" }
        require(division and 0x8000 == 0 && division > 0) { "timed in frames, not beats" }

        var tempo: Float? = null
        var signature: Signature? = null
        // By (track, channel), in the order they were first heard.
        val parts = LinkedHashMap<Pair<Int, Int>, MutableList<Note>>()
        val names = HashMap<Int, String>()
        // The first General MIDI program each part asks for, which is how a
        // file that never names its tracks still says what they are.
        val programs = HashMap<Pair<Int, Int>, Int>()
        fun ticks(fileTicks: Long) = ((fileTicks * PPQN + division / 2) / division).toInt()

        for (t in 0 until trackCount) {
            if (r.remaining < 8) break
            val id = r.ascii(4)
            val length = r.int32()
            if (id != "MTrk") { r.skip(length); continue }
            val end = r.pos + length
            var at = 0L
            var status = 0
            // A note is open from its note-on until the matching note-off -
            // or a note-on at velocity nought, which is the same thing.
            val open = HashMap<Int, ArrayDeque<Pair<Long, Int>>>()
            while (r.pos < end) {
                at += r.varLen()
                var b = r.byte()
                if (b < 0x80) {
                    // Running status: the data byte is the first of the last
                    // message's kind, which is how most files save space.
                    require(status != 0) { "running status with nothing to run" }
                    r.pos--
                    b = status
                } else if (b < 0xf0) {
                    status = b
                }
                when {
                    b == 0xff -> {
                        val type = r.byte()
                        val data = r.bytes(r.varLen().toInt())
                        when (type) {
                            0x03 -> names.putIfAbsent(t, data.decodeToString().trim())
                            0x51 -> if (tempo == null && data.size >= 3) {
                                val us = ((data[0].toInt() and 0xff) shl 16) or ((data[1].toInt() and 0xff) shl 8) or (data[2].toInt() and 0xff)
                                if (us > 0) tempo = 60_000_000f / us
                            }
                            0x58 -> if (signature == null && data.size >= 2) {
                                signature = Signature(data[0].toInt().coerceIn(1, 32), 1 shl data[1].toInt().coerceIn(0, 5))
                            }
                            0x2f -> r.pos = end
                        }
                    }
                    b == 0xf0 || b == 0xf7 -> r.skip(r.varLen().toInt())
                    else -> {
                        val kind = b and 0xf0
                        val channel = b and 0x0f
                        val d1 = r.byte()
                        val d2 = if (kind == 0xc0 || kind == 0xd0) 0 else r.byte()
                        val key = (channel shl 8) or d1
                        if (kind == 0xc0) programs.putIfAbsent(t to channel, d1)
                        if (kind == 0x90 && d2 > 0) {
                            open.getOrPut(key) { ArrayDeque() }.addLast(at to d2)
                        } else if (kind == 0x80 || (kind == 0x90 && d2 == 0)) {
                            val started = open[key]?.removeFirstOrNull() ?: continue
                            val on = ticks(started.first)
                            val off = ticks(at).coerceAtLeast(on + 1)
                            parts.getOrPut(t to channel) { mutableListOf() } += Note(on, off - on, d1, started.second.coerceIn(1, 127))
                        }
                    }
                }
            }
            r.pos = end
        }

        val out = parts.map { (where, notes) ->
            val (track, channel) = where
            val name = names[track]?.takeIf { it.isNotEmpty() }
            // Two parts from one named track are told apart by channel.
            val split = parts.keys.count { it.first == track } > 1
            val program = programs[where]
            // Unnamed parts are called what their instrument is, and told
            // apart by channel only when two would share a name.
            val family = program?.let { GM_FAMILIES[(it / 8).coerceIn(0, 15)] }
            Part(
                name = when {
                    name == null && channel == DRUM_CHANNEL -> "Drums"
                    name == null && family != null -> "$family ${channel + 1}"
                    name == null -> "Channel ${channel + 1}"
                    split -> "$name ${channel + 1}"
                    else -> name
                },
                channel = channel,
                notes = notes.sortedWith(compareBy({ it.tick }, { it.pitch })),
                program = program,
            )
        }
        return Parsed(tempo, signature, out)
    }

    /** Channel 10, counted from nought: where General MIDI keeps the drums. */
    const val DRUM_CHANNEL = 9

    /** Our drum machines' sounds, by name, as General MIDI's notes for them. */
    val GM_DRUM_NOTE: Map<String, Int> = mapOf(
        "Kick" to 36, "Rim" to 37, "Snare" to 38, "Clap" to 39,
        "Low Tom" to 45, "Mid Tom" to 47, "Hi Tom" to 50,
        "Closed Hat" to 42, "Open Hat" to 46, "Crash" to 49, "Ride" to 51,
        "Cowbell" to 56, "Clave" to 75,
    )

    /** [program] is the General MIDI instrument the file asked for, if it asked. */
    data class Part(val name: String, val channel: Int, val notes: List<Note>, val program: Int? = null)

    /** General MIDI's sixteen families, eight programs each. */
    val GM_FAMILIES = listOf(
        "Piano", "Bells", "Organ", "Guitar", "Bass", "Strings", "Ensemble", "Brass",
        "Reed", "Pipe", "Lead", "Pad", "Synth FX", "Ethnic", "Percussion", "Effects",
    )

    data class Parsed(val tempo: Float?, val signature: Signature?, val parts: List<Part>)

    private class Reader(val b: ByteArray) {
        var pos = 0
        val remaining get() = b.size - pos
        fun byte(): Int {
            require(pos < b.size) { "the file ends early" }
            return b[pos++].toInt() and 0xff
        }
        fun bytes(n: Int): ByteArray {
            require(n >= 0 && pos + n <= b.size) { "the file ends early" }
            return b.copyOfRange(pos, pos + n).also { pos += n }
        }
        fun skip(n: Int) { pos = (pos + n.coerceAtLeast(0)).coerceAtMost(b.size) }
        fun ascii(n: Int) = bytes(n).toString(Charsets.US_ASCII)
        fun int16() = (byte() shl 8) or byte()
        fun int32() = (byte() shl 24) or (byte() shl 16) or (byte() shl 8) or byte()
        fun varLen(): Long {
            var v = 0L
            repeat(4) {
                val x = byte()
                v = (v shl 7) or (x and 0x7f).toLong()
                if (x and 0x80 == 0) return v
            }
            return v
        }
    }

    // --- the tracks ---------------------------------------------------------------

    private fun tempoTrack(song: Song): ByteArray {
        val events = mutableListOf<Event>()
        events += Event(0, 0, meta(0x03, song.name.toByteArray(Charsets.UTF_8)))

        var at = 0
        var lastBpm = -1f
        var lastSignature: Signature? = null
        for (scene in song.scenes) {
            val signature = song.signatureOf(scene)
            val bpm = scene.tempo?.bpm ?: song.tempo
            val sceneTicks = song.barsOf(scene) * signature.ticksPerBar
            for (repeat in 0 until scene.repeat) {
                // Only write a change when it *is* one: a tempo event every
                // scene would make the map unreadable in another program.
                if (bpm != lastBpm) {
                    events += tempoEvent(at, bpm)
                    lastBpm = bpm
                }
                // A ramp, on the last pass, as a step every beat: a MIDI file
                // has no glide, and a beat is fine enough that nobody hears
                // the stairs.
                val ramp = scene.ramp
                if (ramp != null && repeat == scene.repeat - 1 && ramp.toBpm > 0f && ramp.bars > 0) {
                    val length = minOf(sceneTicks, ramp.bars * signature.ticksPerBar)
                    val from = at + sceneTicks - length
                    var t = 0
                    while (t < length) {
                        t += PPQN
                        val v = bpm + (ramp.toBpm - bpm) * minOf(1f, t.toFloat() / length)
                        events += tempoEvent(from + t - PPQN, v)
                    }
                    lastBpm = ramp.toBpm
                }
                if (signature != lastSignature) {
                    // The denominator is stored as its power of two, and the
                    // last two bytes are the metronome's business: 24 clocks
                    // to a click, 8 thirty-seconds to a quarter, both normal.
                    var power = 0
                    var unit = signature.unit
                    while (unit > 1) { unit = unit shr 1; power++ }
                    events += Event(at, 0, meta(0x58, byteArrayOf(
                        signature.beats.toByte(), power.toByte(), 24, 8,
                    )))
                    lastSignature = signature
                }
                at += sceneTicks
            }
        }
        return trackBytes(events)
    }

    private fun noteTrack(song: Song, track: Track, channel: Int): ByteArray? {
        // A drum machine's sounds sit on notes of its own; another program
        // expects General MIDI's, so each is written as the one of those
        // with its name. A machine with numbered pads keeps its notes.
        val drumNames = if (channel == DRUM_CHANNEL) {
            MachineUi.voicesOf(track.machine.type).associate { it.note to it.name }
        } else {
            emptyMap()
        }
        // And as the track plays them: its transpose and its fixed velocity,
        // so the file is what was heard rather than what was written.
        val shift = if (MachineUi.takesTranspose(track.machine.type)) track.transpose else 0
        fun out(pitch: Int) = drumNames[pitch]?.let { GM_DRUM_NOTE[it] } ?: (pitch + shift)
        val events = mutableListOf<Event>()
        events += Event(0, 0, meta(0x03, track.name.toByteArray(Charsets.UTF_8)))

        var at = 0
        var any = false
        for (scene in song.scenes) {
            val signature = song.signatureOf(scene)
            val sceneTicks = song.barsOf(scene) * signature.ticksPerBar
            val clip = track.clips[scene.id]
            for (repeat in 0 until scene.repeat) {
                if (clip != null && !clip.mute && clip.notes.isNotEmpty()) {
                    val clipTicks = (clip.bars * signature.ticksPerBar).coerceAtLeast(1)
                    // A clip shorter than its scene loops to fill it, unless
                    // it is a one-shot, which is exactly what the scheduler
                    // does with the same two numbers.
                    val passes = if (clip.playMode == PlayMode.OneShot) 1
                    else (sceneTicks + clipTicks - 1) / clipTicks
                    for (pass in 0 until passes) {
                        val origin = at + pass * clipTicks
                        // The same decisions the engine makes, so the file is
                        // the performance rather than an idea of it. Fill is
                        // false here for the reason it is false in a render:
                        // nobody is holding a button while a file is written.
                        var prevPlayed = false
                        for (note in clip.notes) {
                            val plays = trigPlays(note, pass, clip.seed, prevPlayed)
                            if (note.conditional()) prevPlayed = plays
                            if (!plays) continue
                            val start = origin + note.tick + note.nudge
                            if (start >= at + sceneTicks) continue // past the scene's end
                            if (start < at) continue // nudged off the front of the scene
                            // A note is not allowed to ring past its scene:
                            // the next scene's notes start there, and a
                            // hanging note is the classic export bug.
                            // A ratchet is the note struck several times
                            // inside its own length, clamped to its own pass
                            // exactly as the player clamps it.
                            val span = minOf(note.length, clipTicks - note.tick).coerceAtLeast(1)
                            val rat = note.ratchet.coerceIn(1, 8)
                            val step = if (rat > 1) (span / rat).coerceAtLeast(1) else span
                            val velocity = (track.velocity ?: note.velocity).coerceIn(1, 127)
                            for (j in 0 until rat) {
                                val on = start + j * step
                                if (on >= at + sceneTicks) break
                                val end = (on + step).coerceAtMost(
                                    minOf(start + span, at + sceneTicks),
                                ).coerceAtLeast(on + 1)
                                events += Event(on, 1, byteArrayOf(
                                    (0x90 or channel).toByte(), out(note.pitch).coerceIn(0, 127).toByte(), velocity.toByte(),
                                ))
                                events += Event(end, 0, byteArrayOf(
                                    (0x80 or channel).toByte(), out(note.pitch).coerceIn(0, 127).toByte(), 64,
                                ))
                            }
                            any = true
                        }
                    }
                }
                at += sceneTicks
            }
        }
        return if (any) trackBytes(events) else null
    }

    private fun tempoEvent(at: Int, bpm: Float): Event {
        val usPerQuarter = (60_000_000.0 / bpm).toInt()
        return Event(at, 0, meta(0x51, byteArrayOf(
            (usPerQuarter shr 16).toByte(), (usPerQuarter shr 8).toByte(), usPerQuarter.toByte(),
        )))
    }

    // --- the bytes ----------------------------------------------------------------

    /**
     * [order] breaks ties at the same tick: note-offs before note-ons, so a
     * repeated note is released before it is struck again rather than the
     * other way round, which would silence it.
     */
    private class Event(val tick: Int, val order: Int, val bytes: ByteArray)

    private fun trackBytes(events: List<Event>): ByteArray {
        val sorted = events.sortedWith(compareBy({ it.tick }, { it.order }))
        val out = ByteArrayOutputStream()
        var last = 0
        for (event in sorted) {
            out.write(varLen(event.tick - last))
            out.write(event.bytes)
            last = event.tick
        }
        out.write(varLen(0))
        out.write(byteArrayOf(0xff.toByte(), 0x2f, 0x00)) // end of track
        return out.toByteArray()
    }

    private fun meta(type: Int, payload: ByteArray): ByteArray =
        byteArrayOf(0xff.toByte(), type.toByte()) + varLen(payload.size) + payload

    /** Seven bits a byte, high bit set on every one but the last. */
    private fun varLen(value: Int): ByteArray {
        var v = if (value < 0) 0 else value
        val bytes = ArrayDeque<Byte>()
        bytes.addFirst((v and 0x7f).toByte())
        v = v shr 7
        while (v > 0) {
            bytes.addFirst(((v and 0x7f) or 0x80).toByte())
            v = v shr 7
        }
        return bytes.toByteArray()
    }

    private fun int16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun int32(v: Int) =
        byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
}
