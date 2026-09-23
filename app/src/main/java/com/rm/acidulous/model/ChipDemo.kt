package com.rm.acidulous.model

import com.rm.acidulous.model.DemoKit.BAR
import com.rm.acidulous.model.DemoKit.E
import com.rm.acidulous.model.DemoKit.Q
import com.rm.acidulous.model.DemoKit.S
import com.rm.acidulous.model.DemoKit.clip
import com.rm.acidulous.model.DemoKit.fx
import com.rm.acidulous.model.DemoKit.machine

/**
 * Cartridge: chiptune at 150, in C major, with a boss fight in 7/8.
 *
 * What it shows:
 *
 *  - **Formulate on every track**: pulse lead, triangle bass, three noise
 *    drums, and an arpeggio that is the machine's own table - one held note
 *    per chord and the table does the rest.
 *  - **A formula**: the boss scene has a voice whose waveform is an
 *    expression, not a table.
 *  - **A scene in its own time signature**: the boss is in 7/8, counted
 *    2 + 2 + 3, and the song goes back to 4/4 after it.
 *  - **Ratchets, chances and every-other-pass notes** on the drums.
 */
internal object ChipDemo {

    /** C, Am, F, G. */
    private val ROOTS = listOf(48, 45, 41, 43)

    /** 7/8: seven eighths a bar. */
    private const val BAR7 = 7 * E

    private fun lead(): List<Note> = listOf(
        Note(0, E, 76, 100), Note(E, E, 79, 92), Note(Q, Q, 84, 104), Note(2 * Q, E, 83, 90), Note(2 * Q + E, E, 79, 88),
        Note(3 * Q, Q, 76, 96),
        Note(BAR, Q, 81, 100), Note(BAR + Q, E, 76, 88), Note(BAR + Q + E, E, 81, 92), Note(BAR + 2 * Q, Q, 84, 100),
        Note(BAR + 3 * Q, Q, 83, 94),
        Note(2 * BAR, E, 81, 96), Note(2 * BAR + E, E, 79, 88), Note(2 * BAR + Q, Q, 77, 94), Note(2 * BAR + 2 * Q, E, 81, 90),
        Note(2 * BAR + 2 * Q + E, E, 84, 96), Note(2 * BAR + 3 * Q, Q, 86, 104),
        Note(3 * BAR, Q, 86, 100), Note(3 * BAR + Q, E, 83, 90), Note(3 * BAR + Q + E, E, 79, 88), Note(3 * BAR + 2 * Q, Q, 74, 94),
        Note(3 * BAR + 3 * Q, Q, 79, 96),
    )

    /** The arpeggio: one note per chord, held, and the table plays it. */
    private fun arp(bars: Int): List<Note> = (0 until bars).map { b -> Note(b * BAR, BAR - 12, ROOTS[b % ROOTS.size] + 24, 90) }

    private fun bass(bars: Int): List<Note> = (0 until bars).flatMap { b ->
        val root = ROOTS[b % ROOTS.size] - 12
        listOf(0, 0, 12, 0, 7, 0, 12, 7).mapIndexed { i, step -> Note(b * BAR + i * E, E - 20, root + step, if (i % 2 == 0) 100 else 80) }
    }

    private fun kick(bars: Int): List<Note> = (0 until bars).flatMap { b ->
        listOf(Note(b * BAR, E, 36, 110), Note(b * BAR + 2 * Q, E, 36, 104), Note(b * BAR + 3 * Q + E, E, 36, 90, chance = 50))
    }

    private fun snare(bars: Int): List<Note> = (0 until bars).flatMap { b ->
        val o = b * BAR
        val base = listOf(Note(o + Q, E, 48, 104), Note(o + 3 * Q, E, 48, 108))
        // Every other pass the last bar rolls: a ratchet, then one to lead in.
        if (b == bars - 1) base + Note(o + 3 * Q + E, E, 48, 90, ratchet = 3, trig = Trig.N2of2) else base
    }

    private fun hats(bars: Int): List<Note> = (0 until bars).flatMap { b ->
        (0 until 8).map { i -> Note(b * BAR + i * E, S, 60, if (i % 2 == 0) 80 else 64, chance = if (i % 2 == 0) 100 else 80) }
    }

    // --- the boss, in 7/8 -------------------------------------------------------

    private fun bossLead(bars: Int): List<Note> = (0 until bars).flatMap { b ->
        listOf(81, 84, 88, 84, 81, 76, 79).mapIndexed { i, p -> Note(b * BAR7 + i * E, E - 10, p, if (i == 0 || i == 2 || i == 4) 100 else 84) }
    }

