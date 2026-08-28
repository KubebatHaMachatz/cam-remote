package com.camremote.core.command.impl

import com.camremote.core.command.CommandOutcome.Failure
import com.camremote.core.command.CommandOutcome.Success
import com.camremote.core.command.DeviceResource
import com.camremote.core.port.CameraController
import com.camremote.core.port.CaptureRequest
import com.camremote.core.port.CaptureResult
import com.camremote.core.port.PermissionInspector
import com.camremote.core.port.PhotoStore
import com.camremote.core.port.StoredPhoto
import com.camremote.core.protocol.ErrorCode
import com.camremote.core.protocol.Params
import com.camremote.core.protocol.PermissionStatus
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class CapturePhotoCommandTest {

    private val allGranted = PermissionStatus(
        camera = true,
        notifications = true,
        canDrawOverlays = true,
        ignoringBatteryOptimizations = true,
    )

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

    private class FakePhotoStore(private val failWith: Exception? = null) : PhotoStore {
        var recorded: String? = null
        var published = false
        override fun destinationFor(directory: String?, filename: String): String {
            failWith?.let { throw it }
            return "${directory ?: "/data/pictures"}/$filename"
        }

        override fun record(path: String, capturedAtMillis: Long) = StoredPhoto(
            id = "photo-id",
            path = path,
            sizeBytes = 2_481_632,
            capturedAtMillis = capturedAtMillis,
        ).also { recorded = path }

        override fun publish(photo: StoredPhoto): String? {
            published = true
            return "content://media/external/images/media/42"
        }

        override fun open(id: String) = null
    }

    private fun command(
        camera: CameraController = FakeCamera(),
        photos: PhotoStore = FakePhotoStore(),
        permissions: PermissionStatus = allGranted,
        nowMillis: Long = 1_699_999_999_123,
    ) = CapturePhotoCommand(
        camera = camera,
        photos = photos,
        permissions = PermissionInspector { permissions },
        clock = { nowMillis },
    )

    @Test
    fun `captures a still and reports where it landed`() = runTest {
        val photos = FakePhotoStore()

        val outcome = command(photos = photos).execute(Params.EMPTY)

        val data = assertIs<Success>(outcome).data
        assertEquals(JsonPrimitive("photo-id"), data?.get("id"))
        assertEquals(JsonPrimitive("/data/pictures/camremote-20231114-221319-123.jpg"), data?.get("path"))
        assertEquals(JsonPrimitive(2_481_632), data?.get("sizeBytes"))
        assertEquals(JsonPrimitive(4032), data?.get("widthPx"))
        assertEquals(JsonPrimitive(3024), data?.get("heightPx"))
        assertEquals(JsonPrimitive(1_699_999_999_123), data?.get("capturedAtMillis"))
    }

    @Test
    fun `tells the client how to fetch the image it just took`() = runTest {
        val outcome = command().execute(Params.EMPTY)

        // A path on the handset is useless to the control machine on its own; the download route is
        // what turns "saved to a specified location" into "the operator has the photo".
        assertEquals(
            JsonPrimitive("/v1/media/photo-id"),
            assertIs<Success>(outcome).data?.get("downloadPath"),
        )
    }

    @Test
    fun `honours a requested destination and filename`() = runTest {
        val photos = FakePhotoStore()

        command(photos = photos).execute(
            Params.of("path" to "/sdcard/custom", "filename" to "door"),
        )

        assertEquals("/sdcard/custom/door.jpg", photos.recorded)
    }

    @Test
    fun `refuses when the camera permission has not been granted`() = runTest {
        val camera = FakeCamera()

        val outcome = command(camera = camera, permissions = allGranted.copy(camera = false))
            .execute(Params.EMPTY)

        val error = assertIs<Failure>(outcome).error
        assertEquals(ErrorCode.PERMISSION_DENIED, error.code)
        assertTrue(error.remediation!!.contains("setup"))
        assertEquals(null, camera.request)
    }

    @Test
    fun `refuses on a device with no rear camera rather than silently using the front one`() = runTest {
        val outcome = command(camera = FakeCamera(hasRear = false)).execute(Params.EMPTY)

        // The assignment says rear camera only, so the absence of one is a reportable failure, not
        // an invitation to substitute the selfie camera.
        val error = assertIs<Failure>(outcome).error
        assertEquals(ErrorCode.DEVICE_ERROR, error.code)
        assertTrue(error.message.contains("rear", ignoreCase = true))
    }

    @Test
    fun `reports a camera that failed mid-capture`() = runTest {
        val outcome = command(camera = FakeCamera(failWith = IOException("camera in use")))
            .execute(Params.EMPTY)

        val error = assertIs<Failure>(outcome).error
        assertEquals(ErrorCode.DEVICE_ERROR, error.code)
        assertTrue(error.message.contains("camera in use"))
    }

    @Test
    fun `lets an invalid destination surface as a parameter error`() = runTest {
        val photos = FakePhotoStore(
            failWith = com.camremote.core.protocol.InvalidParamsException("outside the allowed roots"),
        )

        // Thrown, not returned: the dispatcher owns the mapping to INVALID_PARAMS so that every
        // command reports a bad parameter identically.
        assertTrue(
            runCatching { command(photos = photos).execute(Params.EMPTY) }
                .exceptionOrNull() is com.camremote.core.protocol.InvalidParamsException,
        )
    }

    @Test
    fun `defaults to a sensible jpeg quality and accepts an override`() = runTest {
        val camera = FakeCamera()
        command(camera = camera).execute(Params.EMPTY)
        assertEquals(95, camera.request?.jpegQuality)

        val other = FakeCamera()
        command(camera = other).execute(
            Params(buildJsonObject { put("jpegQuality", JsonPrimitive(60)) }),
        )
        assertEquals(60, other.request?.jpegQuality)
    }

    @Test
    fun `rejects a jpeg quality outside the valid range`() = runTest {
        listOf(0, 101, -5).forEach { quality ->
            val params = Params(buildJsonObject { put("jpegQuality", JsonPrimitive(quality)) })
            assertTrue(
                runCatching { command().execute(params) }
                    .exceptionOrNull() is com.camremote.core.protocol.InvalidParamsException,
                "expected quality $quality to be rejected",
            )
        }
    }

    @Test
    fun `does not publish to the gallery unless asked`() = runTest {
        val photos = FakePhotoStore()

        command(photos = photos).execute(Params.EMPTY)

        assertEquals(false, photos.published)
    }

    @Test
    fun `publishes to the gallery on request and reports the uri`() = runTest {
        val photos = FakePhotoStore()

        val outcome = command(photos = photos).execute(
            Params(buildJsonObject { put("publishToGallery", JsonPrimitive(true)) }),
        )

        assertEquals(true, photos.published)
        assertEquals(
            JsonPrimitive("content://media/external/images/media/42"),
            assertIs<Success>(outcome).data?.get("galleryUri"),
        )
    }

    @Test
    fun `holds the camera exclusively`() {
        assertEquals(DeviceResource.CAMERA, command().exclusiveResource)
    }

    @Test
    fun `advertises its parameters in the catalog`() {
        assertEquals("camera.capture", command().descriptor.name)
        assertEquals(
            listOf("path", "filename", "jpegQuality", "publishToGallery"),
            command().descriptor.parameters.map { it.name },
        )
    }
}
