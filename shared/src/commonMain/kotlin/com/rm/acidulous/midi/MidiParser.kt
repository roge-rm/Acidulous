package com.rm.acidulous.midi

/**
 * Turns a MIDI byte stream back into messages.
 *
 * A port doesn't deliver whole messages, just whatever arrived: half of one,
 * three and a half of the next, or data with no status byte because the
 * sender uses running status. Bluetooth adds header and timestamp bytes and
 * bundles several messages per packet.
 *
 * So bytes go through here and come out as complete channel messages.
 * Real-time bytes (clock, start, stop) can arrive in the middle of another
 * message and mustn't disturb it.
 */
class MidiParser(
    private val onMessage: (status: Int, data1: Int, data2: Int) -> Unit,
    /** Clock, start, continue, stop and song position: what a clock master
     *  sends. Passed with their arrival timestamp, since their timing is what
     *  matters. */
    private val onRealtime: (status: Int, data1: Int, data2: Int, stamp: Long) -> Unit = { _, _, _, _ -> },
    /** A whole SysEx message, the bytes between F0 and F7. One cut short by
     *  another status byte, or longer than any device here sends, is dropped. */
    private val onSysex: (body: ByteArray) -> Unit = {},
) {
    private var runningStatus = 0
    private var pending = 0
    private var wanted = 0
    private var data1 = 0
    private var inSysex = false
    private val sysex = com.rm.acidulous.util.ByteArrayOutputStream()

    fun reset() {
        runningStatus = 0
        pending = 0
        wanted = 0
        inSysex = false
    }

    fun parse(bytes: ByteArray, offset: Int, count: Int, stamp: Long = 0L) {
        timestamp = stamp
        for (i in offset until offset + count) {
            feed(bytes[i].toInt() and 0xff)
        }
    }

    private var timestamp = 0L

    private fun feed(b: Int) {
        when {
            // Real time: a single byte, allowed anywhere, even mid-message,
            // and passed straight out without disturbing a half-read message.
            b >= 0xf8 -> onRealtime(b, 0, 0, timestamp)
            // Any other status byte cancels running status and any
            // half-finished message.
            b == 0xf0 -> { inSysex = true; sysex.reset(); runningStatus = 0; wanted = 0; data1 = -1 }
            b == 0xf7 -> {
                if (inSysex) onSysex(sysex.toByteArray())
                inSysex = false
            }
            b == 0xf2 -> { inSysex = false; runningStatus = 0; wanted = 2; data1 = -1; pending = b }
            b >= 0xf1 -> { inSysex = false; runningStatus = 0; wanted = 0; data1 = -1 }
            b >= 0x80 -> {
                inSysex = false
                runningStatus = b
                pending = b
                wanted = bytesFor(b)
                data1 = -1
            }
            inSysex -> {
                if (sysex.size() < MAX_SYSEX) sysex.write(b) else inSysex = false
            }
            else -> {
                // A data byte without its own status uses the last one
                // (running status). Keyboards sending fast use it for every
                // note after the first.
                if (wanted == 0) {
                    if (runningStatus == 0) return
                    pending = runningStatus
                    wanted = bytesFor(runningStatus)
                    data1 = -1
                }
                if (wanted == 1) {
                    onMessage(pending, b, 0)
                    wanted = if (runningStatus == 0) 0 else bytesFor(runningStatus)
                    data1 = -1
                } else if (data1 < 0) {
                    data1 = b
                } else {
                    // Song position is the only two-byte message that isn't
                    // for a rack. It says where the clock master is, so it goes
                    // out with the clock, not the notes.
                    if (pending == 0xf2) onRealtime(0xf2, data1, b, timestamp) else onMessage(pending, data1, b)
                    if (runningStatus == 0) wanted = 0
                    data1 = -1
                }
            }
        }
    }

    private companion object {
        /** The longest message this app gets is an Exquis snapshot: 255 bytes plus its header. */
        const val MAX_SYSEX = 1024
    }

    private fun bytesFor(status: Int): Int = when (status and 0xf0) {
        0xc0, 0xd0 -> 1   // program change, channel pressure
        else -> 2
    }
}
