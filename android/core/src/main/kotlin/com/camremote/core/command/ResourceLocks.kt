package com.camremote.core.command

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One mutex per exclusive device resource.
 *
 * An HTTP server is concurrent by nature and the camera is not, so something has to arbitrate.
 * Doing it here — rather than inside the camera adapter — means the waiting happens inside the
 * dispatcher's timeout, so a client queued behind a busy camera gets a prompt TIMEOUT instead of
 * hanging until the transport gives up.
 */
class ResourceLocks {

    private val mutexes: Map<DeviceResource, Mutex> =
        DeviceResource.entries.associateWith { Mutex() }

    suspend fun <T> withResource(resource: DeviceResource?, block: suspend () -> T): T =
        if (resource == null) block() else mutexes.getValue(resource).withLock { block() }
}
