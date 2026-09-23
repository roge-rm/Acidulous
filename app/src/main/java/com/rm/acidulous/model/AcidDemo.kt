package com.rm.acidulous.model

import com.rm.acidulous.model.DemoKit.BAR
import com.rm.acidulous.model.DemoKit.E
import com.rm.acidulous.model.DemoKit.Q
import com.rm.acidulous.model.DemoKit.S
import com.rm.acidulous.model.DemoKit.chord
import com.rm.acidulous.model.DemoKit.clip
import com.rm.acidulous.model.DemoKit.duckUnder
import com.rm.acidulous.model.DemoKit.fx
import com.rm.acidulous.model.DemoKit.machine
import com.rm.acidulous.model.DemoKit.ramp
import com.rm.acidulous.model.DemoKit.steps

/**
 * Squelch: acid house at 126, in A minor.
 *
 * What it shows:
 *
 *  - **Reflux the way it is meant to be played**: accents are velocity and
 *    slides are notes that overlap the next one, so the line is written, not
 *    programmed with special steps.
 *  - **A filter sweep** drawn as a lane over the groove, and pulled back down
 *    in the break.
 *  - **Fill**: the snare rolls are fill-only notes, so they play while fill is
 *    held on the live page and not otherwise.
 *  - **The perform pages, recorded**: a riser and a gate over the end of the
 *    break, a sixteenth repeat into the loop point of the drop, and an echo
 *    throw at the end of the groove. They are lanes on the drums' clips, the
 *    same as what recording a performance writes.
 *  - **A pad ducked under the kick** by a sidechain.
 */
internal object AcidDemo {
    // Hexbeat: kick, rim, snare, clap, three toms, closed and open hat.
    private const val KICK = 36
    private const val RIM = 37
    private const val SNARE = 38
    private const val CLAP = 39
    private const val HAT = 43
    private const val OPEN_HAT = 44

    /**
     * Four to the floor, claps on two and four, sixteenth hats that lean on
     * the offbeat. [kick] off for the break. A fill-only snare roll over the
     * last beat of every four bars.
     */
    private fun drums(bars: Int, kick: Boolean = true, openHats: Boolean = true): List<Note> {
        val out = ArrayList<Note>()
        for (b in 0 until bars) {
            val o = b * BAR
            for (beat in 0 until 4) {
                if (kick) out += Note(o + beat * Q, 60, KICK, 120)
                if (beat % 2 == 1) out += Note(o + beat * Q, 60, CLAP, 100)
                for (s in 0 until 4) out += Note(o + beat * Q + s * S, 30, HAT, listOf(58, 36, 92, 40)[s])
                if (openHats) out += Note(o + beat * Q + E, 50, OPEN_HAT, 70)
            }
            out += Note(o + 3 * Q + 3 * S, 30, RIM, 70, chance = 60)
            if (b % 4 == 3) {
                for (s in 0 until 4) out += Note(o + 3 * Q + s * S, S - 10, SNARE, 60 + s * 16, trig = Trig.Fill, ratchet = if (s == 3) 2 else 1)
            }
        }
        return out
    }

    /**
     * The acid line, two bars of sixteenths. Each step is a pitch, or null for
     * a rest; `!` accents it and `~` slides into the next step.
     */
    private val LINE = listOf(
        "A1!", "A1", "A2~", "A1", null, "C2", "A1!", "G2~", "A2", null, "E2!", "A1", "C3~", "A2", "G2!", "E2",
        "A1!", null, "A2", "A1~", "C2", "D2", "A1!", "A2~", "G2", null, "E2!", "E2", "A2~", "G2", "E2!", "D2",
    )

