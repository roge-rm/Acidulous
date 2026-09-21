package com.rm.acidulous.model

/**
 * The song that opens on a first run: eight machines, four scenes, and one of
 * most things the app can do.
 *
 * It replaces M2's proof song, which was three tracks and two scenes and had
 * been carrying that job since before there were sixteen machines to show. A
 * demo earns its place by being the fastest way to find out what is here, so
 * this one puts a working example of each idea somewhere a person will meet it:
 *
 *  - **Scenes of different lengths**, with a repeat count on the first and a
 *    smoothed tempo change into the third. Looping the song drops back to 124
 *    on the way into Intro, which has no override of its own.
 *  - **Swing per track.** The song shuffles its sixteenths a little; the bass
 *    overrides it back to straight, which is what a swing setting is usually
 *    for.
 *  - **A song key**, so the roll shades what is out of it and a new track
 *    arrives in A minor.
 *  - **Insert effects and both send buses**, with factory patches rather than
 *    bare defaults, because a demo should sound like somebody set it up.
 *  - **Automation**: the bass filter opens across the verse, and the pad's
 *    reverb send rises into the lift.
 *  - **Trig conditions**: a percussion part that is never quite the same bar
 *    twice - chances, a ratchet, and one hit that only lands every other pass.
 *  - **A one-shot clip**: the brass figure fires once per scene iteration
 *    rather than looping inside it.
 *  - **Note expression**: the lead's last note bends up and holds.
 *
 * Nothing here needs a file on disk, so it plays on a phone that has never
 * recorded anything. That rules out the machines that hold audio - the sampler,
 * the slicer, the granular, the multisample player and the four-track - which
 * is why those five are the ones this does not reach.
 *
 * **In A minor at 124.** i - VI - III - VII, which is four chords everybody
 * already knows, so the parts can be read against something familiar rather
 * than being interesting on their own.
 */
object DemoSong {

    private const val Q = PPQN          // quarter
    private const val E = PPQN / 2      // eighth
    private const val S = PPQN / 4      // sixteenth
    private const val BAR = 4 * PPQN

    // Genesis and Hexbeat both count from 36. Resonance's eight pads do too.
    private const val KICK = 36
    private const val SNARE = 37        // Genesis: kick, snare, clap, rim, ...
    private const val CLAP = 38
    private const val HAT = 43
    private const val OPEN_HAT = 44
    private const val RIDE = 46

    /** The four chords, as voicings in the pad's register. */
    private val CHORDS = listOf(
        listOf(57, 60, 64), // Am
        listOf(53, 57, 60), // F
        listOf(55, 60, 64), // C
        listOf(55, 59, 62), // G
    )

    /** The root each bar sits on, an octave and a half below the chords. */
    private val ROOTS = listOf(33, 29, 36, 31) // A1 F1 C2 G1

    private fun patch(machine: String, name: String): Map<String, Float> =
        PatchStore.factory(machine).firstOrNull { it.name == name }?.params ?: emptyMap()

    private fun fx(type: String, patchName: String): UnitSlot =
        UnitSlot(type, PatchStore.factory(PatchStore.effectKey(type)).firstOrNull { it.name == patchName }?.params ?: emptyMap())

    private fun clip(bars: Int, notes: List<Note>, block: Clip.() -> Clip = { this }): Clip =
        Clip(bars = bars, notes = notes.sortedBy { it.tick }).block()

    // --- the parts -------------------------------------------------------------------

    /**
     * The acid line: sixteenths on the root with octave jumps, an accent on
     * each downbeat, and slides written as overlaps.
     *
     * Accent is velocity and slide is legato, so both are in the notes rather
     * than in any field of their own - which is the whole idea of the machine
     * and is worth a demo showing rather than a manual saying.
     */
    private fun bassLine(bars: Int): List<Note> {
        val pattern = intArrayOf(0, 0, 12, 0, 7, 0, 12, 3, 0, 0, 12, 0, 5, 12, 7, 0)
        val out = ArrayList<Note>()
        for (b in 0 until bars) {
            val root = ROOTS[b % ROOTS.size]
            for (i in pattern.indices) {
                val accent = i % 4 == 0
                // Two slides a bar: the note runs into the next one, and the
                // machine reads the overlap as a slide rather than a retrigger.
                val slide = i == 6 || i == 13
                out += Note(
                    tick = b * BAR + i * S,
                    length = if (slide) S + S / 2 else S - 20,
                    pitch = root + pattern[i],
                    velocity = if (accent) 112 else 78,
                )
            }
        }
        return out
    }

