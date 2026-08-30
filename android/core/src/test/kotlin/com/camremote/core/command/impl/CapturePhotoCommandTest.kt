package com.camremote.core.command.impl

import com.camremote.core.command.CommandOutcome.Failure
import com.camremote.core.command.CommandOutcome.Success
import com.camremote.core.command.DeviceResource
import com.camremote.core.port.CameraController
import com.camremote.core.port.CaptureRequest
import com.camremote.core.port.CaptureResult
import com.camremote.core.port.PermissionInspector
import com.camremote.core.port.PermissionPrompt
import com.camremote.core.port.PhotoStore
import com.camremote.core.port.StoredPhoto
import com.camremote.core.protocol.ErrorCode
import com.camremote.core.protocol.InvalidParamsException
import com.camremote.core.protocol.Params
import com.camremote.core.protocol.PermissionStatus
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive

/**
 * The assignment's rear-camera requirement, specified against fakes.
 *
 * Covers the preconditions that must be checked before the sensor is touched, the destination and
 * quality parameters, that a device with no rear camera is refused rather than quietly served by
 * the front one, and that a capture which fails part-way leaves nothing behind.
 */
class CapturePhotoCommandTest {

    private val allGranted = PermissionStatus(
        camera = true,
        notifications = true,
        canDrawOverlays = true,
        ignoringBatteryOptimizations = true,
    )

    /** A camera that can be told it has no rear sensor, or made to fail mid-capture. */
    private class FakeCamera(
        private val hasRear: Boolean = true,
        private val failWith: Exception? = null,
    ) : CameraController {
        var request: CaptureRequest? = null
        override fun hasRearCamera(): Boolean = hasRear
        override suspend fun captureRearStill(request: CaptureRequest): CaptureResult {
            failWith?.let { throw it }
            this.request = request
            return CaptureResult(widthPx = 4032, heightPx = 3024)
        }
    }

    /** A store that records what it was asked to publish, and can fail the way a full disk would. */
    private class FakePhotoStore(private val failWith: Exception? = null) : PhotoStore {
        var directory: String? = null
        var filename: String? = null
        var discarded: String? = null

        override fun scratchPathFor(filename: String) = "/data/scratch/$filename"

        override fun publish(
            scratchPath: String,
            relativeDirectory: String,
            filename: String,
            capturedAtMillis: Long,
        ): StoredPhoto {
            failWith?.let { throw it }
            this.directory = relativeDirectory
            this.filename = filename
            return StoredPhoto(
                id = "photo-id",
                uri = "content://media/external/file/42",
                displayPath = "$relativeDirectory/$filename",
                sizeBytes = 2_481_632,
                capturedAtMillis = capturedAtMillis,
            )
        }

        override fun discard(scratchPath: String) {
            discarded = scratchPath
        }

        override fun open(id: String) = null
    }

    private fun command(
        camera: CameraController = FakeCamera(),
        photos: PhotoStore = FakePhotoStore(),
        permissions: PermissionStatus = allGranted,
        nowMillis: Long = 1_699_999_999_123,
        permissionPrompt: PermissionPrompt = PermissionPrompt {},
    ) = CapturePhotoCommand(
        camera = camera,
        photos = photos,
        permissions = PermissionInspector { permissions },
        clock = { nowMillis },
        permissionPrompt = permissionPrompt,
    )

    @Test
    fun `captures a still and saves it where the user can find it`() = runTest {
        val photos = FakePhotoStore()

        val outcome = command(photos = photos).execute(Params.EMPTY)

        val data = assertIs<Success>(outcome).data
        assertEquals("Documents/cam-remote", photos.directory)
        assertEquals(JsonPrimitive("photo-id"), data?.get("id"))
        assertEquals(JsonPrimitive(4032), data?.get("widthPx"))
        assertEquals(JsonPrimitive(3024), data?.get("heightPx"))
        assertEquals(JsonPrimitive(2_481_632), data?.get("sizeBytes"))
        assertEquals(JsonPrimitive(1_699_999_999_123), data?.get("capturedAtMillis"))
    }

    @Test
    fun `reports the location a person would look in, not a private path`() = runTest {
        val outcome = command().execute(Params.EMPTY)

        val path = assertIs<Success>(outcome).data?.get("path").toString().trim('"')
        assertTrue(
            path.startsWith("Documents/cam-remote/camremote-"),
            "expected a location under Documents, got '$path'",
        )
    }

    @Test
    fun `tells the client how to fetch the image it just took`() = runTest {
        val outcome = command().execute(Params.EMPTY)

        // A location on the handset is useless to the control machine on its own; the download
        // route is what turns "saved to a specified location" into "the operator has the photo".
        assertEquals(
            JsonPrimitive("/v1/media/photo-id"),
            assertIs<Success>(outcome).data?.get("downloadPath"),
        )
    }

    @Test
    fun `reports the content uri it published to`() = runTest {
        val outcome = command().execute(Params.EMPTY)

        assertEquals(
            JsonPrimitive("content://media/external/file/42"),
            assertIs<Success>(outcome).data?.get("uri"),
        )
    }

