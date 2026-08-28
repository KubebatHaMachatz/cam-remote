package com.camremote.core.port

/**
 * Time, as a port.
 *
 * Injecting the clock is what makes durations and timestamps assertable to the millisecond in unit
 * tests instead of "some number greater than zero".
 */
fun interface Clock {
    fun nowMillis(): Long
}

/** The real clock. Wall-clock time is enough here: durations are reported, never relied upon. */
object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