    /** Kick, snare, hats. The open hat before each bar line, ride in the lift. */
    private fun beat(bars: Int, ride: Boolean = false, busy: Boolean = false): List<Note> {
        val out = ArrayList<Note>()
        for (b in 0 until bars) {
            val o = b * BAR
            out += Note(o, 40, KICK, 118)
            out += Note(o + 2 * Q + E, 40, KICK, 96)
            if (busy) out += Note(o + 3 * Q + S * 3, 30, KICK, 70)
            out += Note(o + Q, 40, SNARE, 104)
            out += Note(o + 3 * Q, 40, SNARE, 108)
            for (i in 0 until 8) {
                val open = i == 7
                out += Note(o + i * E, 24, if (open) OPEN_HAT else HAT, if (i % 2 == 0) 92 else 64)
            }
            if (ride) for (i in 0 until 4) out += Note(o + i * Q + E, 24, RIDE, 70)
            // A clap doubling the backbeat, from the second bar on, so a
            // two-bar phrase is not two identical bars.
            if (b % 2 == 1) out += Note(o + 3 * Q, 40, CLAP, 90)
        }
        return out
    }

    /**
     * Percussion that is never the same bar twice.
     *
     * Every kind of trig in one part: a chance, a ratchet, and a hit that only
     * lands on the first of every two passes. The clip rolls free, so the
     * chances are redrawn each pass instead of repeating.
     *
     * Five trigs a bar and not six: these are struck modal objects that ring
     * into each other, so the part costs more voices than it has notes.
     */
    private fun perc(bars: Int): List<Note> {
        val out = ArrayList<Note>()
        for (b in 0 until bars) {
            val o = b * BAR
            out += Note(o + E, 60, 36, 96)
            out += Note(o + Q + S, 60, 38, 80, chance = 70)
            out += Note(o + 2 * Q + E, 60, 37, 88, ratchet = 3)
            out += Note(o + 3 * Q, 60, 39, 84, chance = 55)
            out += Note(o + 3 * Q + S * 2, 60, 40, 76, trig = Trig.N1of2)
        }
        return out
    }

    /** A held triad a bar, the chords in order. */
    private fun pad(bars: Int, from: Int = 0): List<Note> =
        (0 until bars).flatMap { b ->
            // Three quarters of the bar, not all of it. Held to the bar line
            // the release of one chord overlapped the attack of the next, so
            // six voices of a spectral pad sounded where three were wanted -
            // and a pad is the most expensive thing here to double.
            CHORDS[(from + b) % CHORDS.size].map { Note(b * BAR, 3 * Q, it, 74) }
        }

    /**
     * The arpeggio, written out as notes.
     *
     * Which is the point: since M59 a modifier acts on the way in, so an arp
     * played live is *written down* like this and can then be edited. A demo
     * built the other way round - one note a bar and an arp left running -
     * would show the old arrangement rather than this one.
     */
    private fun arp(bars: Int, from: Int = 0): List<Note> {
        val out = ArrayList<Note>()
        for (b in 0 until bars) {
            val chord = CHORDS[(from + b) % CHORDS.size].map { it + 12 }
            val order = intArrayOf(0, 1, 2, 1, 0, 2, 1, 2)
            for (i in 0 until 16) {
                val pitch = chord[order[i % order.size] % chord.size] + if (i >= 8) 12 else 0
                out += Note(b * BAR + i * S, S - 16, pitch, if (i % 4 == 0) 96 else 70)
            }
        }
        return out
    }

    /** A plucked counter-line, off the beat, two notes a bar. */
    private fun pluck(bars: Int, from: Int = 0): List<Note> =
        (0 until bars).flatMap { b ->
            val chord = CHORDS[(from + b) % CHORDS.size]
            listOf(
                Note(b * BAR + E + S, Q, chord[2] + 12, 86),
                Note(b * BAR + 2 * Q + E, Q + E, chord[1] + 12, 72),
            )
        }

