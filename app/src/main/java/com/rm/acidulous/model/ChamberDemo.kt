package com.rm.acidulous.model

import com.rm.acidulous.model.DemoKit.E
import com.rm.acidulous.model.DemoKit.Q
import com.rm.acidulous.model.DemoKit.clip
import com.rm.acidulous.model.DemoKit.fx
import com.rm.acidulous.model.DemoKit.machine
import com.rm.acidulous.model.DemoKit.ramp
import com.rm.acidulous.model.DemoKit.vibrato

/**
 * Lantern: a small chamber piece at 72, in three, in D Dorian.
 *
 * What it shows:
 *
 *  - **The modelled instruments**: a plucked string and a bowed one
 *    (Filament), a clarinet and a flute (Timber), and a horn (Brazen).
 *  - **A song in 3/4**, and a key whose bright sixth - the B natural in the
 *    G chord - is the whole colour of the mode.
 *  - **Automation of how hard a horn is blown**: its swells are a pressure
 *    lane, which changes the tone as well as the level.
 *  - **Vibrato drawn into notes** on the strings and the clarinet.
 *  - **A ritardando and a fade**: the last scene slows to 60 and fades out.
 *  - **Trig conditions** on the glockenspiel, so it is never the same twice.
 */
internal object ChamberDemo {
    /** A bar of three. */
    private const val BAR3 = 3 * Q

    /** Dm, G, Dm, C: the harp's broken chords, root first. */
    private val HARP = listOf(
        listOf(50, 57, 62, 65, 62, 57),
        listOf(43, 50, 55, 59, 55, 50),
        listOf(50, 57, 62, 65, 62, 57),
        listOf(48, 55, 60, 64, 60, 55),
    )

    private fun harp(bars: Int): List<Note> = (0 until bars).flatMap { b ->
        HARP[b % 4].mapIndexed { i, p -> Note(b * BAR3 + i * E, E + 60, p, if (i == 0) 92 else 70) }
    }

    /** The strings: one long inner voice a bar, with a vibrato that grows. */
    private fun strings(bars: Int): List<Note> = (0 until bars).map { b ->
        val length = BAR3 - 10
        Note(b * BAR3, length, listOf(57, 59, 57, 55)[b % 4], 80, bend = vibrato(length, delay = Q, depth = 0.2f, period = E + 40))
    }

    private fun clarinet(): List<Note> {
        val last = 3 * Q - 10
        return listOf(
            Note(0, Q, 69, 90), Note(Q, Q + E, 74, 100), Note(2 * Q + E, E, 72, 86),
            Note(BAR3, 2 * Q, 71, 96), Note(BAR3 + 2 * Q, Q, 69, 88),
            Note(2 * BAR3, E, 65, 84), Note(2 * BAR3 + E, E, 67, 86), Note(2 * BAR3 + Q, Q, 69, 92), Note(2 * BAR3 + 2 * Q, Q, 74, 98),
            Note(3 * BAR3, last, 76, 100, bend = vibrato(last, delay = Q, depth = 0.25f)),
        )
    }

    /** The flute's answer, above the clarinet. */
    private fun flute(): List<Note> = listOf(
        Note(0, Q, 77, 90), Note(Q, Q, 76, 86), Note(2 * Q, Q, 74, 88),
        Note(BAR3, 2 * Q, 74, 94), Note(BAR3 + 2 * Q, Q, 71, 84),
        Note(2 * BAR3, 3 * Q - 10, 81, 100),
        Note(3 * BAR3, Q, 79, 92), Note(3 * BAR3 + Q, Q, 76, 88), Note(3 * BAR3 + 2 * Q, Q, 72, 90),
    )

    /** The horn: two long tones, each two bars. */
    private fun horn(): List<Note> = listOf(Note(0, 2 * BAR3 - 20, 50, 90), Note(2 * BAR3, 2 * BAR3 - 20, 48, 90))

    /**
     * Swells: blown up to the middle of each tone and let down again. Around
     * the patch's own 0.85 rather than from nothing, because a horn blown too
     * softly does not speak at all.
     */
    private val HORN_SWELLS = ramp(
        0 to 0.62f, BAR3 to 0.92f, 2 * BAR3 - 30 to 0.62f,
        2 * BAR3 to 0.62f, 3 * BAR3 to 0.95f, 4 * BAR3 - 30 to 0.62f,
    )

