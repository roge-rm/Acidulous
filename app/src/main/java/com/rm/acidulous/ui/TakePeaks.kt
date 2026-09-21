package com.rm.acidulous.ui

import com.rm.acidulous.engine.NativeEngine
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
}