    /**
     * The melody, over the lift's four bars, ending on a note that bends up a
     * tone and stays there.
     */
    private fun lead(): List<Note> {
        val notes = listOf(
            Note(0 * Q, Q + E, 76, 100),
            Note(1 * Q + E, E, 74, 88),
            Note(2 * Q, Q, 72, 92),
            Note(3 * Q, Q, 69, 86),
            Note(BAR + 0 * Q, Q, 72, 96),
            Note(BAR + 1 * Q, E, 74, 84),
            Note(BAR + 1 * Q + E, E + Q, 76, 100),
            Note(2 * BAR + 0 * Q, Q + E, 77, 104),
            Note(2 * BAR + 1 * Q + E, E, 76, 88),
            Note(2 * BAR + 2 * Q, 2 * Q, 74, 94),
            // The last one bends: flat at first, then up a tone over its own
            // first half, and held there. Its ticks are the note's own.
            Note(
                3 * BAR, 3 * Q, 72, 106,
                bend = Lane(
                    listOf(
                        LanePoint(0, Note.bendTo01(0f)),
                        LanePoint(Q / 2, Note.bendTo01(0f)),
                        LanePoint(Q + E, Note.bendTo01(2f)),
                        LanePoint(3 * Q, Note.bendTo01(2f)),
                    ),
                ),
            ),
        )
        return notes
    }

    /** Two stabs a bar, on the chord, for the lift. */
    private fun brass(): List<Note> =
        CHORDS[0].map { Note(0, E, it + 12, 108) } + CHORDS[0].map { Note(Q + E, S * 3, it + 12, 92) }

    /** Eight-bit blips for the break: the chip machine on its own. */
    private fun chip(bars: Int): List<Note> {
        val out = ArrayList<Note>()
        val shape = intArrayOf(0, 7, 12, 7, 3, 10, 15, 10)
        for (b in 0 until bars) {
            for (i in 0 until 16) {
                val step = shape[i % shape.size] + if (i >= 8) 12 else 0
                out += Note(b * BAR + i * S, S - 12, 57 + step, if (i % 4 == 0) 100 else 72, chance = if (i % 2 == 1) 80 else 100)
            }
        }
        return out
    }

    // --- the song --------------------------------------------------------------------

