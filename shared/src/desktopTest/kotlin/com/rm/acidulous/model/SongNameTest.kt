package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** Song file names keep what a player typed, apart from what no file system takes. */
class SongNameTest {
    @Test fun keepsBracketsAndAccents() {
        assertEquals("Squelch (2)", SongStore.safeName("Squelch (2)"))
        assertEquals("Chanson d'été", SongStore.safeName(" Chanson d'été "))
    }

    @Test fun swapsWhatFilesCantHold() {
        assertEquals("a_b_c_d", SongStore.safeName("a/b\\c:d"))
        assertEquals("x_y", SongStore.safeName("x\ny"))
        assertEquals("hidden", SongStore.safeName("..hidden"))
        assertEquals("untitled", SongStore.safeName("  "))
    }

    @Test fun longNamesFitAFile() {
        val name = SongStore.safeName("é".repeat(300) + "😀".repeat(100))
        assert(name.encodeToByteArray().size <= 200)
        assertEquals("é".repeat(100), name)
        val emoji = SongStore.safeName("😀".repeat(100))
        assertEquals("😀".repeat(50), emoji)
    }
}
