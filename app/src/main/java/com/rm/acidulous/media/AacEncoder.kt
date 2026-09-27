package com.rm.acidulous.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AAC export, encoded by Android's own MediaCodec, so there's no encoder
 * code or licence of our own. (There's no MP3 because Android has no MP3
 * encoder and the good ones are LGPL.)
 *
 * It converts the 16-bit WAV the render already writes, which keeps lossy
 * encoding out of the engine and only adds a few seconds.
 */
object AacEncoder {

    private const val MIME = "audio/mp4a-latm"
    private const val TIMEOUT_US = 10_000L

    /** "" on success, otherwise the reason it failed. */
    fun encode(wav: File, out: File, bitRate: Int = 256_000): String {
        val source = runCatching { PcmSource(wav) }.getOrElse { return "cannot read the render: ${it.message}" }
        source.use {
            val format = MediaFormat.createAudioFormat(MIME, source.sampleRate, source.channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }
            val codec = runCatching { MediaCodec.createEncoderByType(MIME) }
                .getOrElse { return "this device has no AAC encoder" }
            var muxer: MediaMuxer? = null
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                var track = -1
                var muxing = false
                val info = MediaCodec.BufferInfo()
                var presentationUs = 0L
                var done = false

                while (!done) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        buffer.clear()
                        val read = source.read(buffer)
                        if (read <= 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            codec.queueInputBuffer(inIndex, 0, read, presentationUs, 0)
                            // 16-bit samples, so this many frames and this much
                            // time. Wrong timestamps make the file play at the
                            // wrong speed.
                            val frames = read / (2 * source.channels)
                            presentationUs += frames * 1_000_000L / source.sampleRate
                        }
                    }

                    var outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    while (outIndex >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            info.size = 0 // the muxer took this from the format already
                        }
                        if (info.size > 0 && muxing) {
                            val encoded = codec.getOutputBuffer(outIndex)!!
                            encoded.position(info.offset)
                            encoded.limit(info.offset + info.size)
                            muxer.writeSampleData(track, encoded, info)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                        codec.releaseOutputBuffer(outIndex, false)
                        outIndex = codec.dequeueOutputBuffer(info, 0)
                    }
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && !muxing) {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxing = true
                    }
                }
                if (!muxing) return "the encoder produced nothing"
            } catch (e: Exception) {
                return e.message ?: "the encoder failed"
            } finally {
                runCatching { codec.stop() }
                codec.release()
                runCatching { muxer?.stop() }
                runCatching { muxer?.release() }
            }
        }
        return ""
    }

    /**
     * Just enough WAV reading for our own file: find `fmt ` and `data` and
     * hand out 16-bit frames. It only reads what we just wrote, so it isn't a
     * general reader (that's `WavReader` in the engine).
     */
    private class PcmSource(wav: File) : AutoCloseable {
        val sampleRate: Int
        val channels: Int
        private val file = RandomAccessFile(wav, "r")
        private var remaining: Long

        init {
            val header = ByteArray(12)
            file.readFully(header)
            require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF") { "not a WAV" }
            var rate = 48000
            var chans = 2
            var dataBytes = 0L
            while (file.filePointer < file.length() - 8) {
                val id = ByteArray(4)
                file.readFully(id)
                val size = readLe32(file)
                val name = String(id, Charsets.US_ASCII)
                if (name == "fmt ") {
                    val fmt = ByteArray(size)
                    file.readFully(fmt)
                    chans = le16(fmt, 2)
                    rate = le32(fmt, 4)
                } else if (name == "data") {
                    dataBytes = size.toLong()
                    break
                } else {
                    file.seek(file.filePointer + size + (size and 1))
                }
            }
            sampleRate = rate
            channels = chans
            remaining = dataBytes
        }

        fun read(into: ByteBuffer): Int {
            if (remaining <= 0) return 0
            val want = minOf(into.remaining().toLong(), remaining).toInt()
            val bytes = ByteArray(want)
            val got = file.read(bytes, 0, want)
            if (got <= 0) return 0
            remaining -= got
            into.order(ByteOrder.LITTLE_ENDIAN)
            into.put(bytes, 0, got)
            return got
        }

        override fun close() {
            runCatching { file.close() }
        }

        private fun readLe32(f: RandomAccessFile): Int {
            val b = ByteArray(4)
            f.readFully(b)
            return le32(b, 0)
        }
        private fun le16(b: ByteArray, at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)
        private fun le32(b: ByteArray, at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or
            ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)
    }
}
