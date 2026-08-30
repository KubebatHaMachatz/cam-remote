package com.camremote.core.protocol

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Identity of the handset, as reported to clients.
 *
 * Kept deliberately thin: enough for a human reading `system.status` to tell two phones apart on a
 * bench, and no more. Anything richer is available through `device.getprop`.
 */
@Serializable
data class DeviceDescription(
    val name: String,
    val model: String,
    val androidRelease: String,
    val apiLevel: Int,
)

/**
 * The reachability probe.
 *
 * Lets a client tell "wrong address" from "the agent is not there at all" before it bothers
 * connecting to `/v1/command`.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class HealthResponse(
    // ProtocolJson omits values equal to their default, which keeps command payloads lean but would
    // silently drop these two constants. A client identifies the service by them, so they are pinned.
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val service: String = SERVICE_NAME,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val apiVersion: String = API_VERSION,
    val device: DeviceDescription,
) {
    companion object {
        const val SERVICE_NAME = "cam-remote"
        const val API_VERSION = "v1"
    }
}

/**
 * A failure that happened before or instead of a command running.
 *
 * Shaped as a strict subset of [CommandResponse] so that a client can read `error` the same way no
 * matter which HTTP status carried it, and needs only one parser for every failure it can meet.
 */
@Serializable
data class ErrorEnvelope(val error: CommandError)

/**
 * The device-side grants the agent needs, and whether they are in place.
 *
 * Part of the wire format because `system.status` returns it: when something is not working, the
 * first question is always which of these is missing, and answering it remotely saves a trip to the
 * handset.
 */
@Serializable
data class PermissionStatus(
    /** Required to capture a photo at all. */
    val camera: Boolean,
    /** Required to show the foreground-service notification on API 33+. */
    val notifications: Boolean,
    /**
     * "Display over other apps". Required twice over: Android blocks background activity launches
     * without it, and it is also an exemption from the rule that a camera-type foreground service
     * cannot be started from the background.
     */
    val canDrawOverlays: Boolean,
    /** Without this, Doze can drop inbound connections once the screen has been off a while. */
    val ignoringBatteryOptimizations: Boolean,
) {
    /** True when nothing stands in the way of the full command set. */
    val isComplete: Boolean
        get() = camera && notifications && canDrawOverlays && ignoringBatteryOptimizations
}
