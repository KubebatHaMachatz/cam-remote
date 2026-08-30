package com.camremote.core.command.impl

import com.camremote.core.command.CommandOutcome.Success
import com.camremote.core.command.CommandRegistry
import com.camremote.core.port.CameraController
import com.camremote.core.port.CaptureRequest
import com.camremote.core.port.CaptureResult
import com.camremote.core.port.PermissionInspector
import com.camremote.core.protocol.CommandCategory
import com.camremote.core.protocol.CommandDescriptor
import com.camremote.core.protocol.DeviceDescription
import com.camremote.core.protocol.ParameterDescriptor
import com.camremote.core.protocol.ParameterType
import com.camremote.core.protocol.Params
import com.camremote.core.protocol.PermissionStatus
import com.camremote.core.testing.FakeClock
import com.camremote.core.testing.TestCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The two commands that make the agent describe itself.
 *
 * They matter more than they look: a device with no UI and no shell access needs some way to answer
 * "what can you do?" and "why did that not work?" over the wire, or every diagnosis becomes a trip
 * to wherever the phone is.
 */
class IntrospectionCommandsTest {

    private val allGranted = PermissionStatus(
        camera = true,
        notifications = true,
        canDrawOverlays = true,
        ignoringBatteryOptimizations = true,
    )

    private val device = DeviceDescription(
        name = "Test Handset",
        model = "Pixel Test",
        androidRelease = "16",
        apiLevel = 37,
    )

    private val camera = object : CameraController {
        override fun hasRearCamera() = true
        override suspend fun captureRearStill(request: CaptureRequest) = CaptureResult(1, 1)
    }

    @Test
    fun `lists the catalog including itself`() = runTest {
        lateinit var registry: CommandRegistry
        val list = ListCommandsCommand { registry.descriptors() }
        registry = CommandRegistry(
            listOf(
                list,
                TestCommand(name = "device.getprop") { Success(null) },
            ),
        )

        val outcome = list.execute(Params.EMPTY)

        val names = assertIs<Success>(outcome).data!!["commands"]!!.jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("device.getprop", "system.commands"), names)
    }

    @Test
    fun `describes each command's parameters so a client can drive it blind`() = runTest {
        lateinit var registry: CommandRegistry
        val list = ListCommandsCommand { registry.descriptors() }
        val documented = object : com.camremote.core.command.Command {
            override val descriptor = CommandDescriptor(
                name = "device.reboot",
                description = "Restart the device.",
                parameters = listOf(
                    ParameterDescriptor(
                        name = "mode",
                        type = ParameterType.STRING,
                        required = false,
                        description = "recovery or bootloader",
                    ),
                ),
            )

            override suspend fun execute(params: Params) = Success(null)
        }
        registry = CommandRegistry(listOf(list, documented))

        val outcome = list.execute(Params.EMPTY)

        val reboot = assertIs<Success>(outcome).data!!["commands"]!!.jsonArray
            .first { it.jsonObject["name"]!!.jsonPrimitive.content == "device.reboot" }
        assertEquals("mode", reboot.jsonObject["parameters"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `reports the device, its clock and the state of every grant`() = runTest {
        val status = StatusCommand(
            permissions = PermissionInspector { allGranted },
            device = { device },
            camera = camera,
            clock = FakeClock(1_699_999_999_123),
        )

        val data = assertIs<Success>(status.execute(Params.EMPTY)).data!!

        assertEquals("Pixel Test", data["device"]!!.jsonObject["model"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), data["permissions"]!!.jsonObject["camera"])
        assertEquals(JsonPrimitive(true), data["hasRearCamera"])
        assertEquals(JsonPrimitive(1_699_999_999_123), data["deviceTimeMillis"])
    }

    @Test
    fun `names the missing grants so an operator knows what to fix`() = runTest {
        val status = StatusCommand(
            permissions = PermissionInspector { allGranted.copy(camera = false, canDrawOverlays = false) },
            device = { device },
            camera = camera,
            clock = FakeClock(),
        )

        val data = assertIs<Success>(status.execute(Params.EMPTY)).data!!

        assertEquals(JsonPrimitive(false), data["setupComplete"])
        val missing = data["missing"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("camera", "canDrawOverlays"), missing)
    }

    @Test
    fun `reports nothing missing when setup is complete`() = runTest {
        val status = StatusCommand(
            permissions = PermissionInspector { allGranted },
            device = { device },
            camera = camera,
            clock = FakeClock(),
        )

        val data = assertIs<Success>(status.execute(Params.EMPTY)).data!!

        assertEquals(JsonPrimitive(true), data["setupComplete"])
        assertTrue(data["missing"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `the introspection commands are diagnostics, not headline capabilities`() {
        // They exist to explain the agent, not to be the reason it is running. Nothing declares
        // this: DIAGNOSTIC is the default, and that is the behaviour being pinned.
        assertEquals(
            CommandCategory.DIAGNOSTIC,
            ListCommandsCommand { emptyList() }.descriptor.category,
        )
    }
}
