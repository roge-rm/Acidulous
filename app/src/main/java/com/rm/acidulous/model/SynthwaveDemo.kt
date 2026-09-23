package com.rm.acidulous.model

import com.rm.acidulous.model.DemoKit.BAR
import com.rm.acidulous.model.DemoKit.E
import com.rm.acidulous.model.DemoKit.Q
import com.rm.acidulous.model.DemoKit.S
import com.rm.acidulous.model.DemoKit.chord
import com.rm.acidulous.model.DemoKit.clip
import com.rm.acidulous.model.DemoKit.fx
import com.rm.acidulous.model.DemoKit.machine
import com.rm.acidulous.model.DemoKit.vibrato

/**
 * Night Drive: synthwave at 100, in D minor.
 *
 * What it shows:
 *
 *  - **FM**: the bass is Ratio, six operators, pumping eighths.
 *  - **Effects doing the era's work**: a gated reverb on the kit, chorus on
 *    the strings, a ping-pong delay on the arpeggio.
 *  - **Note expression**: the lead's long notes carry a vibrato, drawn into
 *    each note as a bend that widens the way a hand on a wheel does.
 *  - **Scene fades**: the intro fades in and the outro fades out, with no
 *    automation to draw.
 */
internal object SynthwaveDemo {
    // Genesis: kick, snare, clap, rim, three toms, closed and open hat, crash.
    private const val KICK = 36
    private const val SNARE = 37
    private const val HAT = 43
    private const val OPEN_HAT = 44
    private const val CRASH = 45

    /** Dm, Bb, F, C - roots, and the pad's voicings. */
    private val ROOTS = listOf(38, 34, 41, 36)
    private val PADS = listOf(
        listOf(50, 53, 57, 62),
        listOf(50, 53, 58, 62),
        listOf(48, 53, 57, 60),
        listOf(48, 52, 55, 60),
    )

    private fun drums(bars: Int, crash: Boolean = false): List<Note> {
        val out = ArrayList<Note>()
        for (b in 0 until bars) {
            val o = b * BAR
            out += Note(o, 60, KICK, 120)
            out += Note(o + 2 * Q, 60, KICK, 116)
            if (b % 2 == 1) out += Note(o + 2 * Q + E, 60, KICK, 96)
            out += Note(o + Q, 60, SNARE, 112)
            out += Note(o + 3 * Q, 60, SNARE, 116)
            for (i in 0 until 8) out += Note(o + i * E, 30, HAT, if (i % 2 == 0) 64 else 84)
            if (b % 4 == 3) out += Note(o + 3 * Q + E, 60, OPEN_HAT, 80)
        }
        if (crash) out += Note(0, Q, CRASH, 100)
        return out
    }

    /** Eighths, root and octave: the engine of the style. */
    private fun bass(bars: Int): List<Note> = (0 until bars).flatMap { b ->
        val root = ROOTS[b % ROOTS.size]
        (0 until 8).map { i -> Note(b * BAR + i * E, E - 24, if (i % 2 == 0) root else root + 12, if (i % 2 == 0) 104 else 84) }
    }

    /** Sixteenths up through the chord and back, an octave over the pad. */
    private fun arp(bars: Int): List<Note> = (0 until bars).flatMap { b ->
        val tones = PADS[b % PADS.size].map { it + 12 }
        val order = listOf(0, 1, 2, 3, 2, 1, 0, 1, 2, 3, 3 + 1, 3, 2, 1, 2, 3)
        order.mapIndexed { i, k ->
            val pitch = if (k < tones.size) tones[k] else tones[0] + 12
            Note(b * BAR + i * S, S - 16, pitch, if (i % 4 == 0) 96 else 74)
        }
    }

    private fun pads(bars: Int): List<Note> = (0 until bars).flatMap { b ->
        chord(b * BAR, BAR - 12, PADS[b % PADS.size], 76)
    }

