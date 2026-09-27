package com.rm.acidulous.io

/**
 * Zip support for the browser, which has no java.util.zip and only an async
 * deflate. Reads whole zips with stored or deflated entries (what the phone
 * and desktop write), and writes whole zips with stored entries. A song bundle
 * is mostly WAVs, which barely compress anyway.
 */
internal object Zip {

    fun read(zip: ByteArray, each: (name: String, isDirectory: Boolean, bytes: () -> ByteArray) -> Unit) {
        // Uses the central directory (found from its end record), which has
        // every entry's sizes even when the local header leaves them out.
        var end = zip.size - 22
        while (end >= 0 && u32(zip, end) != 0x06054b50L) end--
        if (end < 0) throw IllegalArgumentException("not a zip")
        val count = u16(zip, end + 10)
        var at = u32(zip, end + 16).toInt()
        repeat(count) {
            if (u32(zip, at) != 0x02014b50L) throw IllegalArgumentException("a broken zip")
            val method = u16(zip, at + 10)
            val packed = u32(zip, at + 20).toInt()
            val size = u32(zip, at + 24).toInt()
            val nameLen = u16(zip, at + 28)
            val extraLen = u16(zip, at + 30)
            val commentLen = u16(zip, at + 32)
            val local = u32(zip, at + 42).toInt()
            val name = zip.decodeToString(at + 46, at + 46 + nameLen)
            at += 46 + nameLen + extraLen + commentLen
            val data = local + 30 + u16(zip, local + 26) + u16(zip, local + 28)
            each(name, name.endsWith("/")) {
                when (method) {
                    0 -> zip.copyOfRange(data, data + packed)
                    8 -> Inflate.inflate(zip, data, size)
                    else -> throw IllegalArgumentException("$name: compression $method")
                }
            }
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

/** Raw inflate (RFC 1951), which is all a zip entry needs. */
internal object Inflate {
    private val LEN_BASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
    private val LEN_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
    private val DIST_BASE = intArrayOf(1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577)
    private val DIST_EXTRA = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
    private val ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

    /** A canonical Huffman code: how many codes of each length, and the symbols in code order. */
    private class Huffman(lengths: IntArray, n: Int) {
        val counts = IntArray(16)
        val symbols = IntArray(n)
        init {
            for (i in 0 until n) counts[lengths[i]]++
            counts[0] = 0
            val offs = IntArray(16)
            for (i in 1 until 16) offs[i] = offs[i - 1] + counts[i - 1]
            for (i in 0 until n) if (lengths[i] != 0) symbols[offs[lengths[i]]++] = i
        }
    }

    private class Bits(val src: ByteArray, var pos: Int) {
        var buf = 0
        var have = 0
        fun bit(): Int {
            if (have == 0) {
                if (pos >= src.size) throw IllegalArgumentException("deflate data ends early")
                buf = src[pos++].toInt() and 0xff
                have = 8
            }
            val b = buf and 1
            buf = buf ushr 1
            have--
            return b
        }
        fun bits(n: Int): Int {
            var v = 0
            for (i in 0 until n) v = v or (bit() shl i)
            return v
        }
        fun decode(h: Huffman): Int {
            var code = 0
            var first = 0
            var index = 0
            for (len in 1 until 16) {
                code = code or bit()
                val count = h.counts[len]
                if (code - count < first) return h.symbols[index + (code - first)]
                index += count
                first += count
                first = first shl 1
                code = code shl 1
            }
            throw IllegalArgumentException("a bad deflate code")
        }
    }

    private val FIXED_LIT = Huffman(IntArray(288) { when { it < 144 -> 8; it < 256 -> 9; it < 280 -> 7; else -> 8 } }, 288)
    private val FIXED_DIST = Huffman(IntArray(30) { 5 }, 30)

    fun inflate(src: ByteArray, from: Int, size: Int): ByteArray {
        val out = ByteArray(size)
        var n = 0
        val s = Bits(src, from)
        do {
            val last = s.bit()
            when (s.bits(2)) {
                0 -> {
                    s.have = 0
                    val len = (src[s.pos].toInt() and 0xff) or ((src[s.pos + 1].toInt() and 0xff) shl 8)
                    s.pos += 4
                    src.copyInto(out, n, s.pos, s.pos + len)
                    s.pos += len
                    n += len
                }
                1 -> n = codes(s, out, n, FIXED_LIT, FIXED_DIST)
                2 -> {
                    val nlen = s.bits(5) + 257
                    val ndist = s.bits(5) + 1
                    val ncode = s.bits(4) + 4
                    val lengths = IntArray(320)
                    for (i in 0 until ncode) lengths[ORDER[i]] = s.bits(3)
                    val lencode = Huffman(lengths, 19)
                    var i = 0
                    val all = IntArray(320)
                    while (i < nlen + ndist) {
                        var sym = s.decode(lencode)
                        if (sym < 16) { all[i++] = sym; continue }
                        var len = 0
                        when (sym) {
                            16 -> { len = all[i - 1]; sym = 3 + s.bits(2) }
                            17 -> sym = 3 + s.bits(3)
                            else -> sym = 11 + s.bits(7)
                        }
                        repeat(sym) { all[i++] = len }
                    }
                    val lit = Huffman(all.copyOfRange(0, nlen), nlen)
                    val dist = Huffman(all.copyOfRange(nlen, nlen + ndist), ndist)
                    n = codes(s, out, n, lit, dist)
                }
                else -> throw IllegalArgumentException("a bad deflate block")
            }
        } while (last == 0)
        return if (n == size) out else out.copyOf(n)
    }

    private fun codes(s: Bits, out: ByteArray, start: Int, lit: Huffman, dist: Huffman): Int {
        var n = start
        while (true) {
            var sym = s.decode(lit)
            if (sym < 256) { out[n++] = sym.toByte(); continue }
            if (sym == 256) return n
            sym -= 257
            val len = LEN_BASE[sym] + s.bits(LEN_EXTRA[sym])
            val d = s.decode(dist)
            val back = DIST_BASE[d] + s.bits(DIST_EXTRA[d])
            for (k in 0 until len) { out[n] = out[n - back]; n++ }
        }
    }
}
