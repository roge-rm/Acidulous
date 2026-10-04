package com.rm.acidulous.engine

import com.rm.acidulous.model.Clip
import com.rm.acidulous.model.Machine
import com.rm.acidulous.model.Note
import com.rm.acidulous.model.Scene
import com.rm.acidulous.model.Song
import com.rm.acidulous.model.Track
import com.rm.acidulous.model.addNote
import org.junit.Assert.assertEquals
import org.junit.Test

/** A take that replaces: old notes go as the playhead passes them, from the first note on. */
class RecorderReplaceTest {

    private fun song(vararg ticks: Int) = Song(
        name = "r", tempo = 120f,
        tracks = listOf(Track(id = "t", name = "t", machine = Machine("Trinity"),
            clips = mapOf("s" to Clip(bars = 1, notes = ticks.map { Note(it, 60, 60, 100) })))),
        scenes = listOf(Scene(id = "s", name = "s")),
    )
    private fun ticks(s: Song) = s.tracks[0].clips["s"]!!.notes.map { it.tick }.sorted()

    @Test
    fun theOldNotesGoAsThePlayheadPassesThem() {
        val r = Recorder()
        var s = song(0, 240, 480, 720)
        r.startReplacing(s, 0, "s", 240L)
        s = r.takeOutPassed(s) { "s" to 600L }
        assertEquals("from the first note to the playhead", listOf(0, 720), ticks(s))
        // What's played meanwhile stays, and round the loop the rest goes.
        s = s.addNote(0, "s", Note(300, 60, 62, 100))
        s = r.takeOutPassed(s) { "s" to 100L }
        assertEquals(listOf(300), ticks(s))
    }

    @Test
    fun nothingGoesBeforeTheFirstNote() {
        val r = Recorder()
        var s = song(0, 240, 480, 720)
        s = r.takeOutPassed(s) { "s" to 600L }
        assertEquals(listOf(0, 240, 480, 720), ticks(s))
    }
}
