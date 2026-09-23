package com.rm.acidulous.model

/**
 * The song that opens on a first run: a dub, in four scenes, with one of most
 * things the app can do somewhere in it.
 *
 * A demo earns its place by being the fastest way to find out what is here,
 * so this one puts a working example of each idea where a person will meet it:
 *
 *  - **Scenes of different lengths**, with a repeat count on the first and a
 *    smoothed tempo change into the last: the dub drags from 74 to 70 as it
 *    strips down, and looping the song picks the tempo back up on the way
 *    into Intro, which has no override of its own.
 *  - **Swing per track.** The song lilts its sixteenths; the bass overrides
 *    it back to straight, which is what a swing setting is usually for.
 *  - **A song key**, so the roll shades what is out of it and a new track
 *    arrives in A minor.
 *  - **Insert effects and both send buses**, with factory patches rather than
 *    bare defaults, because a demo should sound like somebody set it up. The
 *    sends are a dub delay and a dark room, and they are most of the sound.
 *  - **Automation**: in the dub the skank is thrown into the delay at the end
 *    of every other bar - a stepped lane, on and off like a fader pushed and
 *    pulled back - and the bass's filter closes and opens again.
 *  - **Trig conditions**: hand drums that are never quite the same bar twice
 *    - chances, a ratchet, and one hit that only lands every other pass.
 *  - **A one-shot clip**: the siren fires once per pass of the dub rather
 *    than looping inside it.
 *  - **Note expression**: the siren *is* a bend - one held note whose pitch is
 *    drawn as a curve, whooping up and falling back.
 *  - **A sidechain**: the bass has a compressor keyed to the drums, so it ducks
 *    out of the kick's way on every hit.
 *  - **A group**: the drums and the hand drums are routed into Rhythm, a
 *    group in the mixer with one glue compressor on the pair of them.
 *  - **Master inserts**: a warm tilt and a gentle compressor on the whole mix,
 *    before the limiter.
 *
 * Nothing here needs a file on disk, so it plays on a phone that has never
 * recorded anything. That rules out the machines that hold audio - the sampler,
 * the slicer, the granular, the multisample player, the vocal machine and the
 * four-track.
 *
 * **In A minor at 74.** i - i - iv - v, Am Am Dm Em: a riddim is a loop that
 * goes round for the whole record, so it wants chords that pull back to the
 * top rather than ones that go anywhere.
 */
object DemoSong {

    private const val Q = PPQN          // quarter
    private const val E = PPQN / 2      // eighth
    private const val S = PPQN / 4      // sixteenth
    private const val BAR = 4 * PPQN

    // Genesis counts from 36: kick, snare, clap, rim, three toms, then hats.
    private const val KICK = 36
    private const val RIM = 39
    private const val TOM_LO = 40
    private const val TOM_MID = 41
    private const val TOM_HI = 42
    private const val HAT = 43
    private const val OPEN_HAT = 44

    /** The skank's voicings, in the middle of the keyboard: Am Am Dm Em. */
    private val CHORDS = listOf(
        listOf(57, 60, 64), // Am
        listOf(57, 60, 64), // Am
        listOf(57, 62, 65), // Dm
        listOf(59, 64, 67), // Em
    )

    /**
     * The organ's two hands. The patch splits the keyboard at 64, so the low
     * voicing lands on the lower manual and the high one on the upper - which
     * is the bubble: two hands, two registers, taking turns.
     */
    private val BUBBLE_LOW = listOf(
        listOf(52, 57, 60),
        listOf(52, 57, 60),
        listOf(53, 57, 62),
        listOf(52, 55, 59),
    )
    private val BUBBLE_HIGH = listOf(
        listOf(69, 72, 76),
        listOf(69, 72, 76),
        listOf(69, 74, 77),
        listOf(67, 71, 76),
    )

    /** Where the bass sits under each bar: A1 A1 D2 E2. */
    private val ROOTS = listOf(33, 33, 38, 40)

