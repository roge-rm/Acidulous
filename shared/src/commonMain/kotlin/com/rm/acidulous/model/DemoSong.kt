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
 * Squelch, the song a first run opens: acid house at 126, in A minor, and the
 * one demo. It is the fastest way to find out what is here, so it puts a
 * working example of most ideas where a person will meet them:
 *
 *  - **Reflux the way it is meant to be played**: accents are velocity and
 *    slides are notes that overlap the next one, so the line is written, not
 *    programmed with special steps. Two of them, the second answering the
 *    first in the drop.
 *  - **Automation**: the filter opened over the groove and pulled shut over
 *    the outro, the resonance swelling through the break - and **step locks**,
 *    a longer decay on the accents of the drop.
 *  - **The perform pages, recorded**: an echo throw, a riser and a gate over
 *    the build, a sixteenth repeat into the end of the drop. They are lanes on
 *    the drums' clips, the same as what recording a performance writes.
 *  - **Fill**: snare rolls that play only while fill is held.
 *  - **Trig conditions**: percussion that is never the same bar twice -
 *    chances, a ratchet, and a clave that lands on every other pass.
 *  - **A modifier**: the arp is a held chord, turned into sixteenths by an Arp
 *    on the way in.
 *  - **A one-shot clip**: one stab into the break that is left to ring.
 *  - **Note expression**: the riser is one held note, and all of it is a bend.
 *  - **The modular**: the bleeps are Nexus, a patch you can open and rewire.
 *  - **A sidechain, a group and master inserts**: the pad ducks under the
 *    kick, the drums and the percussion share a glue compressor, and the mix
 *    goes through an EQ and a compressor before the limiter.
 *
 * Nothing here needs a file on disk, so it plays on a phone that has never
 * recorded anything.
 *
 * **i - VI - VII - v**, Am F G Em, under a line that stays on A: the chords
 * move and the acid does not, which is the style.
 */
object DemoSong {

    // Genesis: kick, snare, clap, rim, three toms, hats, crash, ride, cowbell.
    private const val KICK = 36
    private const val SNARE = 37
    private const val CLAP = 38
    private const val RIM = 39
    private const val HAT = 43
    private const val OPEN_HAT = 44
    private const val CRASH = 45
    private const val RIDE = 46

    // Hexbeat, for the percussion: rim, cowbell, clave.
    private const val P_RIM = 37
    private const val P_COWBELL = 47
    private const val P_CLAVE = 48

    /** The mixer group the drums and the percussion play through: group 1. */
    private const val RHYTHM = 1

    /** Am9, Fmaj7, G6, Em7, a bar each, in the middle of the keyboard. */
    private val PROGRESSION = listOf(
        listOf(57, 60, 64, 67, 71),
        listOf(53, 57, 60, 64),
        listOf(55, 59, 62, 64),
        listOf(52, 55, 59, 62),
    )

    /** The same chords, four notes and closer, for short stabs. */
    private val STABS = listOf(
        listOf(57, 60, 64, 67),
        listOf(53, 57, 60, 64),
        listOf(55, 59, 62, 67),
        listOf(55, 59, 62, 64),
    )

    // --- the drums -------------------------------------------------------------------

