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

        return Song(
            name = "Demo",
            tempo = 120f,
            loopSong = true,
            tracks = listOf(
                Track(
                    id = "t-bass",
                    name = "Bass",
                    machine = Machine(type = "Nought"),
                    clips = mapOf(intro.id to introClip, verse.id to verseClip),
                ),
            ),
            scenes = listOf(intro, verse),
        )
    }
}
