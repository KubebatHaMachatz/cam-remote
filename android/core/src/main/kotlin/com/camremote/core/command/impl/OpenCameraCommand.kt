package com.camremote.core.command.impl

import com.camremote.core.command.Command
import com.camremote.core.command.CommandOutcome
import com.camremote.core.command.DeviceResource
import com.camremote.core.logic.CameraAppLaunch
import com.camremote.core.port.ActivityStarter
import com.camremote.core.port.PermissionInspector
import com.camremote.core.protocol.CommandDescriptor
import com.camremote.core.protocol.ErrorCode
import com.camremote.core.protocol.ParameterDescriptor
import com.camremote.core.protocol.ParameterType
import com.camremote.core.protocol.Params
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Opens the device's camera app — the assignment's first requirement.
 *
 * The interesting part is not the intent, it is the precondition. Since Android 10 an app in the
 * background may not start an activity, and this agent is by design always in the background. The
 * documented escape hatch is the "Display over other apps" permission, so the command checks for it
 * and, when it is missing, says so with the fix attached instead of firing an intent the system
 * will silently discard.
 */
class OpenCameraCommand(
    private val activities: ActivityStarter,
    private val permissions: PermissionInspector,
) : Command {

    override val descriptor = CommandDescriptor(
        name = "camera.open",
        description = "Open the device's camera app. The lens hint is best-effort and app-dependent.",
        parameters = listOf(
            ParameterDescriptor(
                name = "lens",
                type = ParameterType.STRING,
                required = false,
                description = "'front' or 'rear'. A hint only; camera apps are free to ignore it.",
            ),
            ParameterDescriptor(
                name = "package",
                type = ParameterType.STRING,
                required = false,
                description = "Open a specific camera app instead of the device default.",
            ),
        ),
    )

    // Launching the camera app takes hold of the same sensor a capture uses.
    override val exclusiveResource = DeviceResource.CAMERA

    override val timeout = 15.seconds

    override suspend fun execute(params: Params): CommandOutcome {
        val spec = CameraAppLaunch.specFor(params)

        if (!permissions.status().canDrawOverlays) {
            return CommandOutcome.failure(
                code = ErrorCode.PRECONDITION_FAILED,
                message = "Android will not let a background app start an activity without the " +
                    "overlay permission",
                remediation = "Open cam-remote on the device and grant \"Display over other apps\"",
            )
        }

        val component = activities.resolve(spec) ?: return CommandOutcome.failure(
            code = ErrorCode.DEVICE_ERROR,
            message = "No installed app handles ${spec.action}" +
                (spec.targetPackage?.let { " in package $it" } ?: ""),
            remediation = "Install a camera app, or omit the 'package' parameter",
        )

        return try {
            activities.start(spec)
            CommandOutcome.Success(
                buildJsonObject {
                    put("launched", JsonPrimitive(true))
                    put("component", JsonPrimitive(component))
                    spec.targetPackage?.let { put("package", JsonPrimitive(it)) }
                },
            )
        } catch (e: Exception) {
            CommandOutcome.failure(
                code = ErrorCode.DEVICE_ERROR,
                message = "Could not start the camera app: ${e.message}",
                remediation = "Check that cam-remote still holds \"Display over other apps\"",
            )
        }
    }
}