    /**
     * The lead, over four bars: a line that sings rather than plays, with its
     * held notes given a vibrato.
     */
    private fun lead(): List<Note> {
        fun held(at: Int, length: Int, pitch: Int, velocity: Int) =
            Note(at, length, pitch, velocity, bend = vibrato(length, delay = length / 3, depth = 0.35f))
        return listOf(
            Note(0, Q + E, 74, 100),
            Note(Q + E, E, 72, 88),
            held(2 * Q, 2 * Q - 10, 69, 96),
            Note(BAR, Q, 65, 90),
            Note(BAR + Q, Q, 67, 88),
            Note(BAR + 2 * Q, Q, 69, 94),
            Note(BAR + 3 * Q, Q, 70, 96),
            held(2 * BAR, 3 * Q - 10, 72, 104),
            Note(2 * BAR + 3 * Q, Q, 69, 90),
            Note(3 * BAR, Q + E, 67, 94),
            Note(3 * BAR + Q + E, E, 69, 86),
            held(3 * BAR + 2 * Q, 2 * Q - 10, 64, 96),
        )
    }

    fun build(): Song {
        val intro = Scene(id = "s-intro", name = "Intro", fadeIn = true)
        val verse = Scene(id = "s-verse", name = "Verse", repeat = 2)
        val chorus = Scene(id = "s-chorus", name = "Chorus", repeat = 2)
        val outro = Scene(id = "s-outro", name = "Outro", fadeOut = true)

        return Song(
            name = "Night Drive",
            tempo = 100f,
            key = SongKey(root = 2, scale = 5), // D Aeolian
            loopSong = true,
            tracks = listOf(
                Track(
                    id = "t-drums", name = "Drums",
                    machine = machine("Genesis", "Boom"),
                    // The whole kit through a gated room: the sound of the era.
                    effects = listOf(fx("Reverb", "Gated")),
                    clips = mapOf(
                        verse.id to clip(4, drums(4)),
                        chorus.id to clip(4, drums(4, crash = true)),
                    ),
                    mixer = Mixer(volume = 0.80f),
                ),
                Track(
                    id = "t-bass", name = "Bass",
                    machine = machine("Ratio", "Grit Bass"),
                    swing = SWING_STRAIGHT,
                    clips = mapOf(verse.id to clip(4, bass(4)), chorus.id to clip(4, bass(4))),
                    mixer = Mixer(volume = 0.70f),
                ),
                Track(
                    id = "t-arp", name = "Arp",
                    machine = machine("Trinity", "Pluck Wide"),
                    effects = listOf(fx("Delay", "Ping Pong")),
                    clips = mapOf(
                        intro.id to clip(4, arp(4)),
                        verse.id to clip(4, arp(4)),
                        chorus.id to clip(4, arp(4)),
                        outro.id to clip(4, arp(4)),
                    ),
                    mixer = Mixer(volume = 0.38f, pan = 0.15f, sendReverb = 0.20f),
                ),
                Track(
                    id = "t-strings", name = "Strings",
                    machine = machine("Trinity", "Analog Strings"),
                    effects = listOf(fx("Chorus", "Lush"), fx("Width", "Wide")),
                    clips = mapOf(
                        intro.id to clip(4, pads(4)),
                        verse.id to clip(4, pads(4)),
                        chorus.id to clip(4, pads(4)),
                        outro.id to clip(4, pads(4)),
                    ),
                    mixer = Mixer(volume = 0.40f, sendReverb = 0.30f),
                ),
                Track(
                    id = "t-lead", name = "Lead",
                    machine = machine("Trinity", "Saw Lead"),
                    effects = listOf(fx("Delay", "Tape")),
                    clips = mapOf(chorus.id to clip(4, lead()), outro.id to clip(4, lead())),
                    mixer = Mixer(volume = 0.46f, pan = -0.1f, sendReverb = 0.26f, sendDelay = 0.12f),
                ),
            ),
            scenes = listOf(intro, verse, chorus, outro),
            master = Master(
                volume = 0.64f,
                sends = listOf(fx("Reverb", "Hall"), fx("Delay", "Eighth Sync")),
                inserts = listOf(fx("Eq", "Brighter"), fx("Compressor", "Glue")),
            ),
        )
    }
}
