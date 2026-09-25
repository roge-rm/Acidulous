package com.rm.acidulous.util

import javax.swing.SwingUtilities

actual fun postToMain(task: () -> Unit) = SwingUtilities.invokeLater(task)
