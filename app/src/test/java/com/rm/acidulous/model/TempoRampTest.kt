package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TempoRampTest {

    private fun song(ramp: TempoRamp?, repeat: Int = 1) = Song(
        name = "ritardando", tempo = 120f,
        tracks = listOf(Track(id = "t", name = "t", machine = Machine("Trinity"),
            clips = mapOf("s" to Clip(bars = 2)))),
        scenes = listOf(Scene(id = "s", name = "s", repeat = repeat, ramp = ramp)),
    )

    @Test
    fun aRampTakesTheTimeItsTempoSays() {
        // Two bars at 120 is 4 s. The second slowing evenly to 60 takes 4 ln 2.
        assertEquals(4f, song(null).durationSeconds(), 1e-4f)
        assertEquals(2f + 4f * kotlin.math.ln(2f), song(TempoRamp(60f, 1)).durationSeconds(), 1e-3f)
        // Speeding up is shorter: 120 to 240 over both bars, 8 ln 2 / 2.
        assertEquals(8f * kotlin.math.ln(2f) / 2f, song(TempoRamp(240f, 2)).durationSeconds(), 1e-3f)
    }

    @Test
    fun onlyTheLastPassRamps() {
        val s = song(TempoRamp(60f, 1), repeat = 3)
        assertEquals(4f * 2 + 2f + 4f * kotlin.math.ln(2f), s.durationSeconds(), 1e-3f)
    }

    @Test
    fun aRampIsWrittenIntoAMidiFileAsATempoEveryBeat() {
        val tmp = java.io.File.createTempFile("ramp", ".mid")
        try {
            MidiFile.write(song(TempoRamp(60f, 1)), tmp)
            val bytes = tmp.readBytes()
            // Count tempo meta events: FF 51 03.
            var n = 0
            for (i in 0 until bytes.size - 2) {
                if (bytes[i] == 0xff.toByte() && bytes[i + 1] == 0x51.toByte() && bytes[i + 2] == 3.toByte()) n++
            }
            assertEquals("the scene's own and one a beat over the last bar", 1 + 4, n)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun aRampSurvivesBeingSavedAndOpened() {
        val s = song(TempoRamp(90f, 2))
        assertEquals(s, SongStore.decode(SongStore.encode(s)))
        // And a scene without one says nothing about it.
        assertEquals(false, SongStore.encode(song(null)).contains("\"ramp\""))
    }
}