    private fun patch(machine: String, name: String): Map<String, Float> =
        PatchStore.factory(machine).firstOrNull { it.name == name }?.params ?: emptyMap()

    private fun fx(type: String, patchName: String): UnitSlot =
        UnitSlot(type, PatchStore.factory(PatchStore.effectKey(type)).firstOrNull { it.name == patchName }?.params ?: emptyMap())

    /** The mixer group the drums and hand drums play through: group 1. */
    private const val RHYTHM = 1

    /**
     * A compressor that ducks this track under [track] (1-based): hard and
     * fast, so the bass gets out of the kick's way and comes straight back.
     * Values are the parameters' normalised positions.
     */
    private fun duckUnder(track: Int): UnitSlot = UnitSlot(
        "Compressor",
        mapOf(
            "threshold" to 0.5f,                  // -30 dB
            "ratio" to 0.694f,                    // 8:1
            "attack" to 0f,                       // 0.1 ms
            "release" to 0.54f,                   // 120 ms
            SIDECHAIN_PARAM to track / (SIDECHAIN_STEPS - 1f),
        ),
    )

    private fun clip(bars: Int, notes: List<Note>, block: Clip.() -> Clip = { this }): Clip =
        Clip(bars = bars, notes = notes.sortedBy { it.tick }).block()

    // --- the parts -------------------------------------------------------------------

    /**
     * The one drop: nothing on the one, kick and rim together on the three.
     *
     * The first beat of the bar is left empty on purpose, and that space is
     * the style - the bass fills it, and everybody else leans on the three.
     * Hats on the eighths with a ghost before the drop; the last bar of a
     * four-bar phrase opens a hat, and with [fills] it rolls down the toms
     * into the next pass.
     */
    private fun oneDrop(bars: Int, fills: Boolean = false): List<Note> {
        val out = ArrayList<Note>()
        for (b in 0 until bars) {
            val o = b * BAR
            out += Note(o + 2 * Q, 40, KICK, 118)
            out += Note(o + 2 * Q, 40, RIM, 104)
            for (i in 0 until 8) out += Note(o + i * E, 20, HAT, if (i % 2 == 1) 78 else 56)
            out += Note(o + 2 * Q - S, 16, HAT, 42)
            val last = b == bars - 1
            if (last) out += Note(o + 3 * Q + E, 30, OPEN_HAT, 70)
            if (last && fills) {
                out += Note(o + 3 * Q, 30, TOM_HI, 86)
                out += Note(o + 3 * Q + S, 30, TOM_HI, 70)
                out += Note(o + 3 * Q + S * 2, 30, TOM_MID, 88)
                out += Note(o + 3 * Q + S * 3, 30, TOM_LO, 96)
            }
        }
        return out
    }

    /**
     * The dub's drums: the kick and rim alone, the hats thinned to quarters,
     * and a tom answering the drop on the last beat.
     */
    private fun dubDrums(bars: Int): List<Note> {
        val out = ArrayList<Note>()
        for (b in 0 until bars) {
            val o = b * BAR
            out += Note(o + 2 * Q, 40, KICK, 118)
            out += Note(o + 2 * Q, 40, RIM, 96)
            for (i in 0 until 4) out += Note(o + i * Q + E, 20, HAT, 52)
            if (b % 2 == 1) out += Note(o + 3 * Q + E, 40, TOM_LO, 90)
        }
        return out
    }

    /**
     * The bass: round, low and in no hurry.
     *
     * It starts a half-beat late, in the gap the drums leave, walks up the
     * chord and comes back down to the root before the bar turns over.
     * [drop] leaves every other bar empty, which is what a dub does to a bass
     * line - takes it away so that its coming back is the event.
     */
    private fun bassLine(bars: Int, drop: Boolean = false): List<Note> {
        val shape = listOf(
            Triple(E, Q, 0),
            Triple(Q + E, S * 3 - 20, 0),
            Triple(2 * Q, E, 7),
            Triple(2 * Q + E + S, S, 5),
            Triple(3 * Q, E - 10, 3),
            Triple(3 * Q + E, E - 10, 0),
        )
        val out = ArrayList<Note>()
        for (b in 0 until bars) {
            if (drop && b % 2 == 1) continue
            val root = ROOTS[b % ROOTS.size]
            shape.forEachIndexed { i, (at, len, step) ->
                out += Note(b * BAR + at, len, root + step, if (i == 0) 108 else 92)
            }
        }
        return out
    }

