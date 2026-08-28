package com.camremote.core.logic

/**
 * Interprets what `getprop` printed.
 *
 * `getprop` prints an empty line both for a property that is unset and for one set to the empty
 * string. Collapsing the two to null is a judgement call, made here — in testable code — rather than
 * inside the adapter that spawns the process, where a desktop JVM could never reach it.
 */
object GetPropOutput {

    fun parse(raw: String): String? = raw.trim().ifEmpty { null }
}
