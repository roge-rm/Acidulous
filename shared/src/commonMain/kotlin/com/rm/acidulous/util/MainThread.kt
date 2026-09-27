package com.rm.acidulous.util

/** Runs [task] later on the UI thread: Android's main looper, or the desktop's event thread. */
expect fun postToMain(task: () -> Unit)
