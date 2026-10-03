package com.rm.acidulous.model

import com.rm.acidulous.model.DemoKit.BAR
import com.rm.acidulous.model.DemoKit.E
import com.rm.acidulous.model.DemoKit.Q
import com.rm.acidulous.model.DemoKit.S
import com.rm.acidulous.model.DemoKit.chord
import com.rm.acidulous.model.DemoKit.clip
import com.rm.acidulous.model.DemoKit.machine
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The song the multi-core work is timed against (PLAN §5.42): Squelch, plus a
 * piano with its chords overlapping as if pedalled, a plucked string playing
 * sixteenths and a clarinet, all three in every scene. Written as a bundle
 * only when YARDSTICK_OUT names a file:
 *
 *     YARDSTICK_OUT=/path/yardstick.zip ./gradlew :shared:desktopTest --tests '*YardstickSong*'
 */
class YardstickSong {

    private val progression = listOf(
        listOf(45, 57, 60, 64, 67, 71),
        listOf(41, 53, 57, 60, 64),
        listOf(43, 55, 59, 62, 64),
        listOf(40, 52, 55, 59, 62),
    )

    /** A chord a bar, each held two bars, so they overlap like a held pedal; a tune on top in eighths. */
    private fun piano(): List<Note> = (0 until 4).flatMap { b ->
        chord(b * BAR, 2 * BAR - 20, progression[b], 70) +
            (0 until 8).map { e -> Note(b * BAR + e * E, Q, progression[b][1 + e % (progression[b].size - 1)] + 12, 60 + 4 * (e % 3)) }
    }

    /** Sixteenths up and down each chord, each let ring an eighth. */
    private fun strings(): List<Note> = (0 until 4).flatMap { b ->
        val notes = progression[b].drop(1)
        (0 until 16).map { s ->
            val i = if ((s / notes.size) % 2 == 0) s % notes.size else notes.size - 1 - s % notes.size
            Note(b * BAR + s * S, E, notes[i], 72 + 8 * (s % 4 == 0).compareTo(false))
        }
    }

    /** A slow line, a beat or two each, joined. */
    private fun clarinet(): List<Note> {
        val line = listOf(69, 72, 71, 67, 65, 69, 67, 64, 67, 71, 69, 66, 64, 67, 66, 62)
        return line.mapIndexed { i, p -> Note(i * Q, Q + 20, p, 84) }
    }

    @Test
    fun write() {
        val out = System.getenv("YARDSTICK_OUT")
        assumeTrue("only when YARDSTICK_OUT is set", !out.isNullOrBlank())
        val squelch = DemoSong.build()
        fun everyScene(notes: List<Note>) = squelch.scenes.associate { it.id to clip(4, notes) }
        val song = squelch.copy(
            name = "Yardstick",
            tracks = squelch.tracks + listOf(
                Track(
                    id = "t-piano", name = "Piano",
                    machine = machine("Hammer", "Concert Hall"),
                    clips = everyScene(piano()),
                    mixer = Mixer(volume = 0.40f, sendReverb = 0.20f),
                ),
                Track(
                    id = "t-strings", name = "Strings",
                    machine = machine("Filament", "Steel"),
                    clips = everyScene(strings()),
                    mixer = Mixer(volume = 0.30f, pan = 0.3f, sendDelay = 0.15f),
                ),
                Track(
                    id = "t-clarinet", name = "Clarinet",
                    machine = machine("Timber", "Clarinet"),
                    clips = everyScene(clarinet()),
                    mixer = Mixer(volume = 0.32f, pan = -0.3f, sendReverb = 0.25f),
                ),
            ),
        )
        val root = kotlin.io.path.createTempDirectory("yardstick").toFile()
        try {
            SongBundle.write(song, root, File(out!!))
        } finally {
            root.deleteRecursively()
        }
    }
}
