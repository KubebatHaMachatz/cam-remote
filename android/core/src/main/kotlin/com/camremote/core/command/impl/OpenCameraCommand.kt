package com.camremote.core.command.impl

import com.camremote.core.command.Command
import com.camremote.core.command.CommandOutcome
import com.camremote.core.command.DeviceResource
import com.camremote.core.logic.CameraAppChoice
import com.camremote.core.logic.CameraAppLaunch
import com.camremote.core.port.ActivityStarter
import com.camremote.core.port.PermissionInspector
import com.camremote.core.port.PermissionPrompt
import com.camremote.core.protocol.CommandCategory
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
 * Two things make this harder than it looks.
 *
 * The precondition: since Android 10 an app in the background may not start an activity, and this
 * agent is by design always in the background. The documented escape hatch is the "Display over
 * other apps" permission, so the command checks for it and, when it is missing, says so with the fix
 * attached instead of firing an intent the system will silently discard.
 *
 * And portability: no single intent opens the camera app on every device. The command works through
 * [CameraAppLaunch]'s ordered candidates, skipping those nothing handles and those the platform
 * refuses to start, and reports which one succeeded so a device's quirks can be diagnosed from the
 * control machine rather than by picking the handset up.
 *
 * Each candidate is launched by explicit component, chosen by [CameraAppChoice] from every handler
 * the device offers. Letting the platform choose would, on a device with two camera apps and no
 * default, produce a chooser dialog in front of nobody.
 */
class OpenCameraCommand(
    private val activities: ActivityStarter,
    private val permissions: PermissionInspector,
    private val permissionPrompt: PermissionPrompt,
) : Command {

    override val descriptor = CommandDescriptor(
        name = "camera.open",
        category = CommandCategory.PRIMARY,
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

    /** Works down the candidate strategies until one both resolves and starts. */
    override suspend fun execute(params: Params): CommandOutcome {
        val candidates = CameraAppLaunch.candidatesFor(params)

        if (!permissions.status().canDrawOverlays) {
            // No setup screen exists, so the only known moment a human might be looking at the
            // phone is right after a command has just failed -- this is that moment.
            permissionPrompt.requestAttention()
            return CommandOutcome.failure(
                code = ErrorCode.PRECONDITION_FAILED,
                message = "Android will not let a background app start an activity without the " +
                    "overlay permission",
                remediation = "A settings prompt was shown on the device; grant \"Display over other " +
                    "apps\" there, then retry",
            )
        }

        var resolvedAny = false
        var lastFailure: Exception? = null

        for (spec in candidates) {
            val chosen = CameraAppChoice.pick(activities.resolveAll(spec)) ?: continue
            resolvedAny = true
            try {
                // Resolving proves an activity exists; it does not prove this app may launch it, so
                // a start that throws falls through to the next candidate rather than ending here.
                activities.start(spec.copy(component = chosen.component))
                return CommandOutcome.Success(
                    buildJsonObject {
                        put("launched", JsonPrimitive(true))
                        put("component", JsonPrimitive(chosen.component))
                        put("package", JsonPrimitive(chosen.packageName))
                        put("preinstalled", JsonPrimitive(chosen.isSystem))
                        put("defaultHandler", JsonPrimitive(chosen.isDefault))
                        put("strategy", JsonPrimitive(spec.strategy))
                        put("action", JsonPrimitive(spec.action))
                    },
                )
            } catch (e: Exception) {
                lastFailure = e
            }
        }

        val scope = candidates.firstOrNull()?.targetPackage?.let { " in package $it" } ?: ""
        return if (resolvedAny) {
            CommandOutcome.failure(
                code = ErrorCode.DEVICE_ERROR,
                message = "Every camera app this device offers refused to start: ${lastFailure?.message}",
                remediation = "Check that cam-remote still holds \"Display over other apps\"",
            )
        } else {
            CommandOutcome.failure(
                code = ErrorCode.DEVICE_ERROR,
                message = "No installed app handles any known camera intent$scope " +
                    "(tried ${candidates.joinToString { it.strategy }})",
                remediation = "Install a camera app, or omit the 'package' parameter. " +
                    "camera.capture does not need one — it drives the sensor directly.",
            )
        }
    }
}
