package com.rm.acidulous

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class ReleaseVersionTest {
    private val withoutDmg = """{"tag_name":"v0.13.0","assets":[{"name":"acidulous-0.13.0.apk"}]}"""
    private val withDmg = """{"tag_name":"v0.13.0","assets":[{"name": "acidulous-0.13.0-macos-arm64.dmg"}]}"""
    private val name = { v: String -> "acidulous-$v-macos-arm64.dmg" }

    @Test
    fun anyReleaseCountsWhenNoFileIsNeeded() {
        assertEquals("0.13.0", releaseVersion(withoutDmg))
    }

    @Test
    fun aMacWaitsForItsDmg() {
        assertNull(releaseVersion(withoutDmg, name))
        assertEquals("0.13.0", releaseVersion(withDmg, name))
        assertNull(releaseVersion(withDmg) { v -> "acidulous-$v-macos-x86_64.dmg" })
    }
}
