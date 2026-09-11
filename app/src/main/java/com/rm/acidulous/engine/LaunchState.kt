package com.rm.acidulous.engine

/**
 * What one track is doing in clip mode; see seq::Transport::packLaunch.
 *
 * [scene] is the scene whose clip is sounding and [pending] the one queued
 * behind it, both as indices into the song's scene list. Either may be
 * [NONE]; [pending] may also be [STOPPING], which is the clip asking to be
 * let go at the end of its cycle.
 */
data class LaunchState(
    val scene: Int = NONE,
    val pending: Int = NONE,
    val tickInCycle: Long = 0,
) {
    val playing: Boolean get() = scene != NONE
    val queued: Boolean get() = pending != NONE && pending != STOPPING
    val stopping: Boolean get() = pending == STOPPING

    companion object {
        const val NONE = 0xff
        const val STOPPING = 0xfe

        fun unpack(packed: Long): LaunchState = LaunchState(
            scene = ((packed ushr 56) and 0xff).toInt(),
            pending = ((packed ushr 48) and 0xff).toInt(),
            tickInCycle = packed and 0xffffffffffffL,
        )

        val idle = LaunchState()
    }
}
