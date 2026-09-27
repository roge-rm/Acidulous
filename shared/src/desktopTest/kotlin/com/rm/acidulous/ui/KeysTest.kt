package com.rm.acidulous.ui


import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keyboard logic: which key chord is which action, how a stored binding reads
 * back, and which note a letter plays. Whether keys reach the screen and notes
 * record needs the app running.
 */
class KeysTest {
    @Test
    fun noTwoActionsShareADefaultKey() {
        val all = DEFAULT_KEYS.values.flatten()
        assertEquals("a default chord is on two actions", all.size, all.toSet().size)
    }

    @Test
    fun everyActionHasAKey() {
        for (a in KeyAction.entries) assertTrue("$a has no key", DEFAULT_KEYS[a].orEmpty().isNotEmpty())
    }

    @Test
    fun aChordFindsItsActionAndOnlyWithItsModifiers() {
        assertEquals(KeyAction.Undo, actionFor(KeyChord(KeyCodes.KEYCODE_Z, ctrl = true), DEFAULT_KEYS))
        assertEquals(KeyAction.Undo, actionFor(KeyChord(KeyCodes.KEYCODE_Z, alt = true), DEFAULT_KEYS))
        assertEquals(KeyAction.Redo, actionFor(KeyChord(KeyCodes.KEYCODE_Z, ctrl = true, shift = true), DEFAULT_KEYS))
        // Plain Z is the octave key in play mode.
        assertNull(actionFor(KeyChord(KeyCodes.KEYCODE_Z), DEFAULT_KEYS))
    }

    @Test
    fun storedBindingsReadBackAsWritten() {
        val mine = mapOf(
            KeyAction.Undo to listOf(KeyChord(KeyCodes.KEYCODE_U, alt = true)),
            KeyAction.Record to emptyList(),
            KeyAction.PlayStop to listOf(KeyChord(KeyCodes.KEYCODE_ENTER, ctrl = true, shift = true, meta = true)),
        )
        assertEquals(mine, decodeKeys(encodeKeys(mine)))
    }

    @Test
    fun anActionAnOlderVersionHadIsIgnored() {
        assertEquals(mapOf(KeyAction.Loop to listOf(KeyChord(KeyCodes.KEYCODE_O))), decodeKeys("Gone=:9;Loop=:43;Junk"))
    }

    @Test
    fun theHomeRowIsAPiano() {
        // A is C, K is the C above, and the black keys sit between.
        assertEquals(60, noteFor(NOTE_KEYS.getValue(KeyCodes.KEYCODE_A), 4, null))
        assertEquals(61, noteFor(NOTE_KEYS.getValue(KeyCodes.KEYCODE_W), 4, null))
        assertEquals(72, noteFor(NOTE_KEYS.getValue(KeyCodes.KEYCODE_K), 4, null))
        assertEquals(48, noteFor(NOTE_KEYS.getValue(KeyCodes.KEYCODE_A), 3, null))
        // Always stays in MIDI's range, whatever the octave.
        assertEquals(127, noteFor(17, 9, null))
        assertEquals(0, noteFor(0, -2, null))
    }

    @Test
    fun onADrumMachineTheKeysAreItsPads() {
        val pads = listOf(36, 38, 42)
        assertEquals(36, noteFor(0, 4, pads))
        assertEquals(42, noteFor(2, 4, pads))
        assertEquals(36, noteFor(3, 4, pads)) // round again
    }

    @Test
    fun theTrackerLayoutIsTwoOctavesOnTwoRows() {
        val t = NoteLayout.Tracker.notes
        assertEquals(60, noteFor(t.getValue(KeyCodes.KEYCODE_Z), 4, null))
        assertEquals(61, noteFor(t.getValue(KeyCodes.KEYCODE_S), 4, null))
        assertEquals(72, noteFor(t.getValue(KeyCodes.KEYCODE_Q), 4, null))
        // The bottom row's C above is the top row's first key.
        assertEquals(t.getValue(KeyCodes.KEYCODE_COMMA), t.getValue(KeyCodes.KEYCODE_Q))
        assertEquals(88, noteFor(t.getValue(KeyCodes.KEYCODE_P), 4, null))
    }

    @Test
    fun aLayoutsOwnKeysAreNotItsNotes() {
        for (l in NoteLayout.entries) {
            for (k in listOf(l.octaveDown, l.octaveUp, l.velocityDown, l.velocityUp)) {
                assertTrue("$l plays a note on its own key $k", k !in l.notes)
            }
        }
    }
}
