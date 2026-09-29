package com.rm.acidulous.model.voice

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * The note a voice is sung on, as a WAV to play before each prompt: a soft
 * tone with a few harmonics, so it's easy to pitch against, and a gentle
 * start and end.
 *
 * Played before the take rather than under it, so it isn't in the recording.
 */
object Tone {
    const val RATE = 48000
    const val SECONDS = 1.2f

    fun wav(note: Int): ByteArray {
        val hz = 440.0 * 2.0.pow((note - 69) / 12.0)
        val frames = (RATE * SECONDS).toInt()
        val fade = RATE / 20
        val data = ByteArray(frames * 2)
        for (i in 0 until frames) {
            val t = i.toDouble() / RATE
            val w = 2.0 * PI * hz * t
            var s = sin(w) + 0.35 * sin(2 * w) + 0.15 * sin(3 * w)
            val edge = minOf(i, frames - 1 - i).coerceAtMost(fade).toDouble() / fade
            s *= 0.3 * edge
            val v = (s * 32767.0).toInt().coerceIn(-32768, 32767)
            data[i * 2] = v.toByte()
            data[i * 2 + 1] = (v shr 8).toByte()
        }
        return header(data.size) + data
    }

    /** A 16-bit mono PCM header. */
    private fun header(bytes: Int): ByteArray {
        val h = ByteArray(44)
        fun text(at: Int, s: String) = s.forEachIndexed { k, ch -> h[at + k] = ch.code.toByte() }
        fun u32(at: Int, v: Int) { for (k in 0 until 4) h[at + k] = (v shr (8 * k)).toByte() }
        fun u16(at: Int, v: Int) { for (k in 0 until 2) h[at + k] = (v shr (8 * k)).toByte() }
        text(0, "RIFF"); u32(4, 36 + bytes); text(8, "WAVE")
        text(12, "fmt "); u32(16, 16); u16(20, 1); u16(22, 1); u32(24, RATE); u32(28, RATE * 2); u16(32, 2); u16(34, 16)
        text(36, "data"); u32(40, bytes)
        return h
    }
}
