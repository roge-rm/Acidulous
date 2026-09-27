package com.rm.acidulous.util

/** Java's String.format, in the device's locale. */
actual fun String.format(vararg args: Any?): String = java.lang.String.format(this, *args)
