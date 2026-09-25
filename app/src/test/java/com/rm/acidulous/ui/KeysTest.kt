package com.rm.acidulous.ui

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keyboard's arithmetic: which chord is which action, what a stored
 * binding reads back as, and which note a letter plays. The rest of it - does
 * a key reach the screen, does the note record - needs the app running.
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
        assertEquals(KeyAction.Undo, actionFor(KeyChord(KeyEvent.KEYCODE_Z, ctrl = true), DEFAULT_KEYS))
        assertEquals(KeyAction.Undo, actionFor(KeyChord(KeyEvent.KEYCODE_Z, alt = true), DEFAULT_KEYS))
        assertEquals(KeyAction.Redo, actionFor(KeyChord(KeyEvent.KEYCODE_Z, ctrl = true, shift = true), DEFAULT_KEYS))
        // Plain Z is play mode's octave key, not undo.
        assertNull(actionFor(KeyChord(KeyEvent.KEYCODE_Z), DEFAULT_KEYS))
    }

    @Test
    fun storedBindingsReadBackAsWritten() {
        val mine = mapOf(
            KeyAction.Undo to listOf(KeyChord(KeyEvent.KEYCODE_U, alt = true)),
            KeyAction.Record to emptyList(),
            KeyAction.PlayStop to listOf(KeyChord(KeyEvent.KEYCODE_ENTER, ctrl = true, shift = true, meta = true)),
        )
        assertEquals(mine, decodeKeys(encodeKeys(mine)))
    }

    @Test
    fun anActionAnOlderVersionHadIsIgnored() {
        assertEquals(mapOf(KeyAction.Loop to listOf(KeyChord(KeyEvent.KEYCODE_O))), decodeKeys("Gone=:9;Loop=:43;Junk"))
    }

    @Test
    fun theHomeRowIsAPiano() {
        // A is the octave's C; K is the C above; the black keys sit between.
        assertEquals(60, noteFor(NOTE_KEYS.getValue(KeyEvent.KEYCODE_A), 4, null))
        assertEquals(61, noteFor(NOTE_KEYS.getValue(KeyEvent.KEYCODE_W), 4, null))
        assertEquals(72, noteFor(NOTE_KEYS.getValue(KeyEvent.KEYCODE_K), 4, null))
        assertEquals(48, noteFor(NOTE_KEYS.getValue(KeyEvent.KEYCODE_A), 3, null))
        // Nothing outside MIDI's range, whatever the octave.
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
}
