package com.camremote.core.command.impl

import com.camremote.core.command.Command
import com.camremote.core.command.CommandOutcome
import com.camremote.core.command.DeviceResource
import com.camremote.core.logic.PhotoNaming
import com.camremote.core.logic.PhotoPaths
import com.camremote.core.port.CameraController
import com.camremote.core.port.CaptureRequest
import com.camremote.core.port.Clock
import com.camremote.core.port.PermissionInspector
import com.camremote.core.port.PermissionPrompt
import com.camremote.core.port.PhotoStore
import com.camremote.core.protocol.CommandCategory
import com.camremote.core.protocol.CommandDescriptor
import com.camremote.core.protocol.ErrorCode
import com.camremote.core.protocol.InvalidParamsException
import com.camremote.core.protocol.ParameterDescriptor
import com.camremote.core.protocol.ParameterType
import com.camremote.core.protocol.Params
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Takes a still with the rear camera — the assignment's second requirement.
 *
 * Three things are deliberate here. The capture is headless: no preview, no shutter button, no
 * `ACTION_IMAGE_CAPTURE` intent that would need a human to press something. "Rear camera only" is
 * enforced rather than assumed — a device without a rear sensor gets a clear failure instead of a
 * quietly substituted selfie. And the photograph is saved somewhere the person holding the phone
 * can actually find it, under `Documents`, rather than in app-private storage only the agent can
 * see.
 *
 * The order of the steps matters. Everything that can be rejected — the permission, the sensor, the
 * quality, the filename, the destination — is settled *before* the shutter fires, so a request that
 * was never going to work does not leave a photograph behind to clean up.
 */
class CapturePhotoCommand(
    private val camera: CameraController,
    private val photos: PhotoStore,
    private val permissions: PermissionInspector,
    private val clock: Clock,
    private val permissionPrompt: PermissionPrompt,
) : Command {

    override val descriptor = CommandDescriptor(
        name = "camera.capture",
        category = CommandCategory.PRIMARY,
        description = "Take a still photograph with the rear camera and save it under Documents.",
        parameters = listOf(
            ParameterDescriptor(
                name = "path",
                type = ParameterType.STRING,
                required = false,
                description = "Destination directory, relative to the device's Documents folder.",
                default = "${PhotoPaths.PRIMARY_DIRECTORY}/${PhotoPaths.DEFAULT_SUBDIRECTORY}",
            ),
            ParameterDescriptor(
                name = "filename",
                type = ParameterType.STRING,
                required = false,
                description = "Bare filename. Defaults to a UTC timestamp.",
            ),
            ParameterDescriptor(
                name = "jpegQuality",
                type = ParameterType.INT,
                required = false,
                description = "JPEG quality, 1-100.",
                default = "95",
            ),
        ),
    )

    override val exclusiveResource = DeviceResource.CAMERA

    // Generous: opening the camera, metering and writing a full-resolution JPEG is seconds of work
    // on a cold sensor, and a spurious timeout here would leave a file half-written.
    override val timeout = 45.seconds

    /** Checks the preconditions, takes the photograph, and publishes it where the user can find it. */
    override suspend fun execute(params: Params): CommandOutcome {
        if (!permissions.status().camera) {
            // No setup screen exists, so the only known moment a human might be looking at the
            // phone is right after a command has just failed -- this is that moment.
            permissionPrompt.requestAttention()
            return CommandOutcome.failure(
                code = ErrorCode.PERMISSION_DENIED,
                message = "The camera permission has not been granted to cam-remote",
                remediation = "A permission prompt was shown on the device; grant camera access there, then retry",
            )
        }

        if (!camera.hasRearCamera()) {
            return CommandOutcome.failure(
                code = ErrorCode.DEVICE_ERROR,
                message = "This device reports no rear camera",
                remediation = "camera.capture is rear-only by design; no substitution is made",
            )
        }

        val quality = params.optInt("jpegQuality", DEFAULT_JPEG_QUALITY)
        if (quality !in 1..100) {
            throw InvalidParamsException("Parameter 'jpegQuality' must be between 1 and 100, got $quality")
        }

        // Both throw InvalidParamsException, and both do so before the sensor is touched.
        val filename = PhotoNaming.filenameFor(clock, params.optString("filename"))
        val directory = PhotoPaths.resolveRelativeDirectory(params.optString("path"))

        // Written privately first: a failed capture must not leave a torn JPEG sitting in the
        // user's Documents, and there is no destination CameraX can be handed that rolls back.
        val scratch = photos.scratchPathFor(filename)

        val result = try {
            camera.captureRearStill(CaptureRequest(destinationPath = scratch, jpegQuality = quality))
        } catch (e: Exception) {
            photos.discard(scratch)
            return CommandOutcome.failure(
                code = ErrorCode.DEVICE_ERROR,
                message = "Capture failed: ${e.message}",
                remediation = "Check no other app is holding the camera, then retry",
            )
        }

        val stored = try {
            photos.publish(scratch, directory, filename, clock.nowMillis())
        } catch (e: Exception) {
            photos.discard(scratch)
            return CommandOutcome.failure(
                code = ErrorCode.DEVICE_ERROR,
                message = "The photograph was taken but could not be saved to $directory: ${e.message}",
                remediation = "Check the device has free storage, then retry",
            )
        }

        return CommandOutcome.Success(
            buildJsonObject {
                put("id", JsonPrimitive(stored.id))
                // Where a person would look for it on the device, not a filesystem path: shared
                // storage is addressed through MediaStore and has no path worth quoting.
                put("path", JsonPrimitive(stored.displayPath))
                put("uri", JsonPrimitive(stored.uri))
                put("sizeBytes", JsonPrimitive(stored.sizeBytes))
                put("widthPx", JsonPrimitive(result.widthPx))
                put("heightPx", JsonPrimitive(result.heightPx))
                put("capturedAtMillis", JsonPrimitive(stored.capturedAtMillis))
                // The control machine cannot read the handset's storage, so it is told how to
                // fetch what was just taken.
                put("downloadPath", JsonPrimitive("/v1/media/${stored.id}"))
            },
        )
    }

    private companion object {
        const val DEFAULT_JPEG_QUALITY = 95
    }
}
