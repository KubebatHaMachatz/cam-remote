package com.camremote.core.port

/**
 * Reads an Android system property.
 *
 * A port because there is more than one way to do it — running `getprop`, or reflecting on the
 * hidden `android.os.SystemProperties` — and which one works depends on the OS version. Commands
 * should not care, and unit tests should not need either.
 *
 * @return the property's value, or null when it is not set.
 */
fun interface PropertyReader {
    fun read(key: String): String?
}