    /**
     * Four to the floor, claps on two and four, sixteenth hats leaning on the
     * offbeat, and a fill-only snare roll over the last beat of every four
     * bars. The switches take things away for the quieter scenes; the drop
     * adds a ride and opens with a crash.
     */
    private fun drums(
        bars: Int,
        kick: Boolean = true,
        clap: Boolean = true,
        openHats: Boolean = true,
        ride: Boolean = false,
        crash: Boolean = false,
    ): List<Note> {
        val out = ArrayList<Note>()
        for (b in 0 until bars) {
            val o = b * BAR
            for (beat in 0 until 4) {
                if (kick) out += Note(o + beat * Q, 60, KICK, 120)
                if (clap && beat % 2 == 1) out += Note(o + beat * Q, 60, CLAP, 100)
                for (s in 0 until 4) out += Note(o + beat * Q + s * S, 30, HAT, listOf(58, 36, 92, 40)[s])
                if (openHats) out += Note(o + beat * Q + E, 50, OPEN_HAT, 70)
                if (ride) out += Note(o + beat * Q, 60, RIDE, 64)
            }
            out += Note(o + 3 * Q + 3 * S, 30, RIM, 70, chance = 60)
            if (b % 4 == 3) {
                for (s in 0 until 4) {
                    out += Note(o + 3 * Q + s * S, S - 10, SNARE, 60 + s * 16, trig = Trig.Fill, ratchet = if (s == 3) 2 else 1)
                }
            }
        }
        if (crash) out += Note(0, 2 * Q, CRASH, 100)
        return out
    }

    /**
     * The build: no kick for a bar, then kicks on the eighths, under a snare
     * that goes from quarters to sixteenths and a ratchet at the very end.
     */
    private fun buildUp(): List<Note> {
        val out = ArrayList<Note>()
        for (i in 0 until 4) out += Note(i * Q, 40, SNARE, 70 + i * 4)
        for (i in 0 until 8) out += Note(BAR + i * E, 60, KICK, 110)
        for (i in 0 until 12) out += Note(BAR + i * S, 30, SNARE, 80 + i * 3)
        out += Note(BAR + 3 * Q, S, SNARE, 124, ratchet = 4)
        for (b in 0 until 2) for (s in 0 until 16) out += Note(b * BAR + s * S, 30, HAT, if (s % 2 == 1) 70 + b * 20 else 40)
        return out
    }

    /**
     * Percussion that is never the same bar twice: a cowbell that turns up
     * seven times in ten, a rim that ratchets, and a clave on the tresillo
     * whose last hit lands only on the first of every two passes. The clip
     * rolls free, so the chances are drawn again each time round.
     */
    private fun percussion(bars: Int): List<Note> {
        val out = ArrayList<Note>()
        for (b in 0 until bars) {
            val o = b * BAR
            out += Note(o, 40, P_CLAVE, 90)
            out += Note(o + Q + E, 40, P_CLAVE, 84)
            out += Note(o + 3 * Q, 40, P_CLAVE, 88, trig = Trig.N1of2)
            out += Note(o + E, 40, P_COWBELL, 70, chance = 70)
            out += Note(o + 2 * Q + E, 40, P_COWBELL, 76, chance = 70)
            out += Note(o + 3 * Q + S * 3, 30, P_RIM, 72, ratchet = 2)
            out += Note(o + Q + 3 * S, 30, P_RIM, 54, chance = 40)
        }
        return out
    }

    // --- the acid --------------------------------------------------------------------

    /**
     * The acid lines, two bars of sixteenths. Each step is a pitch, or null
     * for a rest; `!` accents it and `~` slides into the next step.
     */
    private val LINE = listOf(
        "A1!", "A1", "A2~", "A1", null, "C2", "A1!", "G2~", "A2", null, "E2!", "A1", "C3~", "A2", "G2!", "E2",
        "A1!", null, "A2", "A1~", "C2", "D2", "A1!", "A2~", "G2", null, "E2!", "E2", "A2~", "G2", "E2!", "D2",
    )

    /** The drop's, busier at the top: the same shape, pushed up and leaning on the accents. */
    private val LINE_DROP = listOf(
        "A1!", "A2", "A2~", "C3", null, "A1", "A2!", "G2~", "A2", "C3", "E3!", "A1", "C3~", "D3", "G2!", "E2",
        "A1!", "A1", "A2~", "C3~", "D3", "C3", "A1!", "A2~", "G2", null, "E3!", "D3", "C3~", "A2", "G2!", "E2~",
    )

    /** The answer, high and sparse, in the gaps the first line leaves. */
    private val ANSWER = listOf(
        null, null, "E3!", null, null, "G3~", "A3", null, null, null, "C4!", null, "A3~", "G3", null, null,
        null, null, "E3!", null, "D3~", "E3", null, null, "G3!", null, "A3~", "C4", null, "A3!", null, null,
    )

