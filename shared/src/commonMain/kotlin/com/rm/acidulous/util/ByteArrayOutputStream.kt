package com.rm.acidulous.util

/**
 * The part of java.io.ByteArrayOutputStream the shared code uses: append
 * bytes, then take them all. Used to build MIDI files and collect SysEx.
 */
class ByteArrayOutputStream(initial: Int = 32) {
    private var buf = ByteArray(initial.coerceAtLeast(1))
    private var count = 0

    private fun room(more: Int) {
        if (count + more <= buf.size) return
        var size = buf.size * 2
        while (size < count + more) size *= 2
        buf = buf.copyOf(size)
    }
    fun write(b: Int) {
        room(1)
        buf[count++] = b.toByte()
    }
    fun write(bytes: ByteArray) = write(bytes, 0, bytes.size)
    fun write(bytes: ByteArray, off: Int, len: Int) {
        room(len)
        bytes.copyInto(buf, count, off, off + len)
        count += len
    }
    fun size(): Int = count
    fun reset() { count = 0 }
    fun toByteArray(): ByteArray = buf.copyOf(count)
}
