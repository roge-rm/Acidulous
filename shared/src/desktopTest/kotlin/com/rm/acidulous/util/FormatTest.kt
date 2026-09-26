package com.rm.acidulous.util

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** The browser's formatter against Java's, for every kind of pattern the app writes. */
class FormatTest {
    private fun same(pattern: String, vararg args: Any?) =
        assertEquals("$pattern ${args.toList()}", java.lang.String.format(Locale.ROOT, pattern, *args), javaFormat(pattern, args))

    @Test
    fun theAppsPatterns() {
        for (v in listOf(0, 1, 7, 42, 128, -3, -12, 1000)) {
            same("%02d", v); same("%03d", v); same("%d", v); same("%+d", v)
        }
        for (v in listOf(0.0, 0.004, 0.005, 0.125, 1.005, 2.5, 3.14159, -0.4, -2.345, 99.995, 120.0, 1e6 + 0.5, -0.0)) {
            same("%.0f", v); same("%.1f", v); same("%.2f", v); same("%.3f", v); same("%+.1f", v); same("%+.2f", v)
            same("%.4f", v); same("%.5f", v); same("%f", v)
        }
        same("%.1f", 2.5f); same("%.2f", 0.1f)
        same("%02x", 10); same("%02x", 255); same("%02x", 0)
        same("%s and %s", "a", "b"); same("%1\$s · %2\$d · %1\$s", "x", 3); same("%3\$+.2f %1\$d %2\$s", 5, "q", 1.234)
        same("100%%"); same("%8\$03d", 1, 2, 3, 4, 5, 6, 7, 8); same("%s", null as Any?)
        same("%5d|%-5d|%05d", 42, 42, -42)
    }
}
