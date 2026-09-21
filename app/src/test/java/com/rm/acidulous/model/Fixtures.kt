package com.rm.acidulous.model

/**
 * The small song the model tests are written against.
 *
 * **A demo song is content, and content is a bad fixture.** This was
 * `DemoSong.build()` for a long time, which meant two dozen assertions quietly
 * depended on the demo having two scenes, three tracks, a particular reverb
 * send and no note expression anywhere. Making the demo show more of the app -
 * more scenes, more tracks, a bend on one note - broke fourteen tests that were
 * not about any of that, and re-baselining the numbers would only have set the
 * trap again for the next time somebody improves the demo.
 *
 * So the fixture is here, it is deliberately minimal, and it is what M2's proof
 * song was: two scenes of different lengths, a repeat count on the first, a
 * smoothed tempo change on the second, and three tracks with clips in both.
 * Nothing in it is decorative, and it should only change when a test needs it
 * to.
 */
object Fixtures {

    private const val Q = PPQN
    private const val E = PPQN / 2

    fun song(): Song {
        val intro = Scene(id = "s-intro", name = "Intro", repeat = 2)
        val verse = Scene(id = "s-verse", name = "Verse", tempo = SceneTempo(bpm = 140f, smooth = true))

        val introClip = Clip(
            bars = 1,
            notes = listOf(
                Note(0 * Q, 50, 36, 100),
                Note(1 * Q, 50, 36, 80),
                Note(2 * Q, 110, 43, 110),
                Note(3 * Q, 50, 39, 90),
            ),
        )
        // Two bars of eighths. The last note is a full beat long from the final
        // eighth, so it overhangs the scene boundary into Intro.
        val line = intArrayOf(36, 36, 39, 36, 43, 36, 46, 48, 36, 36, 39, 41, 43, 46, 48, 43)
        val verseClip = Clip(
            bars = 2,
            notes = line.mapIndexed { i, pitch ->
                Note(
                    tick = i * E,
                    length = if (i == line.lastIndex) Q else 50,
                    pitch = pitch,
                    velocity = if (i % 4 == 0) 110 else 85,
                )
            },
        )

        fun beat(bars: Int): Clip {
            val notes = ArrayList<Note>()
            for (b in 0 until bars) {
                val o = b * 4 * Q
                notes += Note(o, 30, 36, 110); notes += Note(o + 2 * Q, 30, 36, 90)
                notes += Note(o + Q, 30, 38, 100); notes += Note(o + 3 * Q, 30, 38, 100)
                for (i in 0 until 8) {
                    notes += Note(o + i * E, 20, if (i == 7) 44 else 43, if (i % 2 == 0) 95 else 70)
                }
            }
            return Clip(bars = bars, notes = notes.sortedBy { it.tick })
        }

        fun pad(bars: Int, chords: List<List<Int>>): Clip {
            val notes = ArrayList<Note>()
            for (b in 0 until bars) {
                for (pitch in chords[b % chords.size]) notes += Note(b * 4 * Q, 4 * Q - 10, pitch, 80)
            }
            return Clip(bars = bars, notes = notes.sortedBy { it.tick })
        }

        return Song(
            name = "Demo",
            tempo = 120f,
            loopSong = true,
            tracks = listOf(
                Track(
                    id = "t-bass",
                    name = "Bass",
                    machine = Machine(type = "Reflux"),
                    clips = mapOf(intro.id to introClip, verse.id to verseClip),
                    mixer = Mixer(sendReverb = 0.25f, sendDelay = 0.2f),
                ),
                Track(
                    id = "t-poly",
                    name = "Poly",
                    machine = Machine(type = "Trinity"),
                    clips = mapOf(
                        intro.id to pad(1, listOf(listOf(60, 63, 67))),
                        verse.id to pad(2, listOf(listOf(60, 63, 67), listOf(58, 62, 65))),
                    ),
                    mixer = Mixer(volume = 0.6f, sendReverb = 0.4f),
                ),
                Track(
                    id = "t-drums",
                    name = "Drums",
                    machine = Machine(type = "Hexbeat"),
                    clips = mapOf(intro.id to beat(1), verse.id to beat(2)),
                    mixer = Mixer(sendReverb = 0.12f),
                ),
            ),
            scenes = listOf(intro, verse),
        )
    }
}