    /** A, then F, then E twice: the fight does not resolve. */
    private val BOSS_ROOTS = listOf(33, 29, 28, 28)

    private fun bossBass(bars: Int): List<Note> = (0 until bars).flatMap { b ->
        (0 until 7).map { i -> Note(b * BAR7 + i * E, E - 16, BOSS_ROOTS[b % 4] + if (i == 4) 12 else 0, if (i % 2 == 0) 104 else 84) }
    }

    /** Counted 2 + 2 + 3: kicks on the groups, snares between. */
    private fun bossKick(bars: Int): List<Note> = (0 until bars).flatMap { b ->
        listOf(0, 4).map { Note(b * BAR7 + it * E, E, 36, 112) }
    }

    private fun bossSnare(bars: Int): List<Note> = (0 until bars).flatMap { b ->
        listOf(Note(b * BAR7 + 2 * E, E, 48, 104), Note(b * BAR7 + 6 * E, E, 48, 96, ratchet = 2))
    }

    private fun bossHats(bars: Int): List<Note> = (0 until bars).flatMap { b ->
        (0 until 7).map { i -> Note(b * BAR7 + i * E, S, 60, if (i == 0 || i == 2 || i == 4) 84 else 60) }
    }

    /** The formula voice: one long note a bar under the fight, two octaves over the bass. */
    private fun formula(bars: Int): List<Note> = (0 until bars).flatMap { b ->
        listOf(Note(b * BAR7, BAR7 - 12, BOSS_ROOTS[b % 4] + 24, 84))
    }

    fun build(): Song {
        val title = Scene(id = "s-title", name = "Title")
        val level = Scene(id = "s-level", name = "Level", repeat = 2)
        val boss = Scene(id = "s-boss", name = "Boss", signature = Signature(7, 8), repeat = 2)

        return Song(
            name = "Cartridge",
            tempo = 150f,
            key = SongKey(root = 0, scale = 0), // C Ionian
            loopSong = true,
            tracks = listOf(
                Track(
                    id = "t-lead", name = "Lead",
                    machine = machine("Formulate", "Pulse Lead"),
                    effects = listOf(fx("Delay", "Eighth Sync")),
                    clips = mapOf(level.id to clip(4, lead()), boss.id to clip(4, bossLead(4))),
                    mixer = Mixer(volume = 0.50f, pan = 0.1f, sendReverb = 0.10f),
                ),
                Track(
                    id = "t-arp", name = "Arp",
                    machine = machine("Formulate", "Octave Trill"),
                    clips = mapOf(title.id to clip(4, arp(4)), level.id to clip(4, arp(4))),
                    mixer = Mixer(volume = 0.36f, pan = -0.25f, sendReverb = 0.14f),
                ),
                Track(
                    id = "t-bass", name = "Bass",
                    machine = machine("Formulate", "Triangle Bass"),
                    clips = mapOf(title.id to clip(4, bass(4)), level.id to clip(4, bass(4)), boss.id to clip(4, bossBass(4))),
                    mixer = Mixer(volume = 0.66f),
                ),
                Track(
                    id = "t-kick", name = "Kick",
                    machine = machine("Formulate", "Noise Kick"),
                    clips = mapOf(level.id to clip(4, kick(4)), boss.id to clip(4, bossKick(4))),
                    mixer = Mixer(volume = 0.66f),
                ),
                Track(
                    id = "t-snare", name = "Snare",
                    machine = machine("Formulate", "Noise Snare"),
                    clips = mapOf(level.id to clip(4, snare(4)), boss.id to clip(4, bossSnare(4))),
                    mixer = Mixer(volume = 0.52f, sendReverb = 0.12f),
                ),
                Track(
                    id = "t-hats", name = "Hats",
                    machine = machine("Formulate", "Noise Hat"),
                    clips = mapOf(title.id to clip(4, hats(4)), level.id to clip(4, hats(4)), boss.id to clip(4, bossHats(4))),
                    mixer = Mixer(volume = 0.34f, pan = 0.2f),
                ),
                Track(
                    id = "t-formula", name = "Formula",
                    machine = machine("Formulate", "Bit Melody"),
                    clips = mapOf(boss.id to clip(4, formula(4))),
                    mixer = Mixer(volume = 0.30f, pan = -0.2f, sendDelay = 0.18f),
                ),
            ),
            scenes = listOf(title, level, boss),
            master = Master(
                // Evened with the other demos on an export, about -14 LUFS.
                volume = 0.90f,
                sends = listOf(fx("Reverb", "Room"), fx("Delay", "Slapback")),
                inserts = listOf(fx("Compressor", "Gentle")),
            ),
        )
    }
}
