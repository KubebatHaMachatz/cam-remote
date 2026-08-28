package com.camremote.core.command

import com.camremote.core.command.CommandOutcome.Failure
import com.camremote.core.command.CommandOutcome.Success
import com.camremote.core.protocol.CommandError
import com.camremote.core.protocol.CommandRequest
import com.camremote.core.protocol.CommandStatus
import com.camremote.core.protocol.ErrorCode
import com.camremote.core.protocol.InvalidParamsException
import com.camremote.core.protocol.Params
import com.camremote.core.testing.FakeClock
import com.camremote.core.testing.TestCommand
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The dispatcher is the driving port: every transport funnels through it, so its behaviour on the
 * unhappy paths is what clients actually experience. These tests are the specification for that.
 */
class CommandDispatcherTest {

    private val clock = FakeClock()

    private fun dispatcher(vararg commands: Command) =
        CommandDispatcher(CommandRegistry(commands.toList()), clock)

    @Test
    fun `returns the command payload and echoes the correlation id`() = runTest {
        val dispatcher = dispatcher(
            TestCommand(name = "system.ping") {
                clock.advance(7)
                Success(buildJsonObject { put("pong", JsonPrimitive(true)) })
            },
        )

        val response = dispatcher.dispatch(CommandRequest(id = "abc", command = "system.ping"))

        assertEquals("abc", response.id)
        assertEquals("system.ping", response.command)
        assertEquals(CommandStatus.OK, response.status)
        assertEquals(JsonPrimitive(true), response.data?.get("pong"))
        assertEquals(7, response.durationMs)
        assertNull(response.error)
    }

    @Test
    fun `reports an unregistered command rather than failing the transport`() = runTest {
        val dispatcher = dispatcher(TestCommand(name = "system.ping") { Success(null) })

        val response = dispatcher.dispatch(CommandRequest(id = "abc", command = "camera.teleport"))

        assertEquals(CommandStatus.ERROR, response.status)
        assertEquals(ErrorCode.UNKNOWN_COMMAND, response.error?.code)
        // The message names what is available, so a mistyped command is self-correcting.
        assertTrue(response.error?.message?.contains("camera.teleport") == true)
    }

    @Test
    fun `passes a command's own failure through untouched`() = runTest {
        val dispatcher = dispatcher(
            TestCommand(name = "camera.open") {
                Failure(
                    CommandError(
                        code = ErrorCode.PRECONDITION_FAILED,
                        message = "overlay permission missing",
                        remediation = "complete setup on the device",
                    ),
                )
            },
        )

        val response = dispatcher.dispatch(CommandRequest(id = "abc", command = "camera.open"))

        assertEquals(ErrorCode.PRECONDITION_FAILED, response.error?.code)
        assertEquals("complete setup on the device", response.error?.remediation)
    }

    @Test
    fun `maps a parameter validation failure to INVALID_PARAMS`() = runTest {
        val dispatcher = dispatcher(
            TestCommand(name = "device.getprop") { throw InvalidParamsException("Missing required parameter 'key'") },
        )

        val response = dispatcher.dispatch(CommandRequest(id = "abc", command = "device.getprop"))

        assertEquals(ErrorCode.INVALID_PARAMS, response.error?.code)
        assertEquals("Missing required parameter 'key'", response.error?.message)
    }

    @Test
    fun `maps an unexpected exception to INTERNAL without propagating it`() = runTest {
        val dispatcher = dispatcher(
            TestCommand(name = "camera.capture") { error("camera driver exploded") },
        )

        // A bug in one command must not take down the server for every other command.
        val response = dispatcher.dispatch(CommandRequest(id = "abc", command = "camera.capture"))

        assertEquals(ErrorCode.INTERNAL, response.error?.code)
        assertTrue(response.error?.message?.contains("camera driver exploded") == true)
    }

    @Test
    fun `times out a command that exceeds its own budget`() = runTest {
        val dispatcher = dispatcher(
            TestCommand(name = "camera.capture", timeout = 500.milliseconds) {
                delay(10.seconds)
                Success(null)
            },
        )

        val response = dispatcher.dispatch(CommandRequest(id = "abc", command = "camera.capture"))

        assertEquals(ErrorCode.TIMEOUT, response.error?.code)
        assertNotNull(response.error?.remediation)
    }

    @Test
    fun `serialises commands competing for the same exclusive resource`() = runTest {
        val concurrent = AtomicInteger()
        val peak = AtomicInteger()
        val dispatcher = dispatcher(
            TestCommand(name = "camera.capture", exclusiveResource = DeviceResource.CAMERA) {
                val inFlight = concurrent.incrementAndGet()
                peak.getAndUpdate { maxOf(it, inFlight) }
                delay(100)
                concurrent.decrementAndGet()
                Success(null)
            },
        )

        repeat(3) { launch { dispatcher.dispatch(CommandRequest(id = "r$it", command = "camera.capture")) } }
        advanceUntilIdle()

        // The camera is a physically exclusive device; two captures must never be in flight at once.
        assertEquals(1, peak.get())
    }

    @Test
    fun `lets free-threaded commands overlap`() = runTest {
        val concurrent = AtomicInteger()
        val peak = AtomicInteger()
        val dispatcher = dispatcher(
            TestCommand(name = "device.getprop", exclusiveResource = null) {
                val inFlight = concurrent.incrementAndGet()
                peak.getAndUpdate { maxOf(it, inFlight) }
                delay(100)
                concurrent.decrementAndGet()
                Success(null)
            },
        )

        repeat(3) { launch { dispatcher.dispatch(CommandRequest(id = "r$it", command = "device.getprop")) } }
        advanceUntilIdle()

        // Serialising everything would make one slow capture block a trivial property read.
        assertEquals(3, peak.get())
    }

    @Test
    fun `counts time spent waiting for a busy resource against the timeout`() = runTest {
        val dispatcher = dispatcher(
            TestCommand(
                name = "camera.capture",
                exclusiveResource = DeviceResource.CAMERA,
                timeout = 300.milliseconds,
            ) {
                delay(200)
                Success(null)
            },
        )

        val results = mutableListOf<ErrorCode?>()
        repeat(3) {
            launch {
                results += dispatcher.dispatch(CommandRequest(id = "r$it", command = "camera.capture")).error?.code
            }
        }
        advanceUntilIdle()

        // A client queued behind a busy camera gets a timely TIMEOUT instead of hanging forever.
        assertEquals(1, results.count { it == null })
        assertEquals(2, results.count { it == ErrorCode.TIMEOUT })
    }

    @Test
    fun `hands the request's params to the command`() = runTest {
        var seen: Params? = null
        val dispatcher = dispatcher(TestCommand(name = "device.getprop") { seen = it; Success(null) })

        dispatcher.dispatch(
            CommandRequest(
                id = "abc",
                command = "device.getprop",
                params = Params.of("key" to "ro.product.model"),
            ),
        )

        assertEquals("ro.product.model", seen?.optString("key"))
    }
}
