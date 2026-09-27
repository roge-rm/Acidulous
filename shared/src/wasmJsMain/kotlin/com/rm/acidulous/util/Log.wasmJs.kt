package com.rm.acidulous.util

private fun consoleLog(line: String): Unit = js("console.log(line)")
private fun consoleWarn(line: String): Unit = js("console.warn(line)")
private fun consoleError(line: String): Unit = js("console.error(line)")

/** Logs to the browser console in Android's "L/tag: message" format, so logs look the same everywhere. */
actual object Log {
    private fun line(level: Char, tag: String, message: String, error: Throwable?) =
        "$level/$tag: $message" + (error?.let { "\n    ${it.stackTraceToString()}" } ?: "")

    actual fun d(tag: String, message: String): Int { consoleLog(line('D', tag, message, null)); return 0 }
    actual fun i(tag: String, message: String): Int { consoleLog(line('I', tag, message, null)); return 0 }
    actual fun w(tag: String, message: String): Int { consoleWarn(line('W', tag, message, null)); return 0 }
    actual fun w(tag: String, message: String, error: Throwable?): Int { consoleWarn(line('W', tag, message, error)); return 0 }
    actual fun e(tag: String, message: String): Int { consoleError(line('E', tag, message, null)); return 0 }
    actual fun e(tag: String, message: String, error: Throwable?): Int { consoleError(line('E', tag, message, error)); return 0 }
}

actual fun postToMain(task: () -> Unit) = postLater(task)

actual fun String.format(vararg args: Any?): String = javaFormat(this, args)
