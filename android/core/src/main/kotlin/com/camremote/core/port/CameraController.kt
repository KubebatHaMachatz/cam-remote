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

    /**
     * Whether this device has a rear-facing sensor at all.
     *
     * Checked rather than assumed, so "rear camera only" is enforced instead of quietly
     * becoming "whichever camera happened to be available".
     */
    fun hasRearCamera(): Boolean

    /**
     * Takes one photograph with the rear camera and writes it where [request] says.
     *
     * @throws Exception when the capture fails or another app holds the camera.
     */
    suspend fun captureRearStill(request: CaptureRequest): CaptureResult
}
