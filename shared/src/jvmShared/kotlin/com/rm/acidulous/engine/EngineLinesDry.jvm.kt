package com.rm.acidulous.engine

/** The writer a thread is recording dry to, if it is. Read by the generated EngineNative. */
internal object DryLines {
    val writer = ThreadLocal<EngineLineWriter?>()
}

actual fun <T> recordDry(writer: EngineLineWriter, block: () -> T): T {
    val before = DryLines.writer.get()
    DryLines.writer.set(writer)
    try {
        return block()
    } finally {
        DryLines.writer.set(before)
    }
}
