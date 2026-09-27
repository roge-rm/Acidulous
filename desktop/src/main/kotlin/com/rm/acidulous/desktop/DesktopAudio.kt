package com.rm.acidulous.desktop

import com.rm.acidulous.AudioInput
import com.rm.acidulous.res.AppStrings
import com.rm.acidulous.res.Res
import com.rm.acidulous.res.settings_output_driver

/**
 * The sound server's inputs and outputs, for the recorder and Settings. On
 * Android AudioManager provides the inputs.
 */
internal object DesktopAudio {
    init {
        // The engine's library. NativeEngine has already loaded it, so this
        // does nothing.
        System.loadLibrary("acidulous")
    }

    @JvmStatic
    private external fun nativeInputs(): Array<String>

    @JvmStatic
    private external fun nativeOutputs(): Array<String>

    @JvmStatic
    private external fun nativeChooseOutput(id: Int)

    /**
     * The sound server's outputs by id and name, offered in Settings. On
     * Windows, audio interfaces' own ASIO drivers are listed after them under
     * the maker's name, marked as the low-latency option.
     */
    fun outputs(): List<Pair<Int, String>> =
        runCatching { nativeOutputs() }.getOrDefault(emptyArray()).toList().chunked(3)
            .mapNotNull { (id, name, key) ->
                val shown = if (key.startsWith("driver:")) AppStrings.getString(Res.string.settings_output_driver, name) else name
                id.toIntOrNull()?.let { it to shown }
            }

    /** Play through [id] from now on, reopening the stream if it's running. 0 is the default. */
    fun chooseOutput(id: Int) {
        runCatching { nativeChooseOutput(id) }
    }

    /**
     * Re-read at most every two seconds. The recorder asks on every redraw,
     * and here each read is a round trip to the sound server.
     */
    private var cached: List<AudioInput> = emptyList()
    private var readAt = 0L

    @Synchronized
    fun inputs(): List<AudioInput> {
        val now = System.nanoTime()
        if (readAt == 0L || now - readAt > 2_000_000_000L) {
            cached = runCatching { nativeInputs() }.getOrDefault(emptyArray()).toList().chunked(3).map { (id, name, key) ->
                AudioInput(id.toInt(), kindOf(name, key), name)
            }
            readAt = now
        }
        return cached
    }
}

/**
 * What kind of input it is, from PulseAudio's names (PipeWire uses the same
 * ones): "alsa_input.usb-...", "bluez_input...", "alsa_input.pci-..." for
 * the built-in one. A monitor is the computer listening to its own output,
 * so it isn't a real input.
 */
internal fun kindOf(name: String, key: String): AudioInput.Kind {
    val k = key.lowercase()
    return when {
        k.endsWith(".monitor") || name.startsWith("Monitor of ") -> AudioInput.Kind.NotAnEar
        k.startsWith("bluez") -> AudioInput.Kind.Bluetooth
        ".usb-" in k || name.contains("USB") -> AudioInput.Kind.Usb
        ".pci-" in k || name.startsWith("Built-in") -> AudioInput.Kind.BuiltIn
        else -> AudioInput.Kind.Other
    }
}