    private fun pitchOf(name: String): Int {
        val letter = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)[name[0]]!!
        return 12 * (name[1].digitToInt() + 1) + letter
    }

    private fun acid(bars: Int): List<Note> {
        val out = ArrayList<Note>()
        for (pair in 0 until bars step 2) {
            for ((i, step) in LINE.withIndex()) {
                if (step == null) continue
                val bar = pair + i / 16
                if (bar >= bars) break
                val slide = step.endsWith("~")
                val accent = step.endsWith("!")
                val pitch = pitchOf(step.trimEnd('!', '~'))
                // A slide is a note that runs past the start of the next.
                val length = if (slide) S + 24 else S - 30
                out += Note(bar * BAR + (i % 16) * S, length, pitch, if (accent) 122 else 82)
            }
        }
        return out.filter { it.tick < bars * BAR }
    }

    /** Stabs on the offbeats: Am7, then G. */
    private fun stabs(bars: Int): List<Note> = (0 until bars).flatMap { b ->
        val voicing = if (b % 2 == 0) listOf(57, 60, 64, 67) else listOf(55, 59, 62, 67)
        chord(b * BAR + E, S, voicing, 96) + chord(b * BAR + 2 * Q + E, S, voicing, 88) +
            chord(b * BAR + 3 * Q + E + S, S, voicing, 76)
    }

    /** One long Am9 under everything. */
    private fun pad(bars: Int): List<Note> = chord(0, bars * BAR - 20, listOf(57, 60, 64, 67, 71), 70)

    fun build(): Song {
        val intro = Scene(id = "s-intro", name = "Intro", repeat = 2)
        val groove = Scene(id = "s-groove", name = "Groove")
        val breakdown = Scene(id = "s-break", name = "Break")
        val drop = Scene(id = "s-drop", name = "Drop", repeat = 2)

        // The cutoff, normalised: the patch sits near 0.41.
        val sweepUp = ramp(0 to 0.28f, 4 * BAR - 1 to 0.62f)
        val sweepBreak = ramp(0 to 0.60f, 2 * BAR to 0.22f, 4 * BAR - 1 to 0.55f)
        val resonanceUp = ramp(0 to 0.70f, 3 * BAR to 0.70f, 4 * BAR - 1 to 0.86f)

        // The performance, as lanes on the drums' clips. Each starts and
        // ends at rest, so nothing is left held when the scene moves on.
        val throwAtTheEnd = mapOf(
            laneKey("perform", "y") to steps(0 to 0f, 4 * BAR - Q to 0.75f, 4 * BAR - 10 to 0f),
        )
        val buildUp = mapOf(
            laneKey("perform", "riser") to steps(0 to 0f, 2 * BAR to 1f, 4 * BAR - 10 to 0f),
            laneKey("perform", "gate") to steps(0 to 0f, 3 * BAR to 0.4f, 4 * BAR - 10 to 0f), // sixteenths
        )
        val stutter = mapOf(
            laneKey("perform", "repeat") to steps(0 to 0f, 4 * BAR - 2 * Q to 0.8f, 4 * BAR - Q to 1f, 4 * BAR - 10 to 0f),
        )

        return Song(
            name = "Squelch",
            tempo = 126f,
            swing = 54f,
            key = SongKey(root = 9, scale = 5), // A Aeolian
            loopSong = true,
            tracks = listOf(
                Track(
                    id = "t-drums", name = "Drums",
                    machine = machine("Hexbeat", "Punchy"),
                    clips = mapOf(
                        intro.id to clip(2, drums(2, openHats = false)),
                        groove.id to clip(4, drums(4)) { copy(automation = throwAtTheEnd) },
                        breakdown.id to clip(4, drums(4, kick = false, openHats = false)) { copy(automation = buildUp) },
                        drop.id to clip(4, drums(4)) { copy(automation = stutter) },
                    ),
                    mixer = Mixer(volume = 0.86f, sendReverb = 0.08f),
                ),
                Track(
                    id = "t-acid", name = "Acid",
                    machine = machine("Reflux", "Classic"),
                    effects = listOf(fx("Distortion", "Warm"), fx("Delay", "Eighth Sync")),
                    clips = mapOf(
                        intro.id to clip(2, acid(2)),
                        groove.id to clip(4, acid(4)) { copy(automation = mapOf(laneKey("machine", "cutoff") to sweepUp)) },
                        breakdown.id to clip(4, acid(4)) {
                            copy(
                                automation = mapOf(
                                    laneKey("machine", "cutoff") to sweepBreak,
                                    laneKey("machine", "resonance") to resonanceUp,
                                ),
                            )
                        },
                        drop.id to clip(4, acid(4)),
                    ),
                    mixer = Mixer(volume = 0.78f, sendReverb = 0.06f, sendDelay = 0.12f),
                ),
                Track(
                    id = "t-stabs", name = "Stabs",
                    machine = machine("Ratio", "Sync Stab"),
                    clips = mapOf(groove.id to clip(2, stabs(2)), drop.id to clip(2, stabs(2))),
                    mixer = Mixer(volume = 0.44f, pan = 0.2f, sendReverb = 0.28f, sendDelay = 0.22f),
                ),
                Track(
                    id = "t-pad", name = "Pad",
                    machine = machine("Cumulus", "Deep Wash"),
                    // Ducked under the kick: the pad breathes with the drums.
                    effects = listOf(duckUnder(track = 1)),
                    clips = mapOf(breakdown.id to clip(4, pad(4)), drop.id to clip(4, pad(4))),
                    mixer = Mixer(volume = 0.46f, pan = -0.15f, sendReverb = 0.30f),
                ),
            ),
            scenes = listOf(intro, groove, breakdown, drop),
            master = Master(
                // Evened with the other demos on an export, about -14 LUFS.
                volume = 0.72f,
                sends = listOf(fx("Reverb", "Room"), fx("Delay", "Eighth Sync")),
                inserts = listOf(fx("Compressor", "Bus")),
                // The riser climbs over two bars, the length of its lane.
                perform = PerformSettings(riserLen = 1),
            ),
        )
    }
}
