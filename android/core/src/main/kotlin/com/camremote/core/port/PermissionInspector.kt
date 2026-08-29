package com.camremote.core.port

import com.camremote.core.protocol.PermissionStatus

/**
 * Reports which of the device-side grants the agent depends on are currently in place.
 *
 * Commands consult this to fail fast with an actionable message rather than attempting something
 * the OS will quietly drop.
 */
fun interface PermissionInspector {
    /** The grants in place right now. Re-read per call, since the user may change them. */
    fun status(): PermissionStatus
}
