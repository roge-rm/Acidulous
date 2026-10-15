package com.rm.acidulous.engine

actual fun <T> recordDry(writer: EngineLineWriter, block: () -> T): T =
    throw UnsupportedOperationException("engine lines are not recorded in a browser")