    private fun pitchOf(name: String): Int {
        val letter = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)[name[0]]!!
        return 12 * (name[1].digitToInt() + 1) + letter
    }

    private fun acid(bars: Int, line: List<String?> = LINE): List<Note> {
        val out = ArrayList<Note>()
        for (pair in 0 until bars step 2) {
            for ((i, step) in line.withIndex()) {
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

    /**
     * The drop's decay, locked longer on its accented steps: those notes ring
     * and the rest stay short. A lock follows the knob everywhere else, so
     * turning decay for the whole clip still moves every other step.
     */
    private fun decayLocks(bars: Int): Lane? {
        val accents = acid(bars, LINE_DROP).filter { it.velocity > 100 }
        return Locks.lane(accents.map { Lock(it.tick, it.tick + S, 0.72f) }, bars * BAR)
    }

    // --- the chords, the arp and the pad ----------------------------------------------

    /** Stabs on the offbeats, a chord a bar. */
    private fun stabs(bars: Int): List<Note> = (0 until bars).flatMap { b ->
        val voicing = STABS[b % STABS.size]
        chord(b * BAR + E, S, voicing, 96) + chord(b * BAR + 2 * Q + E, S, voicing, 88) +
            chord(b * BAR + 3 * Q + E + S, S, voicing, 76)
    }

    /** One long stab into the break: a one-shot clip rings once, where a loop would hit every bar. */
    private fun stabIntoTheBreak(): List<Note> = chord(0, 2 * Q, STABS[0], 110)

    /** The progression held, a chord a bar: the arp and the pad both play from it. */
    private fun held(bars: Int, velocity: Int): List<Note> = (0 until bars).flatMap { b ->
        chord(b * BAR, BAR - 20, PROGRESSION[b % PROGRESSION.size], velocity)
    }

    // --- the bleeps and the riser -----------------------------------------------------

    /** A call over the break, with room after each phrase for the echo to answer. */
    private fun bleeps(): List<Note> = listOf(
        Note(0, Q, 76, 100), Note(Q, E, 72, 84), Note(Q + E, E, 71, 80), Note(2 * Q, Q, 69, 92),
        Note(BAR, Q + E, 72, 96), Note(BAR + Q + E, E, 69, 82), Note(BAR + 2 * Q, 2 * Q, 64, 90),
        Note(2 * BAR, Q, 74, 100), Note(2 * BAR + Q, E, 71, 84), Note(2 * BAR + Q + E, E, 67, 80), Note(2 * BAR + 2 * Q, Q, 71, 92),
        Note(3 * BAR, 2 * Q, 67, 96),
    )

    /** The outro's echo of the call: two notes, falling. */
    private fun bleepsFading(): List<Note> = listOf(Note(0, Q, 76, 90), Note(2 * BAR, Q, 69, 80))

    /**
     * The riser: one note held over the build, and all of it is the bend - an
     * octave below to an octave above, climbing slowly and then all at once.
     */
    private fun riser(): List<Note> = listOf(
        Note(
            0, 2 * BAR - 20, 57, 100,
            bend = Lane(
                listOf(
                    LanePoint(0, Note.bendTo01(-12f)),
                    LanePoint(BAR, Note.bendTo01(-3f)),
                    LanePoint(2 * BAR - 20, Note.bendTo01(12f)),
                ),
            ),
        ),
    )

    // --- the song --------------------------------------------------------------------

    fun build(): Song {
        val intro = Scene(id = "s-intro", name = "Intro")
        val groove = Scene(id = "s-groove", name = "Groove", repeat = 2)
        val breakdown = Scene(id = "s-break", name = "Break")
        val rise = Scene(id = "s-build", name = "Build")
        val drop = Scene(id = "s-drop", name = "Drop", repeat = 2)
        val outro = Scene(id = "s-outro", name = "Outro")

        // The cutoff, normalised: opening from almost shut over the intro,
        // and further over the groove; pulled down and brought back in the
        // break; closing for good over the outro.
        val cutoff = laneKey("machine", "cutoff")
        val openingUp = ramp(0 to 0.12f, 4 * BAR - 1 to 0.34f)
        val sweepUp = ramp(0 to 0.28f, 4 * BAR - 1 to 0.62f)
        val sweepBreak = ramp(0 to 0.60f, 2 * BAR to 0.22f, 4 * BAR - 1 to 0.55f)
        val resonanceUp = ramp(0 to 0.70f, 3 * BAR to 0.70f, 4 * BAR - 1 to 0.86f)
        val closing = ramp(0 to 0.58f, 4 * BAR - 1 to 0.10f)

        // The performance, as lanes on the drums' clips. Each starts and
        // ends at rest, so nothing is left held when the scene moves on.
        val throwAtTheEnd = mapOf(
            laneKey("perform", "y") to steps(0 to 0f, 4 * BAR - Q to 0.75f, 4 * BAR - 10 to 0f),
        )
        val riserAndGate = mapOf(
            laneKey("perform", "riser") to steps(0 to 0f, 10 to 1f, 2 * BAR - 10 to 0f),
            laneKey("perform", "gate") to steps(0 to 0f, BAR to 0.4f, 2 * BAR - 10 to 0f), // sixteenths
        )
        val stutter = mapOf(
            laneKey("perform", "repeat") to steps(0 to 0f, 4 * BAR - 2 * Q to 0.8f, 4 * BAR - Q to 1f, 4 * BAR - 10 to 0f),
        )

        // An Arp on the way in: sixteenths, up and down, over two octaves.
        val arp = UnitSlot(
            "Arp",
            mapOf(
                "rate" to 7f / 9f,    // 1/16
                "mode" to 2f / 12f,   // up-down
                "octaves" to 1f / 3f, // two
            ),
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
                    machine = machine("Genesis", "Straight"),
                    clips = mapOf(
                        intro.id to clip(4, drums(4, clap = false, openHats = false)),
                        groove.id to clip(4, drums(4)) { copy(automation = throwAtTheEnd) },
                        breakdown.id to clip(4, drums(4, kick = false, clap = false, openHats = false)),
                        rise.id to clip(2, buildUp()) { copy(automation = riserAndGate) },
                        drop.id to clip(4, drums(4, ride = true, crash = true)) { copy(automation = stutter) },
                        outro.id to clip(4, drums(4, openHats = false)) { copy(automation = throwAtTheEnd) },
                    ),
                    mixer = Mixer(volume = 0.84f, sendReverb = 0.06f, output = RHYTHM),
                ),
                Track(
                    id = "t-perc", name = "Perc",
                    machine = machine("Hexbeat", "Tight"),
                    clips = mapOf(
                        groove.id to clip(2, percussion(2)) { copy(freeRoll = true) },
                        drop.id to clip(2, percussion(2)) { copy(freeRoll = true) },
                        outro.id to clip(2, percussion(2)) { copy(freeRoll = true) },
                    ),
                    mixer = Mixer(volume = 0.46f, pan = -0.2f, sendReverb = 0.18f, sendDelay = 0.10f, output = RHYTHM),
                ),
                Track(
                    id = "t-acid", name = "Acid",
                    machine = machine("Reflux", "Squelch"),
                    effects = listOf(fx("Distortion", "Warm"), fx("Delay", "Eighth Sync")),
                    clips = mapOf(
                        intro.id to clip(4, acid(4)) { copy(automation = mapOf(cutoff to openingUp)) },
                        groove.id to clip(4, acid(4)) { copy(automation = mapOf(cutoff to sweepUp)) },
                        breakdown.id to clip(4, acid(4)) {
                            copy(automation = mapOf(cutoff to sweepBreak, laneKey("machine", "resonance") to resonanceUp))
                        },
                        drop.id to clip(4, acid(4, LINE_DROP)) {
                            copy(automation = listOfNotNull(decayLocks(4)?.let { laneKey("machine", "decay") to it }).toMap())
                        },
                        outro.id to clip(4, acid(4)) { copy(automation = mapOf(cutoff to closing)) },
                    ),
                    mixer = Mixer(volume = 0.76f, sendReverb = 0.06f, sendDelay = 0.10f),
                ),
                Track(
                    id = "t-answer", name = "Acid 2",
                    machine = machine("Reflux", "Wasp"),
                    effects = listOf(fx("Delay", "Ping Pong")),
                    clips = mapOf(drop.id to clip(2, acid(2, ANSWER))),
                    mixer = Mixer(volume = 0.40f, pan = 0.35f, sendReverb = 0.16f, sendDelay = 0.20f),
                ),
                Track(
                    id = "t-stabs", name = "Stabs",
                    machine = machine("Ratio", "Sync Stab"),
                    clips = mapOf(
                        groove.id to clip(4, stabs(4)),
                        // Four bars of scene, one of clip, played once: the
                        // stab rings into the break and is not repeated.
                        breakdown.id to clip(1, stabIntoTheBreak()) { copy(playMode = PlayMode.OneShot) },
                        drop.id to clip(4, stabs(4)),
                    ),
                    mixer = Mixer(volume = 0.40f, pan = 0.2f, sendReverb = 0.26f, sendDelay = 0.20f),
                ),
                Track(
                    id = "t-arp", name = "Arp",
                    machine = machine("Trinity", "Pluck Wide"),
                    // Chord, scale, arp: each modifier has its own slot, and the arp's is the third.
                    modifiers = listOf(UnitSlot(), UnitSlot(), arp),
                    clips = mapOf(breakdown.id to clip(4, held(4, 88)), drop.id to clip(4, held(4, 80))),
                    mixer = Mixer(volume = 0.34f, pan = -0.3f, sendReverb = 0.24f, sendDelay = 0.18f),
                ),
                Track(
                    id = "t-pad", name = "Pad",
                    machine = machine("Cumulus", "Deep Wash"),
                    // Ducked under the kick: the pad breathes with the drums.
                    effects = listOf(duckUnder(track = 1)),
                    clips = mapOf(breakdown.id to clip(4, held(4, 72)), drop.id to clip(4, held(4, 66))),
                    mixer = Mixer(volume = 0.44f, pan = -0.1f, sendReverb = 0.30f),
                ),
                Track(
                    id = "t-bleeps", name = "Bleeps",
                    machine = machine("Nexus", "Subtractive"),
                    clips = mapOf(breakdown.id to clip(4, bleeps()), outro.id to clip(4, bleepsFading())),
                    mixer = Mixer(volume = 0.36f, pan = 0.25f, sendReverb = 0.28f, sendDelay = 0.40f),
                ),
                Track(
                    id = "t-riser", name = "Riser",
                    machine = machine("Trinity", "Noise Sweep"),
                    clips = mapOf(rise.id to clip(2, riser())),
                    mixer = Mixer(volume = 0.34f, sendReverb = 0.36f),
                ),
            ),
            scenes = listOf(intro, groove, breakdown, rise, drop, outro),
            master = Master(
                // With headroom: the limiter is there for the day somebody
                // turns the acid up, not to hold the factory song together.
                volume = 0.64f,
                sends = listOf(fx("Reverb", "Room"), fx("Delay", "Eighth Sync")),
                // On the whole mix, before the limiter: a little warmth, and
                // a compressor to hold it together.
                inserts = listOf(fx("Eq", "Warmer"), fx("Compressor", "Bus")),
                // The drums and the percussion share one glue compressor.
                groups = listOf(MixGroup("Rhythm", inserts = listOf(fx("Compressor", "Glue")))),
                // The riser climbs over two bars, the length of the build.
                perform = PerformSettings(riserLen = 1),
            ),
        )
    }
}
