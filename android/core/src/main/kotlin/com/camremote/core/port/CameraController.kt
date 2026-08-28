package com.camremote.core.port

/** Where to write the JPEG, and how hard to compress it. */
data class CaptureRequest(
    val destinationPath: String,
    val jpegQuality: Int,
)

/** What the sensor actually produced. */
data class CaptureResult(
    val widthPx: Int,
    val heightPx: Int,
)

/**
 * Takes a still photograph with the rear camera.
 *
 * The port is deliberately narrow — no preview, no lens choice, no session management — because the
 * assignment asks for exactly one thing and a wider port would invite the command layer to start
 * knowing about camera sessions.
 */
interface CameraController {

    /** False on a device with no rear-facing sensor, which is a reportable condition. */
    fun hasRearCamera(): Boolean

    /** @throws Exception when the capture fails or the camera is unavailable. */
    suspend fun captureRearStill(request: CaptureRequest): CaptureResult
}
