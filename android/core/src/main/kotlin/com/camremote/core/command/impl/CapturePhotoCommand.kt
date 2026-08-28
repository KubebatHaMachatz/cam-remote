package com.camremote.core.command.impl

import com.camremote.core.command.Command
import com.camremote.core.command.CommandOutcome
import com.camremote.core.command.DeviceResource
import com.camremote.core.logic.PhotoNaming
import com.camremote.core.port.CameraController
import com.camremote.core.port.CaptureRequest
import com.camremote.core.port.Clock
import com.camremote.core.port.PermissionInspector
import com.camremote.core.port.PhotoStore
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
 * Two things are deliberate here. The capture is headless: no preview, no shutter button, no
 * `ACTION_IMAGE_CAPTURE` intent that would need a human to press something. And "rear camera only"
 * is enforced rather than assumed — a device without a rear sensor gets a clear failure instead of
 * a quietly substituted selfie.
 */
class CapturePhotoCommand(
    private val camera: CameraController,
    private val photos: PhotoStore,
    private val permissions: PermissionInspector,
    private val clock: Clock,
) : Command {

    override val descriptor = CommandDescriptor(
        name = "camera.capture",
        description = "Take a still photograph with the rear camera and save it on the device.",
        parameters = listOf(
            ParameterDescriptor(
                name = "path",
                type = ParameterType.STRING,
                required = false,
                description = "Destination directory. Must be inside the agent's writable roots.",
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
            ParameterDescriptor(
                name = "publishToGallery",
                type = ParameterType.BOOLEAN,
                required = false,
                description = "Also index the photo in MediaStore so it appears in the gallery.",
                default = "false",
            ),
        ),
    )

    override val exclusiveResource = DeviceResource.CAMERA

    // Generous: opening the camera, metering and writing a full-resolution JPEG is seconds of work
    // on a cold sensor, and a spurious timeout here would leave a file half-written.
    override val timeout = 45.seconds

    override suspend fun execute(params: Params): CommandOutcome {
        if (!permissions.status().camera) {
            return CommandOutcome.failure(
                code = ErrorCode.PERMISSION_DENIED,
                message = "The camera permission has not been granted to cam-remote",
                remediation = "Open cam-remote on the device and complete setup to grant camera access",
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

        val filename = PhotoNaming.filenameFor(clock, params.optString("filename"))
        val destination = photos.destinationFor(params.optString("path"), filename)

        val result = try {
            camera.captureRearStill(CaptureRequest(destinationPath = destination, jpegQuality = quality))
        } catch (e: Exception) {
            return CommandOutcome.failure(
                code = ErrorCode.DEVICE_ERROR,
                message = "Capture failed: ${e.message}",
                remediation = "Check no other app is holding the camera, then retry",
            )
        }

        val stored = photos.record(destination, clock.nowMillis())
        val galleryUri = if (params.optBoolean("publishToGallery", false)) photos.publish(stored) else null

        return CommandOutcome.Success(
            buildJsonObject {
                put("id", JsonPrimitive(stored.id))
                put("path", JsonPrimitive(stored.path))
                put("sizeBytes", JsonPrimitive(stored.sizeBytes))
                put("widthPx", JsonPrimitive(result.widthPx))
                put("heightPx", JsonPrimitive(result.heightPx))
                put("capturedAtMillis", JsonPrimitive(stored.capturedAtMillis))
                // The control machine cannot read the handset's filesystem, so it is told how to
                // fetch what was just taken.
                put("downloadPath", JsonPrimitive("/v1/media/${stored.id}"))
                galleryUri?.let { put("galleryUri", JsonPrimitive(it)) }
            },
        )
    }

    private companion object {
        const val DEFAULT_JPEG_QUALITY = 95
    }
}
