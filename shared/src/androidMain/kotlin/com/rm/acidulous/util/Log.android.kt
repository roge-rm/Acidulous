package com.rm.acidulous.util

actual object Log {
    actual fun d(tag: String, message: String): Int = android.util.Log.d(tag, message)
    actual fun i(tag: String, message: String): Int = android.util.Log.i(tag, message)
    actual fun w(tag: String, message: String): Int = android.util.Log.w(tag, message)
    actual fun w(tag: String, message: String, error: Throwable?): Int = android.util.Log.w(tag, message, error)
    actual fun e(tag: String, message: String): Int = android.util.Log.e(tag, message)
    actual fun e(tag: String, message: String, error: Throwable?): Int = android.util.Log.e(tag, message, error)
}
