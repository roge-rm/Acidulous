package com.rm.acidulous.desktop

import com.rm.acidulous.AudioInput

/**
 * The sound server's inputs, for the recorder's list of them: what Android's
 * AudioManager answers on the phone.
 */
internal object DesktopAudio {
    init {
        // The engine's library, which NativeEngine has loaded already; a second
        // load is nothing.
        System.loadLibrary("acidulous")
    }

    @JvmStatic
    private external fun nativeInputs(): Array<String>

    @JvmStatic
    private external fun nativeOutputs(): Array<String>

    @JvmStatic
    private external fun nativeChooseOutput(id: Int)

    /** The sound server's outputs, by id and name: what Settings offers to play through. */
    fun outputs(): List<Pair<Int, String>> =
        runCatching { nativeOutputs() }.getOrDefault(emptyArray()).toList().chunked(3)
            .mapNotNull { (id, name, _) -> id.toIntOrNull()?.let { it to name } }

    /** Play through [id] from now on, reopening the stream if it is running; nought is the default. */
    fun chooseOutput(id: Int) {
        runCatching { nativeChooseOutput(id) }
    }

    /**
     * Read again at most every two seconds: the recorder asks on every
     * redraw, which is nothing on the phone and a round trip to the sound
     * server here.
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
 * What kind of ear, from PulseAudio's names for it - PipeWire answers as
 * PulseAudio and names them the same way: "alsa_input.usb-...",
 * "bluez_input...", "alsa_input.pci-..." for the one on the board. A monitor
 * is the computer listening to its own output, so not an ear at all.
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
