package com.rm.acidulous.model

/**
 * Names the model gives things it creates, like a new scene or a copy.
 *
 * The model can't read resources, so the app sets these from its strings at
 * startup. The English defaults are what tests see.
 */
object Names {
    var scene: (Int) -> String = { "Scene $it" }
    var copyOf: (String) -> String = { "$it copy" }
}
