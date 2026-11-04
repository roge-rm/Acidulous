package com.rm.acidulous.desktop

import com.rm.acidulous.Incoming
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.EventQueue
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files

/**
 * OpenFiles runs on the UI thread, and [OpenFiles.add] queues a [OpenFiles.next]
 * there, so each test runs there too. From the test thread that queued call
 * could take a file while the test looks for it.
 */
class OpenFilesTest {
    private fun onUi(body: () -> Unit) {
        try {
            EventQueue.invokeAndWait(body)
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    @After
    fun reset() = onUi {
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
    fun theNextFileWaitsForTheImportAndItsDialog() = onUi {
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
    fun aFileOpenedAloneIsNotHeldByANotice() = onUi {
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
