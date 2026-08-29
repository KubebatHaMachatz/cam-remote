package com.camremote.core.command.impl

import com.camremote.core.command.CommandOutcome.Failure
import com.camremote.core.command.CommandOutcome.Success
import com.camremote.core.command.DeviceResource
import com.camremote.core.port.ActivityStarter
import com.camremote.core.port.LaunchSpec
import com.camremote.core.port.ResolvedActivity
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

/**
 * The assignment's "open a camera" requirement.
 *
 * Most of these are about portability and failure: falling through the candidate strategies,
 * surviving a handler that resolves but refuses to start, and reporting the overlay permission
 * that Android requires before a background app may start an activity at all.
 */
class OpenCameraCommandTest {

    private val allGranted = PermissionStatus(
        camera = true,
        notifications = true,
        canDrawOverlays = true,
        ignoringBatteryOptimizations = true,
    )

    /**
     * A device that answers only the strategies named in [resolves], and throws from
     * [failsToStart] for those it claims to handle but will not actually launch.
     */
    private class FakeDevice(
        private val resolves: Set<String> = setOf("still_image_camera"),
        private val failsToStart: Set<String> = emptySet(),
    ) : ActivityStarter {
        val attempted = mutableListOf<String>()
        var started: LaunchSpec? = null

        override fun resolveAll(spec: LaunchSpec): List<ResolvedActivity> =
            if (spec.strategy in resolves) {
                listOf(ResolvedActivity("com.example.camera", ".Camera_${spec.strategy}", isSystem = true))
            } else {
                emptyList()
            }

        override fun start(spec: LaunchSpec) {
            attempted += spec.strategy
            if (spec.strategy in failsToStart) throw SecurityException("refused: ${spec.strategy}")
            started = spec
        }
    }

    private fun command(
        starter: ActivityStarter,
        permissions: PermissionStatus = allGranted,
    ) = OpenCameraCommand(starter, PermissionInspector { permissions })

    @Test
    fun `launches the camera app and reports what it launched`() = runTest {
        val device = FakeDevice()

        val outcome = command(device).execute(Params.EMPTY)

        val data = assertIs<Success>(outcome).data
        assertEquals(
            JsonPrimitive("com.example.camera/.Camera_still_image_camera"),
            data?.get("component"),
        )
        // Launched by explicit component, so no chooser can appear on a device with two cameras.
        assertEquals("com.example.camera/.Camera_still_image_camera", device.started?.component)
        assertEquals("android.media.action.STILL_IMAGE_CAMERA", device.started?.action)
    }

    @Test
    fun `reports which strategy worked so a device's quirks are diagnosable remotely`() = runTest {
        val outcome = command(FakeDevice(resolves = setOf("app_camera_category"))).execute(Params.EMPTY)

        // Across a fleet of handsets this is the difference between "it works on the Samsung" and
        // knowing *why* it works differently there.
        assertEquals(JsonPrimitive("app_camera_category"), assertIs<Success>(outcome).data?.get("strategy"))
    }

    @Test
    fun `falls through to a later strategy when the first does not resolve`() = runTest {
        val device = FakeDevice(resolves = setOf("image_capture"))

        val outcome = command(device).execute(Params.EMPTY)

        assertIs<Success>(outcome)
        assertEquals("android.media.action.IMAGE_CAPTURE", device.started?.action)
    }

    @Test
    fun `falls through when a strategy resolves but the platform refuses to start it`() = runTest {
        // Resolving proves an activity exists, not that this app may launch it.
        val device = FakeDevice(
            resolves = setOf("still_image_camera", "app_camera_category"),
            failsToStart = setOf("still_image_camera"),
        )

        val outcome = command(device).execute(Params.EMPTY)

        assertIs<Success>(outcome)
        assertEquals(listOf("still_image_camera", "app_camera_category"), device.attempted)
    }

    @Test
    fun `stops at the first strategy that works`() = runTest {
        val device = FakeDevice(resolves = setOf("still_image_camera", "app_camera_category", "image_capture"))

        command(device).execute(Params.EMPTY)

        assertEquals(listOf("still_image_camera"), device.attempted)
    }

    @Test
    fun `reports a device where nothing handles any camera intent`() = runTest {
        val outcome = command(FakeDevice(resolves = emptySet())).execute(Params.EMPTY)

        // Bare AOSP system images frequently ship no camera app at all. That is a clean answer, not
        // a crash, and camera.capture still works because it drives the sensor directly.
        val error = assertIs<Failure>(outcome).error
        assertEquals(ErrorCode.DEVICE_ERROR, error.code)
        assertTrue(error.message.contains("No installed app"))
    }

    @Test
    fun `reports the last failure when every strategy that resolved refused to start`() = runTest {
        val device = FakeDevice(
            resolves = setOf("still_image_camera", "app_camera_category", "image_capture"),
            failsToStart = setOf("still_image_camera", "app_camera_category", "image_capture"),
        )

        val outcome = command(device).execute(Params.EMPTY)

        val error = assertIs<Failure>(outcome).error
        assertEquals(ErrorCode.DEVICE_ERROR, error.code)
        assertTrue(error.message.contains("refused"))
        assertEquals(3, device.attempted.size)
    }

    @Test
    fun `refuses without the overlay permission and says how to fix it`() = runTest {
        val device = FakeDevice()

        val outcome = command(device, allGranted.copy(canDrawOverlays = false)).execute(Params.EMPTY)

        // Android blocks activity launches from the background unless this is granted. Failing fast
        // with the fix beats firing an intent that the OS silently drops.
        val error = assertIs<Failure>(outcome).error
        assertEquals(ErrorCode.PRECONDITION_FAILED, error.code)
        assertTrue(error.remediation!!.contains("Display over other apps"))
        assertEquals(null, device.started)
    }

    @Test
    fun `passes a requested target package through to every attempt`() = runTest {
        val device = FakeDevice(resolves = setOf("image_capture"))

        command(device).execute(Params.of("package" to "com.sec.android.app.camera"))

        assertEquals("com.sec.android.app.camera", device.started?.targetPackage)
    }

    @Test
    fun `contends for the camera so it cannot interleave with a capture`() {
        // Launching the camera app grabs the same hardware a capture is using.
        assertEquals(DeviceResource.CAMERA, command(FakeDevice()).exclusiveResource)
    }

    @Test
    fun `advertises its parameters in the catalog`() {
        val names = command(FakeDevice()).descriptor.parameters.map { it.name }

        assertEquals(listOf("lens", "package"), names)
        assertEquals("camera.open", command(FakeDevice()).descriptor.name)
    }
}