    fun build(): Song {
        val intro = Scene(id = "s-intro", name = "Intro", repeat = 2)
        val verse = Scene(id = "s-verse", name = "Verse")
        val lift = Scene(id = "s-lift", name = "Lift", tempo = SceneTempo(bpm = 132f, smooth = true))
        val brk = Scene(id = "s-break", name = "Break")

        // The bass filter opens across the verse and stays open for the lift.
        val cutoffRise = Lane(
            listOf(
                LanePoint(0, 0.30f),
                LanePoint(2 * BAR, 0.52f),
                LanePoint(4 * BAR - 1, 0.74f),
            ),
        )
        // And the pad's reverb send comes up into the lift.
        val sendRise = Lane(listOf(LanePoint(0, 0.30f), LanePoint(4 * BAR - 1, 0.85f)))

        return Song(
            name = "Demo",
            tempo = 124f,
            swing = 56f,
            swingUnit = 0,
            key = SongKey(root = 9, scale = 5), // A Aeolian
            loopSong = true,
            tracks = listOf(
                Track(
                    id = "t-bass",
                    name = "Bass",
                    machine = Machine(type = "Reflux", params = patch("Reflux", "Squelch")),
                    // **Straight, against a song that shuffles.** The one
                    // sentence the per-track override exists for.
                    swing = SWING_STRAIGHT,
                    clips = mapOf(
                        verse.id to clip(4, bassLine(4)) {
                            copy(automation = mapOf(laneKey("machine", "cutoff") to cutoffRise))
                        },
                        lift.id to clip(4, bassLine(4)),
                    ),
                    effects = listOf(fx("Distortion", "Warm")),
                    mixer = Mixer(volume = 0.80f, sendReverb = 0.10f, sendDelay = 0.12f),
                ),
                Track(
                    id = "t-drums",
                    name = "Drums",
                    machine = Machine(type = "Genesis", params = patch("Genesis", "Pump")),
                    clips = mapOf(
                        intro.id to clip(2, beat(2)),
                        verse.id to clip(2, beat(2)),
                        lift.id to clip(4, beat(4, ride = true, busy = true)),
                    ),
                    mixer = Mixer(volume = 0.86f, sendReverb = 0.10f),
                ),
                Track(
                    id = "t-perc",
                    name = "Perc",
                    machine = Machine(type = "Resonance", params = patch("Resonance", "Hang")),
                    clips = mapOf(
                        verse.id to clip(2, perc(2)) { copy(freeRoll = true) },
                        lift.id to clip(2, perc(2)) { copy(freeRoll = true) },
                        brk.id to clip(2, perc(2)) { copy(freeRoll = true) },
                    ),
                    mixer = Mixer(volume = 0.52f, sendReverb = 0.35f, sendDelay = 0.2f),
                ),
                Track(
                    id = "t-pad",
                    name = "Pad",
                    machine = Machine(type = "Cumulus", params = patch("Cumulus", "Halo")),
                    clips = mapOf(
                        intro.id to clip(2, pad(2)),
                        verse.id to clip(4, pad(4)),
                        lift.id to clip(4, pad(4)) {
                            copy(automation = mapOf(laneKey("channel", "sendreverb") to sendRise))
                        },
                    ),
                    mixer = Mixer(volume = 0.46f, sendReverb = 0.30f),
                ),
                Track(
                    id = "t-keys",
                    name = "Keys",
                    machine = Machine(type = "Trinity", params = patch("Trinity", "Bell Keys")),
                    clips = mapOf(
                        verse.id to clip(4, arp(4)),
                        lift.id to clip(4, arp(4)),
                    ),
                    effects = listOf(fx("Delay", "Eighth Sync")),
                    mixer = Mixer(volume = 0.44f, pan = 0.25f, sendReverb = 0.28f),
                ),
                Track(
                    id = "t-pluck",
                    name = "Pluck",
                    machine = Machine(type = "Filament", params = patch("Filament", "Nylon")),
                    // Verse only. Nine machines at once put the emulator at
                    // 71% and stuttering, and a plucked string model is one of
                    // the dearest voices here - so the lift hands its job to
                    // the lead, which is the part that wants the room anyway.
                    clips = mapOf(verse.id to clip(4, pluck(4))),
                    effects = listOf(fx("Chorus", "Ensemble")),
                    mixer = Mixer(volume = 0.50f, pan = -0.3f, sendReverb = 0.32f),
                ),
                Track(
                    id = "t-lead",
                    name = "Lead",
                    machine = Machine(type = "Ratio", params = patch("Ratio", "Fold Lead")),
                    clips = mapOf(lift.id to clip(4, lead())),
                    effects = listOf(fx("Eq", "Presence")),
                    mixer = Mixer(volume = 0.56f, sendReverb = 0.22f, sendDelay = 0.3f),
                ),
                Track(
                    id = "t-brass",
                    name = "Brass",
                    machine = Machine(type = "Brazen", params = patch("Brazen", "Section")),
                    // One bar, one shot: it fires on the first pass of each
                    // four-bar iteration and is silent for the other three,
                    // which a looping clip cannot do.
                    clips = mapOf(lift.id to clip(1, brass()) { copy(playMode = PlayMode.OneShot) }),
                    mixer = Mixer(volume = 0.46f, sendReverb = 0.26f),
                ),
                Track(
                    id = "t-chip",
                    name = "Chip",
                    machine = Machine(type = "Formulate", params = patch("Formulate", "Echo Pluck")),
                    clips = mapOf(brk.id to clip(2, chip(2))),
                    effects = listOf(fx("Bitcrusher", "Init")),
                    mixer = Mixer(volume = 0.48f, pan = 0.15f, sendDelay = 0.35f),
                ),
            ),
            scenes = listOf(intro, verse, lift, brk),
            // **0.64, not the default 0.8.** Nine tracks and two send buses
            // summing into a limiter whose ceiling is 0.95 read exactly 0.950
            // on the loud scenes, which is the meter saying "limiting" rather
            // than "mixed". A demo should arrive with headroom in it: the
            // limiter is there for the day somebody turns the bass up, not to
            // hold the factory song together.
            master = Master(
                volume = 0.64f,
                sends = listOf(fx("Reverb", "Hall"), fx("Delay", "Eighth Sync")),
            ),
        )
    }
}
