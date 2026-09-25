package com.rm.acidulous.model

/**
 * The names the model gives things it makes: a new scene, a copy.
 *
 * The model has no resources to read them from, so the app sets these from
 * its strings when it starts, and the English here is what a test sees.
 */
object Names {
    var scene: (Int) -> String = { "Scene $it" }
    var copyOf: (String) -> String = { "$it copy" }
}
