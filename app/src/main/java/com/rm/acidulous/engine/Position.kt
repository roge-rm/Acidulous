package com.rm.acidulous.engine

/** Decoded transport position; see seq::Transport::pack on the native side. */
data class Position(val scene: Int, val repeat: Int, val tickInIteration: Long) {
    companion object {
        fun unpack(packed: Long): Position = Position(
            scene = ((packed ushr 56) and 0xff).toInt(),
            repeat = ((packed ushr 48) and 0xff).toInt(),
            tickInIteration = packed and 0xffffffffffffL,
        )
    }
}
