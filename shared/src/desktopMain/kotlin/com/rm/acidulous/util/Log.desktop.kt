package com.rm.acidulous.util

actual object Log {
    /** The last lines said, for a crash report: what a phone's log keeps for the dead process. */
    private val recent = ArrayDeque<String>()
    private const val KEEP = 300

    private fun say(level: Char, tag: String, message: String, error: Throwable? = null): Int {
        val line = "$level/$tag: $message"
        System.err.println(line)
        error?.printStackTrace()
        synchronized(recent) {
            recent.addLast(if (error == null) line else "$line\n    $error")
            while (recent.size > KEEP) recent.removeFirst()
        }
        return 0
    }

    /** What was logged lately, oldest first. */
    fun recent(): String = synchronized(recent) { recent.joinToString("\n") }

    actual fun d(tag: String, message: String): Int = say('D', tag, message)
    actual fun i(tag: String, message: String): Int = say('I', tag, message)
    actual fun w(tag: String, message: String): Int = say('W', tag, message)
    actual fun w(tag: String, message: String, error: Throwable?): Int = say('W', tag, message, error)
    actual fun e(tag: String, message: String): Int = say('E', tag, message)
    actual fun e(tag: String, message: String, error: Throwable?): Int = say('E', tag, message, error)
}
