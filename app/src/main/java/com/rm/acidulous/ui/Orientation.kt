package com.rm.acidulous.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalWindowInfo

/**
 * Is the long edge across?
 *
 * **From the window, not from the configuration.** `Configuration.ORIENTATION`
 * describes the *device*, and there are two ordinary cases where the device
 * and the window disagree: a split-screen app is half a landscape phone and
 * is shaped like a portrait one, and a fold changes shape without the
 * orientation constant moving at all. `ui/Cutout.kt` has read the window size
 * for the camera hole since it was written, for exactly that reason, and this
 * is the same test.
 *
 * It lived as a local in `EditScreen` while the editor was the only screen
 * that knew the phone had turned. M43 turns all four, so it lives here.
 */
@Composable
fun isLandscape(): Boolean {
    val size = LocalWindowInfo.current.containerSize
    return size.width > size.height
}
