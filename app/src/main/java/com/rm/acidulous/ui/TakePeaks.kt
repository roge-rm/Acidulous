package com.rm.acidulous.ui

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
import java.io.File

/**
 * The shape of a take, drawn from the document rather than from the disk.
 *
 * `NativeEngine.fileShape` decodes the whole file on every call, so a grid of
 * waveform cells that asked it per cell per scroll would decode hundreds of
 * megabytes to draw forty pixels. The coarse shape is therefore measured once,
 * when the take is made, and **stored on the take** - which is why `TakeRef`
 * carries `peaks`. A cell then costs no disk at all, survives a reopen, and is
 * right even for a file that has since been moved away.
 *
 * Forty pairs is about what a cell can show and eighty floats is nothing next
 * to the notes in a clip. The editor wants far more than forty and asks for its
 * own, off-thread, against the region it is actually showing.
 */
object TakePeaks {
    /**
     * A file's length and coarse shape in one decode.
     *
     * Returns null when the file will not read. **Blocking**: the caller is on
     * a worker, and for a five-minute file this is a second of work and a peak
     * of over a hundred megabytes - see the note in `assemble`.
     */
    fun survey(root: File?, relative: String): Survey? {
        val out = FloatArray(TAKE_PEAK_COLUMNS * 2)
        val info = NativeEngine.fileSurvey(File(root, relative).absolutePath, out)
        val frames = info.split('|').getOrNull(1)?.toIntOrNull() ?: return null
        if (frames <= 0) return null
        return Survey(frames, out.toList())
    }

    data class Survey(val frames: Int, val peaks: List<Float>)

    // --- The editor's copy ---------------------------------------------------

    /**
     * How finely the lane editor draws a take. A phone is under four hundred dp
     * wide, so this is more than a pixel each even turned sideways.
     */
    const val EDIT_COLUMNS = 512

    /**
     * **Keyed on the file alone, and always the whole of it.**
     *
     * The obvious cache key is the region - file, offset, frames - and it is
     * the wrong one, because the gesture this cache exists for is *trimming*,
     * which changes the region on every frame of a drag. Keyed on the region,
     * a trim is a decode a frame; keyed on the file, a trim is arithmetic over
     * columns that are already in hand, and nothing is read at all.
     *
     * Eight files, because that is two tapes' worth of lanes and each entry is
     * four kilobytes.
     */
    private const val KEEP = 8
    private val cache = object : LinkedHashMap<String, Survey>(KEEP, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Survey>) = size > KEEP
    }

    /** Cached, or null when it has not been read yet. Never reads here. */
    @Synchronized
    fun cached(relative: String): Survey? = cache[relative]

    /** Reads and caches. **Blocking**: a worker, never the main thread. */
    fun load(root: File?, relative: String): Survey? {
        cached(relative)?.let { return it }
        val out = FloatArray(EDIT_COLUMNS * 2)
        val info = NativeEngine.fileSurvey(File(root, relative).absolutePath, out)
        val frames = info.split('|').getOrNull(1)?.toIntOrNull() ?: return null
        if (frames <= 0) return null
        val survey = Survey(frames, out.toList())
        synchronized(this) { cache[relative] = survey }
        return survey
    }

    /**
     * The shape of [relative], read off-thread the first time it is asked for.
     *
     * Recomposes once when it arrives; a lane draws a flat line until then
     * rather than an empty box, so a slow file looks like a quiet one rather
     * than a broken one.
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
