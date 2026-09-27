package com.rm.acidulous.io

import kotlinx.coroutines.await
import kotlinx.coroutines.yield
import kotlin.js.Promise

private fun jsSub(a: JsAny, from: Int, until: Int): JsAny = js("a.subarray(from, until)")

/** Raw deflate data inflated by the browser, off the page's thread. */
private fun inflateRaw(packed: JsAny): Promise<JsAny> = js(
    "new Response(new Blob([packed]).stream().pipeThrough(new DecompressionStream('deflate-raw')))" +
        ".arrayBuffer().then(b => new Uint8Array(b))",
)

/**
 * Zip support for the browser, which has no java.util.zip. Reads whole zips
 * with stored or deflated entries (what the phone and desktop write), and
 * writes whole zips with stored entries. A song bundle is mostly WAVs, which
 * barely compress anyway.
 *
 * Reading waits for the browser's own inflate and yields after each entry, so
 * the page keeps drawing while a big bundle comes in.
 */
internal object Zip {

    suspend fun read(zip: JsAny, each: suspend (name: String, isDirectory: Boolean, bytes: suspend () -> ByteArray) -> Unit) {
        // The zip stays a Uint8Array. Only its central directory (found from
        // the end record, and holding every entry's sizes even when the local
        // header leaves them out) and the headers come into Kotlin, and each
        // entry's data goes straight to the browser's inflate.
        val size = jsLength(zip)
        val tailFrom = maxOf(0, size - 22 - 0xffff)
        val tail = bytesOf(jsSub(zip, tailFrom, size))
        var end = tail.size - 22
        while (end >= 0 && u32(tail, end) != 0x06054b50L) end--
        if (end < 0) throw IllegalArgumentException("not a zip")
        val count = u16(tail, end + 10)
        val dirSize = u32(tail, end + 12).toInt()
        val dirFrom = u32(tail, end + 16).toInt()
        val dir = bytesOf(jsSub(zip, dirFrom, dirFrom + dirSize))
        var at = 0
        repeat(count) {
            if (u32(dir, at) != 0x02014b50L) throw IllegalArgumentException("a broken zip")
            val method = u16(dir, at + 10)
            val packed = u32(dir, at + 20).toInt()
            val nameLen = u16(dir, at + 28)
            val extraLen = u16(dir, at + 30)
            val commentLen = u16(dir, at + 32)
            val local = u32(dir, at + 42).toInt()
            val name = dir.decodeToString(at + 46, at + 46 + nameLen)
            at += 46 + nameLen + extraLen + commentLen
            val head = bytesOf(jsSub(zip, local, local + 30))
            val data = local + 30 + u16(head, 26) + u16(head, 28)
            each(name, name.endsWith("/")) {
                when (method) {
                    0 -> bytesOf(jsSub(zip, data, data + packed))
                    8 -> bytesOf(inflateRaw(jsSub(zip, data, data + packed)).await())
                    else -> throw IllegalArgumentException("$name: compression $method")
                }
            }
            yield()
        }
    }

    class Writer {
        private val out = com.rm.acidulous.util.ByteArrayOutputStream()
        private val central = com.rm.acidulous.util.ByteArrayOutputStream()
        private var count = 0

        fun add(name: String, bytes: ByteArray) {
            val n = name.encodeToByteArray()
            val crc = crc32(bytes)
            val offset = out.size()
            header(out, 0x04034b50, n, crc, bytes.size, local = true, offset = 0)
            out.write(bytes)
            header(central, 0x02014b50, n, crc, bytes.size, local = false, offset = offset)
            count++
        }

        fun finish(): ByteArray {
            val start = out.size()
            val dir = central.toByteArray()
            out.write(dir)
            le(out, 0x06054b50, 4); le(out, 0, 4)
            le(out, count, 2); le(out, count, 2)
            le(out, dir.size, 4); le(out, start, 4); le(out, 0, 2)
            return out.toByteArray()
        }

        private fun header(o: com.rm.acidulous.util.ByteArrayOutputStream, sig: Int, name: ByteArray, crc: Int, size: Int, local: Boolean, offset: Int) {
            le(o, sig, 4)
            if (!local) le(o, 20, 2) // made by
            le(o, 20, 2) // needed to extract
            le(o, 0x0800, 2) // names in UTF-8
            le(o, 0, 2) // stored
            le(o, 0, 2); le(o, 0x21, 2) // time and date: 1980-01-01
            le(o, crc, 4); le(o, size, 4); le(o, size, 4)
            le(o, name.size, 2); le(o, 0, 2)
            if (!local) {
                le(o, 0, 2); le(o, 0, 2); le(o, 0, 2); le(o, 0, 4)
                le(o, offset, 4)
            }
            o.write(name)
        }
    }

    private fun le(o: com.rm.acidulous.util.ByteArrayOutputStream, v: Int, bytes: Int) {
        for (i in 0 until bytes) o.write((v ushr (8 * i)) and 0xff)
    }
    private fun u16(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)
    private fun u32(b: ByteArray, i: Int): Long = (u16(b, i).toLong()) or (u16(b, i + 2).toLong() shl 16)

    private val CRC = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }
    fun crc32(b: ByteArray): Int {
        var c = -1
        for (x in b) c = CRC[(c xor x.toInt()) and 0xff] xor (c ushr 8)
        return c.inv()
    }
}