    /** The skank: a short chord on the two and the four, and nothing else. */
    private fun skank(bars: Int): List<Note> =
        (0 until bars).flatMap { b ->
            val chord = CHORDS[b % CHORDS.size]
            listOf(Q, 3 * Q).flatMap { at -> chord.map { Note(b * BAR + at, S + 10, it, 92) } }
        }

    /**
     * The organ bubble: the left hand on the second and fourth sixteenths of
     * every beat, the right hand on the offbeat between them.
     */
    private fun bubble(bars: Int): List<Note> {
        val out = ArrayList<Note>()
        for (b in 0 until bars) {
            val low = BUBBLE_LOW[b % BUBBLE_LOW.size]
            val high = BUBBLE_HIGH[b % BUBBLE_HIGH.size]
            for (beat in 0 until 4) {
                val o = b * BAR + beat * Q
                low.forEach { out += Note(o + S, S - 30, it, 62) }
                high.forEach { out += Note(o + E, S - 20, it, 74) }
                low.forEach { out += Note(o + 3 * S, S - 30, it, 56) }
            }
        }
        return out
    }

    /**
     * Hand drums that are never the same bar twice.
     *
     * Every kind of trig in one part: two chances, a ratchet, and a hit that
     * only lands on the first of every two passes. The clip rolls free, so the
     * chances are redrawn each pass instead of repeating.
     */
    private fun handDrums(bars: Int): List<Note> {
        val out = ArrayList<Note>()
        for (b in 0 until bars) {
            val o = b * BAR
            out += Note(o, 60, 36, 88)
            out += Note(o + Q + E, 60, 39, 76, chance = 70)
            out += Note(o + 2 * Q + S * 3, 60, 37, 84, ratchet = 2)
            out += Note(o + 3 * Q + E, 60, 38, 80, trig = Trig.N1of2)
            out += Note(o + 3 * Q + S * 3, 60, 39, 64, chance = 50)
        }
        return out
    }

    /**
     * The melodica, over the version's four bars: a pentatonic tune in the
     * top of the reed's range, with rests long enough for the tape echo to
     * answer each phrase.
     */
    private fun melodica(): List<Note> = listOf(
        Note(0, Q + E, 76, 100),
        Note(Q + E, E, 74, 86),
        Note(2 * Q, Q, 72, 92),
        Note(3 * Q, Q, 69, 88),
        Note(BAR + E, E, 72, 84),
        Note(BAR + Q, E, 74, 88),
        Note(BAR + Q + E, Q + E, 76, 100),
        Note(BAR + 3 * Q, Q, 79, 96),
        Note(2 * BAR, Q, 81, 104),
        Note(2 * BAR + Q, E, 79, 88),
        Note(2 * BAR + Q + E, E, 77, 86),
        Note(2 * BAR + 2 * Q, Q + E, 74, 94),
        Note(3 * BAR, 2 * Q, 76, 100),
        Note(3 * BAR + 2 * Q + E, E, 74, 84),
        Note(3 * BAR + 3 * Q, Q - 20, 71, 90),
    )

    /** The horns: two stabs answering the melodica at the ends of its phrases. */
    private fun horns(): List<Note> =
        listOf(64, 69, 72).map { Note(BAR + 3 * Q + E, S * 3, it, 100) } +
            listOf(64, 67, 71).map { Note(3 * BAR + 2 * Q + E, E, it, 96) } +
            listOf(64, 67, 71).map { Note(3 * BAR + 3 * Q, S * 3, it, 104) }

