package com.camremote.core.command.impl

import com.camremote.core.command.CommandOutcome.Failure
import com.camremote.core.command.CommandOutcome.Success
import com.camremote.core.command.DeviceResource
import com.camremote.core.port.ActivityStarter
import com.camremote.core.port.LaunchSpec
import com.camremote.core.port.PermissionInspector
import com.camremote.core.protocol.ErrorCode
import com.camremote.core.protocol.Params
import com.camremote.core.protocol.PermissionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive

class OpenCameraCommandTest {

    private val allGranted = PermissionStatus(
        camera = true,
        notifications = true,
        canDrawOverlays = true,
        ignoringBatteryOptimizations = true,
    )

    private class RecordingStarter(
        private val resolved: String? = "com.example.camera/.CameraActivity",
        private val failWith: Exception? = null,
    ) : ActivityStarter {
        var started: LaunchSpec? = null
        override fun resolve(spec: LaunchSpec): String? = resolved
        override fun start(spec: LaunchSpec) {
            failWith?.let { throw it }
            started = spec
        }
    }

    private fun command(
        starter: ActivityStarter,
        permissions: PermissionStatus = allGranted,
    ) = OpenCameraCommand(starter, PermissionInspector { permissions })

    @Test
    fun `launches the camera app and reports what it launched`() = runTest {
        val starter = RecordingStarter()

        val outcome = command(starter).execute(Params.EMPTY)

        val data = assertIs<Success>(outcome).data
        assertEquals(JsonPrimitive("com.example.camera/.CameraActivity"), data?.get("component"))
        assertEquals("android.media.action.STILL_IMAGE_CAMERA", starter.started?.action)
    }

    @Test
    fun `refuses without the overlay permission and says how to fix it`() = runTest {
        val starter = RecordingStarter()

        val outcome = command(starter, allGranted.copy(canDrawOverlays = false)).execute(Params.EMPTY)

        // Android blocks activity launches from the background unless this is granted. Failing fast
        // with the fix beats firing an intent that the OS silently drops.
        val error = assertIs<Failure>(outcome).error
        assertEquals(ErrorCode.PRECONDITION_FAILED, error.code)
        assertTrue(error.remediation!!.contains("Display over other apps"))
        assertEquals(null, starter.started)
    }

    @Test
    fun `reports a device with no camera app`() = runTest {
        val outcome = command(RecordingStarter(resolved = null)).execute(Params.EMPTY)

        val error = assertIs<Failure>(outcome).error
        assertEquals(ErrorCode.DEVICE_ERROR, error.code)
    }

    @Test
    fun `reports a launch that the platform refused`() = runTest {
        val starter = RecordingStarter(failWith = SecurityException("background activity start blocked"))

        val outcome = command(starter).execute(Params.EMPTY)

        val error = assertIs<Failure>(outcome).error
        assertEquals(ErrorCode.DEVICE_ERROR, error.code)
        assertTrue(error.message.contains("background activity start blocked"))
    }

    @Test
    fun `passes a requested target package through to the launch`() = runTest {
        val starter = RecordingStarter()

        command(starter).execute(Params.of("package" to "com.example.other"))

        assertEquals("com.example.other", starter.started?.targetPackage)
    }

    @Test
    fun `contends for the camera so it cannot interleave with a capture`() {
        // Launching the camera app grabs the same hardware a capture is using.
        assertEquals(DeviceResource.CAMERA, command(RecordingStarter()).exclusiveResource)
    }

    @Test
    fun `advertises its parameters in the catalog`() {
        val names = command(RecordingStarter()).descriptor.parameters.map { it.name }

        assertEquals(listOf("lens", "package"), names)
        assertEquals("camera.open", command(RecordingStarter()).descriptor.name)
    }
}
