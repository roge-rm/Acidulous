package com.rm.acidulous.util

/** Java's own, in the device's locale: what the app has always used here. */
actual fun String.format(vararg args: Any?): String = java.lang.String.format(this, *args)
