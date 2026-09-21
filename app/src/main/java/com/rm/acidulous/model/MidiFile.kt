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
        song.tracks.forEachIndexed { index, track ->
            noteTrack(song, track, index)?.let { tracks += it }
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
                    val usPerQuarter = (60_000_000.0 / bpm).toInt()
                    events += Event(at, 0, meta(0x51, byteArrayOf(
                        (usPerQuarter shr 16).toByte(), (usPerQuarter shr 8).toByte(), usPerQuarter.toByte(),
                    )))
                    lastBpm = bpm
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

    private fun noteTrack(song: Song, track: Track, index: Int): ByteArray? {
        val channel = index % MAX_CHANNELS
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
                            val velocity = note.velocity.coerceIn(1, 127)
                            for (j in 0 until rat) {
                                val on = start + j * step
                                if (on >= at + sceneTicks) break
                                val end = (on + step).coerceAtMost(
                                    minOf(start + span, at + sceneTicks),
                                ).coerceAtLeast(on + 1)
                                events += Event(on, 1, byteArrayOf(
                                    (0x90 or channel).toByte(), note.pitch.coerceIn(0, 127).toByte(), velocity.toByte(),
                                ))
                                events += Event(end, 0, byteArrayOf(
                                    (0x80 or channel).toByte(), note.pitch.coerceIn(0, 127).toByte(), 64,
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
