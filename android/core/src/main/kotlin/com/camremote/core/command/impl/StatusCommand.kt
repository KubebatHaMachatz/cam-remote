package com.camremote.core.command.impl

import com.camremote.core.command.Command
import com.camremote.core.command.CommandOutcome
import com.camremote.core.port.CameraController
import com.camremote.core.port.Clock
import com.camremote.core.port.PermissionInspector
import com.camremote.core.protocol.CommandDescriptor
import com.camremote.core.protocol.DeviceDescription
import com.camremote.core.protocol.Params
import com.camremote.core.protocol.PermissionStatus
import com.camremote.core.protocol.ProtocolJson
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Reports the agent's readiness.
 *
 * A headless agent on a phone in another room fails silently by default. This command exists so the
 * first question after any failure — "which grant is missing?" — can be answered from the control
 * machine rather than by walking over to the handset.
 */
class StatusCommand(
    private val permissions: PermissionInspector,
    private val device: () -> DeviceDescription,
    private val camera: CameraController,
    private val clock: Clock,
) : Command {

    override val descriptor = CommandDescriptor(
        name = "system.status",
        description = "Report the device, its permissions, and whether the agent is fully set up.",
    )

    override suspend fun execute(params: Params): CommandOutcome {
        val status = permissions.status()
        return CommandOutcome.Success(
            buildJsonObject {
                put(
                    "device",
                    ProtocolJson.json.encodeToJsonElement(DeviceDescription.serializer(), device()),
                )
                put(
                    "permissions",
                    ProtocolJson.json.encodeToJsonElement(PermissionStatus.serializer(), status),
                )
                put("hasRearCamera", JsonPrimitive(camera.hasRearCamera()))
                put("setupComplete", JsonPrimitive(status.isComplete))
                put(
                    "missing",
                    buildJsonArray { missingGrants(status).forEach { add(JsonPrimitive(it)) } },
                )
                put("deviceTimeMillis", JsonPrimitive(clock.nowMillis()))
            },
        )
    }

    /** Named rather than merely flagged, so the client can print the exact remedy. */
    private fun missingGrants(status: PermissionStatus): List<String> = buildList {
        if (!status.camera) add("camera")
        if (!status.notifications) add("notifications")
        if (!status.canDrawOverlays) add("canDrawOverlays")
        if (!status.ignoringBatteryOptimizations) add("ignoringBatteryOptimizations")
    }
}