    /**
     * The siren: one note held for two bars, and all of it is the bend.
     *
     * A dub siren is a pitch being swept by hand, so this is written as what
     * a hand would do - rise, fall back, rise, then a long climb that holds at
     * the top. Its ticks are the note's own.
     */
    private fun siren(): List<Note> = listOf(
        Note(
            0, 2 * BAR - 20, 76, 96,
            bend = Lane(
                listOf(
                    LanePoint(0, Note.bendTo01(-7f)),
                    LanePoint(Q, Note.bendTo01(5f)),
                    LanePoint(2 * Q, Note.bendTo01(-7f)),
                    LanePoint(3 * Q, Note.bendTo01(5f)),
                    LanePoint(BAR, Note.bendTo01(-7f)),
                    LanePoint(BAR + 2 * Q + E, Note.bendTo01(12f)),
                    LanePoint(2 * BAR - 20, Note.bendTo01(12f)),
                ),
            ),
        ),
    )

    // --- the song --------------------------------------------------------------------

    fun build(): Song {
        val intro = Scene(id = "s-intro", name = "Intro", repeat = 2)
        val riddim = Scene(id = "s-riddim", name = "Riddim")
        val version = Scene(id = "s-version", name = "Version")
        val dub = Scene(id = "s-dub", name = "Dub", tempo = SceneTempo(bpm = 70f, smooth = true))

        // The skank thrown into the delay on the last beat of every other
        // bar: a stepped lane, so the send jumps up and drops back the way a
        // hand on a fader does, and the echo carries on after the chord stops.
        val throws = Lane(
            points = listOf(
                LanePoint(0, 0.10f),
                LanePoint(BAR + 3 * Q, 0.95f),
                LanePoint(2 * BAR, 0.10f),
                LanePoint(3 * BAR + 3 * Q, 0.95f),
                LanePoint(4 * BAR - 1, 0.10f),
            ),
            linear = false,
        )
        // And the bass's filter closing over the dub's second bar and opening
        // again over its fourth.
        val bassFilter = Lane(
            listOf(
                LanePoint(0, 0.52f),
                LanePoint(BAR, 0.52f),
                LanePoint(2 * BAR, 0.30f),
                LanePoint(3 * BAR, 0.30f),
                LanePoint(4 * BAR - 1, 0.52f),
            ),
        )

        return Song(
            name = "Demo",
            tempo = 74f,
            swing = 58f,
            swingUnit = 0,
            key = SongKey(root = 9, scale = 5), // A Aeolian
            loopSong = true,
            tracks = listOf(
                Track(
                    id = "t-drums",
                    name = "Drums",
                    machine = Machine(type = "Genesis", params = patch("Genesis", "Rimshot")),
                    clips = mapOf(
                        intro.id to clip(2, oneDrop(2, fills = true)),
                        riddim.id to clip(4, oneDrop(4, fills = true)),
                        version.id to clip(4, oneDrop(4)),
                        dub.id to clip(4, dubDrums(4)),
                    ),
                    mixer = Mixer(volume = 0.86f, sendReverb = 0.14f, sendDelay = 0.08f, output = RHYTHM),
                ),
                Track(
                    id = "t-bass",
                    name = "Bass",
                    // Ducked under the kick: a compressor that listens to the
                    // drums (track 1) rather than to the bass itself.
                    effects = listOf(duckUnder(track = 1)),
                    machine = Machine(type = "Trinity", params = patch("Trinity", "Sub Bass")),
                    // **Straight, against a song that lilts.** The one
                    // sentence the per-track override exists for.
                    swing = SWING_STRAIGHT,
                    clips = mapOf(
                        riddim.id to clip(4, bassLine(4)),
                        version.id to clip(4, bassLine(4)),
                        dub.id to clip(4, bassLine(4, drop = true)) {
                            copy(automation = mapOf(laneKey("machine", "f1_freq") to bassFilter))
                        },
                    ),
                    mixer = Mixer(volume = 0.84f),
                ),
                Track(
                    id = "t-skank",
                    name = "Skank",
                    machine = Machine(type = "Trinity", params = patch("Trinity", "Clav")),
                    clips = mapOf(
                        intro.id to clip(2, skank(2)),
                        riddim.id to clip(4, skank(4)),
                        version.id to clip(4, skank(4)),
                        dub.id to clip(4, skank(4)) {
                            copy(automation = mapOf(laneKey("channel", "senddelay") to throws))
                        },
                    ),
                    effects = listOf(fx("Eq", "Low Cut")),
                    mixer = Mixer(volume = 0.42f, pan = 0.2f, sendReverb = 0.18f, sendDelay = 0.10f),
                ),
                Track(
                    id = "t-bubble",
                    name = "Bubble",
                    machine = Machine(type = "Manual", params = patch("Manual", "Comping")),
                    clips = mapOf(
                        riddim.id to clip(4, bubble(4)),
                        version.id to clip(4, bubble(4)),
                    ),
                    mixer = Mixer(volume = 0.30f, pan = -0.25f, sendReverb = 0.12f),
                ),
                Track(
                    id = "t-hands",
                    name = "Hands",
                    machine = Machine(type = "Resonance", params = patch("Resonance", "Skins")),
                    clips = mapOf(
                        riddim.id to clip(2, handDrums(2)) { copy(freeRoll = true) },
                        version.id to clip(2, handDrums(2)) { copy(freeRoll = true) },
                        dub.id to clip(2, handDrums(2)) { copy(freeRoll = true) },
                    ),
                    mixer = Mixer(volume = 0.40f, pan = -0.15f, sendReverb = 0.22f, sendDelay = 0.22f, output = RHYTHM),
                ),
                Track(
                    id = "t-melodica",
                    name = "Melodica",
                    machine = Machine(type = "Manual", params = patch("Manual", "Melodeon")),
                    clips = mapOf(version.id to clip(4, melodica())),
                    effects = listOf(fx("Delay", "Tape")),
                    mixer = Mixer(volume = 0.50f, pan = 0.1f, sendReverb = 0.24f, sendDelay = 0.30f),
                ),
                Track(
                    id = "t-horns",
                    name = "Horns",
                    machine = Machine(type = "Brazen", params = patch("Brazen", "Section")),
                    clips = mapOf(version.id to clip(4, horns())),
                    mixer = Mixer(volume = 0.42f, pan = -0.1f, sendReverb = 0.30f, sendDelay = 0.20f),
                ),
                Track(
                    id = "t-siren",
                    name = "Siren",
                    machine = Machine(type = "Trinity", params = patch("Trinity", "Whistle")),
                    // Two bars, one shot: it sounds on the first half of each
                    // pass of the dub and leaves the second half to the echo,
                    // which a looping clip cannot do.
                    clips = mapOf(dub.id to clip(2, siren()) { copy(playMode = PlayMode.OneShot) }),
                    mixer = Mixer(volume = 0.30f, pan = 0.3f, sendReverb = 0.40f, sendDelay = 0.55f),
                ),
            ),
            scenes = listOf(intro, riddim, version, dub),
            // **0.64, not the default 0.8.** The demo should arrive with
            // headroom in it: the limiter is there for the day somebody turns
            // the bass up, not to hold the factory song together.
            master = Master(
                volume = 0.64f,
                sends = listOf(fx("Reverb", "Dark"), fx("Delay", "Dub")),
                // On the whole mix, before the limiter: a little warmth, and
                // a gentle compressor to hold it together.
                inserts = listOf(fx("Eq", "Warmer"), fx("Compressor", "Gentle")),
                // The drums and the hand drums share one glue compressor.
                groups = listOf(MixGroup("Rhythm", inserts = listOf(fx("Compressor", "Glue")))),
            ),
        )
    }
}
