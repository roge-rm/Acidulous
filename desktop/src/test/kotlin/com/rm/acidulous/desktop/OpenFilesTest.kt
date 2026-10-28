package com.rm.acidulous.desktop

import com.rm.acidulous.Incoming
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OpenFilesTest {
    @After
    fun reset() {
        OpenFiles.running = false
        OpenFiles.drain()
        Incoming.doc = null
        Incoming.running = 0
        Incoming.dialogOpen = { false }
    }

    private fun files(vararg names: String): List<File> {
        val dir = Files.createTempDirectory("open").toFile().apply { deleteOnExit() }
        return names.map { File(dir, it).apply { writeText("x"); deleteOnExit() } }
    }

    @Test
    fun theNextFileWaitsForTheImportAndItsDialog() {
        val list = files("one.zip", "two.zip", "bank.acpreset")
        OpenFiles.add(list)
        OpenFiles.running = true
        var dialog = false
        Incoming.dialogOpen = { dialog }

        OpenFiles.next()
        assertNotNull(Incoming.doc)
        Incoming.doc = null
        Incoming.running++                 // the first import starts
        OpenFiles.next()
        assertNull(Incoming.doc)
        dialog = true                      // it ends with a notice up
        Incoming.running--
        OpenFiles.next()
        assertNull(Incoming.doc)
        dialog = false                     // the notice is closed
        OpenFiles.next()
        assertNotNull(Incoming.doc)
        Incoming.doc = null
        Incoming.running++
        OpenFiles.next()
        assertNull(Incoming.doc)
        Incoming.running--
        OpenFiles.next()                   // the preset comes last
        assertNotNull(Incoming.doc)
        Incoming.doc = null
        OpenFiles.next()
        assertNull(Incoming.doc)
        assertEquals(emptyList<File>(), OpenFiles.drain())
    }

    @Test
    fun aFileOpenedAloneIsNotHeldByANotice() {
        OpenFiles.running = true
        Incoming.dialogOpen = { true }
        OpenFiles.add(files("one.acpreset"))
        OpenFiles.next()
        assertNotNull(Incoming.doc)
        Incoming.doc = null
        OpenFiles.add(files("two.zip"))
        OpenFiles.next()
        assertNotNull(Incoming.doc)
    }

    @Test
    fun importsGetTheirOwnTempFiles() {
        val names = (1..50).map { Incoming.tempName("import", "zip") }.toSet()
        assertEquals(50, names.size)
    }
}
