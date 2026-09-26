package com.rm.acidulous.util

/** Run [task] on the UI thread, later: Android's main looper, or the desktop's event thread. */
expect fun postToMain(task: () -> Unit)
