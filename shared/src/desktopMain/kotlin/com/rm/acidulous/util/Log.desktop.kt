package com.rm.acidulous.util

actual object Log {
    private fun say(level: Char, tag: String, message: String, error: Throwable? = null): Int {
        System.err.println("$level/$tag: $message")
        error?.printStackTrace()
        return 0
    }
    actual fun d(tag: String, message: String): Int = say('D', tag, message)
    actual fun i(tag: String, message: String): Int = say('I', tag, message)
    actual fun w(tag: String, message: String): Int = say('W', tag, message)
    actual fun w(tag: String, message: String, error: Throwable?): Int = say('W', tag, message, error)
    actual fun e(tag: String, message: String): Int = say('E', tag, message)
    actual fun e(tag: String, message: String, error: Throwable?): Int = say('E', tag, message, error)
}
