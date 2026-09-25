package com.rm.acidulous.util

import android.os.Handler
import android.os.Looper

private val main by lazy { Handler(Looper.getMainLooper()) }

actual fun postToMain(task: () -> Unit) { main.post(task) }
