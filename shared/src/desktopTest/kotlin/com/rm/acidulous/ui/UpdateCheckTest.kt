package com.rm.acidulous.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    @Test fun `later versions are newer, part by part`() {
        assertTrue(UpdateCheck.isNewer("0.11.3", "0.11.2"))
        assertTrue(UpdateCheck.isNewer("0.12.0", "0.11.9"))
        assertTrue(UpdateCheck.isNewer("0.10.0", "0.9.13"))
        assertTrue(UpdateCheck.isNewer("1.0", "0.99.99"))
        assertFalse(UpdateCheck.isNewer("0.11.2", "0.11.2"))
        assertFalse(UpdateCheck.isNewer("0.11.1", "0.11.2"))
        assertFalse(UpdateCheck.isNewer("0.11", "0.11.0"))
    }
}
