package com.rm.acidulous.util

/** android.util.Log's calls, for code that also runs off Android: the system log there, stderr on the desktop. */
expect object Log {
    fun d(tag: String, message: String): Int
    fun i(tag: String, message: String): Int
    fun w(tag: String, message: String): Int
    fun w(tag: String, message: String, error: Throwable?): Int
    fun e(tag: String, message: String): Int
    fun e(tag: String, message: String, error: Throwable?): Int
}
