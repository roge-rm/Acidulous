package com.rm.acidulous.desktop

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportsTest {
    private fun dataDir(): File = Files.createTempDirectory("crashes").toFile().apply { deleteOnExit() }

    @Test
    fun aKotlinCrashIsWrittenAndUnread() {
        val reports = CrashReports(dataDir())
        val before = Thread.getDefaultUncaughtExceptionHandler()
        try {
            // No handler after ours, so the test run itself doesn't end.
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
            reports.install()
            Thread({ throw IllegalStateException("boom in a test") }, "crasher").apply { start(); join() }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
        val report = reports.unread()
        assertNotNull(report)
        val text = report!!.readText()
        assertTrue(text.startsWith("Acidulous "))
        assertTrue(text.contains("Crashed on thread \"crasher\""))
        assertTrue(text.contains("IllegalStateException: boom in a test"))
        assertEquals(report, reports.latest())
        reports.markRead()
        assertNull(reports.unread())
        assertEquals(report, reports.latest())
    }

    @Test
    fun aNativeCrashFileBecomesAReport() {
        val data = dataDir()
        val reports = CrashReports(data)
        val hsErr = File(reports.directory, "hs_err_4242.log").apply {
            writeText("# A fatal error has been detected by the Java Runtime Environment:\n# SIGSEGV (0xb)\n")
        }
        reports.collect()
        assertFalse(hsErr.exists())
        val report = reports.unread()!!
        assertTrue(report.name.endsWith("-native.txt"))
        assertTrue(report.readText().contains("SIGSEGV (0xb)"))
    }

    @Test
    fun onlyTheLastTenAreKept() {
        val reports = CrashReports(dataDir())
        repeat(12) { i ->
            File(reports.directory, "hs_err_$i.log").apply {
                writeText("crash $i")
                setLastModified(1_700_000_000_000L + i * 60_000L)
            }
        }
        reports.collect()
        val left = reports.directory.listFiles { f -> f.extension == "txt" }!!
        assertEquals(10, left.size)
        assertTrue(reports.latest()!!.readText().contains("crash 11"))
    }
}
