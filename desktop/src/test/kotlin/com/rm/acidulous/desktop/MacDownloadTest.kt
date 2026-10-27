package com.rm.acidulous.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MacDownloadTest {
    private val base = "https://github.com/roge-rm/Acidulous/releases/download/v0.13.0/acidulous-0.13.0-macos-"

    @Test
    fun eachMacGetsItsOwnDmg() {
        assertEquals(base + "arm64.dmg", DesktopHost.macDmg("0.13.0", "aarch64", mac = true))
        assertEquals(base + "x86_64.dmg", DesktopHost.macDmg("0.13.0", "x86_64", mac = true))
    }

    @Test
    fun otherDesktopsKeepTheReleasesPage() {
        assertNull(DesktopHost.macDmg("0.13.0", "amd64", mac = false))
    }
}
