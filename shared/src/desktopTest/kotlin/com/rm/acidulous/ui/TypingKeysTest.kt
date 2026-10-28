package com.rm.acidulous.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Alt (Option on a Mac) chords reach a focused text field, where they type
 * accents and other characters, and are shortcuts everywhere else. And the
 * keys a Mac's Edit menu shows. Whether a Mac types é from Option+E then E
 * needs a Mac.
 */
class TypingKeysTest {
    private val handle = KeyScopeHandle(window = false, handlers = emptyMap())

    @After
    fun reset() {
        KeyHub.remove(handle)
        KeyHub.typingFields = 0
    }

    private fun altDown(code: Int) = KeyPress(KeyCodes.ACTION_DOWN, code, isAltPressed = true)

    @Test
    fun anAltChordIsAShortcutWithNoFieldFocused() {
        var duplicated = 0
        handle.handlers = mapOf(KeyAction.Duplicate to { duplicated++ })
        KeyHub.push(handle)
        assertTrue(KeyHub.preview(altDown(KeyCodes.KEYCODE_E)))
        assertEquals(1, duplicated)
    }

    @Test
    fun anAltChordGoesToAFocusedField() {
        var ran = 0
        handle.handlers = KeyAction.entries.associateWith { { ran++ } }
        KeyHub.push(handle)
        TypingField().focused(true)
        // Option+E (a dead key), Option+U, Option+N, Option+8.
        for (code in listOf(KeyCodes.KEYCODE_E, KeyCodes.KEYCODE_U, KeyCodes.KEYCODE_N, KeyCodes.KEYCODE_8)) {
            assertFalse(KeyHub.preview(altDown(code)))
            assertFalse(KeyHub.fallback(altDown(code)))
        }
        assertEquals(0, ran)
    }

    @Test
    fun movingBetweenFieldsKeepsTypingWhicheverIsToldFirst() {
        val a = TypingField()
        val b = TypingField()
        a.focused(true)
        b.focused(true)
        a.focused(false)
        assertTrue(KeyHub.typing)
        b.focused(false)
        assertFalse(KeyHub.typing)
    }

    @Test
    fun aFieldToldTwiceCountsOnce() {
        val a = TypingField()
        a.focused(true)
        a.focused(true)
        a.focused(false)
        assertFalse(KeyHub.typing)
        // Gone from the screen after losing focus: no change.
        a.focused(false)
        assertEquals(0, KeyHub.typingFields)
    }

    @Test
    fun theMenuShowsTheCmdKey() {
        assertEquals(KeyShortcut(Key.Z, meta = true), macMenuShortcut(DEFAULT_KEYS.getValue(KeyAction.Undo)))
        assertEquals(KeyShortcut(Key.Z, meta = true, shift = true), macMenuShortcut(DEFAULT_KEYS.getValue(KeyAction.Redo)))
    }

    @Test
    fun theMenuFollowsARemappedKey() {
        assertEquals(
            KeyShortcut(Key.K, meta = true, shift = true),
            macMenuShortcut(listOf(KeyChord(KeyCodes.KEYCODE_K, alt = true), KeyChord(KeyCodes.KEYCODE_K, ctrl = true, shift = true))),
        )
        // Ctrl on a Mac when there's no Cmd chord.
        assertEquals(KeyShortcut(Key.P, ctrl = true), macMenuShortcut(listOf(KeyChord(KeyCodes.KEYCODE_P, meta = true))))
    }

    @Test
    fun theMenuNeverShowsOptionOrPlainKeys() {
        assertNull(macMenuShortcut(listOf(KeyChord(KeyCodes.KEYCODE_E, alt = true))))
        assertNull(macMenuShortcut(listOf(KeyChord(KeyCodes.KEYCODE_D))))
        assertNull(macMenuShortcut(emptyList()))
    }
}