    @Test
    fun `honours a requested destination and filename`() = runTest {
        val photos = FakePhotoStore()
        command(photos = photos).execute(Params.of("path" to "reports", "filename" to "front-door"))

        assertEquals("Documents/reports", photos.directory)
        assertEquals("front-door.jpg", photos.filename)
    }

    @Test
    fun `writes privately first so a failed capture leaves nothing in Documents`() = runTest {
        val camera = FakeCamera()
        val photos = FakePhotoStore()

        command(camera = camera, photos = photos).execute(Params.EMPTY)

        // The camera is handed a scratch path, never the user's own folder.
        val written = camera.request!!.destinationPath
        assertTrue(
            written.startsWith("/data/scratch/") && !written.contains("Documents"),
            "the camera should be handed a scratch path, got '$written'",
        )
    }

    @Test
    fun `refuses when the camera permission has not been granted`() = runTest {
        val outcome = command(permissions = allGranted.copy(camera = false)).execute(Params.EMPTY)

        val failure = assertIs<Failure>(outcome)
        assertEquals(ErrorCode.PERMISSION_DENIED, failure.error.code)
    }

    @Test
    fun `prompts for the camera permission as part of failing, so the human is asked right then`() = runTest {
        var prompted = 0

        command(
            permissions = allGranted.copy(camera = false),
            permissionPrompt = { prompted++ },
        ).execute(Params.EMPTY)

        assertEquals(1, prompted)
    }

    @Test
    fun `does not prompt when the permission is already granted`() = runTest {
        var prompted = 0

        command(permissionPrompt = { prompted++ }).execute(Params.EMPTY)

        assertEquals(0, prompted)
    }

    @Test
    fun `refuses on a device with no rear camera rather than silently using the front one`() = runTest {
        val outcome = command(camera = FakeCamera(hasRear = false)).execute(Params.EMPTY)

        val failure = assertIs<Failure>(outcome)
        assertEquals(ErrorCode.DEVICE_ERROR, failure.error.code)
        assertTrue(failure.error.message.contains("no rear camera"))
    }

    @Test
    fun `reports a camera that failed mid-capture`() = runTest {
        val outcome = command(camera = FakeCamera(failWith = IOException("sensor busy")))
            .execute(Params.EMPTY)

        val failure = assertIs<Failure>(outcome)
        assertEquals(ErrorCode.DEVICE_ERROR, failure.error.code)
        assertTrue(failure.error.message.contains("sensor busy"))
    }

    @Test
    fun `throws away the scratch file when the capture fails`() = runTest {
        val photos = FakePhotoStore()

        command(camera = FakeCamera(failWith = IOException("sensor busy")), photos = photos)
            .execute(Params.EMPTY)

        assertEquals("/data/scratch/", photos.discarded?.substringBeforeLast("camremote-"))
    }

    @Test
    fun `reports a photograph that was taken but could not be saved`() = runTest {
        val photos = FakePhotoStore(failWith = IOException("No space left on device"))

        val outcome = command(photos = photos).execute(Params.EMPTY)

        val failure = assertIs<Failure>(outcome)
        assertEquals(ErrorCode.DEVICE_ERROR, failure.error.code)
        assertTrue(failure.error.message.contains("No space left on device"))
        // Nothing may be left in scratch storage after a failure to publish either.
        assertTrue(photos.discarded != null, "the scratch file should have been discarded")
    }

    @Test
    fun `lets an invalid destination surface as a parameter error`() = runTest {
        // Thrown, not returned: the dispatcher owns the mapping to INVALID_PARAMS so that every
        // command reports a bad parameter identically.
        assertFailsWith<InvalidParamsException> {
            command().execute(Params.of("path" to "../../etc"))
        }
    }

    @Test
    fun `rejects a bad destination before touching the sensor`() = runTest {
        val camera = FakeCamera()

        runCatching { command(camera = camera).execute(Params.of("path" to "/etc")) }

        assertNull(camera.request, "the shutter must not fire for a request that cannot be saved")
    }

    @Test
    fun `defaults to a sensible jpeg quality and accepts an override`() = runTest {
        val defaulted = FakeCamera()
        command(camera = defaulted).execute(Params.EMPTY)
        assertEquals(95, defaulted.request?.jpegQuality)

        val overridden = FakeCamera()
        command(camera = overridden).execute(Params.of("jpegQuality" to "60"))
        assertEquals(60, overridden.request?.jpegQuality)
    }

    @Test
    fun `rejects a jpeg quality outside the valid range`() = runTest {
        listOf(0, 101, -5).forEach { quality ->
            assertFailsWith<InvalidParamsException>("expected quality $quality to be rejected") {
                command().execute(Params.of("jpegQuality" to quality.toString()))
            }
        }
    }

    @Test
    fun `holds the camera exclusively`() {
        assertEquals(DeviceResource.CAMERA, command().exclusiveResource)
    }

    @Test
    fun `advertises its parameters in the catalog`() {
        val names = command().descriptor.parameters.map { it.name }

        assertEquals(listOf("path", "filename", "jpegQuality"), names)
    }

    @Test
    fun `says in the catalog where photos go by default`() {
        val path = command().descriptor.parameters.single { it.name == "path" }

        assertEquals("Documents/cam-remote", path.default)
    }
}
