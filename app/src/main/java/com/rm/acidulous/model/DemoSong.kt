package com.rm.acidulous.model

/**
 * M2's proof song: two scenes of different lengths, a repeat count, and a
 * smoothed tempo change. Intro is one bar played twice at the song tempo;
 * Verse is two bars gliding up to 140. Looping the song drops straight back
 * to 120 on the way into Intro, which has no override.
 */
object DemoSong {

    fun build(): Song {
        val intro = Scene(id = "s-intro", name = "Intro", repeat = 2)
        val verse = Scene(id = "s-verse", name = "Verse", tempo = SceneTempo(bpm = 140f, smooth = true))

        val q = PPQN          // quarter
        val e = PPQN / 2      // eighth
        val introClip = Clip(
            bars = 1,
            notes = listOf(
                Note(0 * q, 50, 36, 100),
                Note(1 * q, 50, 36, 80),
                Note(2 * q, 110, 43, 110),
                Note(3 * q, 50, 39, 90),
            ),
        )
        // Two bars of eighths. The last note is a full beat long from the
        // final eighth, so it overhangs the scene boundary into Intro.
        val line = intArrayOf(36, 36, 39, 36, 43, 36, 46, 48, 36, 36, 39, 41, 43, 46, 48, 43)
        val verseClip = Clip(
            bars = 2,
            notes = line.mapIndexed { i, pitch ->
                val last = i == line.lastIndex
                Note(
                    tick = i * e,
                    length = if (last) q else 50,
                    pitch = pitch,
                    velocity = if (i % 4 == 0) 110 else 85,
                )
            },
        )

        // Drums: kick on 1 and 3, snare on 2 and 4, closed hats on the eighths,
        // an open hat before the bar line. Accented downbeat.
        fun beat(bars: Int): Clip {
            val notes = ArrayList<Note>()
            for (b in 0 until bars) {
                val o = b * 4 * q
                notes += Note(o, 30, 36, 110); notes += Note(o + 2 * q, 30, 36, 90)
                notes += Note(o + q, 30, 38, 100); notes += Note(o + 3 * q, 30, 38, 100)
                for (i in 0 until 8) notes += Note(o + i * e, 20, if (i == 7) 44 else 43, if (i % 2 == 0) 95 else 70)
            }
            return Clip(bars = bars, notes = notes.sortedBy { it.tick })
        }

        // A held triad per bar for the pad, an octave above the bass.
        fun pad(bars: Int, chords: List<List<Int>>): Clip {
            val notes = ArrayList<Note>()
            for (b in 0 until bars) {
                for (pitch in chords[b % chords.size]) notes += Note(b * 4 * q, 4 * q - 10, pitch, 80)
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
                    machine = Machine(type = "Subvert"),
                    clips = mapOf(intro.id to introClip, verse.id to verseClip),
                    mixer = Mixer(sendReverb = 0.25f, sendDelay = 0.2f),
                ),
                Track(
                    id = "t-poly",
                    name = "Poly",
                    machine = Machine(type = "Trinity", params = PatchStore.factory("Trinity").first { it.name == "Glass Pad" }.params),
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
