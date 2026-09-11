package com.rm.acidulous.midi

/**
 * A MIDI byte stream, turned back into messages.
 *
 * A port does not hand over whole messages. It hands over whatever arrived,
 * which may be half of one, three and a half of the next, or a status byte
 * that is not there at all because the sender is using running status and
 * assumes we remember. Bluetooth makes this worse: a packet carries its own
 * header and timestamp bytes, and several messages are bundled behind them.
 *
 * So the bytes go through here and come out as complete channel messages.
 * Real-time bytes (clock, start, stop) can arrive *inside* another message
 * and must not disturb it, which is the one rule that catches people out.
 */
class MidiParser(
    private val onMessage: (status: Int, data1: Int, data2: Int) -> Unit,
    /** Clock, start, continue, stop and song position - the bytes a master
     *  sends to be followed. Given the timestamp they arrived with, because
     *  the whole value of them is *when* they were. */
    private val onRealtime: (status: Int, data1: Int, data2: Int, stamp: Long) -> Unit = { _, _, _, _ -> },
) {
    private var runningStatus = 0
    private var pending = 0
    private var wanted = 0
    private var data1 = 0
    private var inSysex = false

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
            // Real time: a single byte, legal anywhere, even mid-message,
            // and passed straight out without disturbing anything half read.
            // These used to be dropped on the floor, which is why nothing
            // could follow an external clock.
            b >= 0xf8 -> onRealtime(b, 0, 0, timestamp)
            // Any status byte that is not real time cancels running status,
            // and a half-finished message with it.
            b == 0xf0 -> { inSysex = true; runningStatus = 0; wanted = 0; data1 = -1 }
            b == 0xf7 -> inSysex = false
            b == 0xf2 -> { inSysex = false; runningStatus = 0; wanted = 2; data1 = -1; pending = b }
            b >= 0xf1 -> { inSysex = false; runningStatus = 0; wanted = 0; data1 = -1 }
            b >= 0x80 -> {
                inSysex = false
                runningStatus = b
                pending = b
                wanted = bytesFor(b)
                data1 = -1
            }
            inSysex -> return
            else -> {
                // A data byte with no status of its own belongs to the last
                // one: that is running status, and a keyboard sending fast
                // will use it for every note after the first.
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
                    // Song position is the one two-byte message that is not
                    // for a rack: it says where the master is, so it goes
                    // out with the clock and not with the notes.
                    if (pending == 0xf2) onRealtime(0xf2, data1, b, timestamp) else onMessage(pending, data1, b)
                    if (runningStatus == 0) wanted = 0
                    data1 = -1
                }
            }
        }
    }

    private fun bytesFor(status: Int): Int = when (status and 0xf0) {
        0xc0, 0xd0 -> 1   // program change, channel pressure
        else -> 2
    }
}
