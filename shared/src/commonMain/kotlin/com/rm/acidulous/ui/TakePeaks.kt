package com.rm.acidulous.ui

import com.rm.acidulous.io.*

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.rm.acidulous.engine.NativeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.rm.acidulous.model.TAKE_PEAK_COLUMNS
import com.rm.acidulous.model.Envelope
import com.rm.acidulous.model.TakeRef
import com.rm.acidulous.model.stack

/**
 * The shape of a take, stored in the document instead of read from disk.
 *
 * `NativeEngine.fileShape` decodes the whole file on every call, which is
 * far too slow for a grid of waveform cells. So the coarse shape is measured
 * once when the take is made and stored on it (`TakeRef.peaks`). A cell then
 * reads no disk, and still draws if the file has been moved.
 *
 * Forty pairs is about what a cell can show. The editor wants more and
 * fetches its own, off-thread, for the region it shows.
 */
object TakePeaks {
    /**
     * A file's length and coarse shape in one decode, or null when it won't
     * read. Blocking: call it on a worker. A five minute file takes about a
     * second and peaks at over 100 MB, see the note in `assemble`.
     */
    suspend fun survey(root: File?, relative: String): Survey? {
        val out = FloatArray(TAKE_PEAK_COLUMNS * 2)
        val info = NativeEngine.fileSurvey(File(root, relative).absolutePath, out)
        val frames = info.split('|').getOrNull(1)?.toIntOrNull() ?: return null
        if (frames <= 0) return null
        return Survey(frames, out.toList())
    }

    data class Survey(val frames: Int, val peaks: List<Float>)

    // --- The editor's copy ---------------------------------------------------

    /**
     * How finely the lane editor draws a take. More than a pixel each on a
     * phone, even turned sideways.
     */
    const val EDIT_COLUMNS = 512

    /**
     * Cached per file, always the whole file, not per region. Trimming changes
     * the region every frame of a drag, so keying on it would decode every
     * frame. Keyed on the file, a trim is just arithmetic over columns already
     * in hand.
     *
     * Eight files is two tapes' worth of lanes, at 4 KB each.
     */
    private const val KEEP = 8
    private val cache = com.rm.acidulous.util.LruMap<String, Survey>(KEEP)

    /**
     * One region's coarse shape, taken from the whole file's. A take split into
     * five cells would otherwise decode the file five times.
     */
    fun slice(whole: Survey, offset: Int, frames: Int, columns: Int = TAKE_PEAK_COLUMNS): List<Float> {
        if (whole.frames <= 0 || frames <= 0) return emptyList()
        val all = whole.peaks.size / 2
        if (all <= 0) return emptyList()
        val out = ArrayList<Float>(columns * 2)
        for (i in 0 until columns) {
            val from = offset.toLong() + frames.toLong() * i / columns
            val to = offset.toLong() + frames.toLong() * (i + 1) / columns
            var a = (all * from / whole.frames).toInt().coerceIn(0, all - 1)
            val b = (all * to / whole.frames).toInt().coerceIn(a + 1, all)
            var lo = 0f
            var hi = 0f
            while (a < b) {
                lo = minOf(lo, whole.peaks[a * 2])
                hi = maxOf(hi, whole.peaks[a * 2 + 1])
                ++a
            }
            out.add(lo)
            out.add(hi)
        }
        return out
    }

    /**
     * A stacked lane's coarse shape: each column from the take its pieces
     * play there, silence between them. Null if [shapeOf] lacks a file the
     * pieces use, or the lane has no pieces.
     */
    fun comp(take: TakeRef, columns: Int = TAKE_PEAK_COLUMNS, shapeOf: (String) -> Survey?): List<Float>? {
        if (take.pieces.isEmpty() || take.frames <= 0) return null
        val stack = take.stack()
        val out = ArrayList<Float>(columns * 2)
        for (i in 0 until columns) {
            val from = (take.frames.toLong() * i / columns).toInt()
            val to = (take.frames.toLong() * (i + 1) / columns).toInt()
            val mid = (from + to) / 2
            val p = take.pieces.firstOrNull { mid >= it.start && mid < it.end }
            val t = p?.let { stack.getOrNull(it.take) }
            if (t == null) { out.add(0f); out.add(0f); continue }
            val whole = shapeOf(t.file) ?: return null
            val col = slice(whole, t.offset + from - p.shift, (to - from).coerceAtLeast(1), 1)
            if (col.size == 2) out.addAll(col) else { out.add(0f); out.add(0f) }
        }
        return out
    }

    /** The cached shape, or null if it hasn't been read yet. Never reads. */
    fun cached(relative: String): Survey? = com.rm.acidulous.util.locked(this) { cache[relative] }

    /**
     * Reads and caches. Blocking: call it on a worker, never the main thread.
     * One decode gives both the editor's shape and the phrase finder's finer
     * [envelope].
     */
    suspend fun load(root: File?, relative: String): Survey? {
        cached(relative)?.let { return it }
        val fine = FloatArray(FINE_COLUMNS * 2)
        val info = NativeEngine.fileSurvey(File(root, relative).absolutePath, fine)
        val frames = info.split('|').getOrNull(1)?.toIntOrNull() ?: return null
        if (frames <= 0) return null
        // The engine splits a file into columns at frames * c / columns, so
        // each editor column is exactly FINE_COLUMNS / EDIT_COLUMNS fine ones.
        val per = FINE_COLUMNS / EDIT_COLUMNS
        val out = ArrayList<Float>(EDIT_COLUMNS * 2)
        for (c in 0 until EDIT_COLUMNS) {
            var lo = 0f
            var hi = 0f
            for (f in c * per until (c + 1) * per) {
                lo = minOf(lo, fine[f * 2])
                hi = maxOf(hi, fine[f * 2 + 1])
            }
            out.add(lo)
            out.add(hi)
        }
        val survey = Survey(frames, out)
        com.rm.acidulous.util.locked(this) {
            cache[relative] = survey
            envelopes[relative] = Envelope(frames, fine)
        }
        return survey
    }

    /**
     * Columns in a file's fine envelope: about 10 ms each on a five minute
     * file, finer on a shorter one. 256 KB a file.
     */
    const val FINE_COLUMNS = 32768

    private val envelopes = com.rm.acidulous.util.LruMap<String, Envelope>(KEEP)

    /** A file's fine envelope for the phrase finder, read if need be. Blocking: call it on a worker. */
    suspend fun envelope(root: File?, relative: String): Envelope? {
        com.rm.acidulous.util.locked(this) { envelopes[relative] }?.let { return it }
        // The editor's shape may be cached without the envelope, if the
        // envelope has since been pushed out, so read again.
        com.rm.acidulous.util.locked(this) { cache.remove(relative) }
        load(root, relative)
        return com.rm.acidulous.util.locked(this) { envelopes[relative] }
    }

    /**
     * The shape of [relative], read off-thread the first time it's asked for.
     * Recomposes once it arrives. Until then a lane draws a flat line, so a slow
     * file looks quiet instead of broken.
     */
    @Composable
    fun rememberShape(root: File?, relative: String): Survey? {
        var shape by remember(relative) { mutableStateOf(cached(relative)) }
        LaunchedEffect(relative) {
            if (shape == null && relative.isNotEmpty()) {
                shape = withContext(Dispatchers.Default) { load(root, relative) }
            }
        }
        return shape
    }
}