    /**
     * The glockenspiel: a pad a beat, but each only some of the time, and two
     * of them only every other pass.
     */
    private fun glock(bars: Int): List<Note> = (0 until bars).flatMap { b ->
        listOf(
            Note(b * BAR3, E, 36 + (b * 3) % 8, 70, chance = 70),
            Note(b * BAR3 + Q + E, E, 36 + (b * 5 + 2) % 8, 56, trig = if (b % 2 == 0) Trig.N1of2 else Trig.N2of2),
            Note(b * BAR3 + 2 * Q, E, 36 + (b * 7 + 4) % 8, 50, chance = 40),
        )
    }

    /** The drone: D and A, the whole scene long. */
    private fun drone(bars: Int): List<Note> = listOf(Note(0, bars * BAR3 - 20, 38, 70), Note(0, bars * BAR3 - 20, 45, 64))

    fun build(): Song {
        val dusk = Scene(id = "s-dusk", name = "Dusk")
        val tune = Scene(id = "s-tune", name = "Tune", repeat = 2)
        val answer = Scene(id = "s-answer", name = "Answer", repeat = 2)
        val last = Scene(id = "s-last", name = "Last Light", tempo = SceneTempo(bpm = 60f, smooth = true), fadeOut = true)

        return Song(
            name = "Lantern",
            tempo = 72f,
            signature = Signature(3, 4),
            key = SongKey(root = 2, scale = 1), // D Dorian
            loopSong = true,
            tracks = listOf(
                Track(
                    id = "t-harp", name = "Harp",
                    machine = machine("Filament", "Nylon"),
                    clips = mapOf(
                        dusk.id to clip(4, harp(4)),
                        tune.id to clip(4, harp(4)),
                        answer.id to clip(4, harp(4)),
                        last.id to clip(4, harp(4)),
                    ),
                    mixer = Mixer(volume = 0.62f, pan = -0.2f, sendReverb = 0.30f),
                ),
                Track(
                    id = "t-strings", name = "Strings",
                    machine = machine("Filament", "Bowed"),
                    clips = mapOf(dusk.id to clip(4, strings(4)), answer.id to clip(4, strings(4)), last.id to clip(4, strings(4))),
                    mixer = Mixer(volume = 0.48f, pan = 0.25f, sendReverb = 0.40f),
                ),
                Track(
                    id = "t-clarinet", name = "Clarinet",
                    machine = machine("Timber", "Clarinet"),
                    clips = mapOf(tune.id to clip(4, clarinet()), last.id to clip(4, clarinet())),
                    mixer = Mixer(volume = 0.58f, pan = 0.1f, sendReverb = 0.34f, sendDelay = 0.08f),
                ),
                Track(
                    id = "t-flute", name = "Flute",
                    machine = machine("Timber", "Flute"),
                    clips = mapOf(answer.id to clip(4, flute())),
                    mixer = Mixer(volume = 0.46f, pan = -0.3f, sendReverb = 0.40f, sendDelay = 0.12f),
                ),
                Track(
                    id = "t-horn", name = "Horn",
                    machine = machine("Brazen", "Horn"),
                    clips = mapOf(
                        answer.id to clip(4, horn()) { copy(automation = mapOf(laneKey("machine", "pressure") to HORN_SWELLS)) },
                    ),
                    mixer = Mixer(volume = 0.44f, pan = 0.35f, sendReverb = 0.44f),
                ),
                Track(
                    id = "t-glock", name = "Glock",
                    machine = machine("Resonance", "Glockenspiel"),
                    clips = mapOf(answer.id to clip(4, glock(4)) { copy(freeRoll = true) }),
                    mixer = Mixer(volume = 0.30f, pan = 0.4f, sendReverb = 0.46f, sendDelay = 0.20f),
                ),
                Track(
                    id = "t-drone", name = "Drone",
                    machine = machine("Cumulus", "Warm Bed"),
                    clips = mapOf(
                        dusk.id to clip(4, drone(4)),
                        tune.id to clip(4, drone(4)),
                        answer.id to clip(4, drone(4)),
                        last.id to clip(4, drone(4)),
                    ),
                    mixer = Mixer(volume = 0.30f, sendReverb = 0.30f),
                ),
            ),
            scenes = listOf(dusk, tune, answer, last),
            master = Master(
                // About -15 LUFS on an export: a little under the others, as
                // a quiet piece with a fade in it should be.
                volume = 0.64f,
                sends = listOf(fx("Reverb", "Hall"), fx("Delay", "Tape")),
                inserts = listOf(fx("Eq", "Warmer")),
            ),
        )
    }
}
