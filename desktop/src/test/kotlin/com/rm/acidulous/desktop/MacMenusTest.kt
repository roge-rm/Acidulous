package com.rm.acidulous.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent

/** An Edit item runs its action from a mouse click, never from its key, which the app has had already. */
class MacMenusTest {
    private val source = object : java.awt.Component() {}
    private val second = 1_000_000_000L

    @Test
    fun aClickRuns() {
        assertTrue(menuClickRuns(ActionEvent(source, ActionEvent.ACTION_PERFORMED, "undo"), second))
        assertTrue(menuClickRuns(null, second))
    }

    @Test
    fun itsKeyDoesNot() {
        val cmdZ = KeyEvent(source, KeyEvent.KEY_PRESSED, 0L, KeyEvent.META_DOWN_MASK, KeyEvent.VK_Z, 'z')
        assertFalse(menuClickRuns(cmdZ, second))
    }

    @Test
    fun aClickJustAfterAKeyTheAppUsedDoesNot() {
        assertFalse(menuClickRuns(ActionEvent(source, ActionEvent.ACTION_PERFORMED, "undo"), 10_000_000L))
    }
}
